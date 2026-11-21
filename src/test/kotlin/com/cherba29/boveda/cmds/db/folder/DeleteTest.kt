package com.cherba29.boveda.cmds.db.folder

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.BovedaCommand
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.cmds.db.FolderCommand
import com.cherba29.boveda.cmds.db.file.InteractiveDuplicateSelector
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFolder
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateFolderGroup
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.SymlinkEntry
import com.cherba29.boveda.model.formatDisplayPath
import com.cherba29.boveda.progress.StatusSpinner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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

  @Test
  fun `formatDisplayPath and selectors render native OS separators while returning DB paths`() {
    assertEquals(
      "E:\\repo\\bookArt\\EPS",
      formatDisplayPath("E:/repo/bookArt/EPS", '\\')
    )
    assertEquals(
      "E:/repo/bookArt/EPS",
      formatDisplayPath("E:/repo/bookArt/EPS", '/')
    )
    assertEquals(
      "\\\\server\\share\\dir",
      formatDisplayPath("//server/share/dir", '\\')
    )

    val folderGroup = DuplicateFolderGroup(
      signature = "win-sig",
      fileCount = 2,
      folderBytes = 100L,
      paths = listOf("E:/repo/a/EPS", "E:/repo/b/EPS"),
      symlinkPaths = listOf(SymlinkEntry("E:/repo/link_eps", "E:/repo/a/EPS"))
    )
    val folderRec = TerminalRecorder(inputInteractive = false, outputInteractive = false).apply {
      inputLines.add("1, 3")
    }
    val folderSel = InteractiveDuplicateFolderSelector(
      terminal = Terminal(terminalInterface = folderRec),
      pathSeparator = '\\'
    )
    val chosenFolders = folderSel.selectDuplicateFolders(folderGroup)
    assertEquals(listOf("E:/repo/a/EPS", "E:/repo/link_eps"), chosenFolders)
    assertTrue(folderRec.output().contains("E:\\repo\\a\\EPS"))
    assertTrue(folderRec.output().contains("E:\\repo\\b\\EPS"))
    assertTrue(folderRec.output().contains("E:\\repo\\link_eps [symlink -> E:\\repo\\a\\EPS]"))

    val fileGroup = DuplicateGroup(
      hash = "h".repeat(64),
      size = 50L,
      copyCount = 2,
      paths = listOf("E:/repo/a/f.eps", "E:/repo/b/f.eps"),
      symlinkPaths = listOf(SymlinkEntry("E:/repo/link.eps", "E:/repo/a/f.eps"))
    )
    val fileRec = TerminalRecorder(inputInteractive = false, outputInteractive = false).apply {
      inputLines.add("2")
    }
    val fileSel = InteractiveDuplicateSelector(
      terminal = Terminal(terminalInterface = fileRec),
      pathSeparator = '\\'
    )
    val chosenFiles = fileSel.selectDuplicates(fileGroup)
    assertEquals(listOf("E:/repo/b/f.eps"), chosenFiles)
    assertTrue(fileRec.output().contains("E:\\repo\\a\\f.eps"))
    assertTrue(fileRec.output().contains("E:\\repo\\link.eps [symlink -> E:\\repo\\a\\f.eps]"))
  }

  @Test
  fun `deleteFolder removes read-only files and read-only directories from disk`() {
    val tempDir = Files.createTempDirectory("folder-readonly-del-dir")
    val dbDir = Files.createTempDirectory("folder-readonly-del-db")
    try {
      val dir1 = tempDir.resolve("dir1").apply { createDirectories() }
      val dir2 = tempDir.resolve("dir2").apply { createDirectories() }
      val f1 = dir1.resolve("art.eps").apply { writeText("eps vector data") }
      val f2 = dir2.resolve("art.eps").apply { writeText("eps vector data") }

      val dbFile = dbDir.resolve("readonly_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Mark file and directory read-only before deletion (simulating CD-ROM / Windows archive)
      f2.toFile().setWritable(false)
      dir2.toFile().setWritable(false)

      val db = DatabaseFactory.connectFile(dbFile.toString())
      try {
        val service = BovedaService(database = db)
        val snap = service.getLatestSnapshot()!!
        val res = service.deleteFolder(snap.id.value, dir2)
        assertEquals(1, res.filesDeletedFromDisk)
        assertTrue(res.folderDeletedFromDisk)
        assertNull(res.folderDeletionError)
        assertFalse(dir2.exists())
        assertTrue(dir1.exists())
        assertTrue(f1.exists())
      } finally {
        DatabaseFactory.shutdown(db)
      }
    } finally {
      tempDir.toFile().walkBottomUp().forEach { it.setWritable(true) }
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `deleteFolder reports diagnostic reason when untracked leftover prevents directory removal`() {
    val tempDir = Files.createTempDirectory("folder-leftover-del-dir")
    val dbDir = Files.createTempDirectory("folder-leftover-del-db")
    try {
      val dir1 = tempDir.resolve("dir1").apply { createDirectories() }
      val dir2 = tempDir.resolve("dir2").apply { createDirectories() }
      dir1.resolve("doc.txt").writeText("duplicate content")
      dir2.resolve("doc.txt").writeText("duplicate content")

      val dbFile = dbDir.resolve("leftover_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Add an untracked file on disk after scanning
      val leftover = dir2.resolve("untracked.tmp").apply { writeText("created later") }

      val res = DbCommand().test(listOf(dbFile.toString(), "folder", "delete", dir2.toString()))
      assertEquals(0, res.statusCode)
      assertTrue(res.output.contains("directory not removed from disk"))
      assertTrue(res.output.contains("directory not empty"))
      assertTrue(res.output.contains("untracked.tmp"))
      assertTrue(leftover.exists())
      assertTrue(dir2.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `findParentFolderPaths accurately detects ancestors on Unix and Windows paths`() {
    val db = DatabaseFactory.connectInMemory("parent_paths_test_${System.nanoTime()}")
    val service = BovedaService(database = db)
    val snap = service.createSnapshot(Path.of("E:/repo"))
    val h = "c".repeat(64)

    service.recordBatch(
      snap,
      listOf(
        ScannedFile(Path.of("E:/repo/dir/a.txt"), "dir/a.txt", 10L, h),
        ScannedFile(Path.of("E:/repo/dir/sub/b.txt"), "dir/sub/b.txt", 10L, h),
        ScannedFile(Path.of("E:/repo/dir-copy/a.txt"), "dir-copy/a.txt", 10L, h),
        ScannedFile(Path.of("E:/rootfile.txt"), "rootfile.txt", 10L, h)
      )
    )

    val parents = transaction(db) {
      val conn = TransactionManager.current().connection.connection as Connection
      service.findParentFolderPaths(
        conn,
        snap.id.value,
        setOf("E:/", "E:/repo", "E:/repo/dir", "E:/repo/dir/sub", "E:/repo/dir-copy")
      )
    }
    assertTrue("E:/" in parents)
    assertTrue("E:/repo" in parents)
    assertTrue("E:/repo/dir" in parents)
    assertFalse("E:/repo/dir/sub" in parents)
    assertFalse("E:/repo/dir-copy" in parents)
  }

  @Test
  fun `interactive folder delete exposes matching parent folders after child deletion`() {
    val tempDir = Files.createTempDirectory("folder-nested-expose-dir")
    val dbDir = Files.createTempDirectory("folder-nested-expose-db")
    try {
      val p1 = tempDir.resolve("p1").apply { createDirectories() }
      val p2 = tempDir.resolve("p2").apply { createDirectories() }
      val c1 = p1.resolve("child").apply { createDirectories() }
      val c2 = p2.resolve("child").apply { createDirectories() }
      p1.resolve("parent.txt").writeText("identical parent file")
      p2.resolve("parent.txt").writeText("identical parent file")
      c1.resolve("child.txt").writeText("identical child file")
      c2.resolve("child.txt").writeText("identical child file")

      val dbFile = dbDir.resolve("nested_expose_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val seenGroups = mutableListOf<List<String>>()
      val cmd = DeleteCommand(
        folderSelector = { group ->
          seenGroups.add(group.paths)
          if (seenGroups.size == 1) {
            // Delete both identical child folders, exposing p1 and p2 as leaf duplicates
            group.paths
          } else {
            // Delete p2
            listOf(group.paths.last())
          }
        }
      )
      val res = cmd.test(listOf(dbFile.toString()))
      assertEquals(0, res.statusCode)
      assertEquals(2, seenGroups.size)
      assertTrue(seenGroups[0].all { it.endsWith("/child") })
      assertTrue(
        seenGroups[1].any { it.endsWith("/p1") } && seenGroups[1].any { it.endsWith("/p2") }
      )
      assertFalse(c1.exists())
      assertFalse(c2.exists())
      assertFalse(p2.exists())
      assertTrue(p1.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `StatusSpinner updates and clears cleanly in interactive and non-interactive modes`() {
    val nonInteractive = TerminalRecorder(outputInteractive = false)
    StatusSpinner(Terminal(terminalInterface = nonInteractive), "Working...").use { spinner ->
      spinner.update("Still working...")
    }
    assertEquals("", nonInteractive.output())

    val interactive = TerminalRecorder(outputInteractive = true)
    val spinner = StatusSpinner(Terminal(terminalInterface = interactive), "Searching...")
    spinner.update("Deleting files...")
    Thread.sleep(50)
    spinner.close()
    assertNotNull(interactive.output())
  }
}
