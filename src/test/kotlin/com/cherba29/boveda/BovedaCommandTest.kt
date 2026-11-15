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
        assertTrue(result.output.contains("scan"))
        assertTrue(result.output.contains("db"))
        assertTrue(result.output.contains("--log-level"))

        // Test scan help
        val scanResult = command.test(listOf("scan", "--help"))
        assertEquals(0, scanResult.statusCode)
        assertTrue(scanResult.output.contains("<directory>"))
        assertTrue(scanResult.output.contains("<database>"))
        assertTrue(scanResult.output.contains("--algorithm"))
        assertTrue(scanResult.output.contains("--db"))
        assertTrue(scanResult.output.contains("--quiet"))
        assertTrue(scanResult.output.contains("--batch-size"))
        assertTrue(scanResult.output.contains("--threads"))
        assertTrue(scanResult.output.contains("--scanner"))
        assertTrue(scanResult.output.contains("--work-stealing"))
        assertTrue(scanResult.output.contains("--no-cache-warmup"))

        // Test db help
        val dbResult = command.test(listOf("db", "--help"))
        assertEquals(0, dbResult.statusCode)
        assertTrue(dbResult.output.contains("<database>"))
        assertTrue(dbResult.output.contains("--db"))
        assertTrue(dbResult.output.contains("--snapshot"))
        assertTrue(dbResult.output.contains("print-duplicates"))

        // Test db print-duplicates help
        val printDupResult = command.test(listOf("db", "print-duplicates", "--help"))
        assertEquals(0, printDupResult.statusCode)
        assertTrue(printDupResult.output.contains("<database>"))
        assertTrue(printDupResult.output.contains("--db"))
        assertTrue(printDupResult.output.contains("--snapshot"))
    }

    @Test
    fun `scan command populates database from directory argument`() {
        val tempDir = Files.createTempDirectory("clikt-scan-dir")
        try {
            tempDir.resolve("doc.txt").writeText("clikt test content")
            val dbFile = tempDir.resolve("scan_test_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("doc.txt"))

            // Verify H2 persistent database file was created
            val h2Db = tempDir.resolve("scan_test_db.mv.db")
            assertTrue(Files.exists(h2Db))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan command with --db option populates database`() {
        val tempDir = Files.createTempDirectory("clikt-scan-db-opt")
        try {
            tempDir.resolve("file.txt").writeText("scan opt content")
            val dbFile = tempDir.resolve("opt_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", tempDir.toString(), "--db", dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("file.txt"))

            val h2Db = tempDir.resolve("opt_db.mv.db")
            assertTrue(Files.exists(h2Db))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan command fails when target database is omitted and no service factory`() {
        val tempDir = Files.createTempDirectory("clikt-no-db")
        try {
            val command = BovedaCommand()
            val result = command.test(listOf("scan", tempDir.toString()))

            assertNotEquals(0, result.statusCode)
            assertTrue(result.output.contains("Target database must be specified"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quiet flag suppresses per-file logging and outputs progress indicator with ingestion speed`() {
        val tempDir = Files.createTempDirectory("clikt-quiet-test")
        val dbDir = Files.createTempDirectory("clikt-quiet-db")
        try {
            tempDir.resolve("fileA.txt").writeText("content A")
            val dbFile = dbDir.resolve("quiet_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", "--quiet", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("Scanned 1 file"))
            assertTrue(result.output.contains("files/s"))
            assertTrue(result.output.contains("MB/s"))
        } finally {
            tempDir.toFile().deleteRecursively()
            dbDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quiet flag in interactive terminal displays animated progress indicator`() {
        val tempDir = Files.createTempDirectory("clikt-quiet-interactive-test")
        try {
            tempDir.resolve("file1.txt").writeText("file 1 content")
            tempDir.resolve("file2.txt").writeText("file 2 content")
            val dbFile = tempDir.resolve("interactive_db")

            val command = BovedaCommand()
            val result = command.test(
                listOf("scan", "--quiet", tempDir.toString(), dbFile.toString()),
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
            val dbFile = tempDir.resolve("algo_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", "--algorithm", "SHA-256", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            // Known SHA-256 for "hello world"
            assertTrue(result.output.contains("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `batch-size option executes scan and records files in custom batch sizes`() {
        val tempDir = Files.createTempDirectory("clikt-batch-test")
        try {
            tempDir.resolve("f1.txt").writeText("content 1")
            tempDir.resolve("f2.txt").writeText("content 2")
            tempDir.resolve("f3.txt").writeText("content 3")
            val dbFile = tempDir.resolve("batch_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", "--batch-size", "2", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("f1.txt"))
            assertTrue(result.output.contains("f2.txt"))
            assertTrue(result.output.contains("f3.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `threads option executes parallel scanning with specified thread count`() {
        val tempDir = Files.createTempDirectory("clikt-threads-test")
        try {
            tempDir.resolve("t1.txt").writeText("thread content 1")
            tempDir.resolve("t2.txt").writeText("thread content 2")
            val dbFile = tempDir.resolve("threads_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", "-t", "2", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("t1.txt"))
            assertTrue(result.output.contains("t2.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scanner options configure work-stealing, parallel, and sequential strategies`() {
        val tempDir = Files.createTempDirectory("clikt-scanner-test")
        try {
            tempDir.resolve("test.txt").writeText("scanner strategy content")
            val dbFile = tempDir.resolve("scanner_db")

            val command1 = BovedaCommand()
            val resultWs = command1.test(listOf("scan", "--scanner", "work-stealing", tempDir.toString(), dbFile.toString()))
            assertEquals(0, resultWs.statusCode)
            assertTrue(resultWs.output.contains("test.txt"))

            val command2 = BovedaCommand()
            val resultFlag = command2.test(listOf("scan", "--work-stealing", tempDir.toString(), dbFile.toString()))
            assertEquals(0, resultFlag.statusCode)
            assertTrue(resultFlag.output.contains("test.txt"))

            val command3 = BovedaCommand()
            val resultParallel = command3.test(listOf("scan", "--scanner", "parallel", tempDir.toString(), dbFile.toString()))
            assertEquals(0, resultParallel.statusCode)
            assertTrue(resultParallel.output.contains("test.txt"))

            val command4 = BovedaCommand()
            val resultSeq = command4.test(listOf("scan", "--scanner", "sequential", tempDir.toString(), dbFile.toString()))
            assertEquals(0, resultSeq.statusCode)
            assertTrue(resultSeq.output.contains("test.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no-cache-warmup flag executes without error`() {
        val tempDir = Files.createTempDirectory("clikt-no-warmup-test")
        try {
            tempDir.resolve("file.txt").writeText("data")
            val dbFile = tempDir.resolve("warmup_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", "--no-cache-warmup", tempDir.toString(), dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Scanning directory: $tempDir"))
            assertTrue(result.output.contains("file.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan fails with non-zero status code when directory path does not exist`() {
        val nonExistent = "/path/to/definitely/non/existent/boveda_dir"
        val command = BovedaCommand()
        val result = command.test(listOf("scan", nonExistent, "/tmp/db"))

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("does not exist"))
    }

    @Test
    fun `scan fails with non-zero status code when directory path is a file`() {
        val tempFile = Files.createTempFile("clikt-file-not-dir", ".txt")
        try {
            val command = BovedaCommand()
            val result = command.test(listOf("scan", tempFile.toString(), "/tmp/db"))

            assertNotEquals(0, result.statusCode)
            assertTrue(result.output.contains("is a file"))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    @Test
    fun `db command queries already populated database and prints query results`() {
        val tempDir = Files.createTempDirectory("clikt-db-query-test")
        try {
            tempDir.resolve("item.txt").writeText("database persistence test")
            val dbFile = tempDir.resolve("query_db")

            val command = BovedaCommand()
            // 1. Scan directory into database
            val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
            assertEquals(0, scanResult.statusCode)

            // 2. Query database using db command
            val dbResult = command.test(listOf("db", dbFile.toString()))
            assertEquals(0, dbResult.statusCode)
            assertTrue(dbResult.output.contains("### ==== Selecting from DB ==== ###"))
            assertTrue(dbResult.output.contains("item.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `db print-duplicates subcommand queries latest snapshot and prints duplicate files and statistics`() {
        val tempDir = Files.createTempDirectory("clikt-print-dups-test")
        try {
            val subDir = tempDir.resolve("sub").apply { createDirectories() }
            tempDir.resolve("f1.txt").writeText("duplicate content")
            subDir.resolve("f2.txt").writeText("duplicate content")
            tempDir.resolve("f3.txt").writeText("unique content")
            val dbFile = tempDir.resolve("dups_db")

            val command = BovedaCommand()
            // 1. Scan directory into database
            val scanResult = command.test(listOf("scan", "--quiet", tempDir.toString(), dbFile.toString()))
            assertEquals(0, scanResult.statusCode)

            // 2. Query duplicates using db print-duplicates (database on subcommand)
            val dupResult1 = command.test(listOf("db", "print-duplicates", dbFile.toString()))
            assertEquals(0, dupResult1.statusCode)
            assertTrue(dupResult1.output.contains("### ==== Duplicate Files in DB ==== ###"))
            assertTrue(dupResult1.output.contains("f1.txt"))
            assertTrue(dupResult1.output.contains("f2.txt"))
            assertFalse(dupResult1.output.contains("f3.txt"))
            assertTrue(dupResult1.output.contains("DUPLICATION SUMMARY"))
            assertTrue(dupResult1.output.contains("Scan & Ingestion Statistics:"))
            assertTrue(dupResult1.output.contains("Top 10 Largest Duplicate Files:"))
            assertTrue(dupResult1.output.contains("Percentile Buckets by File Size (10% Deciles):"))
            assertTrue(dupResult1.output.contains("Total Processing Time:"))

            // 3. Query duplicates using db <DATABASE> print-duplicates (database on parent command)
            val dupResult2 = command.test(listOf("db", dbFile.toString(), "print-duplicates"))
            assertEquals(0, dupResult2.statusCode)
            assertTrue(dupResult2.output.contains("### ==== Duplicate Files in DB ==== ###"))
            assertTrue(dupResult2.output.contains("f1.txt"))
            assertTrue(dupResult2.output.contains("f2.txt"))
            assertFalse(dupResult2.output.contains("f3.txt"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `db print-duplicates with no duplicates reports no duplicates found`() {
        val tempDir = Files.createTempDirectory("clikt-no-dup-test")
        try {
            tempDir.resolve("unique.txt").writeText("unique content")
            val dbFile = tempDir.resolve("no_dups_db")

            val command = BovedaCommand()
            val scanResult = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))
            assertEquals(0, scanResult.statusCode)

            val dupResult = command.test(listOf("db", "print-duplicates", dbFile.toString()))
            assertEquals(0, dupResult.statusCode)
            assertTrue(dupResult.output.contains("No duplicates found."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `db command fails when database file does not exist`() {
        val command = BovedaCommand()
        val result = command.test(listOf("db", "/path/to/definitely/nonexistent.db"))

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("Database file does not exist"))
    }

    @Test
    fun `log-level DEBUG flag configures root logger and logs Exposed queries to stderr`() {
        val tempDir = Files.createTempDirectory("clikt-log-debug-test")
        val originalErr = System.err
        val errStream = ByteArrayOutputStream()

        try {
            System.setErr(PrintStream(errStream))
            tempDir.resolve("file.txt").writeText("data")
            val dbFile = tempDir.resolve("log_debug_db")

            val command = BovedaCommand()
            val result = command.test(listOf("-l", "DEBUG", "scan", tempDir.toString(), dbFile.toString()))

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
            val dbFile = tempDir.resolve("log_warn_db")

            val command = BovedaCommand()
            val result = command.test(listOf("scan", tempDir.toString(), dbFile.toString()))

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
}
