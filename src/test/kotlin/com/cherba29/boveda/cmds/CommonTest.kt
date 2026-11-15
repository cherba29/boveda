package com.cherba29.boveda.cmds

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommonTest {

  @Test
  fun `validateDatabaseFileExists succeeds when file exists and fails when missing`() {
    val tempDir = Files.createTempDirectory("common-db-check")
    try {
      val dbFile = tempDir.resolve("check_db")
      // Not existing yet
      val ex = assertFailsWith<UsageError> {
        validateDatabaseFileExists(dbFile)
      }
      assertTrue(ex.message!!.contains("Database file does not exist"))

      // Create H2 db file
      val db = DatabaseFactory.connectFile(dbFile.toString())
      DatabaseFactory.initSchema(db)

      // Should succeed without exception
      validateDatabaseFileExists(dbFile)
      validateDatabaseFileExists(Path.of("$dbFile.mv.db"))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `connectDatabase connects to in-memory database when factory is provided or path when missing`() {
    val tempDir = Files.createTempDirectory("common-connect-db")
    try {
      val dbFile = tempDir.resolve("conn_db")
      val db1 = DatabaseFactory.connectFile(dbFile.toString())
      DatabaseFactory.initSchema(db1)

      val connectedFileDb = connectDatabase(dbFile, null)
      assertNotNull(connectedFileDb)

      val connectedInMemoryDb = connectDatabase(null) { BovedaService(it) }
      assertNotNull(connectedInMemoryDb)

      val ex = assertFailsWith<UsageError> {
        connectDatabase(null, null)
      }
      assertTrue(ex.message!!.contains("Database must be specified"))
    } finally {
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `resolveSnapshot finds by id or returns latest`() {
    val db = DatabaseFactory.connectInMemory("resolve_snapshot_test")
    val service = BovedaService(database = db)

    assertNull(resolveSnapshot(service, null))
    assertNull(resolveSnapshot(service, 1))

    val snap1 = service.createSnapshot(Path.of("/dir1"))
    service.completeSnapshot(snap1)

    val snap2 = service.createSnapshot(Path.of("/dir2"))
    service.completeSnapshot(snap2)

    val latest = resolveSnapshot(service, null)
    assertNotNull(latest)
    assertEquals(snap2.id.value, latest.id.value)

    val specific = resolveSnapshot(service, snap1.id.value)
    assertNotNull(specific)
    assertEquals(snap1.id.value, specific.id.value)
  }
}
