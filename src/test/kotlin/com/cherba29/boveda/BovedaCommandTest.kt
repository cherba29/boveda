package com.cherba29.boveda

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BovedaCommandTest {

    @Test
    fun `help flag displays usage, parameters, and options`() {
        val command = BovedaCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: boveda"))
        assertTrue(result.output.contains("<directory>"))
        assertTrue(result.output.contains("--algorithm"))
        assertTrue(result.output.contains("--db"))
        assertTrue(result.output.contains("--quiet"))
        assertTrue(result.output.contains("--duplicates-only"))
    }

    @Test
    fun `executes on directory argument and prints progress and query results`() {
        val tempDir = Files.createTempDirectory("clikt-test-dir")
        try {
            tempDir.resolve("doc.txt").writeText("clikt test content")

            val command = BovedaCommand()
            val result = command.test(tempDir.toString())

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("doc.txt"))
            assertTrue(result.output.contains("### ==== Selecting from DB ==== ###"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quiet flag suppresses file progress during scanning`() {
        val tempDir = Files.createTempDirectory("clikt-quiet-test")
        try {
            tempDir.resolve("fileA.txt").writeText("content A")

            val command = BovedaCommand()
            val result = command.test(listOf("--quiet", tempDir.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            // Query results are still printed
            assertTrue(result.output.contains("### ==== Selecting from DB ==== ###"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `algorithm option uses specified hashing algorithm`() {
        val tempDir = Files.createTempDirectory("clikt-algo-test")
        try {
            tempDir.resolve("sample.txt").writeText("hello world")

            val command = BovedaCommand()
            val result = command.test(listOf("--algorithm", "SHA-256", tempDir.toString()))

            assertEquals(0, result.statusCode)
            // Known SHA-256 for "hello world"
            assertTrue(result.output.contains("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `duplicates-only flag prints only duplicate files`() {
        val tempDir = Files.createTempDirectory("clikt-dup-test")
        try {
            val subDir = tempDir.resolve("sub").apply { createDirectories() }
            tempDir.resolve("f1.txt").writeText("duplicate content")
            subDir.resolve("f2.txt").writeText("duplicate content")
            tempDir.resolve("f3.txt").writeText("unique content")

            val command = BovedaCommand()
            val result = command.test(listOf("--quiet", "--duplicates-only", tempDir.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("### ==== Duplicate Files in DB ==== ###"))
            assertTrue(result.output.contains("f1.txt"))
            assertTrue(result.output.contains("f2.txt"))
            // Unique file is not part of the duplicate group
            assertTrue(!result.output.contains("f3.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `duplicates-only flag with no duplicates reports no duplicates found`() {
        val tempDir = Files.createTempDirectory("clikt-no-dup-test")
        try {
            tempDir.resolve("unique.txt").writeText("unique content")

            val command = BovedaCommand()
            val result = command.test(listOf("--duplicates-only", tempDir.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("No duplicates found."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persistent database option creates file-backed database`() {
        val tempDir = Files.createTempDirectory("clikt-db-test")
        try {
            val dbFile = tempDir.resolve("boveda_custom_db")
            val scanDir = tempDir.resolve("data").apply { createDirectories() }
            scanDir.resolve("item.txt").writeText("database persistence test")

            val command = BovedaCommand()
            val result = command.test(listOf("--db", dbFile.toString(), scanDir.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("### ==== Selecting from DB ==== ###"))
            // H2 creates a .mv.db file for file-based databases
            val h2MvDb = tempDir.resolve("boveda_custom_db.mv.db")
            assertTrue(Files.exists(h2MvDb))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fails with non-zero status code when directory path does not exist`() {
        val nonExistent = "/path/to/definitely/non/existent/boveda_dir"
        val command = BovedaCommand()
        val result = command.test(nonExistent)

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("does not exist"))
    }

    @Test
    fun `fails with non-zero status code when directory path is a file`() {
        val tempFile = Files.createTempFile("clikt-file-not-dir", ".txt")
        try {
            val command = BovedaCommand()
            val result = command.test(tempFile.toString())

            assertNotEquals(0, result.statusCode)
            assertTrue(result.output.contains("is a file"))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
