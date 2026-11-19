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
