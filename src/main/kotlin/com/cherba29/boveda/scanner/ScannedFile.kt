package com.cherba29.boveda.scanner

import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Represents a regular file or symbolic link encountered during directory scanning.
 *
 * @property path The absolute or normalized path to the entry.
 * @property relativePath The path relative to the scanned root directory.
 * @property size The size of the entry in bytes (for symlinks, the symlink's own size).
 * @property hash The content hash (or deterministic symlink marker hash for symlinks).
 * @property lastModifiedTime The last modified timestamp of the entry, if available.
 * @property isSymlink Whether this entry is a symbolic link.
 * @property symlinkTarget Normalized target path if [isSymlink] is true, otherwise null.
 */
data class ScannedFile(
  val path: Path,
  val relativePath: String,
  val size: Long,
  val hash: String,
  val lastModifiedTime: FileTime? = null,
  val isSymlink: Boolean = false,
  val symlinkTarget: String? = null
)
