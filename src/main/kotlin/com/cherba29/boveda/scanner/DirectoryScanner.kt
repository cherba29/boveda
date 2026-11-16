package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
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
      shouldSkip(DiscoveredFile(it.path, it.relativePath, it.size, it.lastModifiedTime))
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
      .filter { it.isRegularFile() }
      .mapNotNull { filePath ->
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

        if (shouldSkip != null && shouldSkip(discovered)) {
          return@mapNotNull null
        }

        val hash = hasher.computeHash(normalizedFilePath.toFile(), algorithm, effectiveMaxBytes)

        ScannedFile(
          path = normalizedFilePath,
          relativePath = relativePath,
          size = size,
          hash = hash,
          lastModifiedTime = lastModified
        )
      }
  }
}
