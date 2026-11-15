package com.cherba29.boveda.db

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class EntitiesTest {

  private fun createTestDb() = DatabaseFactory.connectInMemory(
    "entities_${UUID.randomUUID().toString().replace("-", "")}"
  )

  @Test
  fun `RepoSnapshot entity lifecycle and default values`() {
    val db = createTestDb()

    transaction(db) {
      val snapshot = RepoSnapshot.new {
        rootPath = "/test/root"
      }

      assertFalse(snapshot.isCompleted)
      assertEquals("/test/root", snapshot.rootPath)

      snapshot.isCompleted = true
      assertEquals(true, snapshot.isCompleted)
    }
  }

  @Test
  fun `ReportSnapshot typealias can be used interchangeably`() {
    val db = createTestDb()

    transaction(db) {
      val snapshot: ReportSnapshot = ReportSnapshot.new {
        rootPath = "/alias/root"
        isCompleted = false
      }

      assertEquals("/alias/root", snapshot.rootPath)
      assertEquals(0, snapshot.instances.count())
    }
  }

  @Test
  fun `RepoFile and RepoFileInstance navigation and references`() {
    val db = createTestDb()

    transaction(db) {
      val snapshot = RepoSnapshot.new {
        rootPath = "/vault"
      }

      val file = RepoFile.new {
        size = 1024L
        hash = "abcdef123456"
      }

      val instance1 = RepoFileInstance.new {
        this.snapshot = snapshot
        this.file = file
        this.path = "doc1.txt"
      }

      val instance2 = RepoFileInstance.new {
        this.snapshot = snapshot
        this.file = file
        this.path = "copy/doc1.txt"
      }

      assertEquals(snapshot.id, instance1.snapshot.id)
      assertEquals(file.id, instance1.file.id)
      assertEquals("doc1.txt", instance1.path)

      // Test navigation from parent entities
      val snapshotInstances = snapshot.instances.toList()
      assertEquals(2, snapshotInstances.size)

      val fileInstances = file.instances.toList()
      assertEquals(2, fileInstances.size)
      assertEquals(setOf("doc1.txt", "copy/doc1.txt"), fileInstances.map { it.path }.toSet())
    }
  }
}
