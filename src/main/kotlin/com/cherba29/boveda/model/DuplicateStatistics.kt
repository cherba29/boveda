package com.cherba29.boveda.model

import java.util.Locale
import kotlin.time.Duration

/**
 * Represents a group of duplicate file instances sharing the exact same content hash and size.
 *
 * @property hash The cryptographic hash of the file content.
 * @property size The size of a single file copy in bytes.
 * @property copyCount The total number of copies discovered.
 * @property paths The relative or absolute file paths where this duplicate exists.
 */
data class DuplicateGroup(
  val hash: String,
  val size: Long,
  val copyCount: Int,
  val paths: List<String>
) {
  /** Total storage wasted by redundant copies (excluding the 1 original copy). */
  val wastedBytes: Long get() = (copyCount - 1L) * size

  /** Total storage consumed by all copies combined. */
  val totalDuplicateBytes: Long get() = copyCount.toLong() * size
}

/**
 * Represents a 10% percentile decile bucket of duplicate files partitioned by file size.
 *
 * @property bucketIndex 1-based index (1 to 10).
 * @property percentileRange String representation of the percentile span (e.g. "0% - 10%").
 * @property minSize The minimum file size in bytes in this bucket.
 * @property maxSize The maximum file size in bytes in this bucket.
 * @property fileCount The number of duplicate file instances in this bucket.
 * @property sizeBytes The total unique content size in bytes in this bucket.
 * @property totalDuplicateBytes The total duplicated storage occupied by files in this bucket.
 * @property wastedBytes The total redundant/wasted storage in this bucket ((copies - 1) * size).
 * @property percentageOfTotalWasted The percentage of total wasted storage accounted for by this bucket.
 */
data class PercentileBucket(
  val bucketIndex: Int,
  val percentileRange: String,
  val minSize: Long,
  val maxSize: Long,
  val fileCount: Int,
  val sizeBytes: Long,
  val totalDuplicateBytes: Long,
  val wastedBytes: Long,
  val percentageOfTotalWasted: Double
) {
  /** Backward-compatible constructor computing sizeBytes as totalDuplicateBytes - wastedBytes. */
  constructor(
    bucketIndex: Int,
    percentileRange: String,
    minSize: Long,
    maxSize: Long,
    fileCount: Int,
    totalDuplicateBytes: Long,
    wastedBytes: Long,
    percentageOfTotalWasted: Double
  ) : this(
    bucketIndex = bucketIndex,
    percentileRange = percentileRange,
    minSize = minSize,
    maxSize = maxSize,
    fileCount = fileCount,
    sizeBytes = totalDuplicateBytes - wastedBytes,
    totalDuplicateBytes = totalDuplicateBytes,
    wastedBytes = wastedBytes,
    percentageOfTotalWasted = percentageOfTotalWasted
  )
}

/**
 * Summary statistics describing scanned files, duplication metrics, top duplicate examples,
 * percentile distributions, and processing duration.
 */
data class DuplicateStatistics(
  val totalFilesScanned: Long,
  val totalScannedBytes: Long,
  val distinctFiles: Long,
  val distinctBytes: Long,
  val duplicateFileCount: Long,
  val duplicateGroupsCount: Long,
  val wastedBytes: Long,
  val totalDuplicateBytes: Long,
  val wastedPercentage: Double,
  val largestDuplicates: List<DuplicateGroup>,
  val percentileBuckets: List<PercentileBucket>,
  val processingDuration: Duration
)

/**
 * Formats a byte count into a human-readable string with exact bytes (e.g. "12.50 MB (13,107,200 bytes)").
 */
fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(Locale.US, "%.2f KB (%,d bytes)", kb, bytes)
  val mb = kb / 1024.0
  if (mb < 1024) return String.format(Locale.US, "%.2f MB (%,d bytes)", mb, bytes)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB (%,d bytes)", gb, bytes)
}

/**
 * Formats a byte count into a compact string suitable for table columns (e.g. "12.50 MB").
 */
fun formatBytesShort(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(Locale.US, "%.2f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB", gb)
}

/**
 * Formats a duration into a clean human-readable representation.
 */
fun formatDuration(duration: Duration): String {
  val totalMs = duration.inWholeMilliseconds
  if (totalMs < 1000) return "${totalMs}ms"
  val totalSeconds = duration.inWholeSeconds
  if (totalSeconds < 60) {
    val sec = totalMs / 1000.0
    return String.format(Locale.US, "%.2fs", sec)
  }
  val minutes = duration.inWholeMinutes
  val remainingSec = totalSeconds % 60
  return "${minutes}m ${remainingSec}s"
}
