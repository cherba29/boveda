package com.cherba29.boveda

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BovedaCommandTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `help flag displays usage, parameters, and options`() {
    val command = BovedaCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: boveda"))
    assertTrue(result.output.contains("scan"))
    assertTrue(result.output.contains("db"))
    assertTrue(result.output.contains("--log-level"))

    // Test scan help
    val scanResult = command.test(listOf("scan", "--help"))
    assertEquals(0, scanResult.statusCode)
    assertTrue(scanResult.output.contains("<directory>"))
    assertTrue(scanResult.output.contains("<database>"))
    assertTrue(scanResult.output.contains("--algorithm"))
    assertTrue(scanResult.output.contains("--max-hash-bytes"))
    assertTrue(scanResult.output.contains("--db"))
    assertTrue(scanResult.output.contains("--quiet"))
    assertTrue(scanResult.output.contains("--batch-size"))
    assertTrue(scanResult.output.contains("--threads"))
    assertTrue(scanResult.output.contains("--scanner"))
    assertTrue(scanResult.output.contains("--work-stealing"))
    assertTrue(scanResult.output.contains("--no-cache-warmup"))

    // Test db help
    val dbResult = command.test(listOf("db", "--help"))
    assertEquals(0, dbResult.statusCode)
    assertTrue(dbResult.output.contains("<database>"))
    assertTrue(dbResult.output.contains("--db"))
    assertTrue(dbResult.output.contains("--snapshot"))
    assertTrue(dbResult.output.contains("print-duplicates"))
    assertTrue(dbResult.output.contains("snapshots"))

    // Test db print-duplicates help
    val printDupResult = command.test(listOf("db", "print-duplicates", "--help"))
    assertEquals(0, printDupResult.statusCode)
    assertTrue(printDupResult.output.contains("<database>"))
    assertTrue(printDupResult.output.contains("--db"))
    assertTrue(printDupResult.output.contains("--snapshot"))

    // Test db snapshots help
    val snapshotsResult = command.test(listOf("db", "snapshots", "--help"))
    assertEquals(0, snapshotsResult.statusCode)
    assertTrue(snapshotsResult.output.contains("list"))
    assertTrue(snapshotsResult.output.contains("delete"))

    // Test db snapshots list help
    val listResult = command.test(listOf("db", "snapshots", "list", "--help"))
    assertEquals(0, listResult.statusCode)
    assertTrue(listResult.output.contains("<database>"))
    assertTrue(listResult.output.contains("--db"))

    // Test db snapshots delete help
    val deleteResult = command.test(listOf("db", "snapshots", "delete", "--help"))
    assertEquals(0, deleteResult.statusCode)
    assertTrue(deleteResult.output.contains("<snapshot_id>"))
    assertTrue(deleteResult.output.contains("<database>"))
    assertTrue(deleteResult.output.contains("--db"))
  }

  @Test
  fun `scan command populates database from directory argument`() {
    val tempDir = Files.createTempDirectory("clikt-scan-dir")
    val dbDir = Files.createTempDirectory("clikt-scan-db")
    try {
      tempDir.resolve("doc.txt").writeText("clikt test content")
      val dbFile = dbDir.resolve("scan_test_db")

      val command = BovedaCommand()
      val result = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("doc.txt"))

      // Verify H2 persistent database file was created
      val h2Db = dbDir.resolve("scan_test_db.mv.db")
      assertTrue(Files.exists(h2Db))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command with --db option populates database`() {
    val tempDir = Files.createTempDirectory("clikt-scan-db-opt")
    val dbDir = Files.createTempDirectory("clikt-scan-db-opt-db")
    try {
      tempDir.resolve("file.txt").writeText("scan opt content")
      val dbFile = dbDir.resolve("opt_db")

      val command = BovedaCommand()
      val result = command.test(listOf("scan", tempDir.toString(), "--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("file.txt"))

      val h2Db = dbDir.resolve("opt_db.mv.db")
      assertTrue(Files.exists(h2Db))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan command fails when target database is omitted and no service factory`() {
    val tempDir = Files.createTempDirectory("clikt-no-db")
    try {
      val command = BovedaCommand()
      val result = command.test(listOf("scan", tempDir.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("Target database must be specified"))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `quiet flag suppresses per-file logging and outputs progress indicator with ingestion speed`() {
    val tempDir = Files.createTempDirectory("clikt-quiet-test")
    val dbDir = Files.createTempDirectory("clikt-quiet-db")
    try {
      tempDir.resolve("fileA.txt").writeText("content A")
      val dbFile = dbDir.resolve("quiet_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "--quiet", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("Scanned 1 file"))
      assertTrue(result.output.contains("files/s"))
      assertTrue(result.output.contains("MB/s"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `quiet flag in interactive terminal displays animated progress indicator`() {
    val tempDir = Files.createTempDirectory("clikt-quiet-interactive-test")
    val dbDir = Files.createTempDirectory("clikt-quiet-interactive-db")
    try {
      tempDir.resolve("file1.txt").writeText("file 1 content")
      tempDir.resolve("file2.txt").writeText("file 2 content")
      val dbFile = dbDir.resolve("interactive_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "--quiet", tempDir.toString(), dbFile.toString()),
        outputInteractive = true,
        ansiLevel = com.github.ajalt.mordant.rendering.AnsiLevel.TRUECOLOR
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("files/s"))
      assertTrue(result.output.contains("MB/s"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `algorithm option uses specified hashing algorithm`() {
    val tempDir = Files.createTempDirectory("clikt-algo-test")
    val dbDir = Files.createTempDirectory("clikt-algo-db")
    try {
      tempDir.resolve("sample.txt").writeText("hello world")
      val dbFile = dbDir.resolve("algo_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "--algorithm", "SHA-256", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      // Known SHA-256 for "hello world"
      assertTrue(
        result.output.contains(
          "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        )
      )
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `batch-size option executes scan and records files in custom batch sizes`() {
    val tempDir = Files.createTempDirectory("clikt-batch-test")
    val dbDir = Files.createTempDirectory("clikt-batch-db")
    try {
      tempDir.resolve("f1.txt").writeText("content 1")
      tempDir.resolve("f2.txt").writeText("content 2")
      tempDir.resolve("f3.txt").writeText("content 3")
      val dbFile = dbDir.resolve("batch_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "--batch-size", "2", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("f1.txt"))
      assertTrue(result.output.contains("f2.txt"))
      assertTrue(result.output.contains("f3.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `threads option executes parallel scanning with specified thread count`() {
    val tempDir = Files.createTempDirectory("clikt-threads-test")
    val dbDir = Files.createTempDirectory("clikt-threads-db")
    try {
      tempDir.resolve("t1.txt").writeText("thread content 1")
      tempDir.resolve("t2.txt").writeText("thread content 2")
      val dbFile = dbDir.resolve("threads_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "-t", "2", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("t1.txt"))
      assertTrue(result.output.contains("t2.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scanner options configure work-stealing, parallel, and sequential strategies`() {
    val tempDir = Files.createTempDirectory("clikt-scanner-test")
    val dbDir = Files.createTempDirectory("clikt-scanner-db")
    try {
      tempDir.resolve("test.txt").writeText("scanner strategy content")
      val dbFile = dbDir.resolve("scanner_db")

      val command1 = BovedaCommand()
      val resultWs = command1.test(
        listOf("scan", "--scanner", "work-stealing", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, resultWs.statusCode)
      assertTrue(resultWs.output.contains("test.txt"))

      val command2 = BovedaCommand()
      val resultFlag = command2.test(
        listOf("scan", "--work-stealing", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, resultFlag.statusCode)
      assertTrue(resultFlag.output.contains("test.txt"))

      val command3 = BovedaCommand()
      val resultParallel = command3.test(
        listOf("scan", "--scanner", "parallel", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, resultParallel.statusCode)
      assertTrue(resultParallel.output.contains("test.txt"))

      val command4 = BovedaCommand()
      val resultSeq = command4.test(
        listOf("scan", "--scanner", "sequential", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, resultSeq.statusCode)
      assertTrue(resultSeq.output.contains("test.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `no-cache-warmup flag executes without error`() {
    val tempDir = Files.createTempDirectory("clikt-no-warmup-test")
    val dbDir = Files.createTempDirectory("clikt-no-warmup-db")
    try {
      tempDir.resolve("file.txt").writeText("data")
      val dbFile = dbDir.resolve("warmup_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("scan", "--no-cache-warmup", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Scanning directory: $tempDir"))
      assertTrue(result.output.contains("file.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan fails with non-zero status code when directory path does not exist`() {
    val nonExistent = "/path/to/definitely/non/existent/boveda_dir"
    val command = BovedaCommand()
    val result = command.test(listOf("scan", nonExistent, "/tmp/db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("does not exist"))
  }

  @Test
  fun `scan fails with non-zero status code when directory path is a file`() {
    val tempFile = Files.createTempFile("clikt-file-not-dir", ".txt")
    try {
      val command = BovedaCommand()
      val result = command.test(listOf("scan", tempFile.toString(), "/tmp/db"))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("is a file"))
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `db command queries already populated database and prints query results`() {
    val tempDir = Files.createTempDirectory("clikt-db-query-test")
    val dbDir = Files.createTempDirectory("clikt-db-query-db")
    try {
      tempDir.resolve("item.txt").writeText("database persistence test")
      val dbFile = dbDir.resolve("query_db")

      val command = BovedaCommand()
      // 1. Scan directory into database
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // 2. Query database using db command
      val dbResult = command.test(listOf("db", dbFile.toString()))
      assertEquals(0, dbResult.statusCode)
      assertTrue(dbResult.output.contains("### ==== Selecting from DB ==== ###"))
      assertTrue(dbResult.output.contains("item.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db print-duplicates subcommand queries latest snapshot and prints duplicate files and statistics`() {
    val tempDir = Files.createTempDirectory("clikt-print-dups-test")
    val dbDir = Files.createTempDirectory("clikt-print-dups-db")
    try {
      val subDir = tempDir.resolve("sub").apply { createDirectories() }
      tempDir.resolve("f1.txt").writeText("duplicate content")
      subDir.resolve("f2.txt").writeText("duplicate content")
      tempDir.resolve("f3.txt").writeText("unique content")
      val dbFile = dbDir.resolve("dups_db")

      val command = BovedaCommand()
      // 1. Scan directory into database
      val scanResult = command.test(
        listOf("scan", "--quiet", tempDir.toString(), dbFile.toString())
      )
      assertEquals(0, scanResult.statusCode)

      // 2. Query duplicates using db print-duplicates --verbose (database on subcommand)
      val dupResult1 = command.test(
        listOf("db", "print-duplicates", "--verbose", dbFile.toString())
      )
      assertEquals(0, dupResult1.statusCode)
      assertTrue(dupResult1.output.contains("### ==== Duplicate Files in DB ==== ###"))
      assertTrue(dupResult1.output.contains("f1.txt"))
      assertTrue(dupResult1.output.contains("f2.txt"))
      assertFalse(dupResult1.output.contains("f3.txt"))
      assertTrue(dupResult1.output.contains("DUPLICATION SUMMARY"))
      assertTrue(dupResult1.output.contains("Scan & Ingestion Statistics:"))
      assertTrue(dupResult1.output.contains("Top 10 Largest Duplicate Files:"))
      assertTrue(dupResult1.output.contains("Percentile Buckets by File Size (10% Deciles):"))
      assertTrue(dupResult1.output.contains("Total Processing Time:"))

      // 3. Query duplicates using db <DATABASE> print-duplicates --verbose (database on parent command)
      val dupResult2 = command.test(
        listOf("db", dbFile.toString(), "print-duplicates", "--verbose")
      )
      assertEquals(0, dupResult2.statusCode)
      assertTrue(dupResult2.output.contains("### ==== Duplicate Files in DB ==== ###"))
      assertTrue(dupResult2.output.contains("f1.txt"))
      assertTrue(dupResult2.output.contains("f2.txt"))
      assertFalse(dupResult2.output.contains("f3.txt"))

      // 4. Query duplicates without --verbose (only summary and top 10, no individual duplicate file entries)
      val dupResultDefault = command.test(listOf("db", "print-duplicates", dbFile.toString()))
      assertEquals(0, dupResultDefault.statusCode)
      assertFalse(dupResultDefault.output.contains("### ==== Duplicate Files in DB ==== ###"))
      assertFalse(dupResultDefault.output.contains("snapshot="))
      assertTrue(dupResultDefault.output.contains("DUPLICATION SUMMARY"))
      assertTrue(dupResultDefault.output.contains("Top 10 Largest Duplicate Files:"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db print-duplicates with no duplicates reports no duplicates found`() {
    val tempDir = Files.createTempDirectory("clikt-no-dup-test")
    val dbDir = Files.createTempDirectory("clikt-no-dup-db")
    try {
      tempDir.resolve("unique.txt").writeText("unique content")
      val dbFile = dbDir.resolve("no_dups_db")

      val command = BovedaCommand()
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      val dupResult = command.test(listOf("db", "print-duplicates", dbFile.toString()))
      assertEquals(0, dupResult.statusCode)
      assertTrue(dupResult.output.contains("No duplicates found."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db command fails when database file does not exist`() {
    val command = BovedaCommand()
    val result = command.test(listOf("db", "/path/to/definitely/nonexistent.db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database file does not exist"))
  }

  @Test
  fun `log-level DEBUG flag configures root logger and logs Exposed queries to stderr`() {
    val tempDir = Files.createTempDirectory("clikt-log-debug-test")
    val dbDir = Files.createTempDirectory("clikt-log-debug-db")
    val originalErr = System.err
    val errStream = ByteArrayOutputStream()

    try {
      System.setErr(PrintStream(errStream))
      tempDir.resolve("file.txt").writeText("data")
      val dbFile = dbDir.resolve("log_debug_db")

      val command = BovedaCommand()
      val result = command.test(
        listOf("-l", "DEBUG", "scan", tempDir.toString(), dbFile.toString())
      )

      assertEquals(0, result.statusCode)
      assertEquals(Level.DEBUG, LoggingConfig.getRootLevel())

      val stderrText = errStream.toString()
      assertTrue(stderrText.contains("DEBUG Exposed"))
    } finally {
      System.setErr(originalErr)
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `log-level WARN default suppresses Exposed queries from stderr`() {
    val tempDir = Files.createTempDirectory("clikt-log-warn-test")
    val dbDir = Files.createTempDirectory("clikt-log-warn-db")
    val originalErr = System.err
    val errStream = ByteArrayOutputStream()

    try {
      System.setErr(PrintStream(errStream))
      tempDir.resolve("file.txt").writeText("data")
      val dbFile = dbDir.resolve("log_warn_db")

      val command = BovedaCommand()
      val result = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertEquals(Level.WARN, LoggingConfig.getRootLevel())

      val stderrText = errStream.toString()
      assertFalse(stderrText.contains("DEBUG Exposed"))
    } finally {
      System.setErr(originalErr)
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `invalid log-level fails with non-zero exit code`() {
    val command = BovedaCommand()
    val result = command.test(listOf("--log-level", "INVALID_LEVEL"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("invalid choice: INVALID_LEVEL"))
  }

  @Test
  fun `db snapshots list displays empty message when no snapshots exist`() {
    val tempDir = Files.createTempDirectory("clikt-empty-snapshots-test")
    try {
      val emptyDb = tempDir.resolve("empty_db")
      val db = com.cherba29.boveda.db.DatabaseFactory.connectFile(emptyDb.toString())
      // Initialize schema without snapshots
      com.cherba29.boveda.db.DatabaseFactory.initSchema(db)

      val command = BovedaCommand()
      val result = command.test(listOf("db", "snapshots", "list", emptyDb.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No snapshots found in database."))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots list displays formatted table for populated database`() {
    val tempDir = Files.createTempDirectory("clikt-list-snapshots-test")
    val dbDir = Files.createTempDirectory("clikt-list-snapshots-db")
    try {
      tempDir.resolve("file1.txt").writeText("content 1")
      tempDir.resolve("file2.txt").writeText("content 2")
      val dbFile = dbDir.resolve("snapshots_db")

      val command = BovedaCommand()
      // 1. Scan directory to create snapshot
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // 2. List snapshots
      val listResult = command.test(listOf("db", "snapshots", "list", dbFile.toString()))
      assertEquals(0, listResult.statusCode)
      assertTrue(listResult.output.contains("ID"))
      assertTrue(listResult.output.contains("Root Path"))
      assertTrue(listResult.output.contains("Files"))
      assertTrue(listResult.output.contains("Total Size"))
      assertTrue(listResult.output.contains("Status"))
      assertTrue(listResult.output.contains("Completed"))
      assertTrue(listResult.output.contains(tempDir.invariantSeparatorsPathString))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots list inherits database path from db parent command`() {
    val tempDir = Files.createTempDirectory("clikt-parent-list-snapshots-test")
    val dbDir = Files.createTempDirectory("clikt-parent-list-snapshots-db")
    try {
      tempDir.resolve("file.txt").writeText("content")
      val dbFile = dbDir.resolve("parent_list_db")

      val command = BovedaCommand()
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // Database specified on db command
      val listResult = command.test(listOf("db", dbFile.toString(), "snapshots", "list"))
      assertEquals(0, listResult.statusCode)
      assertTrue(listResult.output.contains("Completed"))
      assertTrue(listResult.output.contains(tempDir.invariantSeparatorsPathString))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots delete removes specified snapshot and outputs success`() {
    val tempDir = Files.createTempDirectory("clikt-delete-snapshots-test")
    val dbDir = Files.createTempDirectory("clikt-delete-snapshots-db")
    try {
      tempDir.resolve("file1.txt").writeText("content")
      val dbFile = dbDir.resolve("delete_db")

      val command = BovedaCommand()
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // List to check snapshot ID 1
      val listResultBefore = command.test(
        listOf("db", "snapshots", "list", dbFile.toString())
      )
      assertEquals(0, listResultBefore.statusCode)
      assertTrue(listResultBefore.output.contains("Completed"))

      // Delete snapshot 1
      val deleteResult = command.test(
        listOf("db", "snapshots", "delete", "1", dbFile.toString())
      )
      assertEquals(0, deleteResult.statusCode)
      assertTrue(deleteResult.output.contains("Snapshot 1 deleted successfully."))

      // Verify list is now empty
      val listResultAfter = command.test(listOf("db", "snapshots", "list", dbFile.toString()))
      assertEquals(0, listResultAfter.statusCode)
      assertTrue(listResultAfter.output.contains("No snapshots found in database."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots delete inherits database path from db parent command`() {
    val tempDir = Files.createTempDirectory("clikt-parent-delete-snapshots-test")
    val dbDir = Files.createTempDirectory("clikt-parent-delete-snapshots-db")
    try {
      tempDir.resolve("file.txt").writeText("content")
      val dbFile = dbDir.resolve("parent_delete_db")

      val command = BovedaCommand()
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // Database specified on db command
      val deleteResult = command.test(
        listOf("db", dbFile.toString(), "snapshots", "delete", "1")
      )
      assertEquals(0, deleteResult.statusCode)
      assertTrue(deleteResult.output.contains("Snapshot 1 deleted successfully."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots delete fails when snapshot does not exist`() {
    val tempDir = Files.createTempDirectory("clikt-delete-not-found-test")
    val dbDir = Files.createTempDirectory("clikt-delete-not-found-db")
    try {
      tempDir.resolve("file.txt").writeText("content")
      val dbFile = dbDir.resolve("not_found_db")

      val command = BovedaCommand()
      val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // Delete non-existent snapshot ID 999
      val deleteResult = command.test(
        listOf("db", "snapshots", "delete", "999", dbFile.toString())
      )
      assertNotEquals(0, deleteResult.statusCode)
      assertTrue(deleteResult.output.contains("Snapshot with ID 999 does not exist."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db snapshots delete fails when database does not exist`() {
    val command = BovedaCommand()
    val result = command.test(
      listOf("db", "snapshots", "delete", "1", "/path/to/definitely/nonexistent.db")
    )

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database file does not exist"))
  }
}
