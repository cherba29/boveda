package com.cherba29.boveda.service

import com.cherba29.boveda.cache.DeduplicationCache
import com.cherba29.boveda.cache.FileContentKey
import com.cherba29.boveda.cache.InMemoryDeduplicationCache
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFileInstances
import com.cherba29.boveda.db.RepoFiles
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.db.RepoSnapshots
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.DuplicateStatistics
import com.cherba29.boveda.model.FileRecord
import com.cherba29.boveda.model.FileRecordWithDuplicateRank
import com.cherba29.boveda.model.PercentileBucket
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatBytesShort
import com.cherba29.boveda.model.formatDuration
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Service managing snapshot creation, file ingestion, and analytical database queries.
 *
 * @property database The Exposed [Database] instance.
 * @property scanner The [DirectoryScanner] for traversing files.
 * @property deduplicateFiles Whether files with identical hash and size share a single [RepoFile] record.
 * @property batchSize Number of files to batch per database transaction during scanning.
 * @property cache The [DeduplicationCache] storing `(size, hash) -> file_id` mappings.
 * @property warmUpCache Whether to pre-warm the cache from [RepoFiles] on startup / scanning.
 */
class BovedaService(
    private val database: Database,
    private val scanner: DirectoryScanner = FileTreeDirectoryScanner(),
    private val deduplicateFiles: Boolean = true,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val cache: DeduplicationCache = InMemoryDeduplicationCache(),
    private val warmUpCache: Boolean = true
) {

    companion object {
        const val DEFAULT_BATCH_SIZE = 1000
    }

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
            val cachedId = cache.get(scannedFile.size, scannedFile.hash)
            if (cachedId != null) {
                RepoFile[cachedId]
            } else {
                val found = RepoFile.find {
                    (RepoFiles.hash eq scannedFile.hash) and (RepoFiles.size eq scannedFile.size)
                }.firstOrNull()
                val file = found ?: RepoFile.new {
                    size = scannedFile.size
                    hash = scannedFile.hash
                }
                cache.put(scannedFile.size, scannedFile.hash, file.id)
                file
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
     * Ingests a batch of [ScannedFile]s under [snapshot] in a single transaction,
     * utilizing the in-memory cache and batch inserts for high throughput.
     */
    fun recordBatch(snapshot: RepoSnapshot, batch: List<ScannedFile>) {
        if (batch.isEmpty()) return

        transaction(database) {
            if (!deduplicateFiles) {
                val insertedFiles = RepoFiles.batchInsert(batch, shouldReturnGeneratedValues = true) { file ->
                    this[RepoFiles.size] = file.size
                    this[RepoFiles.hash] = file.hash
                }
                val instances = batch.zip(insertedFiles)
                RepoFileInstances.batchInsert(instances, shouldReturnGeneratedValues = false) { (file, fileRow) ->
                    this[RepoFileInstances.snapshot] = snapshot.id
                    this[RepoFileInstances.file] = fileRow[RepoFiles.id]
                    this[RepoFileInstances.path] = file.path.invariantSeparatorsPathString
                }
                return@transaction
            }

            // Deduplication enabled:
            // 1. Identify files missing from the in-memory cache (increments hits/misses)
            val missingFromCache = batch.filter { cache.get(it.size, it.hash) == null }

            if (missingFromCache.isNotEmpty()) {
                val uniqueMissing = missingFromCache.distinctBy { FileContentKey(it.size, it.hash) }

                // Check if any missing files exist in DB (e.g. if cache was not pre-warmed)
                val missingHashes = uniqueMissing.map { it.hash }.distinct()
                val existingInDb = mutableMapOf<FileContentKey, EntityID<Int>>()
                for (chunk in missingHashes.chunked(500)) {
                    RepoFiles.select(RepoFiles.id, RepoFiles.size, RepoFiles.hash)
                        .where { RepoFiles.hash inList chunk }
                        .forEach { row ->
                            val key = FileContentKey(row[RepoFiles.size], row[RepoFiles.hash])
                            existingInDb[key] = row[RepoFiles.id]
                        }
                }

                for ((key, entityId) in existingInDb) {
                    cache.put(key.size, key.hash, entityId)
                }

                // Brand new files not in cache or DB
                val brandNewFiles = uniqueMissing.filter { !cache.contains(it.size, it.hash) }
                if (brandNewFiles.isNotEmpty()) {
                    val insertedRows = RepoFiles.batchInsert(brandNewFiles, shouldReturnGeneratedValues = true) { file ->
                        this[RepoFiles.size] = file.size
                        this[RepoFiles.hash] = file.hash
                    }
                    for (row in insertedRows) {
                        cache.put(row[RepoFiles.size], row[RepoFiles.hash], row[RepoFiles.id])
                    }
                }
            }

            // 2. All files are guaranteed to be in cache. Batch insert instances.
            RepoFileInstances.batchInsert(batch, shouldReturnGeneratedValues = false) { file ->
                val entityId = cache.peek(file.size, file.hash)
                    ?: error("Cache must contain file id for hash ${file.hash} and size ${file.size}")
                this[RepoFileInstances.snapshot] = snapshot.id
                this[RepoFileInstances.file] = entityId
                this[RepoFileInstances.path] = file.path.invariantSeparatorsPathString
            }
        }
    }

    /**
     * Records a sequence of [files] under [snapshot] in batches of [batchSize].
     */
    fun recordFiles(
        snapshot: RepoSnapshot,
        files: Sequence<ScannedFile>
    ) {
        val buffer = ArrayList<ScannedFile>(batchSize)
        for (file in files) {
            buffer.add(file)
            if (buffer.size >= batchSize) {
                recordBatch(snapshot, buffer)
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) {
            recordBatch(snapshot, buffer)
            buffer.clear()
        }
    }

    /**
     * Records an iterable collection of [files] under [snapshot] in batches of [batchSize].
     */
    fun recordFiles(
        snapshot: RepoSnapshot,
        files: Iterable<ScannedFile>
    ) = recordFiles(snapshot, files.asSequence())

    /**
     * Marks [snapshot] as completed.
     */
    fun completeSnapshot(snapshot: RepoSnapshot) = transaction(database) {
        snapshot.isCompleted = true
    }

    /**
     * Scans [rootPath], records each discovered file in the database in batches, and marks the snapshot complete.
     */
    fun scanAndRecord(
        rootPath: Path,
        algorithm: String = "MD5",
        onFileScanned: ((ScannedFile) -> Unit)? = null
    ): RepoSnapshot {
        if (deduplicateFiles && warmUpCache) {
            cache.warmUp(database)
        }

        val snapshot = createSnapshot(rootPath)

        val scannedFiles = scanner.scan(rootPath, algorithm).onEach { file ->
            onFileScanned?.invoke(file)
        }

        recordFiles(snapshot, scannedFiles)
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

    /**
     * Computes comprehensive statistics about files scanned, duplication metrics,
     * largest duplicate examples, and decile percentile distribution of duplicate storage.
     *
     * @param snapshotId The snapshot ID to analyze.
     * @param duration The total processing duration to include in the statistics.
     */
    fun computeDuplicateStatistics(
        snapshotId: Int,
        duration: Duration
    ): DuplicateStatistics = transaction(database) {
        val instances = (RepoFileInstances innerJoin RepoFiles)
            .select(
                RepoFileInstances.id,
                RepoFileInstances.path,
                RepoFiles.id,
                RepoFiles.size,
                RepoFiles.hash
            )
            .where { RepoFileInstances.snapshot eq snapshotId }
            .toList()

        val totalFilesScanned = instances.size.toLong()
        val totalScannedBytes = instances.sumOf { it[RepoFiles.size] }

        val groupedByFile = instances.groupBy { it[RepoFiles.id].value }
        val distinctFiles = groupedByFile.size.toLong()
        val distinctBytes = groupedByFile.values.sumOf { it.first()[RepoFiles.size] }

        val duplicateGroups = groupedByFile.values
            .filter { it.size > 1 }
            .map { rows ->
                val first = rows.first()
                DuplicateGroup(
                    hash = first[RepoFiles.hash],
                    size = first[RepoFiles.size],
                    copyCount = rows.size,
                    paths = rows.map { it[RepoFileInstances.path] }
                )
            }

        val duplicateGroupsCount = duplicateGroups.size.toLong()
        val duplicateFileCount = duplicateGroups.sumOf { it.copyCount }.toLong()
        val wastedBytes = duplicateGroups.sumOf { it.wastedBytes }
        val totalDuplicateBytes = duplicateGroups.sumOf { it.totalDuplicateBytes }
        val wastedPercentage = if (totalScannedBytes > 0) {
            (wastedBytes.toDouble() / totalScannedBytes.toDouble()) * 100.0
        } else 0.0

        val largest10 = duplicateGroups
            .sortedWith(compareByDescending<DuplicateGroup> { it.size }.thenByDescending { it.wastedBytes })
            .take(10)

        val percentileBuckets = computePercentileBuckets(duplicateGroups, wastedBytes)

        DuplicateStatistics(
            totalFilesScanned = totalFilesScanned,
            totalScannedBytes = totalScannedBytes,
            distinctFiles = distinctFiles,
            distinctBytes = distinctBytes,
            duplicateFileCount = duplicateFileCount,
            duplicateGroupsCount = duplicateGroupsCount,
            wastedBytes = wastedBytes,
            totalDuplicateBytes = totalDuplicateBytes,
            wastedPercentage = wastedPercentage,
            largestDuplicates = largest10,
            percentileBuckets = percentileBuckets,
            processingDuration = duration
        )
    }

    private fun computePercentileBuckets(
        duplicateGroups: List<DuplicateGroup>,
        totalWastedBytes: Long
    ): List<PercentileBucket> {
        if (duplicateGroups.isEmpty()) {
            return (0..9).map { index ->
                val start = index * 10
                val end = (index + 1) * 10
                PercentileBucket(
                    bucketIndex = index + 1,
                    percentileRange = "$start% - $end%",
                    minSize = 0L,
                    maxSize = 0L,
                    fileCount = 0,
                    totalDuplicateBytes = 0L,
                    wastedBytes = 0L,
                    percentageOfTotalWasted = 0.0
                )
            }
        }

        val sorted = duplicateGroups.sortedBy { it.size }
        val total = sorted.size
        val bucketLists = Array(10) { mutableListOf<DuplicateGroup>() }

        for ((i, group) in sorted.withIndex()) {
            val bucketIdx = minOf(9, (i * 10) / total)
            bucketLists[bucketIdx].add(group)
        }

        return bucketLists.mapIndexed { index, items ->
            val start = index * 10
            val end = (index + 1) * 10
            val bucketWasted = items.sumOf { it.wastedBytes }
            val bucketDuplicated = items.sumOf { it.totalDuplicateBytes }
            val pct = if (totalWastedBytes > 0) {
                (bucketWasted.toDouble() / totalWastedBytes.toDouble()) * 100.0
            } else 0.0

            PercentileBucket(
                bucketIndex = index + 1,
                percentileRange = "$start% - $end%",
                minSize = items.minOfOrNull { it.size } ?: 0L,
                maxSize = items.maxOfOrNull { it.size } ?: 0L,
                fileCount = items.sumOf { it.copyCount },
                totalDuplicateBytes = bucketDuplicated,
                wastedBytes = bucketWasted,
                percentageOfTotalWasted = pct
            )
        }
    }

    /**
     * Prints a comprehensive duplicate statistics summary to the specified [out] printer.
     */
    fun printDuplicateStatistics(stats: DuplicateStatistics, out: (String) -> Unit = ::println) {
        val line = "=".repeat(80)
        val sep = "-".repeat(80)

        out("")
        out(line)
        out("                           DUPLICATION SUMMARY")
        out(line)
        out("Scan & Ingestion Statistics:")
        out("  Total Files Scanned:       ${String.format(Locale.US, "%,d", stats.totalFilesScanned)} files")
        out("  Total Storage Scanned:     ${formatBytes(stats.totalScannedBytes)}")
        out("  Unique File Contents:      ${String.format(Locale.US, "%,d", stats.distinctFiles)} files")
        out("  Unique Storage:            ${formatBytes(stats.distinctBytes)}")
        out("  Total Processing Time:     ${formatDuration(stats.processingDuration)}")
        out("")
        out("Duplication Overview:")
        out("  Duplicate Content Groups:  ${String.format(Locale.US, "%,d", stats.duplicateGroupsCount)} unique files duplicated")
        val redundantCopies = if (stats.duplicateFileCount > stats.duplicateGroupsCount) {
            stats.duplicateFileCount - stats.duplicateGroupsCount
        } else 0L
        out("  Duplicate File Instances:  ${String.format(Locale.US, "%,d", stats.duplicateFileCount)} files ($redundantCopies redundant copies)")
        out("  Total Duplicated Storage:  ${formatBytes(stats.totalDuplicateBytes)}")
        out("  Wasted Storage:            ${formatBytes(stats.wastedBytes)}")
        out("  Wasted Storage Share:      ${String.format(Locale.US, "%.2f%%", stats.wastedPercentage)} of total scanned storage")
        out("")
        out(sep)
        out("Top 10 Largest Duplicate Files:")
        out(sep)
        if (stats.largestDuplicates.isEmpty()) {
            out("  No duplicate files found.")
        } else {
            stats.largestDuplicates.forEachIndexed { idx, group ->
                val num = (idx + 1).toString().padStart(2)
                out("$num. ${formatBytes(group.size)} | ${group.copyCount} copies | Wasted: ${formatBytes(group.wastedBytes)}")
                out("    Hash: ${group.hash}")
                out("    Paths:")
                group.paths.forEach { path ->
                    out("      - $path")
                }
            }
        }
        out("")
        out(sep)
        out("Percentile Buckets by File Size (10% Deciles):")
        out(sep)
        out("Percentile  Size Range              Files  Duplicated Storage   Wasted Storage  Share (%)")
        out(sep)
        stats.percentileBuckets.forEach { bucket ->
            val rangeStr = "[${formatBytesShort(bucket.minSize)} - ${formatBytesShort(bucket.maxSize)}]".padEnd(20)
            val filesStr = String.format(Locale.US, "%,d", bucket.fileCount).padStart(7)
            val dupStr = formatBytesShort(bucket.totalDuplicateBytes).padStart(19)
            val wastedStr = formatBytesShort(bucket.wastedBytes).padStart(16)
            val pctStr = String.format(Locale.US, "%6.2f%%", bucket.percentageOfTotalWasted)
            out("${bucket.percentileRange.padEnd(10)}  $rangeStr$filesStr$dupStr$wastedStr  $pctStr")
        }
        out(sep)
        out("Total Wasted Storage: ${formatBytes(stats.wastedBytes)}")
        out(line)
        out("")
    }
}
