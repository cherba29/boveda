package com.cherba29.boveda.service

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BovedaServiceTest {

    private fun createService(
        scanner: DirectoryScanner = object : DirectoryScanner {
            override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> = emptySequence()
        },
        deduplicateFiles: Boolean = true
    ): Pair<BovedaService, org.jetbrains.exposed.v1.jdbc.Database> {
        val db = DatabaseFactory.connectInMemory("service_${UUID.randomUUID().toString().replace("-", "")}")
        val service = BovedaService(database = db, scanner = scanner, deduplicateFiles = deduplicateFiles)
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
            override fun scan(rootPath: Path, algorithm: String): Sequence<ScannedFile> = testFiles.asSequence()
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
        service.recordFile(snapshot, ScannedFile(Paths.get("path/a.txt"), "path/a.txt", 50L, "dup-hash"))
        service.recordFile(snapshot, ScannedFile(Paths.get("path/b.txt"), "path/b.txt", 50L, "dup-hash"))
        service.recordFile(snapshot, ScannedFile(Paths.get("path/c.txt"), "path/c.txt", 80L, "uniq-hash"))

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
        service.recordFile(snapshot, ScannedFile(Paths.get("unique.txt"), "unique.txt", 20L, "unique"))

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
        assertTrue(printed.contains("snapshot=${snapshot.id.value} size=15 path=test.txt hash=hash123"))
    }
}
