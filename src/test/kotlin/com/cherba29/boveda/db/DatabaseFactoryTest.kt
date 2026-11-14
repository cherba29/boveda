package com.cherba29.boveda.db

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DatabaseFactoryTest {

    @Test
    fun `connectInMemory initializes schema and enables table operations`() {
        val dbName = "mem_test_${UUID.randomUUID().toString().replace("-", "")}"
        val db = DatabaseFactory.connectInMemory(dbName)

        transaction(db) {
            assertTrue(RepoSnapshots.exists())
            assertTrue(RepoFiles.exists())
            assertTrue(RepoFileInstances.exists())
        }
    }

    @Test
    fun `connectFile creates file-backed database and initializes schema`() {
        val tempDir = Files.createTempDirectory("boveda-h2-test")
        try {
            val dbPath = tempDir.resolve("test_db").toString()
            val db = DatabaseFactory.connectFile(dbPath)

            transaction(db) {
                assertTrue(RepoSnapshots.exists())
                assertTrue(RepoFiles.exists())
                assertTrue(RepoFileInstances.exists())
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `initSchema is idempotent when called repeatedly`() {
        val dbName = "mem_idempotent_${UUID.randomUUID().toString().replace("-", "")}"
        val db = DatabaseFactory.connectInMemory(dbName)

        // Call initSchema a second time to ensure CREATE TABLE IF NOT EXISTS behaves idempotently
        DatabaseFactory.initSchema(db)

        transaction(db) {
            assertTrue(RepoSnapshots.exists())
            assertTrue(RepoFiles.exists())
            assertTrue(RepoFileInstances.exists())
        }
    }
}
