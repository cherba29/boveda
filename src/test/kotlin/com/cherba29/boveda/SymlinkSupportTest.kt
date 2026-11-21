package com.cherba29.boveda

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.cmds.db.file.DeleteCommand as FileDeleteCommand
import com.cherba29.boveda.cmds.db.file.InteractiveDuplicateSelector
import com.cherba29.boveda.cmds.db.file.PrintDuplicatesCommand as FilePrintDuplicatesCommand
import com.cherba29.boveda.cmds.db.folder.DeleteCommand as FolderDeleteCommand
import com.cherba29.boveda.cmds.db.folder.InteractiveDuplicateFolderSelector
import com.cherba29.boveda.cmds.db.folder.PrintDuplicatesCommand as FolderPrintDuplicatesCommand
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateFolderGroup
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.SymlinkEntry
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.FileTreeMetadataScanner
import com.cherba29.boveda.scanner.ParallelDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.scanner.SymlinkUtils
import com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
import com.cherba29.boveda.scanner.WorkStealingMetadataScanner
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assumptions.assumeTrue

class SymlinkSupportTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  private fun createSymlinkOrAssume(link: Path, target: Path): Path {
    try {
      return Files.createSymbolicLink(link, target)
    } catch (e: Exception) {
      assumeTrue(false, "OS does not permit creating symbolic links: ${e.message}")
      throw e
    }
  }

  @Test
  fun `all directory and metadata scanners record symlinks with actual size without following`() {
    val tempDir = Files.createTempDirectory("symlink-scanners-test")
    try {
      val targetDir = tempDir.resolve("target_dir").apply { createDirectories() }
      val realFile = targetDir.resolve("big.bin")
      realFile.writeBytes(ByteArray(10_240) { (it % 251).toByte() })

      val fileLink = createSymlinkOrAssume(tempDir.resolve("big_link.bin"), realFile)
      val dirLink = createSymlinkOrAssume(tempDir.resolve("dir_link"), targetDir)

      val expectedFileLinkSize = Files.readAttributes(
        fileLink,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
      ).size()
      val expectedDirLinkSize = Files.readAttributes(
        dirLink,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
      ).size()

      val scanners = listOf(
        FileTreeDirectoryScanner(),
        ParallelDirectoryScanner(threadCount = 2),
        WorkStealingDirectoryScanner(parallelism = 2)
      )

      for (scanner in scanners) {
        val entries = scanner.scan(tempDir).toList()
        assertEquals(
          3,
          entries.size,
          "Expected 1 real file + 2 symlinks without recursing into dir symlink " +
            "for ${scanner::class.simpleName}"
        )

        val realEntry = entries.first { it.path.normalize() == realFile.normalize() }
        assertFalse(realEntry.isSymlink)
        assertEquals(10_240L, realEntry.size)

        val fileLinkEntry = entries.first { it.path.normalize() == fileLink.normalize() }
        assertTrue(fileLinkEntry.isSymlink)
        assertEquals(expectedFileLinkSize, fileLinkEntry.size)
        assertTrue(fileLinkEntry.size < 10_240L)
        assertEquals(
          realFile.normalize().invariantSeparatorsPathString,
          fileLinkEntry.symlinkTarget
        )
        assertTrue(fileLinkEntry.hash.startsWith("symlink:"))

        val dirLinkEntry = entries.first { it.path.normalize() == dirLink.normalize() }
        assertTrue(dirLinkEntry.isSymlink)
        assertEquals(expectedDirLinkSize, dirLinkEntry.size)
        assertEquals(
          targetDir.normalize().invariantSeparatorsPathString,
          dirLinkEntry.symlinkTarget
        )
        assertTrue(dirLinkEntry.hash.startsWith("symlink:"))
      }

      val expectedTotalBytes = 10_240L + expectedFileLinkSize + expectedDirLinkSize
      val meta1 = FileTreeMetadataScanner.scan(tempDir)
      assertEquals(3L, meta1.fileCount)
      assertEquals(expectedTotalBytes, meta1.totalBytes)

      val meta2 = WorkStealingMetadataScanner(parallelism = 2).scan(tempDir)
      assertEquals(3L, meta2.fileCount)
      assertEquals(expectedTotalBytes, meta2.totalBytes)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `file symlink to unique file is not treated as a duplicate`() {
    val tempDir = Files.createTempDirectory("symlink-unique-file")
    val dbDir = Files.createTempDirectory("symlink-unique-db")
    try {
      val realFile = tempDir.resolve("only_copy.bin")
      realFile.writeBytes(ByteArray(8_192) { 42 })
      val fileLink = createSymlinkOrAssume(tempDir.resolve("link_copy.bin"), realFile)
      val expectedLinkSize = Files.readAttributes(
        fileLink,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
      ).size()

      val dbFile = dbDir.resolve("unique_sym_db")
      val scanRes = ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString()))
      assertEquals(0, scanRes.statusCode)

      val db = DatabaseFactory.connectFile(dbFile.toString())
      try {
        val service = BovedaService(database = db)
        val snap = service.getLatestSnapshot()
        assertNotNull(snap)

        val stats = service.computeDuplicateStatistics(snap.id.value, Duration.ZERO)
        assertEquals(2L, stats.totalFilesScanned)
        assertEquals(8_192L + expectedLinkSize, stats.totalScannedBytes)
        assertEquals(0L, stats.duplicateGroupsCount)
        assertEquals(0L, stats.duplicateFileCount)
        assertEquals(0L, stats.wastedBytes)
        assertTrue(stats.largestDuplicates.isEmpty())
      } finally {
        DatabaseFactory.shutdown(db)
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db file print-duplicates marks symlinks and excludes them from duplicate counts`() {
    val tempDir = Files.createTempDirectory("symlink-file-dups")
    val dbDir = Files.createTempDirectory("symlink-file-dups-db")
    try {
      val dirA = tempDir.resolve("dirA").apply { createDirectories() }
      val dirB = tempDir.resolve("dirB").apply { createDirectories() }
      val payload = ByteArray(4_096) { (it % 127).toByte() }
      val fileA = dirA.resolve("dup.bin").apply { writeBytes(payload) }
      val fileB = dirB.resolve("dup.bin").apply { writeBytes(payload) }
      val linkToA = createSymlinkOrAssume(tempDir.resolve("alias_dup.bin"), fileA)
      val linkSize = Files.readAttributes(
        linkToA,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
      ).size()

      val dbFile = dbDir.resolve("file_dups_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val printRes = FilePrintDuplicatesCommand().test(listOf("--verbose", dbFile.toString()))
      assertEquals(0, printRes.statusCode)
      val linkPathStr = linkToA.toAbsolutePath().normalize().invariantSeparatorsPathString
      val fileAPathStr = fileA.toAbsolutePath().normalize().invariantSeparatorsPathString
      val fileBPathStr = fileB.toAbsolutePath().normalize().invariantSeparatorsPathString

      assertTrue(printRes.output.contains("2 copies"))
      assertTrue(printRes.output.contains(fileAPathStr))
      assertTrue(printRes.output.contains(fileBPathStr))
      assertTrue(printRes.output.contains("$linkPathStr [symlink -> $fileAPathStr]"))

      val db = DatabaseFactory.connectFile(dbFile.toString())
      try {
        val service = BovedaService(database = db)
        val snap = service.getLatestSnapshot()!!
        val stats = service.computeDuplicateStatistics(snap.id.value, Duration.ZERO)
        assertEquals(3L, stats.totalFilesScanned)
        assertEquals(8_192L + linkSize, stats.totalScannedBytes)
        assertEquals(1L, stats.duplicateGroupsCount)
        assertEquals(2L, stats.duplicateFileCount)
        assertEquals(4_096L, stats.wastedBytes)
        assertEquals(1, stats.largestDuplicates.size)
        assertEquals(
          listOf(SymlinkEntry(linkPathStr, fileAPathStr)),
          stats.largestDuplicates[0].symlinkPaths
        )
      } finally {
        DatabaseFactory.shutdown(db)
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db folder print-duplicates excludes symlinks from counts and displays them clearly`() {
    val tempDir = Files.createTempDirectory("symlink-folder-dups")
    val dbDir = Files.createTempDirectory("symlink-folder-dups-db")
    try {
      val dir1 = tempDir.resolve("dir1").apply { createDirectories() }
      val dir2 = tempDir.resolve("dir2").apply { createDirectories() }
      val onlyLinks = tempDir.resolve("only_links").apply { createDirectories() }

      val doc1 = dir1.resolve("report.txt").apply { writeText("shared folder duplicate body") }
      dir2.resolve("report.txt").writeText("shared folder duplicate body")

      val dirLink = createSymlinkOrAssume(tempDir.resolve("dir1_symlink"), dir1)
      val fileLinkInDir2 = createSymlinkOrAssume(dir2.resolve("report_link.txt"), doc1)
      createSymlinkOrAssume(onlyLinks.resolve("only_sym.txt"), doc1)

      val dbFile = dbDir.resolve("folder_dups_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      val printRes = FolderPrintDuplicatesCommand().test(listOf(dbFile.toString()))
      assertEquals(0, printRes.statusCode)
      val dir1Str = dir1.toAbsolutePath().normalize().invariantSeparatorsPathString
      val dirLinkStr = dirLink.toAbsolutePath().normalize().invariantSeparatorsPathString
      val fileLinkStr = fileLinkInDir2.toAbsolutePath().normalize().invariantSeparatorsPathString
      val doc1Str = doc1.toAbsolutePath().normalize().invariantSeparatorsPathString

      assertTrue(printRes.output.contains("Found 1 folder pair(s)"))
      assertTrue(printRes.output.contains("Symlinks:"))
      assertTrue(printRes.output.contains("$dirLinkStr [symlink -> $dir1Str]"))
      assertTrue(printRes.output.contains("$fileLinkStr [symlink -> $doc1Str]"))
      assertFalse(printRes.output.contains("only_links <->"))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db file delete deletes only file symlink and preserves target file`() {
    val tempDir = Files.createTempDirectory("symlink-file-delete")
    val dbDir = Files.createTempDirectory("symlink-file-delete-db")
    try {
      val file1 = tempDir.resolve("copy1.bin").apply { writeBytes(ByteArray(2_048) { 7 }) }
      val file2 = tempDir.resolve("copy2.bin").apply { writeBytes(ByteArray(2_048) { 7 }) }
      val link1 = createSymlinkOrAssume(tempDir.resolve("link_to_copy1.bin"), file1)
      val link2 = createSymlinkOrAssume(tempDir.resolve("link2_to_copy1.bin"), file1)

      val dbFile = dbDir.resolve("file_delete_sym_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // 1. Interactive deletion: select ONLY link1, then quit
      var seenGroup: DuplicateGroup? = null
      var promptCount = 0
      val interactiveCmd = FileDeleteCommand(
        selector = { group ->
          promptCount++
          if (promptCount == 1) {
            seenGroup = group
            listOf(link1.toAbsolutePath().normalize().invariantSeparatorsPathString)
          } else {
            null
          }
        }
      )
      val interactiveRes = interactiveCmd.test(listOf(dbFile.toString()))
      assertEquals(0, interactiveRes.statusCode)
      assertNotNull(seenGroup)
      assertEquals(2, seenGroup!!.symlinkPaths.size)
      assertTrue(interactiveRes.output.contains("[symlink] (target preserved)"))

      // link1 must be deleted; target file1 and file2 must remain completely intact!
      assertFalse(Files.exists(link1, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(file1, LinkOption.NOFOLLOW_LINKS))
      assertEquals(2_048L, Files.size(file1))
      assertTrue(Files.exists(file2, LinkOption.NOFOLLOW_LINKS))

      // 2. Single-file deletion of link2 without --force
      val singleRes = FileDeleteCommand().test(listOf(link2.toString(), dbFile.toString()))
      assertEquals(0, singleRes.statusCode)
      assertTrue(
        singleRes.output.contains("Deleted symlink from disk and database (target preserved)")
      )
      assertFalse(Files.exists(link2, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(file1, LinkOption.NOFOLLOW_LINKS))
      assertEquals(2_048L, Files.size(file1))

      // Verify DB still has both real files and 0 symlinks
      val db = DatabaseFactory.connectFile(dbFile.toString())
      try {
        val service = BovedaService(database = db)
        val snap = service.getLatestSnapshot()!!
        val remainingInstances = transaction(db) {
          RepoFileInstance.all().map { it.path to it.isSymlink }
        }
        assertEquals(2, remainingInstances.size)
        assertTrue(remainingInstances.none { it.second })
        assertEquals(2, service.findLargestDuplicateGroup(snap.id.value)?.copyCount)
      } finally {
        DatabaseFactory.shutdown(db)
      }
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `db folder delete deletes only directory symlink and preserves target folder and files`() {
    val tempDir = Files.createTempDirectory("symlink-folder-delete")
    val dbDir = Files.createTempDirectory("symlink-folder-delete-db")
    try {
      val dirA = tempDir.resolve("dirA").apply { createDirectories() }
      val dirB = tempDir.resolve("dirB").apply { createDirectories() }
      val fileA = dirA.resolve("data.bin").apply { writeBytes(ByteArray(3_000) { 9 }) }
      dirB.resolve("data.bin").writeBytes(ByteArray(3_000) { 9 })

      val dirLink1 = createSymlinkOrAssume(tempDir.resolve("dirA_link1"), dirA)
      val dirLink2 = createSymlinkOrAssume(tempDir.resolve("dirA_link2"), dirA)

      val dbFile = dbDir.resolve("folder_delete_sym_db")
      assertEquals(
        0,
        ScanCommand().test(listOf("--quiet", tempDir.toString(), dbFile.toString())).statusCode
      )

      // 1. Interactive deletion: select ONLY dirLink1, then quit
      var seenGroup: DuplicateFolderGroup? = null
      var promptCount = 0
      val interactiveCmd = FolderDeleteCommand(
        folderSelector = { group ->
          promptCount++
          if (promptCount == 1) {
            seenGroup = group
            listOf(dirLink1.toAbsolutePath().normalize().invariantSeparatorsPathString)
          } else {
            null
          }
        }
      )
      val interactiveRes = interactiveCmd.test(listOf(dbFile.toString()))
      assertEquals(0, interactiveRes.statusCode)
      assertNotNull(seenGroup)
      assertEquals(2, seenGroup!!.symlinkPaths.size)
      assertTrue(interactiveRes.output.contains("[symlink] (target preserved)"))

      // dirLink1 is deleted, while target dirA and its file data.bin remain intact!
      assertFalse(Files.exists(dirLink1, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.isDirectory(dirA, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(fileA, LinkOption.NOFOLLOW_LINKS))
      assertEquals(3_000L, Files.size(fileA))

      // 2. Single-folder deletion of dirLink2 without --force
      val singleRes = FolderDeleteCommand().test(listOf(dirLink2.toString(), dbFile.toString()))
      assertEquals(0, singleRes.statusCode)
      assertTrue(
        singleRes.output.contains("Deleted symlink from disk and database (target preserved)")
      )
      assertFalse(Files.exists(dirLink2, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.isDirectory(dirA, LinkOption.NOFOLLOW_LINKS))
      assertTrue(Files.exists(fileA, LinkOption.NOFOLLOW_LINKS))
      assertEquals(3_000L, Files.size(fileA))
    } finally {
      tempDir.toFile().deleteRecursively()
      dbDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `selectors and service handle Windows-style normalized paths and symlink display`() {
    val db = DatabaseFactory.connectInMemory("windows_symlink_test_${System.nanoTime()}")
    val service = BovedaService(database = db)
    val snapshot = service.createSnapshot(Path.of("C:/vault"))

    val fileHash = "a".repeat(64)
    val fileTarget = "C:/vault/dir1/photo.jpg"
    val folderTarget = "C:/vault/dir1"

    service.recordBatch(
      snapshot,
      listOf(
        ScannedFile(
          path = Path.of("C:/vault/dir1/photo.jpg"),
          relativePath = "dir1/photo.jpg",
          size = 5_000L,
          hash = fileHash
        ),
        ScannedFile(
          path = Path.of("C:/vault/dir2/photo.jpg"),
          relativePath = "dir2/photo.jpg",
          size = 5_000L,
          hash = fileHash
        ),
        ScannedFile(
          path = Path.of("C:/vault/shortcut_photo.jpg"),
          relativePath = "shortcut_photo.jpg",
          size = 24L,
          hash = SymlinkUtils.computeSymlinkHash(fileTarget),
          isSymlink = true,
          symlinkTarget = fileTarget
        ),
        ScannedFile(
          path = Path.of("C:/vault/shortcut_dir1"),
          relativePath = "shortcut_dir1",
          size = 14L,
          hash = SymlinkUtils.computeSymlinkHash(folderTarget),
          isSymlink = true,
          symlinkTarget = folderTarget
        )
      )
    )
    service.updateFolderStatistics(snapshot.id.value)

    val stats = service.computeDuplicateStatistics(snapshot.id.value, Duration.ZERO)
    assertEquals(4L, stats.totalFilesScanned)
    assertEquals(10_038L, stats.totalScannedBytes)
    assertEquals(1L, stats.duplicateGroupsCount)
    assertEquals(2L, stats.duplicateFileCount)
    assertEquals(5_000L, stats.wastedBytes)

    val largestFileGroup = service.findLargestDuplicateGroup(snapshot.id.value)
    assertNotNull(largestFileGroup)
    assertEquals(
      listOf(SymlinkEntry("C:/vault/shortcut_photo.jpg", fileTarget)),
      largestFileGroup.symlinkPaths
    )

    // Verify InteractiveDuplicateSelector displays symlink clearly and selects raw path
    val fileRecorder = TerminalRecorder(inputInteractive = false).apply {
      inputLines.add("3")
    }
    val selectedFiles = InteractiveDuplicateSelector(Terminal(terminalInterface = fileRecorder))
      .selectDuplicates(largestFileGroup)
    assertEquals(listOf("C:/vault/shortcut_photo.jpg"), selectedFiles)
    assertTrue(
      fileRecorder.output().contains("C:/vault/shortcut_photo.jpg [symlink -> $fileTarget]")
    )

    val folderPairs = service.findTopDuplicateFolderPairs(snapshot.id.value)
    assertEquals(1, folderPairs.size)
    assertEquals(
      listOf(SymlinkEntry("C:/vault/shortcut_dir1", folderTarget)),
      folderPairs[0].symlinks
    )

    val largestFolderGroup = service.findLargestIdenticalFolderGroup(snapshot.id.value)
    assertNotNull(largestFolderGroup)
    assertEquals(
      listOf(SymlinkEntry("C:/vault/shortcut_dir1", folderTarget)),
      largestFolderGroup.symlinkPaths
    )

    // Verify InteractiveDuplicateFolderSelector displays symlink clearly and selects raw path
    val folderRecorder = TerminalRecorder(inputInteractive = false).apply {
      inputLines.add("3")
    }
    val selectedFolders = InteractiveDuplicateFolderSelector(
      Terminal(terminalInterface = folderRecorder)
    ).selectDuplicateFolders(largestFolderGroup)
    assertEquals(listOf("C:/vault/shortcut_dir1"), selectedFolders)
    assertTrue(
      folderRecorder.output().contains("C:/vault/shortcut_dir1 [symlink -> $folderTarget]")
    )
  }

  @Test
  fun `initSchema migrates legacy database lacking symlink columns`() {
    val dbName = "legacy_symlink_migration_${System.nanoTime()}"
    val db = DatabaseFactory.connectInMemory(dbName)
    transaction(db) {
      exec("DROP TABLE IF EXISTS file_instances")
      exec("DROP TABLE IF EXISTS folders")
      exec("DROP TABLE IF EXISTS files")
      exec("DROP TABLE IF EXISTS snapshots")
      exec(
        """
        CREATE TABLE snapshots (
          id INT AUTO_INCREMENT PRIMARY KEY,
          root_path VARCHAR(4096) NOT NULL,
          completed BOOLEAN DEFAULT FALSE NOT NULL
        )
        """.trimIndent()
      )
      exec(
        """
        CREATE TABLE folders (
          id INT AUTO_INCREMENT PRIMARY KEY,
          snapshot_id INT NOT NULL,
          "path" VARCHAR(4096) NOT NULL,
          file_count INT DEFAULT 0 NOT NULL,
          total_bytes BIGINT DEFAULT 0 NOT NULL
        )
        """.trimIndent()
      )
      exec(
        """
        CREATE TABLE files (
          id INT AUTO_INCREMENT PRIMARY KEY,
          "size" BIGINT NOT NULL,
          hash VARCHAR(64) NOT NULL
        )
        """.trimIndent()
      )
      exec(
        """
        CREATE TABLE file_instances (
          id INT AUTO_INCREMENT PRIMARY KEY,
          snapshot_id INT NOT NULL,
          folder_id INT NOT NULL,
          file_id INT NOT NULL,
          "path" VARCHAR(4096) NOT NULL,
          "timestamp" BIGINT NULL
        )
        """.trimIndent()
      )
    }

    // Re-running initSchema should seamlessly add is_symlink and symlink_target columns
    DatabaseFactory.initSchema(db)
    val service = BovedaService(database = db)
    val snap = service.createSnapshot(Path.of("/legacy"))
    service.recordFile(
      snap,
      ScannedFile(
        path = Path.of("/legacy/a.txt"),
        relativePath = "a.txt",
        size = 10L,
        hash = "b".repeat(64)
      )
    )
    val stats = service.computeDuplicateStatistics(snap.id.value, Duration.ZERO)
    assertEquals(1L, stats.totalFilesScanned)
    assertEquals(10L, stats.totalScannedBytes)
  }
}
