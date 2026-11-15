package com.cherba29.boveda.cmds.db.snapshots

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ListTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `list command help displays usage, parameters, and options`() {
    val command = ListCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: list"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--log-level"))
  }

  @Test
  fun `list command displays empty message when database has no snapshots`() {
    val tempDir = Files.createTempDirectory("list-empty-test")
    try {
      val emptyDb = tempDir.resolve("empty_db")
      val db = DatabaseFactory.connectFile(emptyDb.toString())
      DatabaseFactory.initSchema(db)

      val command = ListCommand()
      val result = command.test(listOf(emptyDb.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No snapshots found in database."))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `list command outputs formatted table with snapshots`() {
    val tempDir = Files.createTempDirectory("list-table-test")
    val dbDir = Files.createTempDirectory("list-table-db")
    try {
      tempDir.resolve("sample.txt").writeText("content for list")
      val dbFile = dbDir.resolve("list_db")

      val scanCmd = ScanCommand()
      assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

      val listCmd = ListCommand()
      val result = listCmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("ID"))
      assertTrue(result.output.contains("Root Path"))
      assertTrue(result.output.contains("Files"))
      assertTrue(result.output.contains("Total Size"))
      assertTrue(result.output.contains("Status"))
      assertTrue(result.output.contains("Completed"))
      assertTrue(result.output.contains(tempDir.invariantSeparatorsPathString))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `list command resolves database path from parent DbCommand`() {
    val tempDir = Files.createTempDirectory("list-parent-db-test")
    val dbDir = Files.createTempDirectory("list-parent-db")
    try {
      tempDir.resolve("doc.txt").writeText("parent db content")
      val dbFile = dbDir.resolve("parent_db")

      val scanCmd = ScanCommand()
      assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

      val dbCmd = DbCommand()
      val result = dbCmd.test(listOf(dbFile.toString(), "snapshots", "list"))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Completed"))
      assertTrue(result.output.contains(tempDir.invariantSeparatorsPathString))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `list command fails when database file does not exist`() {
    val command = ListCommand()
    val result = command.test(listOf("/path/to/definitely/nonexistent.db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database file does not exist"))
  }
}
