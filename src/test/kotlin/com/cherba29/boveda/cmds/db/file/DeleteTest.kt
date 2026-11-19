package com.cherba29.boveda.cmds.db.file

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateGroup
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.input.KeyboardEvent
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
  fun `file delete help displays usage, parameters, options, and description`() {
    val command = DeleteCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: delete"))
    assertTrue(result.output.contains("<file>"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("-f, --file"))
    assertTrue(result.output.contains("-d, --db"))
    assertTrue(result.output.contains("--force"))
    assertTrue(result.output.contains("-s, --snapshot"))
    assertTrue(result.output.contains("-l, --log-level"))
  }

  @Test
  fun `file delete removes duplicate file from disk and database when duplicates exist`() {
    val tempDir = Files.createTempDirectory("file-del-dup-dir")
    val dbDir = Files.createTempDirectory("file-del-dup-db")
    try {
      val subDir = tempDir.resolve("sub").apply { createDirectories() }
      val file1 = tempDir.resolve("orig.txt").apply { writeText("identical content") }
      val file2 = subDir.resolve("copy.txt").apply { writeText("identical content") }
      val dbFile = dbDir.resolve("dup_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      assertTrue(file1.exists())
      assertTrue(file2.exists())

      // Delete file2 (has duplicate file1)
      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf(file2.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Deleted file from disk and database"))
      assertTrue(result.output.contains("copy.txt"))

      // file2 should be deleted from filesystem, file1 should remain
      assertFalse(file2.exists())
      assertTrue(file1.exists())

      // file2 should be deleted from database, file1 should remain
      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        val instances = RepoFileInstance.all().toList()
        assertEquals(1, instances.size)
        assertTrue(instances[0].path.endsWith("orig.txt"))

        // RepoFiles should still retain the content row because file1 still references it
        val files = RepoFile.all().toList()
        assertEquals(1, files.size)
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete fails when file does not have duplicates and force flag is not specified`() {
    val tempDir = Files.createTempDirectory("file-del-unique-dir")
    val dbDir = Files.createTempDirectory("file-del-unique-db")
    try {
      val uniqueFile = tempDir.resolve("unique.txt").apply { writeText("unique content") }
      val dbFile = dbDir.resolve("unique_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf(uniqueFile.toString(), dbFile.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("File does not have duplicates"))
      assertTrue(result.output.contains("Use --force to delete anyway"))

      // File should not be deleted from disk or DB
      assertTrue(uniqueFile.exists())
      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        assertEquals(1, RepoFileInstance.all().count())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete succeeds when file does not have duplicates but force flag is specified`() {
    val tempDir = Files.createTempDirectory("file-del-force-dir")
    val dbDir = Files.createTempDirectory("file-del-force-db")
    try {
      val uniqueFile = tempDir.resolve(
        "unique_forced.txt"
      ).apply { writeText("unique forced content") }
      val dbFile = dbDir.resolve("force_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf("--force", uniqueFile.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Deleted file from disk and database"))

      // File should be deleted from disk
      assertFalse(uniqueFile.exists())

      // Orphaned RepoFiles and instance should be removed from database
      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        assertEquals(0, RepoFileInstance.all().count())
        assertEquals(0, RepoFile.all().count())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete fails when file is not found in database`() {
    val tempDir = Files.createTempDirectory("file-del-missing-dir")
    val dbDir = Files.createTempDirectory("file-del-missing-db")
    try {
      tempDir.resolve("scanned.txt").writeText("content")
      val dbFile = dbDir.resolve("missing_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf("/non/existent/file.txt", dbFile.toString()))

      assertNotEquals(0, result.statusCode)
      assertTrue(result.output.contains("File not found in database"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete handles file already removed from disk gracefully`() {
    val tempDir = Files.createTempDirectory("file-del-diskless-dir")
    val dbDir = Files.createTempDirectory("file-del-diskless-db")
    try {
      val subDir = tempDir.resolve("sub").apply { createDirectories() }
      val file1 = tempDir.resolve("copy1.txt").apply { writeText("duplicate") }
      val file2 = subDir.resolve("copy2.txt").apply { writeText("duplicate") }
      val dbFile = dbDir.resolve("diskless_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Manually remove file2 from filesystem before running command
      Files.delete(file2)
      assertFalse(file2.exists())

      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf(file2.toString(), dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Deleted file from database"))

      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        assertEquals(1, RepoFileInstance.all().count())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete interactive mode finds largest wasted duplicate group and deletes selected duplicates`() {
    val tempDir = Files.createTempDirectory("file-del-interactive-dir")
    val dbDir = Files.createTempDirectory("file-del-interactive-db")
    try {
      val dirA = tempDir.resolve("dirA").apply { createDirectories() }
      val dirB = tempDir.resolve("dirB").apply { createDirectories() }
      val dirC = tempDir.resolve("dirC").apply { createDirectories() }

      // Group 1: 3 copies of 100 bytes (wasted: 200 bytes)
      val a1 = dirA.resolve("small1.txt").apply { writeText("a".repeat(100)) }
      val a2 = dirB.resolve("small2.txt").apply { writeText("a".repeat(100)) }
      val a3 = dirC.resolve("small3.txt").apply { writeText("a".repeat(100)) }

      // Group 2: 2 copies of 500 bytes (wasted: 500 bytes) -> largest wasted!
      val b1 = dirA.resolve("big1.txt").apply { writeText("b".repeat(500)) }
      val b2 = dirB.resolve("big2.txt").apply { writeText("b".repeat(500)) }

      val dbFile = dbDir.resolve("interactive_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Injected selector: when presented with group, selects b2 on first prompt, then quits
      var promptCount = 0
      var firstPromptGroup: DuplicateGroup? = null
      val mockSelector: (DuplicateGroup) -> List<String>? = { group ->
        promptCount++
        if (promptCount == 1) {
          firstPromptGroup = group
          // Select b2 to delete
          listOf(b2.toAbsolutePath().normalize().toString())
        } else {
          // Esc on subsequent prompt
          null
        }
      }

      val deleteCmd = DeleteCommand(selector = mockSelector)
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      // First prompt must be Group 2 (wasted: 500 bytes), NOT Group 1 (wasted: 200 bytes)
      assertEquals(500L, firstPromptGroup?.wastedBytes)
      assertEquals(2, firstPromptGroup?.copyCount)

      // b2 should be deleted from disk and DB
      assertFalse(b2.exists())
      assertTrue(b1.exists())
      assertTrue(a1.exists())
      assertTrue(a2.exists())
      assertTrue(a3.exists())

      assertTrue(result.output.contains("Deleted 1 duplicate file(s):"))
      assertTrue(result.output.contains("Quitting."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete interactive mode allows selecting multiple duplicates and reruns query until no duplicates remain`() {
    val tempDir = Files.createTempDirectory("file-del-multi-dir")
    val dbDir = Files.createTempDirectory("file-del-multi-db")
    try {
      val d1 = tempDir.resolve("d1").apply { createDirectories() }
      val d2 = tempDir.resolve("d2").apply { createDirectories() }
      val d3 = tempDir.resolve("d3").apply { createDirectories() }
      val d4 = tempDir.resolve("d4").apply { createDirectories() }

      // 4 copies of 1000 bytes
      val f1 = d1.resolve("item1.txt").apply { writeText("x".repeat(1000)) }
      val f2 = d2.resolve("item2.txt").apply { writeText("x".repeat(1000)) }
      val f3 = d3.resolve("item3.txt").apply { writeText("x".repeat(1000)) }
      val f4 = d4.resolve("item4.txt").apply { writeText("x".repeat(1000)) }

      val dbFile = dbDir.resolve("multi_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // User selects 2 files (f2, f3), leaving 2 files (f1, f4)
      // Then on next rerun, user selects f4, leaving only 1 unique file (f1)
      var promptIndex = 0
      val mockSelector: (DuplicateGroup) -> List<String>? = { group ->
        promptIndex++
        when (promptIndex) {
          1 -> {
            assertEquals(4, group.copyCount)
            listOf(
              f2.toAbsolutePath().normalize().toString(),
              f3.toAbsolutePath().normalize().toString()
            )
          }
          2 -> {
            assertEquals(2, group.copyCount)
            listOf(f4.toAbsolutePath().normalize().toString())
          }
          else -> null
        }
      }

      val deleteCmd = DeleteCommand(selector = mockSelector)
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertEquals(2, promptIndex)
      assertTrue(result.output.contains("Deleted 2 duplicate file(s):"))
      assertTrue(result.output.contains("Deleted 1 duplicate file(s):"))
      assertTrue(result.output.contains("No duplicates found."))

      // f2, f3, f4 deleted; f1 remains
      assertFalse(f2.exists())
      assertFalse(f3.exists())
      assertFalse(f4.exists())
      assertTrue(f1.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete interactive mode quits immediately when user presses Esc`() {
    val tempDir = Files.createTempDirectory("file-del-esc-dir")
    val dbDir = Files.createTempDirectory("file-del-esc-db")
    try {
      tempDir.resolve("c1.txt").writeText("dup")
      tempDir.resolve("c2.txt").writeText("dup")
      val dbFile = dbDir.resolve("esc_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Selector simulates Esc on first prompt
      val mockSelector: (DuplicateGroup) -> List<String>? = { null }

      val deleteCmd = DeleteCommand(selector = mockSelector)
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Quitting."))

      // No files deleted
      val db = DatabaseFactory.connectFile(dbFile.toString())
      transaction(db) {
        assertEquals(2, RepoFileInstance.all().count())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete interactive mode skips group when user selects 0 files`() {
    val tempDir = Files.createTempDirectory("file-del-skip-dir")
    val dbDir = Files.createTempDirectory("file-del-skip-db")
    try {
      // Group A: 2 copies of 200 bytes
      tempDir.resolve("a1.txt").writeText("a".repeat(200))
      tempDir.resolve("a2.txt").writeText("a".repeat(200))

      // Group B: 2 copies of 100 bytes
      tempDir.resolve("b1.txt").writeText("b".repeat(100))
      tempDir.resolve("b2.txt").writeText("b".repeat(100))

      val dbFile = dbDir.resolve("skip_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      var promptCount = 0
      val seenSizes = mutableListOf<Long>()
      val mockSelector: (DuplicateGroup) -> List<String>? = { group ->
        promptCount++
        seenSizes.add(group.size)
        if (promptCount == 1) {
          // Select 0 files for the largest group (Group A)
          emptyList()
        } else {
          // Then for Group B, user presses Esc
          null
        }
      }

      val deleteCmd = DeleteCommand(selector = mockSelector)
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertEquals(2, promptCount)
      assertEquals(listOf(200L, 100L), seenSizes)
      assertTrue(
        result.output.contains("No files selected to delete. Skipping this duplicate group.")
      )
      assertTrue(result.output.contains("Quitting."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete interactive mode reports no duplicates when database has no duplicate files`() {
    val tempDir = Files.createTempDirectory("file-del-nodups-dir")
    val dbDir = Files.createTempDirectory("file-del-nodups-db")
    try {
      tempDir.resolve("unique1.txt").writeText("unique1")
      tempDir.resolve("unique2.txt").writeText("unique2")
      val dbFile = dbDir.resolve("nodups_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val deleteCmd = DeleteCommand()
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("No duplicates found."))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file delete works when invoked as db file delete subcommand hierarchy`() {
    val tempDir = Files.createTempDirectory("file-del-subcmd-dir")
    val dbDir = Files.createTempDirectory("file-del-subcmd-db")
    try {
      val f1 = tempDir.resolve("copy1.txt").apply { writeText("hierarchy test") }
      val f2 = tempDir.resolve("copy2.txt").apply { writeText("hierarchy test") }
      val dbFile = dbDir.resolve("subcmd_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // Test invoking via parent DbCommand: db <DATABASE> file delete <FILE>
      val dbCmd = DbCommand()
      val result = dbCmd.test(listOf(dbFile.toString(), "file", "delete", f2.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(result.output.contains("Deleted file from disk and database"))
      assertFalse(f2.exists())
      assertTrue(f1.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `InteractiveDuplicateSelector keyboard navigation and selection with TerminalRecorder`() {
    val recorder = TerminalRecorder(inputInteractive = true)
    recorder.inputEvents.addAll(
      listOf(
        KeyboardEvent("ArrowDown"),
        KeyboardEvent(" "),
        KeyboardEvent("ArrowDown"),
        KeyboardEvent("x"),
        KeyboardEvent("Enter")
      )
    )

    val terminal = Terminal(terminalInterface = recorder)
    val selector = InteractiveDuplicateSelector(terminal)
    val group = DuplicateGroup(
      hash = "hash123",
      size = 1000L,
      copyCount = 3,
      paths = listOf("/path/a", "/path/b", "/path/c")
    )

    val selected = selector.selectDuplicates(group)
    assertEquals(listOf("/path/b", "/path/c"), selected)
  }

  @Test
  fun `InteractiveDuplicateSelector Escape key cancels selection`() {
    val recorder = TerminalRecorder(inputInteractive = true)
    recorder.inputEvents.addAll(
      listOf(
        KeyboardEvent("ArrowDown"),
        KeyboardEvent(" "),
        KeyboardEvent("Escape")
      )
    )

    val terminal = Terminal(terminalInterface = recorder)
    val selector = InteractiveDuplicateSelector(terminal)
    val group = DuplicateGroup(
      hash = "hash123",
      size = 1000L,
      copyCount = 2,
      paths = listOf("/path/a", "/path/b")
    )

    val selected = selector.selectDuplicates(group)
    assertNull(selected)
  }

  @Test
  fun `InteractiveDuplicateSelector fallback line prompt allows selecting by numbers`() {
    val recorder = TerminalRecorder(inputInteractive = false)
    recorder.inputLines.add("1, 3")

    val terminal = Terminal(terminalInterface = recorder)
    val selector = InteractiveDuplicateSelector(terminal)
    val group = DuplicateGroup(
      hash = "hash123",
      size = 500L,
      copyCount = 3,
      paths = listOf("/path/1", "/path/2", "/path/3")
    )

    val selected = selector.selectDuplicates(group)
    assertEquals(listOf("/path/1", "/path/3"), selected)
  }

  @Test
  fun `InteractiveDuplicateSelector fallback line prompt quits on esc or q`() {
    val recorder = TerminalRecorder(inputInteractive = false)
    recorder.inputLines.add("esc")

    val terminal = Terminal(terminalInterface = recorder)
    val selector = InteractiveDuplicateSelector(terminal)
    val group = DuplicateGroup(
      hash = "hash123",
      size = 500L,
      copyCount = 2,
      paths = listOf("/path/1", "/path/2")
    )

    val selected = selector.selectDuplicates(group)
    assertNull(selected)
  }

  @Test
  fun `InteractiveDuplicateSelector s key skips selection returning empty list`() {
    val recorder = TerminalRecorder(inputInteractive = true)
    recorder.inputEvents.addAll(
      listOf(
        KeyboardEvent("ArrowDown"),
        KeyboardEvent(" "),
        KeyboardEvent("s")
      )
    )

    val terminal = Terminal(terminalInterface = recorder)
    val selector = InteractiveDuplicateSelector(terminal)
    val group = DuplicateGroup(
      hash = "hash123",
      size = 1000L,
      copyCount = 2,
      paths = listOf("/path/a", "/path/b")
    )

    val selected = selector.selectDuplicates(group)
    assertEquals(emptyList(), selected)
  }

  @Test
  fun `InteractiveDuplicateSelector fallback line prompt skips on s or skip input`() {
    val recorder1 = TerminalRecorder(inputInteractive = false)
    recorder1.inputLines.add("s")
    val selector1 = InteractiveDuplicateSelector(Terminal(terminalInterface = recorder1))
    val group = DuplicateGroup(
      hash = "hash123",
      size = 500L,
      copyCount = 2,
      paths = listOf("/path/1", "/path/2")
    )
    assertEquals(emptyList(), selector1.selectDuplicates(group))

    val recorder2 = TerminalRecorder(inputInteractive = false)
    recorder2.inputLines.add("skip")
    val selector2 = InteractiveDuplicateSelector(Terminal(terminalInterface = recorder2))
    assertEquals(emptyList(), selector2.selectDuplicates(group))
  }

  @Test
  fun `file delete interactive mode skips group when s key is pressed and moves to next largest duplicate group`() {
    val tempDir = Files.createTempDirectory("file-del-skip-s-dir")
    val dbDir = Files.createTempDirectory("file-del-skip-s-db")
    try {
      val a1 = tempDir.resolve("a1.txt").apply { writeText("a".repeat(200)) }
      val a2 = tempDir.resolve("a2.txt").apply { writeText("a".repeat(200)) }

      val b1 = tempDir.resolve("b1.txt").apply { writeText("b".repeat(100)) }
      val b2 = tempDir.resolve("b2.txt").apply { writeText("b".repeat(100)) }

      val dbFile = dbDir.resolve("skip_s_db")

      val scanCmd = ScanCommand()
      assertEquals(
        0,
        scanCmd.test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val recorder = TerminalRecorder(inputInteractive = true)
      // Group A (200 bytes): user presses 's' to skip
      recorder.inputEvents.add(KeyboardEvent("s"))
      // Group B (100 bytes): user selects item 2 (b2) and presses Enter
      recorder.inputEvents.addAll(
        listOf(
          KeyboardEvent("ArrowDown"),
          KeyboardEvent(" "),
          KeyboardEvent("Enter")
        )
      )
      // Next prompt: user presses Escape
      recorder.inputEvents.add(KeyboardEvent("Escape"))

      val terminal = Terminal(terminalInterface = recorder)
      val selector = InteractiveDuplicateSelector(terminal)
      val deleteCmd = DeleteCommand(selector = { selector.selectDuplicates(it) })
      val result = deleteCmd.test(listOf("--db", dbFile.toString()))

      assertEquals(0, result.statusCode)
      assertTrue(
        result.output.contains("No files selected to delete. Skipping this duplicate group.")
      )
      assertTrue(result.output.contains("Deleted 1 duplicate file(s):"))
      assertTrue(result.output.contains("b2.txt"))

      // a1 and a2 were skipped and still exist
      assertTrue(a1.exists())
      assertTrue(a2.exists())

      // b2 was deleted; b1 remains
      assertFalse(b2.exists())
      assertTrue(b1.exists())
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }
}
