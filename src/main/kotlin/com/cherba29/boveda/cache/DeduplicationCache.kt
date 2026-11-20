package com.cherba29.boveda.cache

import com.cherba29.boveda.db.RepoFiles
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
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
 * Thread-safe bounded in-memory implementation of [DeduplicationCache] backed by a Guava [Cache].
 *
 * Uses size-based LRU eviction and stores raw primitive integer IDs to keep heap usage bounded
 * even when scanning millions of unique files.
 *
 * @param initialCapacity Initial capacity for the underlying cache.
 * @param maxSize Maximum number of `(size, hash) -> file_id` entries retained in memory.
 */
class InMemoryDeduplicationCache(
  initialCapacity: Int = 256,
  val maxSize: Long = DEFAULT_MAX_SIZE
) : DeduplicationCache {

  companion object {
    const val DEFAULT_MAX_SIZE = 250_000L
  }

  init {
    require(initialCapacity >= 0) {
      "initialCapacity must be non-negative, but was $initialCapacity"
    }
    require(maxSize >= 1L) { "maxSize must be at least 1, but was $maxSize" }
  }

  private val cache: Cache<FileContentKey, Int> = run {
    val maxCap = maxSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    CacheBuilder.newBuilder()
      .initialCapacity(initialCapacity.coerceAtMost(maxCap))
      .maximumSize(maxSize)
      .build()
  }
  private val hits = AtomicLong(0)
  private val misses = AtomicLong(0)

  override fun get(size: Long, hash: String): EntityID<Int>? {
    val key = FileContentKey(size, hash)
    val result = cache.getIfPresent(key)
    if (result != null) {
      hits.incrementAndGet()
      return EntityID(result, RepoFiles)
    } else {
      misses.incrementAndGet()
      return null
    }
  }

  override fun peek(size: Long, hash: String): EntityID<Int>? {
    val result = cache.getIfPresent(FileContentKey(size, hash)) ?: return null
    return EntityID(result, RepoFiles)
  }

  override fun put(size: Long, hash: String, id: EntityID<Int>) {
    cache.put(FileContentKey(size, hash), id.value)
  }

  override fun contains(size: Long, hash: String): Boolean {
    return cache.asMap().containsKey(FileContentKey(size, hash))
  }

  override fun warmUp(database: Database): Int = transaction(database) {
    var count = 0
    val limitCount = maxSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    RepoFiles.select(RepoFiles.id, RepoFiles.size, RepoFiles.hash)
      .limit(limitCount)
      .forEach { row ->
        put(row[RepoFiles.size], row[RepoFiles.hash], row[RepoFiles.id])
        count++
      }
    count
  }

  override fun size(): Int = cache.size().toInt()

  override fun clear() {
    cache.invalidateAll()
    cache.cleanUp()
    hits.set(0)
    misses.set(0)
  }

  override fun getStatistics(): CacheStatistics = CacheStatistics(
    size = size(),
    hits = hits.get(),
    misses = misses.get()
  )
}
