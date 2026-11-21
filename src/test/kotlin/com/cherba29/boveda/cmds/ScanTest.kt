package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.progress.EstimatingProgressIndicator
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.scanner.DirectoryMetadata
import com.cherba29.boveda.scanner.DirectoryMetadataScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
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
    assertTrue(result.output.contains("--continue"))
    assertTrue(result.output.contains("--algorithm"))
    assertTrue(result.output.contains("--max-hash-bytes"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--quiet"))
    assertTrue(result.output.contains("--batch-size"))
    assertTrue(result.output.contains("--threads"))
    assertTrue(result.output.contains("--scanner"))
    assertTrue(result.output.contains("--work-stealing"))
    assertTrue(result.output.contains("--no-cache-warmup"))
    assertTrue(result.output.contains("--no-estimate"))
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
      assertTrue(result.output.contains("Updating folder statistics and finalizing snapshot..."))
      assertTrue(result.output.contains("Snapshot finalized in"))
      assertTrue(result.output.contains("Flushing and closing database..."))
      assertTrue(result.output.contains("Database closed in"))
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

  @Test
  fun `scan command passes estimating progress updates from metadata scanner to estimating indicator`() {
    val tempDir = Files.createTempDirectory("scan-est-meta-dir")
    val dbDir = Files.createTempDirectory("scan-est-meta-db")
    try {
      tempDir.resolve("item1.txt").writeText("content 1")
      val dbFile = dbDir.resolve("est_meta_db")

      var estimatingFinished = false
      val reportedProgress = mutableListOf<Pair<Long, Long>>()

      val mockEstimatingIndicator = object : EstimatingProgressIndicator {
        override fun onProgress(filesScanned: Long, bytesScanned: Long) {
          reportedProgress.add(filesScanned to bytesScanned)
        }

        override fun finish() {
          estimatingFinished = true
        }
      }

      val mockMetadataScanner = object : DirectoryMetadataScanner {
        override fun scan(rootPath: Path): DirectoryMetadata {
          return DirectoryMetadata(fileCount = 1L, totalBytes = 9L)
        }

        override fun scan(
          rootPath: Path,
          onProgress: (filesScanned: Long, bytesScanned: Long) -> Unit
        ): DirectoryMetadata {
          onProgress(1L, 9L)
          return DirectoryMetadata(fileCount = 1L, totalBytes = 9L)
        }
      }

      val command = ScanCommand(
        metadataScannerFactory = { mockMetadataScanner },
        estimatingProgressIndicatorFactory = { mockEstimatingIndicator }
      )

      val result = command.test(listOf(tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(estimatingFinished)
      assertEquals(listOf(1L to 9L), reportedProgress)
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command fails when neither directory nor continue option is specified`() {
    val dbDir = Files.createTempDirectory("scan-no-dir-db")
    try {
      val dbFile = dbDir.resolve("test_db")
      val command = ScanCommand()
      val result = command.test(listOf("-d", dbFile.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("DIRECTORY must be specified"))
    } finally {
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with continue fails when snapshot does not exist`() {
    val dbDir = Files.createTempDirectory("scan-nonexistent-snap-db")
    try {
      val dbFile = dbDir.resolve("test_db")
      DatabaseFactory.connectFile(dbFile.toString())

      val command = ScanCommand()
      val result = command.test(listOf("-c", "999", "-d", dbFile.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("Snapshot not found: 999"))
    } finally {
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with continue fails when snapshot is already completed`() {
    val tempDir = Files.createTempDirectory("scan-completed-dir")
    val dbDir = Files.createTempDirectory("scan-completed-db")
    try {
      tempDir.resolve("doc.txt").writeText("completed content")
      val dbFile = dbDir.resolve("test_db")
      val db = DatabaseFactory.connectFile(dbFile.toString())
      val service = BovedaService(database = db)
      val snapshot = service.scanAndRecord(tempDir)

      val command = ScanCommand()
      val result = command.test(
        listOf("-c", snapshot.id.value.toString(), "-d", dbFile.toString())
      )

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("already completed"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with continue resumes incomplete snapshot without specifying directory`() {
    val tempDir = Files.createTempDirectory("scan-resume-dir")
    val dbDir = Files.createTempDirectory("scan-resume-db")
    try {
      val fileA = tempDir.resolve("fileA.txt")
      fileA.writeText("content of file A")
      val fileB = tempDir.resolve("fileB.txt")
      fileB.writeText("content of file B")

      val dbFile = dbDir.resolve("resume_db")
      val db = DatabaseFactory.connectFile(dbFile.toString())
      val service = BovedaService(database = db)

      val snapshot = service.createSnapshot(tempDir)
      val scannedA = ScannedFile(
        path = fileA,
        relativePath = "fileA.txt",
        size = Files.size(fileA),
        hash = "hashA123",
        lastModifiedTime = fileA.getLastModifiedTime()
      )
      service.recordFile(snapshot, scannedA)

      val command = ScanCommand()
      val result = command.test(
        listOf("--continue", snapshot.id.value.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))

      val updatedSnapshot = service.findSnapshot(snapshot.id.value)
      assertEquals(true, updatedSnapshot?.isCompleted)

      val entries = service.getSnapshotFileEntries(snapshot.id.value)
      assertEquals(2, entries.size)
      assertTrue(entries.keys.any { it.endsWith("fileA.txt") })
      assertTrue(entries.keys.any { it.endsWith("fileB.txt") })
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with continue skips already processed unchanged files without recomputing hash`() {
    val tempDir = Files.createTempDirectory("scan-skip-dir")
    val dbDir = Files.createTempDirectory("scan-skip-db")
    try {
      val file1 = tempDir.resolve("file1.txt")
      file1.writeText("unchanged content")
      val file2 = tempDir.resolve("file2.txt")
      file2.writeText("initial content")

      val dbFile = dbDir.resolve("skip_db")
      val db = DatabaseFactory.connectFile(dbFile.toString())
      val service = BovedaService(database = db)

      val snapshot = service.createSnapshot(tempDir)
      val scanned1 = ScannedFile(
        path = file1,
        relativePath = "file1.txt",
        size = Files.size(file1),
        hash = "hash1_valid",
        lastModifiedTime = file1.getLastModifiedTime()
      )
      service.recordFile(snapshot, scanned1)

      file2.writeText("modified content which changed the size")

      val hashedFiles = mutableListOf<String>()
      val spyHasher = object : FileHasher {
        override fun computeHash(file: File, algorithm: String): String {
          hashedFiles.add(file.name)
          return "computed_hash_${file.name}"
        }

        override fun computeHash(inputStream: InputStream, algorithm: String): String =
          "computed_hash_stream"
      }

      val customScanner = FileTreeDirectoryScanner(hasher = spyHasher)
      val customServiceFactory: (org.jetbrains.exposed.v1.jdbc.Database) -> BovedaService = { d ->
        BovedaService(database = d, scanner = customScanner)
      }

      val command = ScanCommand(serviceFactory = customServiceFactory)
      val result = command.test(
        listOf("--continue", snapshot.id.value.toString(), "-d", dbFile.toString())
      )

      assertEquals(0, result.statusCode)

      assertTrue(
        !hashedFiles.contains("file1.txt"),
        "file1.txt should have been skipped without hashing"
      )
      assertTrue(hashedFiles.contains("file2.txt"), "file2.txt should have been hashed")
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with --max-hash-bytes option hashes up to specified prefix size`() {
    val tempDir = Files.createTempDirectory("scan-maxbytes-test")
    val dbDir = Files.createTempDirectory("scan-maxbytes-db")
    try {
      val prefix = "P".repeat(65536) // 64Kb prefix

      // file1 and file2 have same size (100000 bytes) and same 64Kb prefix, but different suffix
      tempDir.resolve("file1.bin").toFile().writeText(prefix + "A".repeat(34464))
      tempDir.resolve("file2.bin").toFile().writeText(prefix + "B".repeat(34464))

      val dbFile = dbDir.resolve("scan_maxbytes.db")
      val command = ScanCommand()

      val result = command.test(
        listOf(
          "-m",
          "64Kb",
          tempDir.toString(),
          dbFile.toString()
        )
      )

      assertEquals(0, result.statusCode)

      val service = BovedaService(database = DatabaseFactory.connectFile(dbFile.toString()))
      val files = service.queryFiles()
      assertEquals(2, files.size)

      // Both files must have the same hash because they have the same size and matching 64Kb prefix!
      assertEquals(files[0].hash, files[1].hash)
      assertEquals(100000L, files[0].size)
      assertEquals(100000L, files[1].size)
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with invalid --max-hash-bytes fails with usage error`() {
    val tempDir = Files.createTempDirectory("scan-invalid-maxbytes")
    val dbDir = Files.createTempDirectory("scan-invalid-maxbytes-db")
    try {
      val dbFile = dbDir.resolve("scan.db")
      val command = ScanCommand()

      val result = command.test(
        listOf(
          "--max-hash-bytes",
          "invalid-size-xyz",
          tempDir.toString(),
          dbFile.toString()
        )
      )

      assertEquals(1, result.statusCode)
      assertTrue(result.output.contains("Invalid value for --max-hash-bytes"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with --no-estimate skips metadata estimation pass`() {
    val tempDir = Files.createTempDirectory("scan-no-estimate-dir")
    val dbDir = Files.createTempDirectory("scan-no-estimate-db")
    try {
      tempDir.resolve("item.txt").writeText("no estimate content")
      val dbFile = dbDir.resolve("no_est_db")

      var metadataScannerInvoked = false
      var configuredTotalFiles: Long? = -1L
      var configuredTotalBytes: Long? = -1L

      val mockMetadataScanner = object : DirectoryMetadataScanner {
        override fun scan(rootPath: Path): DirectoryMetadata {
          metadataScannerInvoked = true
          return DirectoryMetadata(fileCount = 1L, totalBytes = 19L)
        }
      }

      val mockIndicator = object : ScanProgressIndicator {
        override fun setTotals(totalFiles: Long?, totalBytes: Long?) {
          configuredTotalFiles = totalFiles
          configuredTotalBytes = totalBytes
        }

        override fun onFileProcessed(file: ScannedFile) {}
        override fun finish() {}
      }

      val command = ScanCommand(
        progressIndicatorFactory = { mockIndicator },
        metadataScannerFactory = { mockMetadataScanner }
      )

      val result = command.test(
        listOf("--quiet", "--no-estimate", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(!metadataScannerInvoked, "Metadata scanner should not run with --no-estimate")
      assertEquals(null, configuredTotalFiles)
      assertEquals(null, configuredTotalBytes)
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan skips redundant DB hash lookups on fresh DB and falls back after cache eviction`() {
    val tempDir = Files.createTempDirectory("scan-skip-hash-lookup-dir")
    try {
      tempDir.resolve("a.txt").writeText("unique alpha")
      tempDir.resolve("b.txt").writeText("unique beta")
      tempDir.resolve("c.txt").writeText("unique alpha") // duplicate of a.txt
      tempDir.resolve("d.txt").writeText("unique gamma")
      tempDir.resolve("e.txt").writeText("unique delta")

      // 1. Fresh DB with ample cache -> 0 redundant DB hash SELECT queries
      val freshDb = DatabaseFactory.connectInMemory("fresh_no_lookup_${System.nanoTime()}")
      val freshService = BovedaService(database = freshDb, batchSize = 2)
      freshService.scanAndRecord(tempDir)
      assertEquals(0L, freshService.dbHashLookupCount)
      assertEquals(4, freshService.queryFiles().map { it.hash }.distinct().size)
      assertEquals(2, freshService.findDuplicates().size)

      // 2. Tiny cache (maxSize = 2) -> evictions trigger automatic fallback to DB hash lookups
      val evictedDb = DatabaseFactory.connectInMemory("evict_fallback_${System.nanoTime()}")
      val tinyCache = com.cherba29.boveda.cache.InMemoryDeduplicationCache(maxSize = 2)
      val evictedService = BovedaService(database = evictedDb, cache = tinyCache, batchSize = 1)
      evictedService.scanAndRecord(tempDir)
      assertTrue(evictedService.dbHashLookupCount > 0L)
      assertEquals(5, evictedService.queryFiles().size)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command supports --db-cache-mb option and rejects negative values`() {
    val tempDir = Files.createTempDirectory("scan-cache-mb-dir")
    val dbDir = Files.createTempDirectory("scan-cache-mb-db")
    try {
      tempDir.resolve("doc.txt").writeText("hello")
      val dbFile = dbDir.resolve("cache_mb_db")

      val badRes = ScanCommand().test(
        listOf("--db-cache-mb", "-1", tempDir.toString(), dbFile.toString())
      )
      assertNotEquals(0, badRes.statusCode)
      assertTrue(badRes.output.contains("--db-cache-mb"))

      val okRes = ScanCommand().test(
        listOf("--quiet", "--db-cache-mb", "32", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, okRes.statusCode)
      assertTrue(okRes.output.contains("Scanned 1 file"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }
}
