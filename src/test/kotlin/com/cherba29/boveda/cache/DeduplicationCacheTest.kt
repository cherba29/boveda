package com.cherba29.boveda.cache

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DeduplicationCacheTest {

    private fun createTestDb() = DatabaseFactory.connectInMemory(
        "cache_test_${UUID.randomUUID().toString().replace("-", "")}"
    )

    @Test
    fun `put and get stores and retrieves entity ids and tracks hit and miss counts`() {
        val cache = InMemoryDeduplicationCache()
        val dummyId = EntityID(42, RepoFiles)

        // Initial state
        assertEquals(0, cache.size())
        val initialStats = cache.getStatistics()
        assertEquals(0, initialStats.hits)
        assertEquals(0, initialStats.misses)
        assertEquals(0.0, initialStats.hitRatio)

        // Cache miss
        assertNull(cache.get(1024L, "hash1"))
        val statsAfterMiss = cache.getStatistics()
        assertEquals(0, statsAfterMiss.hits)
        assertEquals(1, statsAfterMiss.misses)

        // Put and contains
        cache.put(1024L, "hash1", dummyId)
        assertTrue(cache.contains(1024L, "hash1"))
        assertFalse(cache.contains(2048L, "hash1"))
        assertFalse(cache.contains(1024L, "different-hash"))
        assertEquals(1, cache.size())

        // Cache hit
        val retrieved = cache.get(1024L, "hash1")
        assertNotNull(retrieved)
        assertEquals(42, retrieved.value)

        val statsAfterHit = cache.getStatistics()
        assertEquals(1, statsAfterHit.hits)
        assertEquals(1, statsAfterHit.misses)
        assertEquals(2, statsAfterHit.totalRequests)
        assertEquals(0.5, statsAfterHit.hitRatio)

        // Peek does not increment hit count
        val peeked = cache.peek(1024L, "hash1")
        assertEquals(42, peeked?.value)
        assertEquals(1, cache.getStatistics().hits)

        // Clear resets everything
        cache.clear()
        assertEquals(0, cache.size())
        assertFalse(cache.contains(1024L, "hash1"))
        assertEquals(0, cache.getStatistics().hits)
        assertEquals(0, cache.getStatistics().misses)
    }

    @Test
    fun `warmUp preloads all existing files from database into memory`() {
        val db = createTestDb()

        transaction(db) {
            RepoFile.new {
                size = 100L
                hash = "hashA"
            }
            RepoFile.new {
                size = 200L
                hash = "hashB"
            }
            RepoFile.new {
                size = 300L
                hash = "hashC"
            }
        }

        val cache = InMemoryDeduplicationCache()
        assertEquals(0, cache.size())

        val loadedCount = cache.warmUp(db)
        assertEquals(3, loadedCount)
        assertEquals(3, cache.size())

        assertTrue(cache.contains(100L, "hashA"))
        assertTrue(cache.contains(200L, "hashB"))
        assertTrue(cache.contains(300L, "hashC"))
        assertFalse(cache.contains(400L, "hashD"))

        // Lookup loaded entries
        val idA = cache.get(100L, "hashA")
        assertNotNull(idA)
        assertEquals(1, idA.value)
    }

    @Test
    fun `concurrent access from multiple threads is thread-safe`() {
        val cache = InMemoryDeduplicationCache()
        val executor = Executors.newFixedThreadPool(8)
        val itemCount = 500

        try {
            val tasks = (1..itemCount).map { i ->
                Callable {
                    val size = i.toLong()
                    val hash = "hash-$i"
                    val id = EntityID(i, RepoFiles)
                    cache.put(size, hash, id)
                    cache.get(size, hash)
                }
            }

            val futures = executor.invokeAll(tasks)
            for (f in futures) {
                assertNotNull(f.get())
            }

            assertEquals(itemCount, cache.size())
            assertEquals(itemCount.toLong(), cache.getStatistics().hits)
        } finally {
            executor.shutdown()
        }
    }
}
