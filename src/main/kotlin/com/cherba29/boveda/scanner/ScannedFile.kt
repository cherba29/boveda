package com.cherba29.boveda.scanner

import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Represents a regular file encountered during directory scanning with its computed metadata.
 *
 * @property path The absolute or normalized path to the file.
 * @property relativePath The path relative to the scanned root directory.
 * @property size The size of the file in bytes.
 * @property hash The cryptographic hash of the file content.
 * @property lastModifiedTime The last modified timestamp of the file, if available.
 */
data class ScannedFile(
  val path: Path,
  val relativePath: String,
  val size: Long,
  val hash: String,
  val lastModifiedTime: FileTime? = null
)
