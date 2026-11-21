package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.walk

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

    return normalizedRoot.walk()
      .mapNotNull { filePath ->
        val normalizedFilePath = filePath.normalize()
        val attrs = try {
          Files.readAttributes(
            normalizedFilePath,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
          )
        } catch (_: Exception) {
          return@mapNotNull null
        }

        val isSymlink = attrs.isSymbolicLink
        if (!isSymlink && !attrs.isRegularFile) {
          return@mapNotNull null
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

        if (shouldSkip != null && shouldSkip(discovered)) {
          return@mapNotNull null
        }

        val hash = if (isSymlink) {
          SymlinkUtils.computeSymlinkHash(symlinkTarget)
        } else {
          hasher.computeHash(normalizedFilePath.toFile(), algorithm, effectiveMaxBytes)
        }

        ScannedFile(
          path = normalizedFilePath,
          relativePath = relativePath,
          size = size,
          hash = hash,
          lastModifiedTime = lastModified,
          isSymlink = isSymlink,
          symlinkTarget = symlinkTarget
        )
      }
  }
}
