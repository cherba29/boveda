package com.cherba29.boveda

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BovedaCommandTest {

    @AfterTest
    fun tearDown() {
        LoggingConfig.setRootLevel(Level.WARN)
    }

    @Test
    fun `help flag displays usage, parameters, and options`() {
        val command = BovedaCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: boveda"))
        assertTrue(result.output.contains("<directory>"))
        assertTrue(result.output.contains("--algorithm"))
        assertTrue(result.output.contains("--db"))
        assertTrue(result.output.contains("--log-level"))
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
    fun `quiet flag suppresses per-file logging and outputs progress indicator with ingestion speed`() {
        val tempDir = Files.createTempDirectory("clikt-quiet-test")
        try {
            tempDir.resolve("fileA.txt").writeText("content A")

            val command = BovedaCommand()
            val result = command.test(listOf("--quiet", tempDir.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            // Progress indicator shows files processed and speeds
            assertTrue(result.output.contains("Scanned 1 file"))
            assertTrue(result.output.contains("files/s"))
            assertTrue(result.output.contains("MB/s"))
            // Query results are still printed
            assertTrue(result.output.contains("### ==== Selecting from DB ==== ###"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quiet flag in interactive terminal displays animated progress indicator`() {
        val tempDir = Files.createTempDirectory("clikt-quiet-interactive-test")
        try {
            tempDir.resolve("file1.txt").writeText("file 1 content")
            tempDir.resolve("file2.txt").writeText("file 2 content")

            val command = BovedaCommand()
            val result = command.test(
                listOf("--quiet", tempDir.toString()),
                outputInteractive = true,
                ansiLevel = com.github.ajalt.mordant.rendering.AnsiLevel.TRUECOLOR
            )

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("files/s"))
            assertTrue(result.output.contains("MB/s"))
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
    fun `log-level DEBUG flag configures root logger and logs Exposed queries to stderr`() {
        val tempDir = Files.createTempDirectory("clikt-log-debug-test")
        val originalErr = System.err
        val errStream = ByteArrayOutputStream()

        try {
            System.setErr(PrintStream(errStream))
            tempDir.resolve("file.txt").writeText("data")

            val command = BovedaCommand()
            val result = command.test(listOf("--log-level", "DEBUG", tempDir.toString()))

            assertEquals(0, result.statusCode)
            assertEquals(Level.DEBUG, LoggingConfig.getRootLevel())

            val stderrText = errStream.toString()
            assertTrue(stderrText.contains("DEBUG Exposed"))
        } finally {
            System.setErr(originalErr)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `log-level WARN default suppresses Exposed queries from stderr`() {
        val tempDir = Files.createTempDirectory("clikt-log-warn-test")
        val originalErr = System.err
        val errStream = ByteArrayOutputStream()

        try {
            System.setErr(PrintStream(errStream))
            tempDir.resolve("file.txt").writeText("data")

            val command = BovedaCommand()
            val result = command.test(tempDir.toString())

            assertEquals(0, result.statusCode)
            assertEquals(Level.WARN, LoggingConfig.getRootLevel())

            val stderrText = errStream.toString()
            assertFalse(stderrText.contains("DEBUG Exposed"))
        } finally {
            System.setErr(originalErr)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid log-level fails with non-zero exit code`() {
        val command = BovedaCommand()
        val result = command.test(listOf("--log-level", "INVALID_LEVEL"))

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("invalid choice: INVALID_LEVEL"))
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

            // Verify statistics summary is printed
            assertTrue(result.output.contains("DUPLICATION SUMMARY"))
            assertTrue(result.output.contains("Scan & Ingestion Statistics:"))
            assertTrue(result.output.contains("Total Files Scanned:"))
            assertTrue(result.output.contains("Top 10 Largest Duplicate Files:"))
            assertTrue(result.output.contains("Percentile Buckets by File Size (10% Deciles):"))
            assertTrue(result.output.contains("Total Processing Time:"))
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
