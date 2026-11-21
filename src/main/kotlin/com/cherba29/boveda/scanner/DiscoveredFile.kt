package com.cherba29.boveda.scanner

import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Represents a file or symbolic link discovered during directory traversal prior to hashing.
 *
 * @property path Absolute or normalized path to the entry.
 * @property relativePath Path relative to the scan root directory.
 * @property size Size of the entry in bytes (for symlinks, the symlink's own size, not its target's).
 * @property lastModifiedTime Modification timestamp of the entry, if available.
 * @property isSymlink Whether this entry is a symbolic link.
 * @property symlinkTarget Normalized target path if [isSymlink] is true, otherwise null.
 */
data class DiscoveredFile(
  val path: Path,
  val relativePath: String,
  val size: Long,
  val lastModifiedTime: FileTime? = null,
  val isSymlink: Boolean = false,
  val symlinkTarget: String? = null
)
