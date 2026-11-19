package com.cherba29.boveda.cmds.db.folder

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.BovedaCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.cmds.db.FolderCommand
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrintDuplicatesTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `folder print-duplicates help displays usage, parameters, and options`() {
    val command = PrintDuplicatesCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: print-duplicates"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--snapshot"))
    assertTrue(result.output.contains("--limit"))
    assertTrue(result.output.contains("--log-level"))
  }

  @Test
  fun `folder help displays usage and subcommands`() {
    val command = FolderCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: folder"))
    assertTrue(result.output.contains("print-duplicates"))
  }

  @Test
  fun `folder print-duplicates reports no duplicates when database has no duplicate files`() {
    val tempDir = Files.createTempDirectory("folder-cmd-nodups-dir")
    val dbDir = Files.createTempDirectory("folder-cmd-nodups-db")
    try {
      tempDir.resolve("unique1.txt").writeText("content1")
      tempDir.resolve("unique2.txt").writeText("content2")
      val dbFile = dbDir.resolve("nodups_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val folderCmd = PrintDuplicatesCommand()
      val result = folderCmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No duplicate files found between folders."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `folder print-duplicates outputs top folder pairs with duplicate statistics and top 3 largest duplicate files`() {
    val tempDir = Files.createTempDirectory("folder-cmd-dups-dir")
    val dbDir = Files.createTempDirectory("folder-cmd-dups-db")
    try {
      val dir1 = tempDir.resolve("dir1").apply { createDirectories() }
      val dir2 = tempDir.resolve("dir2").apply { createDirectories() }

      val data1 = ByteArray(20_000) { 1 }
      val data2 = ByteArray(10_000) { 2 }
      val data3 = ByteArray(5_000) { 3 }
      val data4 = ByteArray(500) { 4 }

      dir1.resolve("f1.bin").writeBytes(data1)
      dir1.resolve("f2.bin").writeBytes(data2)
      dir1.resolve("f3.bin").writeBytes(data3)
      dir1.resolve("f4.bin").writeBytes(data4)
      dir1.resolve("diff1.txt").writeText("only in dir1")

      dir2.resolve("copy1.bin").writeBytes(data1)
      dir2.resolve("copy2.bin").writeBytes(data2)
      dir2.resolve("copy3.bin").writeBytes(data3)
      dir2.resolve("copy4.bin").writeBytes(data4)
      dir2.resolve("diff2.txt").writeText("only in dir2")

      val dbFile = dbDir.resolve("dups_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val folderCmd = PrintDuplicatesCommand()
      val result = folderCmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("TOP DUPLICATE FOLDER PAIRS"))
      assertTrue(result.output.contains("dir1 <->") || result.output.contains("<->"))
      assertTrue(result.output.contains("Same Files:"))
      assertTrue(result.output.contains("4 files"))
      assertTrue(result.output.contains("Different Files:"))
      assertTrue(result.output.contains("2 files"))
      assertTrue(result.output.contains("Total Duplicated Storage:"))
      assertTrue(result.output.contains("Duplicate Files Share:"))
      assertTrue(result.output.contains("66.67%"))
      assertTrue(result.output.contains("Duplicate Storage Share:"))
      assertTrue(result.output.contains("Top 3 Largest Duplicate File(s):"))
      assertTrue(result.output.contains("1. f1.bin") || result.output.contains("copy1.bin"))
      assertTrue(result.output.contains("2. f2.bin") || result.output.contains("copy2.bin"))
      assertTrue(result.output.contains("3. f3.bin") || result.output.contains("copy3.bin"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `folder print-duplicates works when invoked as db folder print-duplicates subcommand hierarchy`() {
    val tempDir = Files.createTempDirectory("folder-hier-dir")
    val dbDir = Files.createTempDirectory("folder-hier-db")
    try {
      val d1 = tempDir.resolve("d1").apply { createDirectories() }
      val d2 = tempDir.resolve("d2").apply { createDirectories() }

      val content = ByteArray(1024) { 9 }
      d1.resolve("a.bin").writeBytes(content)
      d2.resolve("b.bin").writeBytes(content)

      val dbFile = dbDir.resolve("hier_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val bovedaCmd = BovedaCommand()
      val result = bovedaCmd.test(
        listOf("db", "--db", dbFile.toString(), "folder", "print-duplicates")
      )

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("TOP DUPLICATE FOLDER PAIRS"))
      assertTrue(result.output.contains("Same Files:"))
      assertTrue(result.output.contains("1 files") || result.output.contains("1 file"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `folder print-duplicates fails when database file does not exist`() {
    val folderCmd = PrintDuplicatesCommand()
    val result = folderCmd.test(listOf("/non/existent/db/file"))

    assertNotEquals(0, result.statusCode)
    assertTrue(result.output.contains("does not exist"))
  }
}
