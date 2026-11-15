package com.cherba29.boveda.service

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFileInstances
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BovedaServiceTest {

  private fun createService(
    scanner: DirectoryScanner = object : DirectoryScanner {
      override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> = emptySequence()
    },
    deduplicateFiles: Boolean = true
  ): Pair<BovedaService, org.jetbrains.exposed.v1.jdbc.Database> {
    val db = DatabaseFactory.connectInMemory(
      "service_${UUID.randomUUID().toString().replace("-", "")}"
    )
    val service =
      BovedaService(database = db, scanner = scanner, deduplicateFiles = deduplicateFiles)
    return Pair(service, db)
  }

  @Test
  fun `createSnapshot and completeSnapshot updates snapshot status`() {
    val (service, db) = createService()
    val snapshot = service.createSnapshot(Paths.get("/test/path"))

    transaction(db) {
      val retrieved = RepoSnapshot.findById(snapshot.id)!!
      assertEquals("/test/path", retrieved.rootPath)
      assertFalse(retrieved.isCompleted)
    }

    service.completeSnapshot(snapshot)

    transaction(db) {
      val retrieved = RepoSnapshot.findById(snapshot.id)!!
      assertTrue(retrieved.isCompleted)
    }
  }

  @Test
  fun `recordFile with deduplication reuses RepoFile row for duplicate content`() {
    val (service, db) = createService(deduplicateFiles = true)
    val snapshot = service.createSnapshot(Paths.get("/data"))

    val file1 = ScannedFile(
      path = Paths.get("/data/fileA.txt"),
      relativePath = "fileA.txt",
      size = 100L,
      hash = "common-hash-123"
    )
    val file2 = ScannedFile(
      path = Paths.get("/data/sub/fileB.txt"),
      relativePath = "sub/fileB.txt",
      size = 100L,
      hash = "common-hash-123"
    )

    service.recordFile(snapshot, file1)
    service.recordFile(snapshot, file2)

    transaction(db) {
      assertEquals(1, RepoFile.all().count())
      assertEquals(2, RepoFileInstance.all().count())
      val instances = RepoFileInstance.all().toList()
      assertEquals(instances[0].file.id, instances[1].file.id)
    }
  }

  @Test
  fun `recordFile without deduplication creates separate RepoFile rows`() {
    val (service, db) = createService(deduplicateFiles = false)
    val snapshot = service.createSnapshot(Paths.get("/data"))

    val file1 = ScannedFile(
      path = Paths.get("/data/fileA.txt"),
      relativePath = "fileA.txt",
      size = 100L,
      hash = "common-hash-123"
    )
    val file2 = ScannedFile(
      path = Paths.get("/data/fileB.txt"),
      relativePath = "fileB.txt",
      size = 100L,
      hash = "common-hash-123"
    )

    service.recordFile(snapshot, file1)
    service.recordFile(snapshot, file2)

    transaction(db) {
      assertEquals(2, RepoFile.all().count())
      assertEquals(2, RepoFileInstance.all().count())
    }
  }

  @Test
  fun `scanAndRecord iterates through scanner and invokes callback`() {
    val testFiles = listOf(
      ScannedFile(Paths.get("/test/a.txt"), "a.txt", 10L, "hashA"),
      ScannedFile(Paths.get("/test/b.txt"), "b.txt", 20L, "hashB")
    )

    val fakeScanner = object : DirectoryScanner {
      override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> =
        testFiles.asSequence()
    }

    val (service, db) = createService(scanner = fakeScanner)
    val scannedCallbacks = mutableListOf<ScannedFile>()

    val snapshot = service.scanAndRecord(Paths.get("/test")) { file ->
      scannedCallbacks.add(file)
    }

    assertEquals(2, scannedCallbacks.size)
    transaction(db) {
      val retrieved = RepoSnapshot.findById(snapshot.id)!!
      assertTrue(retrieved.isCompleted)
      assertEquals(2, retrieved.instances.count())
    }
  }

  @Test
  fun `queryFiles returns ordered records and supports snapshot filtering`() {
    val (service, _) = createService()
    val snapshot1 = service.createSnapshot(Paths.get("/s1"))
    val snapshot2 = service.createSnapshot(Paths.get("/s2"))

    service.recordFile(snapshot1, ScannedFile(Paths.get("z.txt"), "z.txt", 10L, "zzz"))
    service.recordFile(snapshot1, ScannedFile(Paths.get("a.txt"), "a.txt", 20L, "aaa"))
    service.recordFile(snapshot2, ScannedFile(Paths.get("other.txt"), "other.txt", 30L, "ooo"))

    val allRecords = service.queryFiles()
    assertEquals(3, allRecords.size)
    assertEquals("aaa", allRecords[0].hash)
    assertEquals("ooo", allRecords[1].hash)
    assertEquals("zzz", allRecords[2].hash)

    val s1Records = service.queryFiles(snapshot1.id.value)
    assertEquals(2, s1Records.size)
    assertEquals(listOf("aaa", "zzz"), s1Records.map { it.hash })

    val s2Records = service.queryFiles(snapshot2.id.value)
    assertEquals(1, s2Records.size)
    assertEquals("ooo", s2Records[0].hash)
  }

  @Test
  fun `queryFilesWithDuplicateRank assigns correct window row number rank partitioned by hash`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/snap"))

    // Two files with duplicate hash "dup-hash", one file with unique hash "uniq-hash"
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("path/a.txt"), "path/a.txt", 50L, "dup-hash")
    )
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("path/b.txt"), "path/b.txt", 50L, "dup-hash")
    )
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("path/c.txt"), "path/c.txt", 80L, "uniq-hash")
    )

    val ranked = service.queryFilesWithDuplicateRank(snapshot.id.value)
    assertEquals(3, ranked.size)

    val dupGroup = ranked.filter { it.hash == "dup-hash" }.sortedBy { it.duplicateRank }
    assertEquals(2, dupGroup.size)
    assertEquals(1L, dupGroup[0].duplicateRank)
    assertEquals(2L, dupGroup[1].duplicateRank)
    assertEquals("path/a.txt", dupGroup[0].path)
    assertEquals("path/b.txt", dupGroup[1].path)

    val uniqGroup = ranked.filter { it.hash == "uniq-hash" }
    assertEquals(1, uniqGroup.size)
    assertEquals(1L, uniqGroup[0].duplicateRank)
  }

  @Test
  fun `findDuplicates filters only entries belonging to duplicate hashes`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/snap"))

    service.recordFile(snapshot, ScannedFile(Paths.get("f1.txt"), "f1.txt", 10L, "dup"))
    service.recordFile(snapshot, ScannedFile(Paths.get("f2.txt"), "f2.txt", 10L, "dup"))
    service.recordFile(
      snapshot,
      ScannedFile(Paths.get("unique.txt"), "unique.txt", 20L, "unique")
    )

    val duplicates = service.findDuplicates(snapshot.id.value)
    assertEquals(2, duplicates.size)
    assertTrue(duplicates.all { it.hash == "dup" })
  }

  @Test
  fun `printQueryResults executes without error and prints formatted output`() {
    val (service, _) = createService()
    val snapshot = service.createSnapshot(Paths.get("/snap"))
    service.recordFile(snapshot, ScannedFile(Paths.get("test.txt"), "test.txt", 15L, "hash123"))

    val originalOut = System.out
    val outputStream = ByteArrayOutputStream()
    try {
      System.setOut(PrintStream(outputStream))
      service.printQueryResults(snapshot.id.value)
    } finally {
      System.setOut(originalOut)
    }

    val printed = outputStream.toString()
    assertTrue(printed.contains("### ==== Selecting from DB ==== ###"))
    assertTrue(
      printed.contains("snapshot=${snapshot.id.value} size=15 path=test.txt hash=hash123")
    )
  }

  @Test
  fun `recordBatch deduplicates files within the same batch`() {
    val (service, db) = createService()
    val snapshot = service.createSnapshot(Paths.get("/data"))

    val batch = listOf(
      ScannedFile(Paths.get("/data/doc1.txt"), "doc1.txt", 100L, "identical-hash"),
      ScannedFile(Paths.get("/data/sub/doc2.txt"), "sub/doc2.txt", 100L, "identical-hash"),
      ScannedFile(Paths.get("/data/unique.txt"), "unique.txt", 50L, "unique-hash")
    )

    service.recordBatch(snapshot, batch)

    transaction(db) {
      // 2 unique content records: identical-hash (100L) and unique-hash (50L)
      assertEquals(2, RepoFile.all().count())
      // 3 file instances
      assertEquals(3, RepoFileInstance.all().count())

      val instances = RepoFileInstance.all().toList()
      val doc1 = instances.first { it.path == "/data/doc1.txt" }
      val doc2 = instances.first { it.path == "/data/sub/doc2.txt" }
      val unique = instances.first { it.path == "/data/unique.txt" }

      assertEquals(doc1.file.id, doc2.file.id)
      assertTrue(doc1.file.id != unique.file.id)
    }
  }

  @Test
  fun `recordBatch across multiple batches reuses RepoFile rows via cache`() {
    val (service, db) = createService()
    val snapshot = service.createSnapshot(Paths.get("/data"))

    val batch1 = listOf(
      ScannedFile(Paths.get("/data/batch1/a.txt"), "batch1/a.txt", 200L, "shared-hash")
    )
    val batch2 = listOf(
      ScannedFile(Paths.get("/data/batch2/b.txt"), "batch2/b.txt", 200L, "shared-hash")
    )

    service.recordBatch(snapshot, batch1)
    service.recordBatch(snapshot, batch2)

    transaction(db) {
      assertEquals(1, RepoFile.all().count())
      assertEquals(2, RepoFileInstance.all().count())
    }
  }

  @Test
  fun `batch ingestion reconciles seamlessly with existing database from prior snapshot runs`() {
    val db = DatabaseFactory.connectInMemory(
      "reconciliation_test_${UUID.randomUUID().toString().replace("-", "")}"
    )

    // --- Run Snapshot 1 ---
    val service1 = BovedaService(database = db, batchSize = 10)
    val snap1 = service1.createSnapshot(Paths.get("/run1"))
    service1.recordBatch(
      snap1,
      listOf(
        ScannedFile(Paths.get("/run1/docA.txt"), "docA.txt", 150L, "hash-AAA"),
        ScannedFile(Paths.get("/run1/docB.txt"), "docB.txt", 250L, "hash-BBB")
      )
    )
    service1.completeSnapshot(snap1)

    transaction(db) {
      assertEquals(2, RepoFile.all().count())
      assertEquals(2, RepoFileInstance.all().count())
    }

    // --- Run Snapshot 2 with Pre-Warmed Cache (Strategy A) ---
    val service2 = BovedaService(database = db, batchSize = 10, warmUpCache = true)
    val snap2 = service2.createSnapshot(Paths.get("/run2"))
    service2.cache.warmUp(db)

    service2.recordBatch(
      snap2,
      listOf(
        // Reusing hash-AAA from snap1
        ScannedFile(Paths.get("/run2/copyA.txt"), "copyA.txt", 150L, "hash-AAA"),
        // Brand new file in snap2
        ScannedFile(Paths.get("/run2/docC.txt"), "docC.txt", 350L, "hash-CCC")
      )
    )
    service2.completeSnapshot(snap2)

    transaction(db) {
      // RepoFiles should have exactly 3 rows total (AAA, BBB, CCC) - no duplicate AAA row!
      assertEquals(3, RepoFile.all().count())
      // RepoFileInstances should have 4 rows total (2 from snap1, 2 from snap2)
      assertEquals(4, RepoFileInstance.all().count())

      // Verify that copyA in snap2 points to the EXACT same RepoFile as docA in snap1
      val instDocA = RepoFileInstance.find { RepoFileInstances.path eq "/run1/docA.txt" }.first()
      val instCopyA = RepoFileInstance.find { RepoFileInstances.path eq "/run2/copyA.txt" }.first()
      assertEquals(instDocA.file.id, instCopyA.file.id)
      assertEquals(snap1.id, instDocA.snapshot.id)
      assertEquals(snap2.id, instCopyA.snapshot.id)
    }

    // --- Run Snapshot 3 with Cold / Lazy Cache (Strategy B) ---
    val service3 = BovedaService(database = db, batchSize = 10, warmUpCache = false)
    val snap3 = service3.createSnapshot(Paths.get("/run3"))
    assertEquals(0, service3.cache.size()) // Cold cache

    service3.recordBatch(
      snap3,
      listOf(
        // Reusing hash-CCC from snap2 and hash-BBB from snap1
        ScannedFile(Paths.get("/run3/copyC.txt"), "copyC.txt", 350L, "hash-CCC"),
        ScannedFile(Paths.get("/run3/copyB.txt"), "copyB.txt", 250L, "hash-BBB")
      )
    )
    service3.completeSnapshot(snap3)

    transaction(db) {
      // RepoFiles remains at 3 rows total - cold cache queried DB and resolved existing entries!
      assertEquals(3, RepoFile.all().count())
      assertEquals(6, RepoFileInstance.all().count())

      val instCopyC = RepoFileInstance.find { RepoFileInstances.path eq "/run3/copyC.txt" }.first()
      val instDocC = RepoFileInstance.find { RepoFileInstances.path eq "/run2/docC.txt" }.first()
      assertEquals(instDocC.file.id, instCopyC.file.id)
    }
  }

  @Test
  fun `recordFiles flushes batches in chunks of batchSize`() {
    val (service, db) = createService()
    val customService = BovedaService(
      database = db,
      batchSize = 2
    )
    val snapshot = customService.createSnapshot(Paths.get("/batch-test"))

    val files = (1..5).map { i ->
      ScannedFile(Paths.get("/batch-test/f$i.txt"), "f$i.txt", (i * 10).toLong(), "hash-$i")
    }

    customService.recordFiles(snapshot, files)

    transaction(db) {
      assertEquals(5, RepoFile.all().count())
      assertEquals(5, RepoFileInstance.all().count())
    }
  }

  @Test
  fun `scanAndRecord warms up cache and tracks cache hits and misses`() {
    val files = listOf(
      ScannedFile(Paths.get("/root/f1.txt"), "f1.txt", 100L, "common-hash"),
      ScannedFile(Paths.get("/root/f2.txt"), "f2.txt", 100L, "common-hash"),
      ScannedFile(Paths.get("/root/f3.txt"), "f3.txt", 200L, "other-hash")
    )
    val fakeScanner = object : DirectoryScanner {
      override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> =
        files.asSequence()
    }

    val db = DatabaseFactory.connectInMemory(
      "cache_metrics_${UUID.randomUUID().toString().replace("-", "")}"
    )
    val service = BovedaService(database = db, scanner = fakeScanner, batchSize = 10)

    service.scanAndRecord(Paths.get("/root"))

    transaction(db) {
      assertEquals(2, RepoFile.all().count())
      assertEquals(3, RepoFileInstance.all().count())
    }

    val stats = service.cache.getStatistics()
    assertEquals(2, stats.size)
    // During batch ingestion:
    // f1 is miss (new)
    // f2 is miss (new in step 1, but deduplicated before DB insert)
    // f3 is miss (new)
    assertEquals(3, stats.totalRequests)
  }

  @Test
  fun `listSnapshots returns empty list when no snapshots exist`() {
    val (service, _) = createService()
    val snapshots = service.listSnapshots()
    assertTrue(snapshots.isEmpty())
  }

  @Test
  fun `listSnapshots returns snapshots with correct file counts, total bytes, and completion status`() {
    val (service, db) = createService()
    val snap1 = service.createSnapshot(Paths.get("/data1"))
    val snap2 = service.createSnapshot(Paths.get("/data2"))

    val file1 = ScannedFile(Paths.get("/data1/f1.txt"), "f1.txt", 100L, "hash1")
    val file2 = ScannedFile(Paths.get("/data1/f2.txt"), "f2.txt", 200L, "hash2")
    service.recordFiles(snap1, listOf(file1, file2))
    service.completeSnapshot(snap1)

    val file3 = ScannedFile(Paths.get("/data2/f3.txt"), "f3.txt", 500L, "hash3")
    service.recordFiles(snap2, listOf(file3))
    // snap2 is not marked completed

    val list = service.listSnapshots()
    assertEquals(2, list.size)

    val summary1 = list.first { it.id == snap1.id.value }
    assertEquals("/data1", summary1.rootPath)
    assertTrue(summary1.isCompleted)
    assertEquals(2L, summary1.fileCount)
    assertEquals(300L, summary1.totalBytes)

    val summary2 = list.first { it.id == snap2.id.value }
    assertEquals("/data2", summary2.rootPath)
    assertFalse(summary2.isCompleted)
    assertEquals(1L, summary2.fileCount)
    assertEquals(500L, summary2.totalBytes)
  }

  @Test
  fun `deleteSnapshot removes file instances, orphaned files, and snapshot record`() {
    val (service, db) = createService()
    val snap1 = service.createSnapshot(Paths.get("/snap1"))
    val snap2 = service.createSnapshot(Paths.get("/snap2"))

    // snap1 has unique f1, and shared f2 (same content/hash)
    // snap2 has shared f2, and unique f3
    val sharedFile1 =
      ScannedFile(Paths.get("/snap1/shared.txt"), "shared.txt", 150L, "shared-hash")
    val uniqueFile1 =
      ScannedFile(Paths.get("/snap1/unique1.txt"), "unique1.txt", 250L, "unique1-hash")
    service.recordFiles(snap1, listOf(sharedFile1, uniqueFile1))
    service.completeSnapshot(snap1)

    val sharedFile2 =
      ScannedFile(Paths.get("/snap2/shared_copy.txt"), "shared_copy.txt", 150L, "shared-hash")
    val uniqueFile2 =
      ScannedFile(Paths.get("/snap2/unique2.txt"), "unique2.txt", 350L, "unique2-hash")
    service.recordFiles(snap2, listOf(sharedFile2, uniqueFile2))
    service.completeSnapshot(snap2)

    transaction(db) {
      assertEquals(3, RepoFile.all().count()) // shared, unique1, unique2
      assertEquals(4, RepoFileInstance.all().count())
      assertEquals(2, RepoSnapshot.all().count())
    }

    // Delete snap1
    val result = service.deleteSnapshot(snap1.id.value)
    assertTrue(result)

    transaction(db) {
      // unique1 should be deleted because it is no longer referenced
      // shared should remain because snap2 references it
      // unique2 should remain
      assertEquals(2, RepoFile.all().count())
      assertEquals(2, RepoFileInstance.all().count())
      assertEquals(1, RepoSnapshot.all().count())
      assertEquals(snap2.id.value, RepoSnapshot.all().first().id.value)
    }

    // Deleting non-existent snapshot returns false
    assertFalse(service.deleteSnapshot(9999))
  }

  @Test
  fun `formatSnapshotsTable renders formatted table with correct headers and rows`() {
    val (service, _) = createService()
    val snap = service.createSnapshot(Paths.get("/vault"))
    val file =
      ScannedFile(Paths.get("/vault/document.pdf"), "document.pdf", 1024L * 1024L, "doc-hash")
    service.recordFiles(snap, listOf(file))
    service.completeSnapshot(snap)

    val summaries = service.listSnapshots()
    val tableOutput = service.formatSnapshotsTable(summaries)

    assertTrue(tableOutput.contains("ID"))
    assertTrue(tableOutput.contains("Root Path"))
    assertTrue(tableOutput.contains("Files"))
    assertTrue(tableOutput.contains("Total Size"))
    assertTrue(tableOutput.contains("Status"))
    assertTrue(tableOutput.contains("/vault"))
    assertTrue(tableOutput.contains("Completed"))
    assertTrue(tableOutput.contains("1.00 MB"))
  }
}
