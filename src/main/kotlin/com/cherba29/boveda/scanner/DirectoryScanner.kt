package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Interface for scanning directories for regular files.
 */
interface DirectoryScanner {
  /**
   * Recursively scans [rootPath] and yields a [Sequence] of [ScannedFile] entries.
   */
  fun scan(rootPath: Path, algorithm: String = "XXH3"): Sequence<ScannedFile>

  /**
   * Recursively scans [rootPath] and yields a [Sequence] of [ScannedFile] entries.
   * If [shouldSkip] is provided and returns true for a [DiscoveredFile], the file is skipped
   * without reading its contents or computing its cryptographic hash.
   */
  fun scan(
    rootPath: Path,
    algorithm: String = "XXH3",
    shouldSkip: ((DiscoveredFile) -> Boolean)? = null
  ): Sequence<ScannedFile> = scan(rootPath, algorithm, shouldSkip, maxBytes = null)

  /**
   * Recursively scans [rootPath] and yields a [Sequence] of [ScannedFile] entries,
   * hashing at most [maxBytes] per file as well as the file size.
   */
  fun scan(
    rootPath: Path,
    algorithm: String = "XXH3",
    shouldSkip: ((DiscoveredFile) -> Boolean)? = null,
    maxBytes: Long? = null
  ): Sequence<ScannedFile> = if (shouldSkip == null) {
    scan(rootPath, algorithm)
  } else {
    scan(rootPath, algorithm).filterNot {
      shouldSkip(
        DiscoveredFile(
          it.path,
          it.relativePath,
          it.size,
          it.lastModifiedTime,
          it.isSymlink,
          it.symlinkTarget
        )
      )
    }
  }
}

/**
 * Default implementation of [DirectoryScanner] using Kotlin's [Path.walk] API.
 *
 * @property hasher The [FileHasher] used to compute hashes for discovered files.
 * @property maxBytes Maximum initial bytes to hash per file (null = full file).
 */
class FileTreeDirectoryScanner(
  private val hasher: FileHasher = MessageDigestFileHasher(),
  private val maxBytes: Long? = null
) : DirectoryScanner {

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
      val dirStack = ArrayDeque<Path>()
      dirStack.addLast(normalizedRoot)

      while (dirStack.isNotEmpty()) {
        val currentDir = dirStack.removeLast()
        val walkDir = if (currentDir == normalizedRoot && Files.isSymbolicLink(currentDir)) {
          try {
            currentDir.toRealPath()
          } catch (_: Exception) {
            continue
          }
        } else {
          currentDir
        }
        val remapPrefix = walkDir != currentDir
        val childDirs = mutableListOf<Path>()
        val childFiles = mutableListOf<DiscoveredFile>()

        try {
          Files.walkFileTree(
            walkDir,
            emptySet(),
            1,
            object : SimpleFileVisitor<Path>() {
              override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file == walkDir) return FileVisitResult.CONTINUE
                val normalizedFile = if (remapPrefix) {
                  currentDir.resolve(file.fileName).normalize()
                } else {
                  file.normalize()
                }
                val isSymlink = attrs.isSymbolicLink
                if (!isSymlink && attrs.isDirectory) {
                  childDirs.add(normalizedFile)
                  return FileVisitResult.CONTINUE
                }
                if (!isSymlink && !attrs.isRegularFile) {
                  return FileVisitResult.CONTINUE
                }

                val relative = normalizedRoot.relativize(
                  normalizedFile
                ).invariantSeparatorsPathString
                val relativePath = if (relative.isNotEmpty()) {
                  relative
                } else {
                  normalizedFile.invariantSeparatorsPathString
                }
                val symlinkTarget = if (isSymlink) {
                  SymlinkUtils.resolveTarget(normalizedFile)
                } else {
                  null
                }
                childFiles.add(
                  DiscoveredFile(
                    path = normalizedFile,
                    relativePath = relativePath,
                    size = attrs.size(),
                    lastModifiedTime = attrs.lastModifiedTime(),
                    isSymlink = isSymlink,
                    symlinkTarget = symlinkTarget
                  )
                )
                return FileVisitResult.CONTINUE
              }

              override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult =
                FileVisitResult.CONTINUE
            }
          )
        } catch (_: Exception) {
          continue
        }

        for (i in childDirs.lastIndex downTo 0) {
          dirStack.addLast(childDirs[i])
        }

        for (discovered in childFiles) {
          if (shouldSkip != null && shouldSkip(discovered)) continue
          val hash = if (discovered.isSymlink) {
            SymlinkUtils.computeSymlinkHash(discovered.symlinkTarget)
          } else {
            hasher.computeHash(discovered.path, algorithm, effectiveMaxBytes, discovered.size)
          }
          yield(
            ScannedFile(
              path = discovered.path,
              relativePath = discovered.relativePath,
              size = discovered.size,
              hash = hash,
              lastModifiedTime = discovered.lastModifiedTime,
              isSymlink = discovered.isSymlink,
              symlinkTarget = discovered.symlinkTarget
            )
          )
        }
      }
    }
  }
}
