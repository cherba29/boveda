package com.cherba29.boveda.service

import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFileInstances
import com.cherba29.boveda.db.RepoFiles
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.db.RepoSnapshots
import com.cherba29.boveda.model.FileRecord
import com.cherba29.boveda.model.FileRecordWithDuplicateRank
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Service managing snapshot creation, file ingestion, and analytical database queries.
 *
 * @property database The Exposed [Database] instance.
 * @property scanner The [DirectoryScanner] for traversing files.
 * @property deduplicateFiles Whether files with identical hash and size share a single [RepoFile] record.
 */
class BovedaService(
    private val database: Database,
    private val scanner: DirectoryScanner = FileTreeDirectoryScanner(),
    private val deduplicateFiles: Boolean = true
) {

    /**
     * Initializes a new directory snapshot in the database.
     */
    fun createSnapshot(rootPath: Path): RepoSnapshot = transaction(database) {
        RepoSnapshot.new {
            this.rootPath = rootPath.toString()
            this.isCompleted = false
        }
    }

    /**
     * Records a single [scannedFile] under [snapshot].
     */
    fun recordFile(snapshot: RepoSnapshot, scannedFile: ScannedFile): RepoFileInstance = transaction(database) {
        val repoFile = if (deduplicateFiles) {
            RepoFile.find {
                (RepoFiles.hash eq scannedFile.hash) and (RepoFiles.size eq scannedFile.size)
            }.firstOrNull() ?: RepoFile.new {
                size = scannedFile.size
                hash = scannedFile.hash
            }
        } else {
            RepoFile.new {
                size = scannedFile.size
                hash = scannedFile.hash
            }
        }

        RepoFileInstance.new {
            this.snapshot = snapshot
            this.file = repoFile
            this.path = scannedFile.path.invariantSeparatorsPathString
        }
    }

    /**
     * Marks [snapshot] as completed.
     */
    fun completeSnapshot(snapshot: RepoSnapshot) = transaction(database) {
        snapshot.isCompleted = true
    }

    /**
     * Scans [rootPath], records each discovered file in the database, and marks the snapshot complete.
     */
    fun scanAndRecord(
        rootPath: Path,
        algorithm: String = "MD5",
        onFileScanned: ((ScannedFile) -> Unit)? = null
    ): RepoSnapshot {
        val snapshot = createSnapshot(rootPath)

        scanner.scan(rootPath, algorithm).forEach { scannedFile ->
            onFileScanned?.invoke(scannedFile)
            recordFile(snapshot, scannedFile)
        }

        completeSnapshot(snapshot)
        return snapshot
    }

    /**
     * Queries all recorded file instances, optionally filtering by [snapshotId].
     */
    fun queryFiles(snapshotId: Int? = null): List<FileRecord> = transaction(database) {
        val join = RepoSnapshots leftJoin RepoFileInstances leftJoin RepoFiles
        val baseQuery = join.select(
            RepoFileInstances.snapshot,
            RepoFileInstances.path,
            RepoFiles.size,
            RepoFiles.hash
        )

        val query = if (snapshotId != null) {
            baseQuery.where { RepoFileInstances.snapshot eq snapshotId }
        } else {
            baseQuery
        }

        query.orderBy(RepoFiles.hash)
            .map { row ->
                FileRecord(
                    snapshotId = row[RepoFileInstances.snapshot].value,
                    path = row[RepoFileInstances.path],
                    size = row[RepoFiles.size],
                    hash = row[RepoFiles.hash]
                )
            }
    }

    /**
     * Queries files with a window function computing row number partitioned by content hash.
     */
    fun queryFilesWithDuplicateRank(snapshotId: Int? = null): List<FileRecordWithDuplicateRank> = transaction(database) {
        val hashWindow = rowNumber().over().partitionBy(RepoFiles.hash).orderBy(RepoFileInstances.path).alias("rn")
        val join = RepoSnapshots leftJoin RepoFileInstances leftJoin RepoFiles

        val baseQuery = join.select(
            RepoFileInstances.snapshot,
            RepoFileInstances.path,
            RepoFiles.size,
            RepoFiles.hash,
            hashWindow
        )

        val query = if (snapshotId != null) {
            baseQuery.where { RepoFileInstances.snapshot eq snapshotId }
        } else {
            baseQuery
        }

        query.orderBy(RepoFiles.hash)
            .map { row ->
                FileRecordWithDuplicateRank(
                    snapshotId = row[RepoFileInstances.snapshot].value,
                    path = row[RepoFileInstances.path],
                    size = row[RepoFiles.size],
                    hash = row[RepoFiles.hash],
                    duplicateRank = row[hashWindow]
                )
            }
    }

    /**
     * Finds all duplicate files (files whose hash is shared by more than one file instance).
     */
    fun findDuplicates(snapshotId: Int? = null): List<FileRecordWithDuplicateRank> {
        val records = queryFilesWithDuplicateRank(snapshotId)
        val duplicateHashes = records.groupBy { it.hash }
            .filter { it.value.size > 1 }
            .keys
        return records.filter { it.hash in duplicateHashes }
    }

    /**
     * Prints query results matching the original output format.
     */
    fun printQueryResults(snapshotId: Int? = null, out: (String) -> Unit = ::println) {
        out("### ==== Selecting from DB ==== ###")
        queryFiles(snapshotId).forEach { record ->
            out("snapshot=${record.snapshotId} size=${record.size} path=${record.path} hash=${record.hash}")
        }

        queryFilesWithDuplicateRank(snapshotId).forEach { record ->
            out("${record.snapshotId} ${record.size} ${record.path} ${record.duplicateRank} ${record.hash}")
        }
    }
}
