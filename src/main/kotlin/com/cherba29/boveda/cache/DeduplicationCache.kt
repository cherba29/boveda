package com.cherba29.boveda.cache

import com.cherba29.boveda.db.RepoFiles
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Composite key representing the unique content of a file by its byte [size] and content [hash].
 */
data class FileContentKey(
    val size: Long,
    val hash: String
)

/**
 * Statistics snapshot representing deduplication cache performance.
 *
 * @property size Number of cached content entries.
 * @property hits Number of successful cache lookups.
 * @property misses Number of cache misses.
 */
data class CacheStatistics(
    val size: Int,
    val hits: Long,
    val misses: Long
) {
    val totalRequests: Long get() = hits + misses
    val hitRatio: Double get() = if (totalRequests > 0) hits.toDouble() / totalRequests else 0.0
}

/**
 * In-memory deduplication cache mapping file content fingerprints `(size, hash)`
 * to their corresponding database [EntityID] in [RepoFiles].
 */
interface DeduplicationCache {

    /**
     * Retrieves the cached [EntityID] for the given [size] and [hash], or null if absent.
     * Records a cache hit or miss in cache statistics.
     */
    fun get(size: Long, hash: String): EntityID<Int>?

    /**
     * Internal lookup checking if [size] and [hash] is cached without altering hit/miss metrics.
     */
    fun peek(size: Long, hash: String): EntityID<Int>?

    /**
     * Associates the given [size] and [hash] with [id] in the cache.
     */
    fun put(size: Long, hash: String, id: EntityID<Int>)

    /**
     * Checks if the cache contains an entry for [size] and [hash].
     */
    fun contains(size: Long, hash: String): Boolean

    /**
     * Pre-warms the cache by loading all existing records from [RepoFiles] into memory.
     *
     * @param database The [Database] connection to query.
     * @return The number of records loaded into the cache.
     */
    fun warmUp(database: Database): Int

    /**
     * Returns the total number of entries currently held in the cache.
     */
    fun size(): Int

    /**
     * Clears all cached entries and resets statistics.
     */
    fun clear()

    /**
     * Returns a snapshot of current cache statistics.
     */
    fun getStatistics(): CacheStatistics
}

/**
 * Thread-safe in-memory implementation of [DeduplicationCache] backed by [ConcurrentHashMap].
 *
 * @param initialCapacity Initial capacity for the underlying map.
 */
class InMemoryDeduplicationCache(
    initialCapacity: Int = 256
) : DeduplicationCache {

    private val map = ConcurrentHashMap<FileContentKey, EntityID<Int>>(initialCapacity)
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)

    override fun get(size: Long, hash: String): EntityID<Int>? {
        val key = FileContentKey(size, hash)
        val result = map[key]
        if (result != null) {
            hits.incrementAndGet()
        } else {
            misses.incrementAndGet()
        }
        return result
    }

    override fun peek(size: Long, hash: String): EntityID<Int>? {
        return map[FileContentKey(size, hash)]
    }

    override fun put(size: Long, hash: String, id: EntityID<Int>) {
        map[FileContentKey(size, hash)] = id
    }

    override fun contains(size: Long, hash: String): Boolean {
        return map.containsKey(FileContentKey(size, hash))
    }

    override fun warmUp(database: Database): Int = transaction(database) {
        var count = 0
        RepoFiles.selectAll().forEach { row ->
            put(row[RepoFiles.size], row[RepoFiles.hash], row[RepoFiles.id])
            count++
        }
        count
    }

    override fun size(): Int = map.size

    override fun clear() {
        map.clear()
        hits.set(0)
        misses.set(0)
    }

    override fun getStatistics(): CacheStatistics = CacheStatistics(
        size = map.size,
        hits = hits.get(),
        misses = misses.get()
    )
}
