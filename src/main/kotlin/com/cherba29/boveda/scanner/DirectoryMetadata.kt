package com.cherba29.boveda.scanner

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.RecursiveTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Predicate

/**
 * Pre-computed metadata for a directory tree.
 *
 * @property fileCount Total number of regular files discovered.
 * @property totalBytes Total byte size across all regular files discovered.
 */
data class DirectoryMetadata(
  val fileCount: Long,
  val totalBytes: Long
) {
  companion object {
    val EMPTY = DirectoryMetadata(fileCount = 0L, totalBytes = 0L)
  }
}

/**
 * Interface for fast, metadata-only directory scanning without reading file contents.
 */
interface DirectoryMetadataScanner {
  /**
   * Recursively scans [rootPath] and returns the aggregated [DirectoryMetadata].
   */
  fun scan(rootPath: Path): DirectoryMetadata

  /**
   * Recursively scans [rootPath] and returns the aggregated [DirectoryMetadata],
   * reporting progressive discoveries via [onProgress].
   */
  fun scan(
    rootPath: Path,
    onProgress: (filesScanned: Long, bytesScanned: Long) -> Unit
  ): DirectoryMetadata = scan(rootPath)
}

/**
 * Sequential metadata scanner using Java NIO [Files.walkFileTree].
 * Retrieves inode/file attributes directly from directory traversal without reading file contents.
 */
object FileTreeMetadataScanner : DirectoryMetadataScanner {
  override fun scan(rootPath: Path): DirectoryMetadata = scan(rootPath) { _, _ -> }

  override fun scan(
    rootPath: Path,
    onProgress: (filesScanned: Long, bytesScanned: Long) -> Unit
  ): DirectoryMetadata {
    require(Files.exists(rootPath)) { "Target path does not exist: $rootPath" }
    require(Files.isDirectory(rootPath)) { "Target path is not a directory: $rootPath" }

    var fileCount = 0L
    var totalBytes = 0L

    Files.walkFileTree(
      rootPath.normalize(),
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (attrs.isRegularFile || attrs.isSymbolicLink) {
            fileCount++
            totalBytes += attrs.size()
            onProgress(fileCount, totalBytes)
          }
          return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
          return FileVisitResult.CONTINUE
        }
      }
    )

    return DirectoryMetadata(fileCount = fileCount, totalBytes = totalBytes)
  }
}

/**
 * Parallel work-stealing metadata scanner using [ForkJoinPool] for maximum performance
 * on multi-core systems and fast storage (NVMe/SSD).
 *
 * @property parallelism The number of worker threads in the fork-join pool.
 */
class WorkStealingMetadataScanner(
  val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
) : DirectoryMetadataScanner {

  init {
    require(parallelism >= 1) { "parallelism must be at least 1, but was $parallelism" }
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

  private class ScanMetadataTask(
    private val dir: Path,
    private val rootDir: Path,
    private val filesCounter: AtomicLong,
    private val bytesCounter: AtomicLong,
    private val onProgress: (filesScanned: Long, bytesScanned: Long) -> Unit
  ) : RecursiveTask<DirectoryMetadata>() {
    override fun compute(): DirectoryMetadata {
      if (Thread.currentThread().isInterrupted) return DirectoryMetadata.EMPTY

      val subTasks = mutableListOf<ScanMetadataTask>()
      var fileCount = 0L
      var totalBytes = 0L

      try {
        val walkDir = if (dir == rootDir && Files.isSymbolicLink(dir)) {
          dir.toRealPath()
        } else {
          dir
        }
        val remapPrefix = walkDir != dir

        // Files.walkFileTree(depth = 1) reuses Windows FindNextFileW BasicFileAttributesHolder,
        // eliminating per-entry GetFileAttributesExW syscalls.
        Files.walkFileTree(
          walkDir,
          emptySet(),
          1,
          object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
              if (file == walkDir) return FileVisitResult.CONTINUE
              if (attrs.isSymbolicLink || attrs.isRegularFile) {
                val size = attrs.size()
                fileCount++
                totalBytes += size
                onProgress(filesCounter.incrementAndGet(), bytesCounter.addAndGet(size))
              } else if (attrs.isDirectory) {
                val subDir = if (remapPrefix) {
                  dir.resolve(file.fileName).normalize()
                } else {
                  file.normalize()
                }
                subTasks.add(
                  ScanMetadataTask(subDir, rootDir, filesCounter, bytesCounter, onProgress)
                )
              }
              return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult =
              FileVisitResult.CONTINUE
          }
        )
      } catch (_: Exception) {
        // Ignore unreadable directory
      }

      if (subTasks.isNotEmpty()) {
        invokeAll(subTasks)
        for (task in subTasks) {
          val result = task.rawResult ?: task.join()
          fileCount += result.fileCount
          totalBytes += result.totalBytes
        }
      }

      return DirectoryMetadata(fileCount = fileCount, totalBytes = totalBytes)
    }
  }

  override fun scan(rootPath: Path): DirectoryMetadata = scan(rootPath) { _, _ -> }

  override fun scan(
    rootPath: Path,
    onProgress: (filesScanned: Long, bytesScanned: Long) -> Unit
  ): DirectoryMetadata {
    require(Files.exists(rootPath)) { "Target path does not exist: $rootPath" }
    require(Files.isDirectory(rootPath)) { "Target path is not a directory: $rootPath" }

    val normalizedRoot = rootPath.normalize()
    // Strictly cap maximumPoolSize to parallelism to prevent compensation thread storms on slow I/O
    val pool = ForkJoinPool(
      parallelism,
      NamedForkJoinWorkerThreadFactory("boveda-metadata-scanner"),
      null,
      false,
      parallelism,
      parallelism,
      1,
      Predicate { true },
      60L,
      TimeUnit.SECONDS
    )
    val filesCounter = AtomicLong(0L)
    val bytesCounter = AtomicLong(0L)
    return try {
      pool.invoke(
        ScanMetadataTask(normalizedRoot, normalizedRoot, filesCounter, bytesCounter, onProgress)
      )
    } finally {
      pool.shutdownNow()
    }
  }
}
