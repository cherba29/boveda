package com.cherba29.boveda.model

/**
 * Result model representing a file instance recorded in a repository snapshot.
 *
 * @property snapshotId The ID of the snapshot this instance belongs to.
 * @property path The recorded file path.
 * @property size The file size in bytes.
 * @property hash The content hash string.
 */
data class FileRecord(
  val snapshotId: Int,
  val path: String,
  val size: Long,
  val hash: String
)

/**
 * Result model representing a file instance with its partition rank for duplicate detection.
 *
 * @property snapshotId The ID of the snapshot this instance belongs to.
 * @property path The recorded file path.
 * @property size The file size in bytes.
 * @property hash The content hash string.
 * @property duplicateRank The 1-based row number partitioned by content hash. Ranks > 1 denote duplicates.
 */
data class FileRecordWithDuplicateRank(
  val snapshotId: Int,
  val path: String,
  val size: Long,
  val hash: String,
  val duplicateRank: Long
)

/**
 * Summary record representing an existing repository snapshot.
 *
 * @property id The unique snapshot identifier.
 * @property rootPath The root directory path scanned in this snapshot.
 * @property isCompleted Whether the snapshot scan successfully completed.
 * @property fileCount Total number of file instances recorded under this snapshot.
 * @property totalBytes Total storage size in bytes across all files recorded under this snapshot.
 */
data class SnapshotSummary(
  val id: Int,
  val rootPath: String,
  val isCompleted: Boolean,
  val fileCount: Long,
  val totalBytes: Long
)

/**
 * Result model representing the deletion outcome of a file from disk and database.
 *
 * @property path The recorded file path.
 * @property deletedFromDisk Whether the file was deleted from the filesystem.
 * @property deletedFromDb Whether the file instance was deleted from the database.
 * @property hasDuplicates Whether the file content was duplicated in the snapshot prior to deletion.
 */
data class FileDeletionResult(
  val path: String,
  val deletedFromDisk: Boolean,
  val deletedFromDb: Boolean,
  val hasDuplicates: Boolean
)
