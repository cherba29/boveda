package com.cherba29.boveda.scanner

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectoryMetadataScannerTest {

  @Test
  fun `FileTreeMetadataScanner scans empty directory correctly`() {
    val tempDir = Files.createTempDirectory("meta-empty-seq")
    try {
      val result = FileTreeMetadataScanner.scan(tempDir)
      assertEquals(0L, result.fileCount)
      assertEquals(0L, result.totalBytes)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `WorkStealingMetadataScanner scans empty directory correctly`() {
    val tempDir = Files.createTempDirectory("meta-empty-ws")
    try {
      val scanner = WorkStealingMetadataScanner(parallelism = 2)
      val result = scanner.scan(tempDir)
      assertEquals(0L, result.fileCount)
      assertEquals(0L, result.totalBytes)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `both scanners accurately compute fileCount and totalBytes in nested directory`() {
    val tempDir = Files.createTempDirectory("meta-nested")
    try {
      val sub1 = Files.createDirectory(tempDir.resolve("sub1"))
      val sub2 = Files.createDirectory(tempDir.resolve("sub2"))
      val sub1a = Files.createDirectory(sub1.resolve("sub1a"))

      tempDir.resolve("root.bin").writeBytes(ByteArray(100))
      sub1.resolve("file1.bin").writeBytes(ByteArray(250))
      sub2.resolve("file2.bin").writeBytes(ByteArray(500))
      sub1a.resolve("file3.bin").writeBytes(ByteArray(1000))

      val expectedFiles = 4L
      val expectedBytes = 1850L

      val seqResult = FileTreeMetadataScanner.scan(tempDir)
      assertEquals(expectedFiles, seqResult.fileCount)
      assertEquals(expectedBytes, seqResult.totalBytes)

      val wsScanner = WorkStealingMetadataScanner(parallelism = 4)
      val wsResult = wsScanner.scan(tempDir)
      assertEquals(expectedFiles, wsResult.fileCount)
      assertEquals(expectedBytes, wsResult.totalBytes)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scanners throw IllegalArgumentException on non-existent or file paths`() {
    val nonExistent = Path.of("/path/to/definitely/non/existent/meta/dir")
    assertFailsWith<IllegalArgumentException> {
      FileTreeMetadataScanner.scan(nonExistent)
    }
    assertFailsWith<IllegalArgumentException> {
      WorkStealingMetadataScanner(2).scan(nonExistent)
    }

    val tempFile = Files.createTempFile("meta-file-not-dir", ".tmp")
    try {
      assertFailsWith<IllegalArgumentException> {
        FileTreeMetadataScanner.scan(tempFile)
      }
      assertFailsWith<IllegalArgumentException> {
        WorkStealingMetadataScanner(2).scan(tempFile)
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `WorkStealingMetadataScanner rejects invalid parallelism`() {
    assertFailsWith<IllegalArgumentException> {
      WorkStealingMetadataScanner(parallelism = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      WorkStealingMetadataScanner(parallelism = -1)
    }
  }

  @Test
  fun `both scanners invoke onProgress with progressive counts and total size`() {
    val tempDir = Files.createTempDirectory("meta-progress-test")
    try {
      val sub = Files.createDirectory(tempDir.resolve("sub"))
      tempDir.resolve("fileA.bin").writeBytes(ByteArray(100))
      tempDir.resolve("fileB.bin").writeBytes(ByteArray(200))
      sub.resolve("fileC.bin").writeBytes(ByteArray(300))

      val seqProgress = mutableListOf<Pair<Long, Long>>()
      val seqResult = FileTreeMetadataScanner.scan(tempDir) { files, bytes ->
        seqProgress.add(files to bytes)
      }

      assertEquals(3L, seqResult.fileCount)
      assertEquals(600L, seqResult.totalBytes)
      assertEquals(3, seqProgress.size)
      assertEquals(3L, seqProgress.last().first)
      assertEquals(600L, seqProgress.last().second)

      val wsProgress = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
      val wsScanner = WorkStealingMetadataScanner(parallelism = 2)
      val wsResult = wsScanner.scan(tempDir) { files, bytes ->
        wsProgress.add(files to bytes)
      }

      assertEquals(3L, wsResult.fileCount)
      assertEquals(600L, wsResult.totalBytes)
      assertEquals(3, wsProgress.size)
      // Progress calls must end at total counts
      assertEquals(3L, wsProgress.maxOf { it.first })
      assertEquals(600L, wsProgress.maxOf { it.second })
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }
}
