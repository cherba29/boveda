package com.cherba29.boveda.service

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderDuplicateStatisticsTest {

  @Test
  fun `findTopDuplicateFolderPairs on snapshot with no duplicates returns empty list`() {
    val tempDir = Files.createTempDirectory("folder-nodups-dir")
    try {
      val d1 = tempDir.resolve("d1").apply { createDirectories() }
      val d2 = tempDir.resolve("d2").apply { createDirectories() }
      d1.resolve("unique1.txt").writeText("content 1")
      d2.resolve("unique2.txt").writeText("content 2")

      val db = DatabaseFactory.connectInMemory("folder-nodups-test")
      val service = BovedaService(database = db, scanner = FileTreeDirectoryScanner())
      val snapshot = service.createSnapshot(tempDir)
      service.scanAndRecord(tempDir, resumeSnapshotId = snapshot.id.value)
      service.completeSnapshot(snapshot)

      val pairs = service.findTopDuplicateFolderPairs(snapshot.id.value)
      assertTrue(pairs.isEmpty())

      val output = mutableListOf<String>()
      service.printFolderDuplicatePairs(pairs) { output.add(it) }
      val joined = output.joinToString("\n")
      assertTrue(joined.contains("No duplicate files found between folders."))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `findTopDuplicateFolderPairs computes accurate statistics and top 3 largest files`() {
    val tempDir = Files.createTempDirectory("folder-dups-stats-dir")
    try {
      val folderA = tempDir.resolve("folderA").apply { createDirectories() }
      val folderB = tempDir.resolve("folderB").apply { createDirectories() }
      val folderC = tempDir.resolve("folderC").apply { createDirectories() }

      // 4 duplicate files between A and B
      val c1 = ByteArray(10_000) { 1 }
      val c2 = ByteArray(5_000) { 2 }
      val c3 = ByteArray(1_000) { 3 }
      val c4 = ByteArray(200) { 4 }

      folderA.resolve("file1.bin").writeBytes(c1)
      folderA.resolve("file2.bin").writeBytes(c2)
      folderA.resolve("file3.bin").writeBytes(c3)
      folderA.resolve("file4.bin").writeBytes(c4)
      folderA.resolve("uniqueA.txt").writeText("unique to A")

      folderB.resolve("copy1.bin").writeBytes(c1)
      folderB.resolve("copy2.bin").writeBytes(c2)
      folderB.resolve("copy3.bin").writeBytes(c3)
      folderB.resolve("copy4.bin").writeBytes(c4)
      folderB.resolve("uniqueB.txt").writeText("unique to B")

      // 1 duplicate file between A and C (c3, size 1000)
      folderC.resolve("sharedC.bin").writeBytes(c3)
      folderC.resolve("uniqueC.txt").writeText("unique to C")

      val db = DatabaseFactory.connectInMemory("folder-dups-stats-test")
      val service = BovedaService(database = db, scanner = FileTreeDirectoryScanner())
      val snapshot = service.createSnapshot(tempDir)
      service.scanAndRecord(tempDir, resumeSnapshotId = snapshot.id.value)
      service.completeSnapshot(snapshot)

      val pairs = service.findTopDuplicateFolderPairs(snapshot.id.value, limit = 10)
      assertEquals(3, pairs.size)

      // Pair 1 must be (folderA, folderB) since they share 4 duplicate files
      val topPair = pairs[0]
      assertTrue(
        (topPair.folder1.endsWith("folderA") && topPair.folder2.endsWith("folderB")) ||
          (topPair.folder1.endsWith("folderB") && topPair.folder2.endsWith("folderA"))
      )
      assertEquals(4L, topPair.sameFiles)
      // 1 unique in A, 1 unique in B -> 2 different files
      assertEquals(2L, topPair.differentFiles)
      assertEquals(6L, topPair.totalFiles)
      // Duplicated storage: 10000 + 5000 + 1000 + 200 = 16200 bytes
      assertEquals(16_200L, topPair.duplicatedStorage)
      // Duplicate files percentage: 4 / 6 * 100 = 66.67%
      assertEquals(
        66.67,
        String.format(java.util.Locale.US, "%.2f", topPair.duplicateFilesPercentage).toDouble()
      )

      // Top duplicate files should have at most 3 entries ordered by size descending:
      // c1 (10000), c2 (5000), c3 (1000)
      assertEquals(3, topPair.topDuplicateFiles.size)
      assertEquals(10_000L, topPair.topDuplicateFiles[0].size)
      assertEquals(5_000L, topPair.topDuplicateFiles[1].size)
      assertEquals(1_000L, topPair.topDuplicateFiles[2].size)

      // Print and verify all required sections
      val lines = mutableListOf<String>()
      service.printFolderDuplicatePairs(pairs) { lines.add(it) }
      val printed = lines.joinToString("\n")

      assertTrue(printed.contains("TOP DUPLICATE FOLDER PAIRS"))
      assertTrue(printed.contains("Found 3 folder pair(s) with duplicate files:"))
      assertTrue(printed.contains("Same Files:"))
      assertTrue(printed.contains("Different Files:"))
      assertTrue(printed.contains("Total Duplicated Storage:"))
      assertTrue(printed.contains("Duplicate Files Share:"))
      assertTrue(printed.contains("Duplicate Storage Share:"))
      assertTrue(printed.contains("Top 3 Largest Duplicate File(s):"))
      assertTrue(printed.contains("10.00 KB") || printed.contains("9.77 KB"))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `findTopDuplicateFolderPairs respects limit parameter`() {
    val tempDir = Files.createTempDirectory("folder-limit-dir")
    try {
      val f1 = tempDir.resolve("f1").apply { createDirectories() }
      val f2 = tempDir.resolve("f2").apply { createDirectories() }
      val f3 = tempDir.resolve("f3").apply { createDirectories() }

      val content = ByteArray(500) { 7 }
      f1.resolve("a.bin").writeBytes(content)
      f2.resolve("b.bin").writeBytes(content)
      f3.resolve("c.bin").writeBytes(content)

      val db = DatabaseFactory.connectInMemory("folder-limit-test")
      val service = BovedaService(database = db, scanner = FileTreeDirectoryScanner())
      val snapshot = service.createSnapshot(tempDir)
      service.scanAndRecord(tempDir, resumeSnapshotId = snapshot.id.value)
      service.completeSnapshot(snapshot)

      // 3 folder pairs exist: (f1,f2), (f1,f3), (f2,f3)
      val allPairs = service.findTopDuplicateFolderPairs(snapshot.id.value, limit = 10)
      assertEquals(3, allPairs.size)

      val limitedPairs = service.findTopDuplicateFolderPairs(snapshot.id.value, limit = 1)
      assertEquals(1, limitedPairs.size)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }
}
