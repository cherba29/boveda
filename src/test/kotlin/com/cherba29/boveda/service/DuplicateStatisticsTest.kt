package com.cherba29.boveda.service

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.model.PercentileBucket
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatBytesShort
import com.cherba29.boveda.model.formatDuration
import com.cherba29.boveda.scanner.ScannedFile
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DuplicateStatisticsTest {

  private fun createService(): Pair<BovedaService, org.jetbrains.exposed.v1.jdbc.Database> {
    val db = DatabaseFactory.connectInMemory(
      "stats_${UUID.randomUUID().toString().replace("-", "")}"
    )
    val service = BovedaService(database = db)
    return Pair(service, db)
  }

  @Test
  fun `computeDuplicateStatistics calculates accurate ingestion and duplication metrics`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    // File A: 3 copies of size 1000 bytes (wasted: 2 * 1000 = 2000)
    service.recordFile(snapshot, ScannedFile(Paths.get("a1.txt"), "a1.txt", 1000L, "hashA"))
    service.recordFile(snapshot, ScannedFile(Paths.get("a2.txt"), "a2.txt", 1000L, "hashA"))
    service.recordFile(snapshot, ScannedFile(Paths.get("a3.txt"), "a3.txt", 1000L, "hashA"))

    // File B: 2 copies of size 5000 bytes (wasted: 1 * 5000 = 5000)
    service.recordFile(snapshot, ScannedFile(Paths.get("b1.txt"), "b1.txt", 5000L, "hashB"))
    service.recordFile(snapshot, ScannedFile(Paths.get("b2.txt"), "b2.txt", 5000L, "hashB"))

    // File C: 1 unique copy of size 2000 bytes (wasted: 0)
    service.recordFile(snapshot, ScannedFile(Paths.get("c1.txt"), "c1.txt", 2000L, "hashC"))

    val duration = 1250.milliseconds
    val stats = service.computeDuplicateStatistics(snapshot.id.value, duration)

    // 6 total files, size: 3*1000 + 2*5000 + 1*2000 = 15000 bytes
    assertEquals(6L, stats.totalFilesScanned)
    assertEquals(15000L, stats.totalScannedBytes)

    // 3 unique file contents, size: 1000 + 5000 + 2000 = 8000 bytes
    assertEquals(3L, stats.distinctFiles)
    assertEquals(8000L, stats.distinctBytes)

    // Duplication: 2 duplicate groups, 5 duplicate instances, wasted = 2000 + 5000 = 7000 bytes
    assertEquals(2L, stats.duplicateGroupsCount)
    assertEquals(5L, stats.duplicateFileCount)
    assertEquals(7000L, stats.wastedBytes)
    assertEquals(13000L, stats.totalDuplicateBytes) // 3*1000 + 2*5000

    // Wasted percentage: 7000 / 15000 * 100 = 46.666...%
    assertEquals(46.67, Math.round(stats.wastedPercentage * 100.0) / 100.0)
    assertEquals(duration, stats.processingDuration)
  }

  @Test
  fun `computeDuplicateStatistics returns largest 10 examples in descending size order`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    // Create 15 duplicate groups with increasing sizes: 100, 200, ..., 1500
    for (i in 1..15) {
      val size = i * 100L
      val hash = "hash_$i"
      service.recordFile(
        snapshot,
        ScannedFile(Paths.get("dir1/file_$i.txt"), "dir1/file_$i.txt", size, hash)
      )
      service.recordFile(
        snapshot,
        ScannedFile(Paths.get("dir2/file_$i.txt"), "dir2/file_$i.txt", size, hash)
      )
    }

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 500.milliseconds)

    // Only top 10 largest should be returned
    assertEquals(10, stats.largestDuplicates.size)
    // Largest is 1500, then 1400, ..., 600
    assertEquals(1500L, stats.largestDuplicates[0].size)
    assertEquals(1400L, stats.largestDuplicates[1].size)
    assertEquals(600L, stats.largestDuplicates[9].size)

    // Verify paths are retained
    stats.largestDuplicates.forEach { group ->
      assertEquals(2, group.paths.size)
      assertEquals(group.size, group.wastedBytes) // 2 copies -> 1 wasted copy
    }
  }

  @Test
  fun `computeDuplicateStatistics orders top duplicate entries by combined duplicate space`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    // Three 5MB files -> Combined = 15MB
    val fiveMb = 5L * 1024L * 1024L
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("f5_1.bin"), "f5_1.bin", fiveMb, "hash_5mb")
    )
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("f5_2.bin"), "f5_2.bin", fiveMb, "hash_5mb")
    )
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("f5_3.bin"), "f5_3.bin", fiveMb, "hash_5mb")
    )

    // Two 7MB files -> Combined = 14MB
    val sevenMb = 7L * 1024L * 1024L
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("f7_1.bin"), "f7_1.bin", sevenMb, "hash_7mb")
    )
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("f7_2.bin"), "f7_2.bin", sevenMb, "hash_7mb")
    )

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 100.milliseconds)

    assertEquals(2, stats.largestDuplicates.size)
    // 5MB group with 3 copies (15MB combined) must appear before 7MB group with 2 copies (14MB combined)
    assertEquals("hash_5mb", stats.largestDuplicates[0].hash)
    assertEquals(15L * 1024L * 1024L, stats.largestDuplicates[0].totalDuplicateBytes)
    assertEquals(3, stats.largestDuplicates[0].copyCount)

    assertEquals("hash_7mb", stats.largestDuplicates[1].hash)
    assertEquals(14L * 1024L * 1024L, stats.largestDuplicates[1].totalDuplicateBytes)
    assertEquals(2, stats.largestDuplicates[1].copyCount)
  }

  @Test
  fun `computeDuplicateStatistics generates 10 percentile buckets with valid bounds and sums`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    for (i in 1..20) {
      val size = i * 50L
      val hash = "dup_hash_$i"
      service.recordFile(snapshot, ScannedFile(Paths.get("a/$i.bin"), "a/$i.bin", size, hash))
      service.recordFile(snapshot, ScannedFile(Paths.get("b/$i.bin"), "b/$i.bin", size, hash))
    }

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 1.seconds)

    // Exactly 10 buckets
    assertEquals(10, stats.percentileBuckets.size)
    assertEquals("0% - 10%", stats.percentileBuckets[0].percentileRange)
    assertEquals("90% - 100%", stats.percentileBuckets[9].percentileRange)

    // Sum of wasted bytes across all buckets must match total wasted bytes
    val totalBucketWasted = stats.percentileBuckets.sumOf { it.wastedBytes }
    assertEquals(stats.wastedBytes, totalBucketWasted)

    val totalBucketDuplicated = stats.percentileBuckets.sumOf { it.totalDuplicateBytes }
    assertEquals(stats.totalDuplicateBytes, totalBucketDuplicated)

    val totalBucketSize = stats.percentileBuckets.sumOf { it.sizeBytes }
    assertEquals(totalBucketDuplicated - totalBucketWasted, totalBucketSize)
    assertTrue(totalBucketSize > 0)

    // Percentage sums to 100%
    val totalPct = stats.percentileBuckets.sumOf { it.percentageOfTotalWasted }
    assertEquals(100.0, Math.round(totalPct * 100.0) / 100.0)
  }

  @Test
  fun `computeDuplicateStatistics handles empty and zero duplicate snapshots gracefully`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/empty"))

    // Add 2 unique files
    service.recordFile(snapshot, ScannedFile(Paths.get("u1.txt"), "u1.txt", 100L, "h1"))
    service.recordFile(snapshot, ScannedFile(Paths.get("u2.txt"), "u2.txt", 200L, "h2"))

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 100.milliseconds)

    assertEquals(2L, stats.totalFilesScanned)
    assertEquals(0L, stats.duplicateFileCount)
    assertEquals(0L, stats.duplicateGroupsCount)
    assertEquals(0L, stats.wastedBytes)
    assertEquals(0.0, stats.wastedPercentage)
    assertTrue(stats.largestDuplicates.isEmpty())

    assertEquals(10, stats.percentileBuckets.size)
    stats.percentileBuckets.forEach { bucket ->
      assertEquals(0L, bucket.wastedBytes)
      assertEquals(0, bucket.fileCount)
    }
  }

  @Test
  fun `formatBytes and formatBytesShort format numbers correctly`() {
    assertEquals("500 B", formatBytes(500L))
    assertEquals("500 B", formatBytesShort(500L))

    assertEquals("1.50 KB (1,536 bytes)", formatBytes(1536L))
    assertEquals("1.50 KB", formatBytesShort(1536L))

    assertEquals("2.00 MB (2,097,152 bytes)", formatBytes(2 * 1024 * 1024L))
    assertEquals("2.00 MB", formatBytesShort(2 * 1024 * 1024L))

    assertEquals(
      "1.50 GB (1,610,612,736 bytes)",
      formatBytes((1.5 * 1024 * 1024 * 1024L).toLong())
    )
    assertEquals("1.50 GB", formatBytesShort((1.5 * 1024 * 1024 * 1024L).toLong()))
  }

  @Test
  fun `formatDuration formats milliseconds seconds and minutes`() {
    assertEquals("450ms", formatDuration(450.milliseconds))
    assertEquals("1.50s", formatDuration(1500.milliseconds))
    assertEquals("2m 15s", formatDuration(135.seconds))
    assertEquals("3m 0s", formatDuration(3.minutes))
  }

  @Test
  fun `printDuplicateStatistics outputs all expected sections`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    service.recordFile(snapshot, ScannedFile(Paths.get("f1.txt"), "f1.txt", 1024L, "h1"))
    service.recordFile(snapshot, ScannedFile(Paths.get("f2.txt"), "f2.txt", 1024L, "h1"))

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 800.milliseconds)

    val outStream = ByteArrayOutputStream()
    service.printDuplicateStatistics(stats) { line ->
      outStream.write("$line\n".toByteArray())
    }

    val output = outStream.toString()
    assertTrue(output.contains("DUPLICATION SUMMARY"))
    assertTrue(output.contains("Scan & Ingestion Statistics:"))
    assertTrue(output.contains("Total Files Scanned:"))
    assertTrue(output.contains("Total Storage Scanned:"))
    assertTrue(output.contains("Duplication Overview:"))
    assertTrue(output.contains("Wasted Storage:"))
    assertTrue(output.contains("Top 10 Largest Duplicate Files:"))
    assertTrue(output.contains("Percentile Buckets by File Size (10% Deciles):"))
    assertTrue(output.contains("Percentile"))
    assertTrue(output.contains("Size Range"))
    assertTrue(output.contains("Files"))
    assertTrue(output.contains("Size"))
    assertTrue(output.contains("Duplicated Storage"))
    assertTrue(output.contains("Wasted Storage"))
    assertTrue(output.contains("Share (%)"))
    assertTrue(output.contains("0% - 10%"))
    assertTrue(output.contains("90% - 100%"))
    assertTrue(output.contains("Total"))
    assertTrue(output.contains("Total Processing Time:     800ms"))
  }

  @Test
  fun `formatPercentileTable formats table with Mordant and shows totals row`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/vault"))

    for (i in 1..10) {
      val size = i * 1024L
      val hash = "hash_$i"
      service.recordFile(snapshot, ScannedFile(Paths.get("a/$i.bin"), "a/$i.bin", size, hash))
      service.recordFile(snapshot, ScannedFile(Paths.get("b/$i.bin"), "b/$i.bin", size, hash))
    }

    val stats = service.computeDuplicateStatistics(snapshot.id.value, 500.milliseconds)
    val tableString = service.formatPercentileTable(stats)

    assertTrue(tableString.contains("Percentile"))
    assertTrue(tableString.contains("Size Range"))
    assertTrue(tableString.contains("Files"))
    assertTrue(tableString.contains("Size"))
    assertTrue(tableString.contains("Duplicated Storage"))
    assertTrue(tableString.contains("Wasted Storage"))
    assertTrue(tableString.contains("Share (%)"))
    assertTrue(tableString.contains("Total"))
    // Total files = 10 * 2 = 20
    assertTrue(tableString.contains("20"))
    // Total wasted = 55 KB
    assertTrue(tableString.contains("100.00%"))
  }

  @Test
  fun `PercentileBucket backward-compatible constructor computes sizeBytes`() {
    @Suppress("DEPRECATION")
    val bucket = PercentileBucket(
      bucketIndex = 1,
      percentileRange = "0% - 10%",
      minSize = 100L,
      maxSize = 200L,
      fileCount = 4,
      totalDuplicateBytes = 800L,
      wastedBytes = 600L,
      percentageOfTotalWasted = 10.0
    )
    assertEquals(200L, bucket.sizeBytes) // 800 - 600
    assertEquals(800L, bucket.totalDuplicateBytes)
    assertEquals(600L, bucket.wastedBytes)
  }

  @Test
  fun `computeDuplicateStatistics handles completely empty snapshot and zero-byte duplicates`() {
    val (service, _) = createService()

    // Completely empty snapshot (0 files)
    val emptySnap = service.createSnapshot(Paths.get("/truly-empty"))
    val emptyStats = service.computeDuplicateStatistics(emptySnap.id.value, 10.milliseconds)
    assertEquals(0L, emptyStats.totalFilesScanned)
    assertEquals(0L, emptyStats.totalScannedBytes)
    assertEquals(0L, emptyStats.distinctFiles)
    assertEquals(0L, emptyStats.distinctBytes)
    assertEquals(0L, emptyStats.duplicateGroupsCount)
    assertEquals(0L, emptyStats.duplicateFileCount)
    assertEquals(10, emptyStats.percentileBuckets.size)

    // Zero-byte duplicate files
    val zeroSnap = service.createSnapshot(Paths.get("/zeros"))
    service.recordFile(zeroSnap, ScannedFile(Paths.get("z1.txt"), "z1.txt", 0L, "empty_hash"))
    service.recordFile(zeroSnap, ScannedFile(Paths.get("z2.txt"), "z2.txt", 0L, "empty_hash"))
    val zeroStats = service.computeDuplicateStatistics(zeroSnap.id.value, 10.milliseconds)
    assertEquals(2L, zeroStats.totalFilesScanned)
    assertEquals(1L, zeroStats.duplicateGroupsCount)
    assertEquals(2L, zeroStats.duplicateFileCount)
    assertEquals(0L, zeroStats.wastedBytes)
    assertEquals(0.0, zeroStats.wastedPercentage)
    assertEquals(1, zeroStats.largestDuplicates.size)
    assertEquals(2, zeroStats.largestDuplicates[0].paths.size)
  }

  @Test
  fun `forEachDuplicate streams only duplicates for specified snapshot`() {
    val (service, _) = createService()
    val snap1 = service.createSnapshot(Paths.get("/s1"))
    val snap2 = service.createSnapshot(Paths.get("/s2"))

    service.recordFile(snap1, ScannedFile(Paths.get("/s1/a.txt"), "a.txt", 100L, "dup1"))
    service.recordFile(snap1, ScannedFile(Paths.get("/s1/b.txt"), "b.txt", 100L, "dup1"))
    service.recordFile(snap1, ScannedFile(Paths.get("/s1/u.txt"), "u.txt", 200L, "uniq1"))
    service.recordFile(snap2, ScannedFile(Paths.get("/s2/c.txt"), "c.txt", 300L, "dup2"))
    service.recordFile(snap2, ScannedFile(Paths.get("/s2/d.txt"), "d.txt", 300L, "dup2"))

    val streamed = mutableListOf<String>()
    val count = service.forEachDuplicate(snap1.id.value) { rec ->
      streamed.add("${rec.path}#${rec.duplicateRank}")
    }
    assertEquals(2L, count)
    assertEquals(listOf("/s1/a.txt#1", "/s1/b.txt#2"), streamed)
  }
}
