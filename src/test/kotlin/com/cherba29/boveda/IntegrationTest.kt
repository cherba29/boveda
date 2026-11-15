package com.cherba29.boveda

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.service.BovedaService
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {

    @Test
    fun `end to end directory scanning, database persistence, and analytical queries`() {
        val tempDir = Files.createTempDirectory("boveda-e2e")
        try {
            val docsDir = tempDir.resolve("docs").apply { createDirectories() }
            val backupDir = tempDir.resolve("backup").apply { createDirectories() }

            // Create identical files (duplicate content)
            val docA = docsDir.resolve("original.txt").apply { writeText("Shared identical content") }
            val docB = backupDir.resolve("copy.txt").apply { writeText("Shared identical content") }
            val uniqueDoc = tempDir.resolve("unique.txt").apply { writeText("Unique content only") }

            val db = DatabaseFactory.connectInMemory("e2e_test")
            val service = BovedaService(database = db)

            val snapshot = service.scanAndRecord(tempDir)
            assertTrue(snapshot.isCompleted)

            // Verify query results
            val fileRecords = service.queryFiles(snapshot.id.value)
            assertEquals(3, fileRecords.size)

            val rankedRecords = service.queryFilesWithDuplicateRank(snapshot.id.value)
            assertEquals(3, rankedRecords.size)

            val duplicates = service.findDuplicates(snapshot.id.value)
            assertEquals(2, duplicates.size)
            assertEquals(duplicates[0].hash, duplicates[1].hash)

            // Verify stdout printing runs without failure
            val outputStream = ByteArrayOutputStream()
            val originalOut = System.out
            try {
                System.setOut(PrintStream(outputStream))
                service.printQueryResults(snapshot.id.value)
            } finally {
                System.setOut(originalOut)
            }
            val outputText = outputStream.toString()
            assertTrue(outputText.contains("### ==== Selecting from DB ==== ###"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `main function runs successfully with scan and db subcommands`() {
        val tempDir = Files.createTempDirectory("boveda-main-e2e")
        try {
            tempDir.resolve("sample.txt").writeText("sample file for main")
            val dbFile = tempDir.resolve("main_test_db")

            val originalOut = System.out
            val outputStream = ByteArrayOutputStream()
            try {
                System.setOut(PrintStream(outputStream))
                main(arrayOf("scan", tempDir.toString(), dbFile.toString()))
                main(arrayOf("db", dbFile.toString()))
                main(arrayOf("db", "print-duplicates", dbFile.toString()))
                main(arrayOf("db", "snapshots", "list", dbFile.toString()))
                main(arrayOf("db", "snapshots", "delete", "1", dbFile.toString()))
            } finally {
                System.setOut(originalOut)
            }

            val output = outputStream.toString()
            assertTrue(output.contains("Scanning directory: $tempDir"))
            assertTrue(output.contains("sample.txt"))
            assertTrue(output.contains("### ==== Selecting from DB ==== ###"))
            assertTrue(output.contains("### ==== Duplicate Files in DB ==== ###"))
            assertTrue(output.contains("Snapshot 1 deleted successfully."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
