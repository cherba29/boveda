package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.RecursiveAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Work-stealing parallel directory scanner that concurrentizes directory tree traversal
 * across a [ForkJoinPool].
 *
 * Subdirectory tasks and file hashing tasks are pushed to thread-local deques and stolen
 * by idle worker threads, ensuring optimal load balancing across deep and wide hierarchies.
 *
 * @property parallelism Level of parallelism (number of worker threads in the [ForkJoinPool]).
 * @property hasher The [FileHasher] implementation used to compute cryptographic hashes.
 * @property queueCapacity Bounded capacity for the output queue, preventing unbounded memory consumption.
 * @property fileChunkSize Maximum number of files processed per [HashFilesTask] for balanced work-stealing granularity.
 */
class WorkStealingDirectoryScanner(
  val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
  private val hasher: FileHasher = MessageDigestFileHasher(),
  private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
  private val fileChunkSize: Int = DEFAULT_FILE_CHUNK_SIZE,
  private val maxBytes: Long? = null
) : DirectoryScanner {

  companion object {
    const val DEFAULT_QUEUE_CAPACITY = 2048
    const val DEFAULT_FILE_CHUNK_SIZE = 16
  }

  init {
    require(parallelism >= 1) { "parallelism must be at least 1, but was $parallelism" }
    require(queueCapacity >= 1) { "queueCapacity must be at least 1, but was $queueCapacity" }
    require(fileChunkSize >= 1) { "fileChunkSize must be at least 1, but was $fileChunkSize" }
  }

  private sealed interface ResultItem {
    data class Success(val file: ScannedFile) : ResultItem
    data class Failure(val error: Throwable) : ResultItem
    data object Done : ResultItem
  }

  private class NamedForkJoinWorkerThreadFactory(
    private val prefix: String
  ) : ForkJoinPool.ForkJoinWorkerThreadFactory {
    private val counter = AtomicInteger(1)
    override fun newThread(pool: ForkJoinPool): ForkJoinWorkerThread {
      val thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
      thread.name = "$prefix-${counter.getAndIncrement()}"
      return thread
    }
  }

  private class HashFilesTask(
    private val files: List<DiscoveredFile>,
    private val algorithm: String,
    private val hasher: FileHasher,
    private val resultQueue: BlockingQueue<ResultItem>,
    private val maxBytes: Long? = null
  ) : RecursiveAction() {

    override fun compute() {
      for (file in files) {
        if (Thread.currentThread().isInterrupted) return
        try {
          val hash = if (file.isSymlink) {
            SymlinkUtils.computeSymlinkHash(file.symlinkTarget)
          } else {
            hasher.computeHash(file.path.toFile(), algorithm, maxBytes)
          }
          val scanned = ScannedFile(
            path = file.path,
            relativePath = file.relativePath,
            size = file.size,
            hash = hash,
            lastModifiedTime = file.lastModifiedTime,
            isSymlink = file.isSymlink,
            symlinkTarget = file.symlinkTarget
          )
          resultQueue.put(ResultItem.Success(scanned))
        } catch (e: Throwable) {
          if (e !is InterruptedException) {
            resultQueue.put(ResultItem.Failure(e))
          }
          return
        }
      }
    }
  }

  private class ScanDirectoryTask(
    private val dir: Path,
    private val normalizedRoot: Path,
    private val algorithm: String,
    private val hasher: FileHasher,
    private val resultQueue: BlockingQueue<ResultItem>,
    private val fileChunkSize: Int,
    private val shouldSkip: ((DiscoveredFile) -> Boolean)? = null,
    private val maxBytes: Long? = null
  ) : RecursiveAction() {

    override fun compute() {
      if (Thread.currentThread().isInterrupted) return

      val subDirTasks = mutableListOf<ScanDirectoryTask>()
      val discoveredFiles = mutableListOf<DiscoveredFile>()

      try {
        Files.newDirectoryStream(dir).use { stream ->
          for (entry in stream) {
            if (Thread.currentThread().isInterrupted) return
            val normalizedEntry = entry.normalize()
            val attrs = try {
              Files.readAttributes(
                normalizedEntry,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
              )
            } catch (_: Exception) {
              continue
            }

            if (attrs.isSymbolicLink) {
              val relative = normalizedRoot.relativize(
                normalizedEntry
              ).invariantSeparatorsPathString
              val relativePath = if (relative.isNotEmpty()) {
                relative
              } else {
                normalizedEntry.invariantSeparatorsPathString
              }
              val symlinkTarget = SymlinkUtils.resolveTarget(normalizedEntry)
              val discovered = DiscoveredFile(
                path = normalizedEntry,
                relativePath = relativePath,
                size = attrs.size(),
                lastModifiedTime = attrs.lastModifiedTime(),
                isSymlink = true,
                symlinkTarget = symlinkTarget
              )
              if (shouldSkip == null || !shouldSkip(discovered)) {
                discoveredFiles.add(discovered)
              }
            } else if (attrs.isDirectory) {
              subDirTasks.add(
                ScanDirectoryTask(
                  dir = normalizedEntry,
                  normalizedRoot = normalizedRoot,
                  algorithm = algorithm,
                  hasher = hasher,
                  resultQueue = resultQueue,
                  fileChunkSize = fileChunkSize,
                  shouldSkip = shouldSkip,
                  maxBytes = maxBytes
                )
              )
            } else if (attrs.isRegularFile) {
              val relative = normalizedRoot.relativize(
                normalizedEntry
              ).invariantSeparatorsPathString
              val relativePath = if (relative.isNotEmpty()) {
                relative
              } else {
                normalizedEntry.invariantSeparatorsPathString
              }
              val discovered = DiscoveredFile(
                path = normalizedEntry,
                relativePath = relativePath,
                size = attrs.size(),
                lastModifiedTime = attrs.lastModifiedTime(),
                isSymlink = false,
                symlinkTarget = null
              )
              if (shouldSkip == null || !shouldSkip(discovered)) {
                discoveredFiles.add(discovered)
              }
            }
          }
        }
      } catch (e: Throwable) {
        if (e !is InterruptedException) {
          resultQueue.put(ResultItem.Failure(e))
        }
        return
      }

      // Chunk discovered files into hash tasks and clear intermediate list to release references
      val hashTasks = mutableListOf<RecursiveAction>()
      if (discoveredFiles.isNotEmpty()) {
        for (chunk in discoveredFiles.chunked(fileChunkSize)) {
          hashTasks.add(
            HashFilesTask(
              files = chunk,
              algorithm = algorithm,
              hasher = hasher,
              resultQueue = resultQueue,
              maxBytes = maxBytes
            )
          )
        }
        discoveredFiles.clear()
      }

      // Place hash tasks first so the current worker hashes files immediately (applying queue
      // backpressure early) while subdirectory tasks sit at the deque base for stealing by idle
      // workers.
      val allTasks = ArrayList<RecursiveAction>(hashTasks.size + subDirTasks.size)
      allTasks.addAll(hashTasks)
      allTasks.addAll(subDirTasks)
      hashTasks.clear()
      subDirTasks.clear()

      if (allTasks.isNotEmpty()) {
        invokeAll(allTasks)
      }
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
      val resultQueue = ArrayBlockingQueue<ResultItem>(queueCapacity)
      // LIFO depth-first traversal with FIFO work-stealing from top of deque
      val asyncMode = false
      // Cap maximumPoolSize to parallelism and allow saturation so blocking resultQueue.put()
      // backpressure never triggers ForkJoinPool compensation thread storms (pthread_create EAGAIN).
      val pool = ForkJoinPool(
        parallelism,
        NamedForkJoinWorkerThreadFactory("boveda-work-stealing"),
        null,
        asyncMode,
        parallelism,
        parallelism,
        1,
        Predicate { true },
        60L,
        TimeUnit.SECONDS
      )

      val rootTask = ScanDirectoryTask(
        dir = normalizedRoot,
        normalizedRoot = normalizedRoot,
        algorithm = algorithm,
        hasher = hasher,
        resultQueue = resultQueue,
        fileChunkSize = fileChunkSize,
        shouldSkip = shouldSkip,
        maxBytes = effectiveMaxBytes
      )

      val rootFuture = pool.submit(rootTask)

      val monitorThread = Thread({
        try {
          rootFuture.join()
          resultQueue.put(ResultItem.Done)
        } catch (t: Throwable) {
          val cause = t.cause ?: t
          if (cause !is InterruptedException) {
            resultQueue.offer(ResultItem.Failure(cause))
          }
          resultQueue.offer(ResultItem.Done)
        }
      }, "boveda-work-stealing-monitor").apply {
        isDaemon = true
        start()
      }

      try {
        while (true) {
          val item = resultQueue.take()
          when (item) {
            is ResultItem.Success -> yield(item.file)
            is ResultItem.Failure -> throw item.error
            is ResultItem.Done -> break
          }
        }
      } finally {
        pool.shutdownNow()
        monitorThread.interrupt()
      }
    }
  }
}
