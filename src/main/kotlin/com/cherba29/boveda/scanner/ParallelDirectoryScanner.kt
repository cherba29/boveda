package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

/**
 * Multi-threaded producer-consumer directory scanner that decouples filesystem traversal
 * (Stage 1) from parallel cryptographic hashing (Stage 2) using bounded work queues.
 *
 * @property threadCount Number of worker threads allocated for concurrent cryptographic hashing.
 * @property hasher The [FileHasher] implementation used to compute file hashes.
 * @property queueCapacity Capacity limit for bounded queues, preventing unbounded memory growth.
 */
class ParallelDirectoryScanner(
  val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
  private val hasher: FileHasher = MessageDigestFileHasher(),
  private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : DirectoryScanner {

  companion object {
    const val DEFAULT_QUEUE_CAPACITY = 2048
  }

  init {
    require(threadCount >= 1) { "threadCount must be at least 1, but was $threadCount" }
    require(queueCapacity >= 1) { "queueCapacity must be at least 1, but was $queueCapacity" }
  }

  private sealed interface WorkItem {
    data class FileItem(
      val path: Path,
      val relativePath: String,
      val size: Long,
      val lastModifiedTime: FileTime?
    ) : WorkItem

    data object Done : WorkItem
  }

  private sealed interface ResultItem {
    data class Success(val file: ScannedFile) : ResultItem
    data class Failure(val error: Throwable) : ResultItem
    data object Done : ResultItem
  }

  private class NamedThreadFactory(private val prefix: String) : ThreadFactory {
    private val counter = AtomicInteger(1)
    override fun newThread(r: Runnable): Thread {
      val thread = Thread(r, "$prefix-${counter.getAndIncrement()}")
      thread.isDaemon = true
      return thread
    }
  }

  override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> =
    scan(rootPath, algorithm, shouldSkip = null)

  override fun scan(
    rootPath: Path,
    algorithm: String,
    shouldSkip: ((DiscoveredFile) -> Boolean)?
  ): Sequence<ScannedFile> {
    require(Files.exists(rootPath)) { "Target path does not exist: $rootPath" }
    require(Files.isDirectory(rootPath)) { "Target path is not a directory: $rootPath" }

    val normalizedRoot = rootPath.normalize()

    return sequence {
      val workQueue = ArrayBlockingQueue<WorkItem>(queueCapacity)
      val resultQueue = ArrayBlockingQueue<ResultItem>(queueCapacity)
      val activeWorkers = AtomicInteger(threadCount)

      val producerExecutor = Executors.newSingleThreadExecutor(
        NamedThreadFactory("boveda-scanner-producer")
      )
      val workerExecutor = Executors.newFixedThreadPool(
        threadCount,
        NamedThreadFactory("boveda-hash-worker")
      )

      // Stage 1: Producer Thread (Directory Traversal)
      producerExecutor.execute {
        try {
          for (filePath in normalizedRoot.walk()) {
            if (Thread.currentThread().isInterrupted) break
            if (filePath.isRegularFile()) {
              val normalizedFilePath = filePath.normalize()
              val relative = normalizedRoot.relativize(
                normalizedFilePath
              ).invariantSeparatorsPathString
              val relativePath = if (relative.isNotEmpty()) {
                relative
              } else {
                normalizedFilePath.invariantSeparatorsPathString
              }
              val size = try {
                normalizedFilePath.fileSize()
              } catch (_: Exception) {
                0L
              }
              val lastModified = try {
                normalizedFilePath.getLastModifiedTime()
              } catch (_: Exception) {
                null
              }

              val discovered = DiscoveredFile(
                path = normalizedFilePath,
                relativePath = relativePath,
                size = size,
                lastModifiedTime = lastModified
              )

              if (shouldSkip == null || !shouldSkip(discovered)) {
                workQueue.put(
                  WorkItem.FileItem(
                    path = normalizedFilePath,
                    relativePath = relativePath,
                    size = size,
                    lastModifiedTime = lastModified
                  )
                )
              }
            }
          }

          // Traversal finished: enqueue poison pills for each worker thread
          repeat(threadCount) {
            workQueue.put(WorkItem.Done)
          }
        } catch (t: Throwable) {
          if (t !is InterruptedException) {
            resultQueue.put(ResultItem.Failure(t))
          }
          repeat(threadCount) {
            workQueue.offer(WorkItem.Done)
          }
        }
      }

      // Stage 2: Parallel Hash Worker Pool
      for (i in 0 until threadCount) {
        workerExecutor.execute {
          try {
            while (true) {
              val item = workQueue.take()
              if (item is WorkItem.Done) {
                break
              }
              if (item is WorkItem.FileItem) {
                val hash = hasher.computeHash(item.path.toFile(), algorithm)
                val scannedFile = ScannedFile(
                  path = item.path,
                  relativePath = item.relativePath,
                  size = item.size,
                  hash = hash,
                  lastModifiedTime = item.lastModifiedTime
                )
                resultQueue.put(ResultItem.Success(scannedFile))
              }
            }
          } catch (t: Throwable) {
            if (t !is InterruptedException) {
              resultQueue.put(ResultItem.Failure(t))
            }
          } finally {
            if (activeWorkers.decrementAndGet() == 0) {
              try {
                resultQueue.put(ResultItem.Done)
              } catch (_: InterruptedException) {}
            }
          }
        }
      }

      // Stage 3: Consumer (Yields results to pipeline/caller)
      try {
        while (true) {
          val result = resultQueue.take()
          when (result) {
            is ResultItem.Success -> yield(result.file)
            is ResultItem.Failure -> throw result.error
            is ResultItem.Done -> break
          }
        }
      } finally {
        producerExecutor.shutdownNow()
        workerExecutor.shutdownNow()
      }
    }
  }
}
