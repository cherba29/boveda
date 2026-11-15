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

class WorkStealingDirectoryScannerTest {

  @Test
  fun `scan traverses nested directories and hashes all files concurrently using work-stealing`() {
    val tempDir = Files.createTempDirectory("ws-scanner-test")
    try {
      val sub1 = tempDir.resolve("dirA/nested1").apply { createDirectories() }
      val sub2 = tempDir.resolve("dirB/nested2").apply { createDirectories() }
      val sub3 = tempDir.resolve("dirC").apply { createDirectories() }

      val f1 = tempDir.resolve("root.txt").apply { writeText("root") }
      val f2 = sub1.resolve("a.txt").apply { writeText("a content") }
      val f3 = sub2.resolve("b.txt").apply { writeText("b content") }
      val f4 = sub3.resolve("c.txt").apply { writeText("c content") }

      val scanner = WorkStealingDirectoryScanner(parallelism = 4, fileChunkSize = 2)
      val results = scanner.scan(tempDir).toList()

      assertEquals(4, results.size)
      val relativePaths = results.map { it.relativePath }.toSet()
      assertTrue(relativePaths.contains("root.txt"))
      assertTrue(relativePaths.contains("dirA/nested1/a.txt"))
      assertTrue(relativePaths.contains("dirB/nested2/b.txt"))
      assertTrue(relativePaths.contains("dirC/c.txt"))

      results.forEach { file ->
        assertTrue(file.size > 0)
        assertTrue(file.hash.isNotEmpty())
      }
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan works with parallelism 1`() {
    val tempDir = Files.createTempDirectory("ws-single-worker")
    try {
      tempDir.resolve("sample.txt").writeText("single worker content")

      val scanner = WorkStealingDirectoryScanner(parallelism = 1)
      val results = scanner.scan(tempDir).toList()

      assertEquals(1, results.size)
      assertEquals("sample.txt", results.first().relativePath)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan returns empty sequence for empty directory`() {
    val tempDir = Files.createTempDirectory("ws-empty")
    try {
      val scanner = WorkStealingDirectoryScanner(parallelism = 2)
      val results = scanner.scan(tempDir).toList()
      assertTrue(results.isEmpty())
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan throws IllegalArgumentException for non-existent root path`() {
    val nonExistent = Files.createTempDirectory("ws-root").resolve("missing")
    val scanner = WorkStealingDirectoryScanner()

    val exception = assertFailsWith<IllegalArgumentException> {
      scanner.scan(nonExistent).toList()
    }
    assertTrue(exception.message!!.contains("Target path does not exist"))
  }

  @Test
  fun `scan throws IllegalArgumentException when root path is a regular file`() {
    val tempFile = Files.createTempFile("ws-file", ".txt")
    try {
      val scanner = WorkStealingDirectoryScanner()
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
    val tempDir = Files.createTempDirectory("ws-custom-hasher")
    try {
      tempDir.resolve("custom.txt").writeText("custom content")

      val fakeHasher = object : FileHasher {
        override fun computeHash(file: File, algorithm: String): String = "ws-mock-hash"
        override fun computeHash(inputStream: InputStream, algorithm: String): String =
          "ws-mock-hash"
      }

      val scanner = WorkStealingDirectoryScanner(parallelism = 2, hasher = fakeHasher)
      val results = scanner.scan(tempDir).toList()

      assertEquals(1, results.size)
      assertEquals("ws-mock-hash", results.first().hash)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan handles early sequence termination cleanly without thread leaks`() {
    val tempDir = Files.createTempDirectory("ws-early-exit")
    try {
      (1..20).forEach { i ->
        tempDir.resolve("file$i.txt").writeText("content $i")
      }

      val scanner = WorkStealingDirectoryScanner(parallelism = 4, queueCapacity = 5)
      val results = scanner.scan(tempDir).take(3).toList()

      assertEquals(3, results.size)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `scan propagates hashing exceptions from worker tasks`() {
    val tempDir = Files.createTempDirectory("ws-error-test")
    try {
      tempDir.resolve("error.txt").writeText("fail content")

      val faultyHasher = object : FileHasher {
        override fun computeHash(file: File, algorithm: String): String =
          throw IllegalStateException("WorkStealing failure simulation")
        override fun computeHash(inputStream: InputStream, algorithm: String): String =
          throw IllegalStateException("WorkStealing failure simulation")
      }

      val scanner = WorkStealingDirectoryScanner(parallelism = 2, hasher = faultyHasher)
      val exception = assertFailsWith<IllegalStateException> {
        scanner.scan(tempDir).toList()
      }
      assertEquals("WorkStealing failure simulation", exception.message)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `validates parallelism, queueCapacity, and fileChunkSize parameters`() {
    assertFailsWith<IllegalArgumentException> {
      WorkStealingDirectoryScanner(parallelism = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      WorkStealingDirectoryScanner(queueCapacity = 0)
    }
    assertFailsWith<IllegalArgumentException> {
      WorkStealingDirectoryScanner(fileChunkSize = 0)
    }
  }

  @Test
  fun `scan respects shouldSkip predicate and avoids hashing skipped files`() {
    val tempDir = Files.createTempDirectory("ws-skip-test")
    try {
      tempDir.resolve("skip.txt").writeText("skip me")
      tempDir.resolve("keep.txt").writeText("keep me")

      val hashedFiles = mutableListOf<String>()
      val spyHasher = object : FileHasher {
        override fun computeHash(file: File, algorithm: String): String {
          hashedFiles.add(file.name)
          return "hash_${file.name}"
        }

        override fun computeHash(inputStream: InputStream, algorithm: String): String =
          "hash_stream"
      }

      val scanner = WorkStealingDirectoryScanner(parallelism = 2, hasher = spyHasher)
      val results = scanner.scan(
        rootPath = tempDir,
        shouldSkip = { it.path.fileName.toString() == "skip.txt" }
      ).toList()

      assertEquals(1, results.size)
      assertEquals("keep.txt", results[0].relativePath)
      assertEquals(listOf("keep.txt"), hashedFiles)
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }
}
