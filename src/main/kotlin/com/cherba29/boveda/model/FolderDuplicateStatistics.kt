package com.cherba29.boveda.model

/**
 * Details of a duplicate file shared between two folders.
 *
 * @property fileName Display name of the file.
 * @property size File size in bytes.
 * @property hash The cryptographic hash of the file content.
 * @property path1 Path to the file in the first folder.
 * @property path2 Path to the file in the second folder.
 * @property pathsInFolder1 All paths to copies of this file in the first folder.
 * @property pathsInFolder2 All paths to copies of this file in the second folder.
 */
data class DuplicateFileEntry(
  val fileName: String,
  val size: Long,
  val hash: String,
  val path1: String,
  val path2: String,
  val pathsInFolder1: List<String> = listOf(path1),
  val pathsInFolder2: List<String> = listOf(path2)
)

/**
 * Statistics for a pair of folders with duplicate files between them.
 *
 * @property folder1 Normalized path of the first folder.
 * @property folder2 Normalized path of the second folder.
 * @property sameFiles Total number of same (duplicate) files shared between the folders.
 * @property differentFiles Total number of different files between the two folders.
 * @property totalFiles Total distinct files across both folders (sameFiles + differentFiles).
 * @property duplicatedStorage Total byte size of shared duplicate files.
 * @property differentStorage Total byte size of different files between the two folders.
 * @property totalStorage Total byte size across both folders (duplicatedStorage + differentStorage).
 * @property duplicateFilesPercentage Percentage of files that are duplicates.
 * @property duplicateStoragePercentage Percentage of storage that is duplicated.
 * @property topDuplicateFiles Up to 3 largest duplicate files in these folders.
 */
data class FolderDuplicatePair(
  val folder1: String,
  val folder2: String,
  val sameFiles: Long,
  val differentFiles: Long,
  val totalFiles: Long,
  val duplicatedStorage: Long,
  val differentStorage: Long,
  val totalStorage: Long,
  val duplicateFilesPercentage: Double,
  val duplicateStoragePercentage: Double,
  val topDuplicateFiles: List<DuplicateFileEntry>
)

/**
 * Represents a group of 100% matching folders sharing the exact same set of files.
 *
 * @property signature Deterministic fingerprint of the exact multiset of files in each folder.
 * @property fileCount Number of files in each folder.
 * @property folderBytes Total storage size in bytes of a single folder copy.
 * @property paths Normalized folder paths of all 100% matching folders in this group.
 * @property sampleFiles Up to 5 largest sample files `(fileName, sizeBytes)` for display context.
 */
data class DuplicateFolderGroup(
  val signature: String,
  val fileCount: Int,
  val folderBytes: Long,
  val paths: List<String>,
  val sampleFiles: List<Pair<String, Long>> = emptyList()
) {
  /** Number of identical folder copies in this group. */
  val copyCount: Int get() = paths.size

  /** Total storage wasted by redundant folder copies (excluding 1 original copy). */
  val wastedBytes: Long get() = (copyCount - 1L).coerceAtLeast(0L) * folderBytes

  /** Total storage consumed by all folder copies combined. */
  val totalDuplicateBytes: Long get() = copyCount.toLong() * folderBytes
}

/**
 * Result model representing the deletion outcome of a folder from disk and database.
 *
 * @property path The normalized folder path.
 * @property filesDeletedFromDisk Number of files inside this folder deleted from the filesystem.
 * @property filesDeletedFromDb Number of file instances deleted from the database.
 * @property folderDeletedFromDisk Whether the directory itself was removed from the filesystem.
 * @property deletedFromDb Whether the folder record was deleted from the database.
 * @property hasMatchingDuplicate Whether a 100% matching duplicate folder existed prior to deletion.
 */
data class FolderDeletionResult(
  val path: String,
  val filesDeletedFromDisk: Int,
  val filesDeletedFromDb: Int,
  val folderDeletedFromDisk: Boolean,
  val deletedFromDb: Boolean,
  val hasMatchingDuplicate: Boolean
)
