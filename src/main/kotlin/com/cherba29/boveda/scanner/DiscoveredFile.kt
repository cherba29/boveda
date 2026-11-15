package com.cherba29.boveda.scanner

import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Represents a regular file discovered during directory traversal prior to cryptographic hashing.
 *
 * @property path Absolute or normalized path to the file.
 * @property relativePath Path relative to the scan root directory.
 * @property size Size of the file in bytes.
 * @property lastModifiedTime File modification timestamp, if available.
 */
data class DiscoveredFile(
    val path: Path,
    val relativePath: String,
    val size: Long,
    val lastModifiedTime: FileTime? = null
)
