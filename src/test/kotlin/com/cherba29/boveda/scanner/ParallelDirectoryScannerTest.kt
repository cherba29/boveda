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

class ParallelDirectoryScannerTest {

    @Test
    fun `scan traverses directory and discovers all files using multiple worker threads`() {
        val tempDir = Files.createTempDirectory("parallel-scanner-test")
        try {
            val subDir1 = tempDir.resolve("dir1").apply { createDirectories() }
            val subDir2 = tempDir.resolve("dir2/nested").apply { createDirectories() }

            val file1 = tempDir.resolve("root.txt").apply { writeText("root content") }
            val file2 = subDir1.resolve("f1.txt").apply { writeText("f1 content") }
            val file3 = subDir2.resolve("f2.txt").apply { writeText("f2 content") }

            val scanner = ParallelDirectoryScanner(threadCount = 4)
            val results = scanner.scan(tempDir).toList()

            assertEquals(3, results.size)
            val relativePaths = results.map { it.relativePath }.toSet()
            assertTrue(relativePaths.contains("root.txt"))
            assertTrue(relativePaths.contains("dir1/f1.txt"))
            assertTrue(relativePaths.contains("dir2/nested/f2.txt"))

            results.forEach { file ->
                assertTrue(file.size > 0)
                assertTrue(file.hash.isNotEmpty())
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan works with single worker thread`() {
        val tempDir = Files.createTempDirectory("parallel-scanner-single-thread")
        try {
            tempDir.resolve("sample.txt").writeText("single thread sample")

            val scanner = ParallelDirectoryScanner(threadCount = 1)
            val results = scanner.scan(tempDir).toList()

            assertEquals(1, results.size)
            assertEquals("sample.txt", results.first().relativePath)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan returns empty sequence for empty directory`() {
        val tempDir = Files.createTempDirectory("parallel-scanner-empty")
        try {
            val scanner = ParallelDirectoryScanner(threadCount = 2)
            val results = scanner.scan(tempDir).toList()
            assertTrue(results.isEmpty())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan throws IllegalArgumentException for non-existent root path`() {
        val nonExistent = Files.createTempDirectory("parallel-missing").resolve("missing")
        val scanner = ParallelDirectoryScanner()

        val exception = assertFailsWith<IllegalArgumentException> {
            scanner.scan(nonExistent).toList()
        }
        assertTrue(exception.message!!.contains("Target path does not exist"))
    }

    @Test
    fun `scan throws IllegalArgumentException when root path is a regular file`() {
        val tempFile = Files.createTempFile("parallel-file", ".txt")
        try {
            val scanner = ParallelDirectoryScanner()
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
        val tempDir = Files.createTempDirectory("parallel-custom-hasher")
        try {
            tempDir.resolve("custom.txt").writeText("custom content")

            val fakeHasher = object : FileHasher {
                override fun computeHash(file: File, algorithm: String): String = "mock-parallel-hash"
                override fun computeHash(inputStream: InputStream, algorithm: String): String = "mock-parallel-hash"
            }

            val scanner = ParallelDirectoryScanner(threadCount = 2, hasher = fakeHasher)
            val results = scanner.scan(tempDir).toList()

            assertEquals(1, results.size)
            assertEquals("mock-parallel-hash", results.first().hash)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan handles early sequence termination cleanly without thread leaks`() {
        val tempDir = Files.createTempDirectory("parallel-early-exit")
        try {
            (1..20).forEach { i ->
                tempDir.resolve("file$i.txt").writeText("content $i")
            }

            val scanner = ParallelDirectoryScanner(threadCount = 4, queueCapacity = 5)
            // Take only 3 items from 20 available
            val results = scanner.scan(tempDir).take(3).toList()

            assertEquals(3, results.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan propagates hashing exceptions from worker threads`() {
        val tempDir = Files.createTempDirectory("parallel-error-test")
        try {
            tempDir.resolve("error.txt").writeText("fail content")

            val faultyHasher = object : FileHasher {
                override fun computeHash(file: File, algorithm: String): String =
                    throw IllegalStateException("Hasher failure simulation")
                override fun computeHash(inputStream: InputStream, algorithm: String): String =
                    throw IllegalStateException("Hasher failure simulation")
            }

            val scanner = ParallelDirectoryScanner(threadCount = 2, hasher = faultyHasher)
            val exception = assertFailsWith<IllegalStateException> {
                scanner.scan(tempDir).toList()
            }
            assertEquals("Hasher failure simulation", exception.message)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validates threadCount and queueCapacity parameters`() {
        assertFailsWith<IllegalArgumentException> {
            ParallelDirectoryScanner(threadCount = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ParallelDirectoryScanner(queueCapacity = 0)
        }
    }
}
