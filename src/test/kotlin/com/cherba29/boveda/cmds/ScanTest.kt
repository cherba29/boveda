package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.scanner.DirectoryMetadata
import com.cherba29.boveda.scanner.DirectoryMetadataScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScanTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `scan command help displays usage, parameters, and options`() {
    val command = ScanCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: scan"))
    assertTrue(result.output.contains("<directory>"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--algorithm"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--quiet"))
    assertTrue(result.output.contains("--batch-size"))
    assertTrue(result.output.contains("--threads"))
    assertTrue(result.output.contains("--scanner"))
    assertTrue(result.output.contains("--work-stealing"))
    assertTrue(result.output.contains("--no-cache-warmup"))
    assertTrue(result.output.contains("--log-level"))
  }

  @Test
  fun `scan command populates database from directory argument`() {
    val tempDir = Files.createTempDirectory("scan-test-dir")
    val dbDir = Files.createTempDirectory("scan-test-db")
    try {
      tempDir.resolve("file.txt").writeText("scan test content")
      val dbFile = dbDir.resolve("test_db")

      val command = ScanCommand()
      val result = command.test(listOf(tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("file.txt"))
      assertTrue(Files.exists(dbDir.resolve("test_db.mv.db")))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with --db option populates database`() {
    val tempDir = Files.createTempDirectory("scan-opt-dir")
    val dbDir = Files.createTempDirectory("scan-opt-db")
    try {
      tempDir.resolve("item.txt").writeText("scan option content")
      val dbFile = dbDir.resolve("opt_db")

      val command = ScanCommand()
      val result = command.test(listOf(tempDir.toString(), "--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("item.txt"))
      assertTrue(Files.exists(dbDir.resolve("opt_db.mv.db")))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command fails when database is missing and no service factory`() {
    val tempDir = Files.createTempDirectory("scan-missing-db")
    try {
      val command = ScanCommand()
      val result = command.test(listOf(tempDir.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("Target database must be specified"))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command fails when directory does not exist`() {
    val nonExistent = "/path/to/definitely/non/existent/dir"
    val command = ScanCommand()
    val result = command.test(listOf(nonExistent, "/tmp/db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("does not exist"))
  }

  @Test
  fun `scan command fails when directory is a file`() {
    val tempFile = Files.createTempFile("scan-file-not-dir", ".txt")
    try {
      val command = ScanCommand()
      val result = command.test(listOf(tempFile.toString(), "/tmp/db"))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("is a file"))
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `scan command quiet flag suppresses per-file output`() {
    val tempDir = Files.createTempDirectory("scan-quiet-dir")
    val dbDir = Files.createTempDirectory("scan-quiet-db")
    try {
      tempDir.resolve("doc.txt").writeText("quiet content")
      val dbFile = dbDir.resolve("quiet_db")

      val command = ScanCommand()
      val result = command.test(listOf("--quiet", tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("Scanned 1 file"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with custom algorithm, batch-size, and scanner strategies`() {
    val tempDir = Files.createTempDirectory("scan-custom-dir")
    val dbDir = Files.createTempDirectory("scan-custom-db")
    try {
      tempDir.resolve("sample.txt").writeText("custom strategy content")
      val dbFile = dbDir.resolve("custom_db")

      val command = ScanCommand()
      val result = command.test(
        listOf(
          "--algorithm", "SHA-256",
          "--batch-size", "50",
          "--threads", "2",
          "--scanner", "parallel",
          "--no-cache-warmup",
          tempDir.toString(),
          dbFile.toString()
        )
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("sample.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command invokes metadata scanner before scanning and passes totals to progress indicator`() {
    val tempDir = Files.createTempDirectory("scan-meta-dir")
    val dbDir = Files.createTempDirectory("scan-meta-db")
    try {
      tempDir.resolve("item1.txt").writeText("content 1")
      tempDir.resolve("item2.txt").writeText("content 2")
      val dbFile = dbDir.resolve("meta_db")

      var scannedDir: Path? = null
      var configuredTotalFiles: Long? = null
      var configuredTotalBytes: Long? = null
      var processedFilesCount = 0

      val mockMetadataScanner = object : DirectoryMetadataScanner {
        override fun scan(rootPath: Path): DirectoryMetadata {
          scannedDir = rootPath
          return DirectoryMetadata(fileCount = 2L, totalBytes = 18L)
        }
      }

      val mockIndicator = object : ScanProgressIndicator {
        override fun setTotals(totalFiles: Long?, totalBytes: Long?) {
          configuredTotalFiles = totalFiles
          configuredTotalBytes = totalBytes
        }

        override fun onFileProcessed(file: ScannedFile) {
          processedFilesCount++
        }

        override fun finish() {}
      }

      val command = ScanCommand(
        progressIndicatorFactory = { mockIndicator },
        metadataScannerFactory = { mockMetadataScanner }
      )

      val result = command.test(listOf("--quiet", tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertEquals(tempDir, scannedDir)
      assertEquals(2L, configuredTotalFiles)
      assertEquals(18L, configuredTotalBytes)
      assertEquals(2, processedFilesCount)
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }
}
