package com.cherba29.boveda.cmds.db.folder

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.BovedaCommand
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.cmds.db.FolderCommand
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFolder
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateFolderGroup
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DeleteTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `folder delete help displays usage, parameters, and options`() {
    val command = DeleteCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: delete"))
    assertTrue(result.output.contains("<folder>"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("-f, --folder"))
    assertTrue(result.output.contains("-d, --db"))
    assertTrue(result.output.contains("--force"))
    assertTrue(result.output.contains("-s, --snapshot"))
    assertTrue(result.output.contains("-l, --log-level"))

    val folderCmd = FolderCommand()
    val folderHelp = folderCmd.test("--help")
    assertEquals(0, folderHelp.statusCode)
    assertTrue(folderHelp.output.contains("print-duplicates"))
    assertTrue(folderHelp.output.contains("delete"))
  }

  @Test
  fun `getIdenticalFolderGroups returns only 100 percent matching leaf folders`() {
    val tempDir = Files.createTempDirectory("folder-exact-match-dir")
    val dbDir = Files.createTempDirectory("folder-exact-match-db")
    try {
      // 1. 100% matching pair: exactA and exactB
      val exactA = tempDir.resolve("exactA").apply { createDirectories() }
      val exactB = tempDir.resolve("exactB").apply { createDirectories() }
      exactA.resolve("f1.txt").writeText("identical content one")
      exactA.resolve("f2.txt").writeText("identical content two")
      exactB.resolve("f1.txt").writeText("identical content one")
      exactB.resolve("f2.txt").writeText("identical content two")

      // 2. Partial match pair: partialA has an extra file -> must NOT match
      val partialA = tempDir.resolve("partialA").apply { createDirectories() }
      val partialB = tempDir.resolve("partialB").apply { createDirectories() }
      partialA.resolve("shared.txt").writeText("shared file body")
      partialA.resolve("extra.txt").writeText("extra unique file")
      partialB.resolve("shared.txt").writeText("shared file body")

      // 3. Same size & count but different content -> must NOT match
      val diffA = tempDir.resolve("diffA").apply { createDirectories() }
      val diffB = tempDir.resolve("diffB").apply { createDirectories() }
      diffA.resolve("x.txt").writeText("1234567890")
      diffB.resolve("x.txt").writeText("abcdefghij")

      // 4. Folder with child subfolder vs leaf folder with same direct file -> must NOT match
      val parentWithSub = tempDir.resolve("parentWithSub").apply { createDirectories() }
      val childSub = parentWithSub.resolve("sub").apply { createDirectories() }
      val leafOnly = tempDir.resolve("leafOnly").apply { createDirectories() }
      parentWithSub.resolve("doc.txt").writeText("same direct file")
      childSub.resolve("secret.txt").writeText("important child file")
      leafOnly.resolve("doc.txt").writeText("same direct file")

      val dbFile = dbDir.resolve("exact_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val service = BovedaService(database = DatabaseFactory.connectFile(dbFile.toString()))
      val snap = service.getLatestSnapshot()!!
      val groups = service.getIdenticalFolderGroups(snap.id.value)

      assertEquals(1, groups.size)
      assertEquals(2, groups[0].copyCount)
      assertEquals(2, groups[0].fileCount)
      assertTrue(groups[0].paths.any { it.endsWith("/exactA") })
      assertTrue(groups[0].paths.any { it.endsWith("/exactB") })
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `interactive folder delete presents only 100 percent matching folders and deletes selected`() {
    val tempDir = Files.createTempDirectory("folder-del-interactive-dir")
    val dbDir = Files.createTempDirectory("folder-del-interactive-db")
    try {
      // Larger 100% matching pair
      val bigA = tempDir.resolve("bigA").apply { createDirectories() }
      val bigB = tempDir.resolve("bigB").apply { createDirectories() }
      bigA.resolve("large.bin").writeText("L".repeat(2000))
      bigB.resolve("large.bin").writeText("L".repeat(2000))

      // Smaller 100% matching pair
      val smallA = tempDir.resolve("smallA").apply { createDirectories() }
      val smallB = tempDir.resolve("smallB").apply { createDirectories() }
      smallA.resolve("tiny.txt").writeText("tiny")
      smallB.resolve("tiny.txt").writeText("tiny")

      // Non-matching (partial) pair -> must never be shown
      val partA = tempDir.resolve("partA").apply { createDirectories() }
      val partB = tempDir.resolve("partB").apply { createDirectories() }
      partA.resolve("same.txt").writeText("same content")
      partA.resolve("only_in_a.txt").writeText("unique content")
      partB.resolve("same.txt").writeText("same content")

      val dbFile = dbDir.resolve("interactive_folder_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val presentedGroups = mutableListOf<DuplicateFolderGroup>()
      val selector: (DuplicateFolderGroup) -> List<String>? = { group ->
        presentedGroups.add(group)
        // First group (bigA/bigB): delete bigB; second group (smallA/smallB): skip
        if (group.paths.any { it.endsWith("/bigB") }) {
          listOf(group.paths.first { it.endsWith("/bigB") })
        } else {
          emptyList()
        }
      }

      val cmd = BovedaCommand(folderSelector = selector)
      val result = cmd.test(listOf("db", "folder", "delete", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertEquals(2, presentedGroups.size)
      // Largest wasted group presented first
      assertEquals(2000L, presentedGroups[0].folderBytes)
      assertTrue(presentedGroups[0].sampleFiles.any { it.first == "large.bin" })
      assertEquals(4L, presentedGroups[1].folderBytes)

      // bigB was deleted from disk, bigA and smallA/smallB remain
      assertFalse(bigB.exists())
      assertTrue(bigA.exists())
      assertTrue(bigA.resolve("large.bin").exists())
      assertTrue(smallA.exists())
      assertTrue(smallB.exists())

      assertTrue(result.output.contains("Deleted 1 duplicate folder(s):"))
      assertTrue(result.output.contains("No folders selected to delete. Skipping"))
      assertTrue(
        result.output.contains("No more 100% matching duplicate folders found in database.")
      )

      // Verify bigB removed from DB while bigA and shared RepoFile row remain
      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        val folderPaths = RepoFolder.all().map { it.path }
        assertFalse(folderPaths.any { it.endsWith("/bigB") })
        assertTrue(folderPaths.any { it.endsWith("/bigA") })
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `interactive folder delete reports when no 100 percent matching folders exist`() {
    val tempDir = Files.createTempDirectory("folder-del-none-dir")
    val dbDir = Files.createTempDirectory("folder-del-none-db")
    try {
      // Only partially matching folders exist
      val fA = tempDir.resolve("fA").apply { createDirectories() }
      val fB = tempDir.resolve("fB").apply { createDirectories() }
      fA.resolve("common.txt").writeText("common")
      fA.resolve("diffA.txt").writeText("diffA")
      fB.resolve("common.txt").writeText("common")
      fB.resolve("diffB.txt").writeText("diffB")

      val dbFile = dbDir.resolve("no_exact_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val cmd = DeleteCommand(folderSelector = { error("Selector should not be called") })
      val result = cmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No 100% matching duplicate folders found."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `interactive folder delete quits cleanly when user cancels`() {
    val tempDir = Files.createTempDirectory("folder-del-quit-dir")
    val dbDir = Files.createTempDirectory("folder-del-quit-db")
    try {
      val a = tempDir.resolve("a").apply { createDirectories() }
      val b = tempDir.resolve("b").apply { createDirectories() }
      a.resolve("x.txt").writeText("same")
      b.resolve("x.txt").writeText("same")

      val dbFile = dbDir.resolve("quit_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val cmd = DeleteCommand(folderSelector = { null })
      val result = cmd.test(listOf(dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Quitting."))
      assertTrue(a.exists())
      assertTrue(b.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `single folder delete removes matching duplicate folder and enforces force on unique folder`() {
    val tempDir = Files.createTempDirectory("folder-del-single-dir")
    val dbDir = Files.createTempDirectory("folder-del-single-db")
    try {
      val dup1 = tempDir.resolve("dup1").apply { createDirectories() }
      val dup2 = tempDir.resolve("dup2").apply { createDirectories() }
      dup1.resolve("a.txt").writeText("shared content")
      dup2.resolve("a.txt").writeText("shared content")

      val dbFile = dbDir.resolve("single_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Deleting dup2 succeeds because dup1 is a 100% match
      val dbCmd = DbCommand()
      val res1 = dbCmd.test(listOf(dbFile.toString(), "folder", "delete", dup2.toString()))
      assertEquals(0, res1.statusCode)
      assertTrue(res1.output.contains("Deleted folder from disk and database:"))
      assertFalse(dup2.exists())
      assertTrue(dup1.exists())

      // Now dup1 has no matching duplicate -> fails without --force
      val res2 = dbCmd.test(listOf(dbFile.toString(), "folder", "delete", dup1.toString()))
      assertNotEquals(0, res2.statusCode)
      assertTrue(res2.output.contains("does not have a 100% matching duplicate folder"))
      assertTrue(dup1.exists())

      // Deleting dup1 with --force succeeds and cleans up orphaned RepoFile
      val res3 = dbCmd.test(
        listOf(dbFile.toString(), "folder", "delete", "--force", dup1.toString())
      )
      assertEquals(0, res3.statusCode)
      assertFalse(dup1.exists())

      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        assertEquals(0, RepoFolder.all().count())
        assertEquals(0, RepoFileInstance.all().count())
        assertEquals(0, RepoFile.all().count())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `InteractiveDuplicateFolderSelector fallback line prompt handles selection, skip, all, and quit`() {
    val group = DuplicateFolderGroup(
      signature = "sig123",
      fileCount = 2,
      folderBytes = 2048L,
      paths = listOf("/data/copy1", "/data/copy2"),
      sampleFiles = listOf("a.bin" to 1024L, "b.bin" to 1024L)
    )

    // Select item 2
    val rec1 = TerminalRecorder(inputInteractive = false, outputInteractive = false)
    rec1.inputLines.add("2")
    val sel1 = InteractiveDuplicateFolderSelector(Terminal(terminalInterface = rec1))
    assertEquals(listOf("/data/copy2"), sel1.selectDuplicateFolders(group))

    // Select all
    val rec2 = TerminalRecorder(inputInteractive = false, outputInteractive = false)
    rec2.inputLines.add("all")
    val sel2 = InteractiveDuplicateFolderSelector(Terminal(terminalInterface = rec2))
    assertEquals(listOf("/data/copy1", "/data/copy2"), sel2.selectDuplicateFolders(group))

    // Skip
    val rec3 = TerminalRecorder(inputInteractive = false, outputInteractive = false)
    rec3.inputLines.add("s")
    val sel3 = InteractiveDuplicateFolderSelector(Terminal(terminalInterface = rec3))
    assertEquals(emptyList(), sel3.selectDuplicateFolders(group))

    // Quit
    val rec4 = TerminalRecorder(inputInteractive = false, outputInteractive = false)
    rec4.inputLines.add("q")
    val sel4 = InteractiveDuplicateFolderSelector(Terminal(terminalInterface = rec4))
    assertNull(sel4.selectDuplicateFolders(group))
  }
}
