package com.cherba29.boveda.cmds.db

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrintDuplicatesTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `print-duplicates help displays usage, parameters, and options`() {
    val command = PrintDuplicatesCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: print-duplicates"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--snapshot"))
    assertTrue(result.output.contains("--verbose"))
    assertTrue(result.output.contains("--log-level"))
  }

  @Test
  fun `print-duplicates with verbose flag outputs duplicate files and duplication summary statistics`() {
    val tempDir = Files.createTempDirectory("print-dups-test-dir")
    val dbDir = Files.createTempDirectory("print-dups-test-db")
    try {
      val subDir = tempDir.resolve("sub").apply { createDirectories() }
      tempDir.resolve("f1.txt").writeText("duplicate content")
      subDir.resolve("f2.txt").writeText("duplicate content")
      tempDir.resolve("unique.txt").writeText("unique content")
      val dbFile = dbDir.resolve("dups_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val printDupCmd = PrintDuplicatesCommand()
      val result = printDupCmd.test(listOf("--verbose", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("### ==== Duplicate Files in DB ==== ###"))
      assertTrue(result.output.contains("f1.txt"))
      assertTrue(result.output.contains("f2.txt"))
      assertFalse(result.output.contains("unique.txt"))
      assertTrue(result.output.contains("DUPLICATION SUMMARY"))
      assertTrue(result.output.contains("Scan & Ingestion Statistics:"))
      assertTrue(result.output.contains("Top 10 Largest Duplicate Files:"))
      assertTrue(result.output.contains("Percentile Buckets by File Size (10% Deciles):"))
      assertTrue(result.output.contains("Total Wasted Storage:"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `print-duplicates without verbose flag only outputs summary and top 10 without individual entries`() {
    val tempDir = Files.createTempDirectory("print-dups-noverbose-dir")
    val dbDir = Files.createTempDirectory("print-dups-noverbose-db")
    try {
      val subDir = tempDir.resolve("sub").apply { createDirectories() }
      tempDir.resolve("f1.txt").writeText("duplicate content")
      subDir.resolve("f2.txt").writeText("duplicate content")
      tempDir.resolve("unique.txt").writeText("unique content")
      val dbFile = dbDir.resolve("dups_noverbose_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val printDupCmd = PrintDuplicatesCommand()
      val result = printDupCmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertFalse(result.output.contains("### ==== Duplicate Files in DB ==== ###"))
      assertFalse(result.output.contains("snapshot="))
      assertTrue(result.output.contains("DUPLICATION SUMMARY"))
      assertTrue(result.output.contains("Scan & Ingestion Statistics:"))
      assertTrue(result.output.contains("Top 10 Largest Duplicate Files:"))
      assertTrue(result.output.contains("Percentile Buckets by File Size (10% Deciles):"))
      assertTrue(result.output.contains("Total Wasted Storage:"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `print-duplicates reports no duplicates when all files are unique`() {
    val tempDir = Files.createTempDirectory("print-no-dups-dir")
    val dbDir = Files.createTempDirectory("print-no-dups-db")
    try {
      tempDir.resolve("unique.txt").writeText("unique data")
      val dbFile = dbDir.resolve("no_dups_db")

      val scanCmd = ScanCommand()
      assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

      val printDupCmd = PrintDuplicatesCommand()
      val result = printDupCmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No duplicates found."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `print-duplicates resolves database path when invoked under DbCommand`() {
    val tempDir = Files.createTempDirectory("print-dups-parent-dir")
    val dbDir = Files.createTempDirectory("print-dups-parent-db")
    try {
      tempDir.resolve("file.txt").writeText("content")
      val dbFile = dbDir.resolve("parent_db")

      val scanCmd = ScanCommand()
      assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

      val dbCmd = DbCommand()
      val result = dbCmd.test(listOf(dbFile.toString(), "print-duplicates", "--verbose"))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("### ==== Duplicate Files in DB ==== ###"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `print-duplicates reports when no snapshots found in database`() {
    val tempDir = Files.createTempDirectory("print-dups-empty-db")
    try {
      val emptyDb = tempDir.resolve("empty_db")
      val db = DatabaseFactory.connectFile(emptyDb.toString())
      DatabaseFactory.initSchema(db)

      val printDupCmd = PrintDuplicatesCommand()
      val result = printDupCmd.test(listOf(emptyDb.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No snapshots found in database."))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `print-duplicates fails when database file does not exist`() {
    val printDupCmd = PrintDuplicatesCommand()
    val result = printDupCmd.test(listOf("/path/to/definitely/nonexistent.db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database file does not exist"))
  }
}
