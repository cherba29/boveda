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
    fun scan(rootPath: Path, algorithm: String = "MD5"): Sequence<ScannedFile>
}

/**
 * Default implementation of [DirectoryScanner] using Kotlin's [Path.walk] API.
 *
 * @property hasher The [FileHasher] used to compute hashes for discovered files.
 */
class FileTreeDirectoryScanner(
    private val hasher: FileHasher = MessageDigestFileHasher()
) : DirectoryScanner {

    override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> {
        require(Files.exists(rootPath)) { "Target path does not exist: $rootPath" }
        require(Files.isDirectory(rootPath)) { "Target path is not a directory: $rootPath" }

        val normalizedRoot = rootPath.normalize()

        return normalizedRoot.walk()
            .filter { it.isRegularFile() }
            .map { filePath ->
                val normalizedFilePath = filePath.normalize()
                val relative = normalizedRoot.relativize(normalizedFilePath).invariantSeparatorsPathString
                val relativePath = if (relative.isNotEmpty()) relative else normalizedFilePath.invariantSeparatorsPathString
                val hash = hasher.computeHash(normalizedFilePath.toFile(), algorithm)
                val size = normalizedFilePath.fileSize()
                val lastModified = try {
                    normalizedFilePath.getLastModifiedTime()
                } catch (_: Exception) {
                    null
                }

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
