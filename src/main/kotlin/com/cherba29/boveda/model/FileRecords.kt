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
