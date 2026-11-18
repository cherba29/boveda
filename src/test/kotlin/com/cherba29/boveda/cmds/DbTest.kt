package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DbTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `db command help displays usage, parameters, options, and subcommands`() {
    val command = DbCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: db"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--snapshot"))
    assertTrue(result.output.contains("--log-level"))
    assertTrue(result.output.contains("print-duplicates"))
    assertTrue(result.output.contains("snapshots"))
    assertTrue(result.output.contains("file"))
  }

  @Test
  fun `db command queries already populated database and prints results`() {
    val tempDir = Files.createTempDirectory("db-test-dir")
    val dbDir = Files.createTempDirectory("db-test-db")
    try {
      tempDir.resolve("entry.txt").writeText("database query test")
      val dbFile = dbDir.resolve("query_db")

      // Scan directory into database
      val scanCmd = ScanCommand()
      val scanResult = scanCmd.test(listOf(tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanResult.statusCode)

      // Query database using db command
      val dbCmd = DbCommand()
      val dbResult = dbCmd.test(listOf(dbFile.toString()))

      assertEquals(0, dbResult.statusCode)
      assertTrue(dbResult.output.contains("### ==== Selecting from DB ==== ###"))
      assertTrue(dbResult.output.contains("entry.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db command reports when no snapshots found in database`() {
    val tempDir = Files.createTempDirectory("db-empty-dir")
    try {
      val emptyDb = tempDir.resolve("empty_db")
      val db = DatabaseFactory.connectFile(emptyDb.toString())
      DatabaseFactory.initSchema(db)

      val dbCmd = DbCommand()
      val result = dbCmd.test(listOf(emptyDb.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No snapshots found in database."))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db command with snapshot option queries specified snapshot`() {
    val tempDir = Files.createTempDirectory("db-snap-opt-dir")
    val dbDir = Files.createTempDirectory("db-snap-opt-db")
    try {
      tempDir.resolve("f.txt").writeText("content")
      val dbFile = dbDir.resolve("snap_db")

      val scanCmd = ScanCommand()
      assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

      val dbCmd = DbCommand()
      val result = dbCmd.test(listOf("--snapshot", "1", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("### ==== Selecting from DB ==== ###"))
      assertTrue(result.output.contains("f.txt"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db command fails when database file does not exist`() {
    val dbCmd = DbCommand()
    val result = dbCmd.test(listOf("/path/to/definitely/nonexistent.db"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database file does not exist"))
  }

  @Test
  fun `db command fails when database is missing and no service factory`() {
    val dbCmd = DbCommand()
    val result = dbCmd.test(emptyList())

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("Database must be specified"))
  }
}
