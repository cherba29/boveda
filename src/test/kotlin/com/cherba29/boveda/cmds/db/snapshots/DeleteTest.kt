package com.cherba29.boveda.cmds.db.snapshots

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.logging.LoggingConfig
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeleteTest {

    @AfterTest
    fun tearDown() {
        LoggingConfig.setRootLevel(Level.WARN)
    }

    @Test
    fun `delete command help displays usage, parameters, and options`() {
        val command = DeleteCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: delete"))
        assertTrue(result.output.contains("<snapshot_id>"))
        assertTrue(result.output.contains("<database>"))
        assertTrue(result.output.contains("--db"))
        assertTrue(result.output.contains("--log-level"))
    }

    @Test
    fun `delete command deletes existing snapshot and outputs success`() {
        val tempDir = Files.createTempDirectory("delete-test-dir")
        try {
            tempDir.resolve("file.txt").writeText("content to delete")
            val dbFile = tempDir.resolve("delete_db")

            val scanCmd = ScanCommand()
            assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

            val deleteCmd = DeleteCommand()
            val result = deleteCmd.test(listOf("1", dbFile.toString()))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Snapshot 1 deleted successfully."))

            // Verify snapshot no longer exists
            val listCmd = ListCommand()
            val listResult = listCmd.test(listOf(dbFile.toString()))
            assertEquals(0, listResult.statusCode)
            assertTrue(listResult.output.contains("No snapshots found in database."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete command throws UsageError when snapshot ID does not exist`() {
        val tempDir = Files.createTempDirectory("delete-not-found-dir")
        try {
            tempDir.resolve("file.txt").writeText("some content")
            val dbFile = tempDir.resolve("not_found_db")

            val scanCmd = ScanCommand()
            assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

            val deleteCmd = DeleteCommand()
            val result = deleteCmd.test(listOf("999", dbFile.toString()))

            assertNotEquals(0, result.statusCode)
            assertTrue(result.output.contains("Snapshot with ID 999 does not exist."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete command resolves database from parent DbCommand`() {
        val tempDir = Files.createTempDirectory("delete-parent-dir")
        try {
            tempDir.resolve("file.txt").writeText("parent content")
            val dbFile = tempDir.resolve("parent_db")

            val scanCmd = ScanCommand()
            assertEquals(0, scanCmd.test(listOf(tempDir.toString(), dbFile.toString())).statusCode)

            val dbCmd = DbCommand()
            val result = dbCmd.test(listOf(dbFile.toString(), "snapshots", "delete", "1"))

            assertEquals(0, result.statusCode)
            assertTrue(result.output.contains("Snapshot 1 deleted successfully."))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delete command fails when database file does not exist`() {
        val deleteCmd = DeleteCommand()
        val result = deleteCmd.test(listOf("1", "/path/to/definitely/nonexistent.db"))

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("Database file does not exist"))
    }
}
