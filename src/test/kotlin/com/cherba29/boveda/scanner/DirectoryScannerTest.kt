package com.cherba29.boveda.scanner

import com.cherba29.boveda.hash.FileHasher
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DirectoryScannerTest {

  @Test
  fun `scan traverses directory and discovers nested regular files while ignoring directory entries`() {
    val tempDir = Files.createTempDirectory("scanner-test-tree")
    try {
      val subDir = tempDir.resolve("nested/dir").apply { createDirectories() }
      val file1 = tempDir.resolve("file1.txt").apply { writeText("File 1 content") }
      val file2 = subDir.resolve("file2.txt").apply { writeText("File 2 content") }

      val scanner = FileTreeDirectoryScanner()
      val results = scanner.scan(tempDir).toList()

      assertEquals(2, results.size)
      val relativePaths = results.map { it.relativePath }.toSet()
      assertTrue(relativePaths.contains("file1.txt"))
      assertTrue(relativePaths.contains("nested/dir/file2.txt"))

      results.forEach { file ->
        assertTrue(file.size > 0)
        assertTrue(file.hash.isNotEmpty())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan returns empty sequence for empty directory`() {
    val tempDir = Files.createTempDirectory("scanner-empty-test")
    try {
      val scanner = FileTreeDirectoryScanner()
      val results = scanner.scan(tempDir).toList()
      assertTrue(results.isEmpty())
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan throws IllegalArgumentException for non-existent root path`() {
    val nonExistent = Files.createTempDirectory("scanner-root").resolve("missing")
    val scanner = FileTreeDirectoryScanner()

    val exception = assertFailsWith<IllegalArgumentException> {
      scanner.scan(nonExistent).toList()
    }
    assertTrue(exception.message!!.contains("Target path does not exist"))
  }

  @Test
  fun `scan throws IllegalArgumentException when root path is a regular file`() {
    val tempFile = Files.createTempFile("scanner-file", ".txt")
    try {
      val scanner = FileTreeDirectoryScanner()
      val exception = assertFailsWith<IllegalArgumentException> {
        scanner.scan(tempFile).toList()
      }
      assertTrue(exception.message!!.contains("Target path is not a directory"))
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun `scan uses injected FileHasher`() {
    val tempDir = Files.createTempDirectory("scanner-custom-hasher")
    try {
      tempDir.resolve("custom.txt").writeText("custom content")

      val fakeHasher = object : FileHasher {
        override fun computeHash(file: File, algorithm: String): String = "mocked-hash"
        override fun computeHash(inputStream: InputStream, algorithm: String): String =
          "mocked-hash"
      }

      val scanner = FileTreeDirectoryScanner(hasher = fakeHasher)
      val results = scanner.scan(tempDir).toList()

      assertEquals(1, results.size)
      assertEquals("mocked-hash", results.first().hash)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }
}
