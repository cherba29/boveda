package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.invariantSeparatorsPathString

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
  private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
  private val maxBytes: Long? = null
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
      val lastModifiedTime: FileTime?,
      val isSymlink: Boolean = false,
      val symlinkTarget: String? = null
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
    scan(rootPath, algorithm, shouldSkip = null, maxBytes = null)

  override fun scan(
    rootPath: Path,
    algorithm: String,
    shouldSkip: ((DiscoveredFile) -> Boolean)?
  ): Sequence<ScannedFile> = scan(rootPath, algorithm, shouldSkip, maxBytes = null)

  override fun scan(
    rootPath: Path,
    algorithm: String,
    shouldSkip: ((DiscoveredFile) -> Boolean)?,
    maxBytes: Long?
  ): Sequence<ScannedFile> {
    require(Files.exists(rootPath)) { "Target path does not exist: $rootPath" }
    require(Files.isDirectory(rootPath)) { "Target path is not a directory: $rootPath" }

    val normalizedRoot = rootPath.normalize()
    val effectiveMaxBytes = maxBytes ?: this.maxBytes

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

      // Stage 1: Producer Thread (Directory Traversal using Files.walkFileTree to reuse OS
      // cached directory-entry attributes without extra per-file stat syscalls)
      producerExecutor.execute {
        try {
          val walkRoot = if (Files.isSymbolicLink(normalizedRoot)) {
            normalizedRoot.toRealPath()
          } else {
            normalizedRoot
          }
          val remapPrefix = walkRoot != normalizedRoot

          Files.walkFileTree(
            walkRoot,
            object : SimpleFileVisitor<Path>() {
              override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
              ): FileVisitResult = if (Thread.currentThread().isInterrupted) {
                FileVisitResult.TERMINATE
              } else {
                FileVisitResult.CONTINUE
              }

              override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                val isSymlink = attrs.isSymbolicLink
                if (!isSymlink && !attrs.isRegularFile) {
                  return FileVisitResult.CONTINUE
                }

                val normalizedFilePath = if (remapPrefix) {
                  normalizedRoot.resolve(walkRoot.relativize(file)).normalize()
                } else {
                  file.normalize()
                }
                val relative = normalizedRoot.relativize(
                  normalizedFilePath
                ).invariantSeparatorsPathString
                val relativePath = if (relative.isNotEmpty()) {
                  relative
                } else {
                  normalizedFilePath.invariantSeparatorsPathString
                }
                val size = attrs.size()
                val lastModified = attrs.lastModifiedTime()
                val symlinkTarget = if (isSymlink) {
                  SymlinkUtils.resolveTarget(normalizedFilePath)
                } else {
                  null
                }

                val discovered = DiscoveredFile(
                  path = normalizedFilePath,
                  relativePath = relativePath,
                  size = size,
                  lastModifiedTime = lastModified,
                  isSymlink = isSymlink,
                  symlinkTarget = symlinkTarget
                )

                if (shouldSkip == null || !shouldSkip(discovered)) {
                  workQueue.put(
                    WorkItem.FileItem(
                      path = normalizedFilePath,
                      relativePath = relativePath,
                      size = size,
                      lastModifiedTime = lastModified,
                      isSymlink = isSymlink,
                      symlinkTarget = symlinkTarget
                    )
                  )
                }
                return FileVisitResult.CONTINUE
              }

              override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult =
                FileVisitResult.CONTINUE
            }
          )

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
                val hash = if (item.isSymlink) {
                  SymlinkUtils.computeSymlinkHash(item.symlinkTarget)
                } else {
                  hasher.computeHash(item.path, algorithm, effectiveMaxBytes, item.size)
                }
                val scannedFile = ScannedFile(
                  path = item.path,
                  relativePath = item.relativePath,
                  size = item.size,
                  hash = hash,
                  lastModifiedTime = item.lastModifiedTime,
                  isSymlink = item.isSymlink,
                  symlinkTarget = item.symlinkTarget
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
