package com.cherba29.boveda.service

import com.cherba29.boveda.cache.DeduplicationCache
import com.cherba29.boveda.cache.FileContentKey
import com.cherba29.boveda.cache.InMemoryDeduplicationCache
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFileInstances
import com.cherba29.boveda.db.RepoFiles
import com.cherba29.boveda.db.RepoFolder
import com.cherba29.boveda.db.RepoFolders
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.db.RepoSnapshots
import com.cherba29.boveda.model.DuplicateFileEntry
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.DuplicateStatistics
import com.cherba29.boveda.model.FileDeletionResult
import com.cherba29.boveda.model.FileRecord
import com.cherba29.boveda.model.FileRecordWithDuplicateRank
import com.cherba29.boveda.model.FolderDuplicatePair
import com.cherba29.boveda.model.PercentileBucket
import com.cherba29.boveda.model.SnapshotSummary
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatBytesShort
import com.cherba29.boveda.model.formatDuration
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.DiscoveredFile
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
import com.github.ajalt.mordant.rendering.BorderType
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Locale
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Lifecycle phases reported during [BovedaService.scanAndRecord].
 */
enum class ScanPhase {
  WARMING_CACHE,
  LOADING_EXISTING_SNAPSHOT,
  SCANNING,
  UPDATING_FOLDER_STATISTICS,
  COMPLETED
}

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
  val scanner: DirectoryScanner = WorkStealingDirectoryScanner(),
  private val deduplicateFiles: Boolean = true,
  val batchSize: Int = DEFAULT_BATCH_SIZE,
  val cache: DeduplicationCache = InMemoryDeduplicationCache(),
  private val warmUpCache: Boolean = true,
  maxFolderCacheSize: Long = DEFAULT_FOLDER_CACHE_MAX_SIZE
) {

  companion object {
    const val DEFAULT_BATCH_SIZE = 1000
    const val DEFAULT_FOLDER_CACHE_MAX_SIZE = 50_000L
  }

  private val folderCache: Cache<Pair<Int, String>, Int> = CacheBuilder.newBuilder()
    .maximumSize(maxFolderCacheSize.coerceAtLeast(1L))
    .build()

  /**
   * Resolves the parent folder path of [path] in normalized invariant-separator format.
   */
  fun getParentFolderPath(path: Path): String =
    path.parent?.normalize()?.invariantSeparatorsPathString ?: "/"

  private fun ensureFoldersExist(
    snapshotId: EntityID<Int>,
    folderPaths: Collection<String>
  ): Map<String, EntityID<Int>> {
    val resolved = HashMap<String, EntityID<Int>>(folderPaths.size)
    val missing = mutableListOf<String>()

    for (folderPath in folderPaths) {
      val cachedId = folderCache.getIfPresent(Pair(snapshotId.value, folderPath))
      if (cachedId != null) {
        resolved[folderPath] = EntityID(cachedId, RepoFolders)
      } else {
        missing.add(folderPath)
      }
    }

    if (missing.isEmpty()) return resolved

    for (chunk in missing.chunked(500)) {
      RepoFolders.select(RepoFolders.id, RepoFolders.path)
        .where { (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path inList chunk) }
        .forEach { row ->
          val path = row[RepoFolders.path]
          val id = row[RepoFolders.id]
          resolved[path] = id
          folderCache.put(Pair(snapshotId.value, path), id.value)
        }
    }

    val stillMissing = missing.filter { it !in resolved }
    if (stillMissing.isNotEmpty()) {
      val inserted = RepoFolders.batchInsert(
        stillMissing,
        shouldReturnGeneratedValues = true
      ) { p ->
        this[RepoFolders.snapshot] = snapshotId
        this[RepoFolders.path] = p
        this[RepoFolders.fileCount] = 0
        this[RepoFolders.totalBytes] = 0L
      }
      for ((p, row) in stillMissing.zip(inserted)) {
        val id = row[RepoFolders.id]
        resolved[p] = id
        folderCache.put(Pair(snapshotId.value, p), id.value)
      }
    }

    return resolved
  }

  private fun getOrCreateFolderId(snapshotId: EntityID<Int>, folderPath: String): EntityID<Int> {
    val resolved = ensureFoldersExist(snapshotId, listOf(folderPath))
    return resolved[folderPath]
      ?: error("Failed to resolve folder id for $folderPath")
  }

  /**
   * Initializes a new directory snapshot in the database.
   */
  fun createSnapshot(rootPath: Path): RepoSnapshot = transaction(database) {
    RepoSnapshot.new {
      this.rootPath = rootPath.invariantSeparatorsPathString
      this.isCompleted = false
    }
  }

  /**
   * Records a single [scannedFile] under [snapshot].
   */
  fun recordFile(snapshot: RepoSnapshot, scannedFile: ScannedFile): RepoFileInstance =
    transaction(database) {
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

      val folderPath = getParentFolderPath(scannedFile.path)
      val folderId = getOrCreateFolderId(snapshot.id, folderPath)

      RepoFileInstance.new {
        this.snapshot = snapshot
        this.folder = RepoFolder[folderId]
        this.file = repoFile
        this.path = scannedFile.path.invariantSeparatorsPathString
        this.timestamp = scannedFile.lastModifiedTime?.toMillis()
      }
    }

  /**
   * Ingests a batch of [ScannedFile]s under [snapshot] in a single transaction,
   * utilizing the in-memory cache and batch inserts for high throughput.
   */
  fun recordBatch(
    snapshot: RepoSnapshot,
    batch: List<ScannedFile>,
    existingEntriesToUpdate: Set<String>? = null
  ) {
    if (batch.isEmpty()) return

    transaction(database) {
      if (!existingEntriesToUpdate.isNullOrEmpty()) {
        val pathsInBatch = batch.map { it.path.invariantSeparatorsPathString }
        val toDelete = pathsInBatch.filter { it in existingEntriesToUpdate }
        if (toDelete.isNotEmpty()) {
          RepoFileInstances.deleteWhere {
            (RepoFileInstances.snapshot eq snapshot.id) and (RepoFileInstances.path inList toDelete)
          }
        }
      }

      val folderPathsInBatch = batch.map { getParentFolderPath(it.path) }.distinct()
      val folderIds = ensureFoldersExist(snapshot.id, folderPathsInBatch)

      if (!deduplicateFiles) {
        val insertedFiles = RepoFiles.batchInsert(
          batch,
          shouldReturnGeneratedValues = true
        ) { file ->
          this[RepoFiles.size] = file.size
          this[RepoFiles.hash] = file.hash
        }
        val instances = batch.zip(insertedFiles)
        RepoFileInstances.batchInsert(
          instances,
          shouldReturnGeneratedValues = false
        ) { (file, fileRow) ->
          val folderPath = getParentFolderPath(file.path)
          val folderId = folderIds[folderPath]
            ?: error("Folder $folderPath must be resolved")
          this[RepoFileInstances.snapshot] = snapshot.id
          this[RepoFileInstances.folder] = folderId
          this[RepoFileInstances.file] = fileRow[RepoFiles.id]
          this[RepoFileInstances.path] = file.path.invariantSeparatorsPathString
          this[RepoFileInstances.timestamp] = file.lastModifiedTime?.toMillis()
        }
        return@transaction
      }

      // Deduplication enabled:
      // Keep a batch-local map so bounded LRU evictions in `cache` can never lose an ID mid-batch.
      val batchFileIds = HashMap<FileContentKey, EntityID<Int>>(batch.size)
      val missingFromCache = mutableListOf<ScannedFile>()

      // 1. Identify files missing from the in-memory cache (increments hits/misses)
      for (file in batch) {
        val cachedId = cache.get(file.size, file.hash)
        if (cachedId != null) {
          batchFileIds[FileContentKey(file.size, file.hash)] = cachedId
        } else {
          missingFromCache.add(file)
        }
      }

      if (missingFromCache.isNotEmpty()) {
        val uniqueMissing = missingFromCache.distinctBy { FileContentKey(it.size, it.hash) }

        // Check if any missing files exist in DB (e.g. if cache was not pre-warmed or evicted)
        val missingHashes = uniqueMissing.map { it.hash }.distinct()
        for (chunk in missingHashes.chunked(500)) {
          RepoFiles.select(RepoFiles.id, RepoFiles.size, RepoFiles.hash)
            .where { RepoFiles.hash inList chunk }
            .forEach { row ->
              val key = FileContentKey(row[RepoFiles.size], row[RepoFiles.hash])
              val entityId = row[RepoFiles.id]
              batchFileIds[key] = entityId
              cache.put(key.size, key.hash, entityId)
            }
        }

        // Brand new files not in cache or DB
        val brandNewFiles = uniqueMissing.filter {
          FileContentKey(it.size, it.hash) !in batchFileIds
        }
        if (brandNewFiles.isNotEmpty()) {
          val insertedRows = RepoFiles.batchInsert(
            brandNewFiles,
            shouldReturnGeneratedValues = true
          ) { file ->
            this[RepoFiles.size] = file.size
            this[RepoFiles.hash] = file.hash
          }
          for (row in insertedRows) {
            val key = FileContentKey(row[RepoFiles.size], row[RepoFiles.hash])
            val entityId = row[RepoFiles.id]
            batchFileIds[key] = entityId
            cache.put(key.size, key.hash, entityId)
          }
        }
      }

      // 2. All files are guaranteed to be in batchFileIds. Batch insert instances.
      RepoFileInstances.batchInsert(batch, shouldReturnGeneratedValues = false) { file ->
        val entityId = batchFileIds[FileContentKey(file.size, file.hash)]
          ?: error("Resolved file id missing for hash ${file.hash} and size ${file.size}")
        val folderPath = getParentFolderPath(file.path)
        val folderId = folderIds[folderPath]
          ?: error("Folder $folderPath must be resolved")
        this[RepoFileInstances.snapshot] = snapshot.id
        this[RepoFileInstances.folder] = folderId
        this[RepoFileInstances.file] = entityId
        this[RepoFileInstances.path] = file.path.invariantSeparatorsPathString
        this[RepoFileInstances.timestamp] = file.lastModifiedTime?.toMillis()
      }
    }
  }

  /**
   * Records a sequence of [files] under [snapshot] in batches of [batchSize].
   */
  fun recordFiles(
    snapshot: RepoSnapshot,
    files: Sequence<ScannedFile>,
    existingEntriesToUpdate: Set<String>? = null
  ) {
    val buffer = ArrayList<ScannedFile>(batchSize)
    for (file in files) {
      buffer.add(file)
      if (buffer.size >= batchSize) {
        recordBatch(snapshot, buffer, existingEntriesToUpdate)
        buffer.clear()
      }
    }
    if (buffer.isNotEmpty()) {
      recordBatch(snapshot, buffer, existingEntriesToUpdate)
      buffer.clear()
    }
  }

  /**
   * Records an iterable collection of [files] under [snapshot] in batches of [batchSize].
   */
  fun recordFiles(
    snapshot: RepoSnapshot,
    files: Iterable<ScannedFile>,
    existingEntriesToUpdate: Set<String>? = null
  ) = recordFiles(snapshot, files.asSequence(), existingEntriesToUpdate)

  /**
   * Marks [snapshot] as completed and updates aggregate folder statistics.
   */
  fun completeSnapshot(snapshot: RepoSnapshot) = transaction(database) {
    updateFolderStatistics(snapshot.id.value)
    snapshot.isCompleted = true
  }

  /**
   * Updates [RepoFolders.fileCount] and [RepoFolders.totalBytes] for all folders under [snapshotId].
   */
  fun updateFolderStatistics(snapshotId: Int) = transaction(database) {
    val conn = (TransactionManager.current().connection.connection as Connection)
    val sql = """
      MERGE INTO folders f
      USING (
        SELECT folder_id, COUNT(*) AS cnt, SUM("size") AS bytes
        FROM (
          SELECT DISTINCT fi.folder_id, fi.file_id, fl."size"
          FROM file_instances fi
          JOIN files fl ON fi.file_id = fl.id
          WHERE fi.snapshot_id = ?
        )
        GROUP BY folder_id
      ) agg
      ON (f.id = agg.folder_id)
      WHEN MATCHED THEN UPDATE SET f.file_count = agg.cnt, f.total_bytes = agg.bytes
    """.trimIndent()
    conn.prepareStatement(sql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.executeUpdate()
    }
  }

  /**
   * Retrieves the latest directory snapshot from the database, prioritizing completed snapshots.
   */
  fun getLatestSnapshot(): RepoSnapshot? = transaction(database) {
    RepoSnapshot.find { RepoSnapshots.isCompleted eq true }
      .orderBy(RepoSnapshots.id to SortOrder.DESC)
      .limit(1)
      .firstOrNull()
      ?: RepoSnapshot.all()
        .orderBy(RepoSnapshots.id to SortOrder.DESC)
        .limit(1)
        .firstOrNull()
  }

  /**
   * Finds a snapshot by its [snapshotId].
   */
  fun findSnapshot(snapshotId: Int): RepoSnapshot? = transaction(database) {
    RepoSnapshot.findById(snapshotId)
  }

  /**
   * Lists all snapshots with file count and total storage size.
   */
  fun listSnapshots(): List<SnapshotSummary> = transaction(database) {
    val countCol = RepoFileInstances.id.count().alias("file_count")
    val sizeCol = RepoFiles.size.sum().alias("total_bytes")

    val join = RepoSnapshots leftJoin RepoFileInstances leftJoin RepoFiles

    join.select(
      RepoSnapshots.id,
      RepoSnapshots.rootPath,
      RepoSnapshots.isCompleted,
      countCol,
      sizeCol
    )
      .groupBy(RepoSnapshots.id, RepoSnapshots.rootPath, RepoSnapshots.isCompleted)
      .orderBy(RepoSnapshots.id to SortOrder.ASC)
      .map { row ->
        SnapshotSummary(
          id = row[RepoSnapshots.id].value,
          rootPath = row[RepoSnapshots.rootPath],
          isCompleted = row[RepoSnapshots.isCompleted],
          fileCount = row[countCol],
          totalBytes = row[sizeCol] ?: 0L
        )
      }
  }

  /**
   * Deletes a snapshot by its [snapshotId], removing its file instances and any orphaned files.
   *
   * @return true if the snapshot was found and deleted, false otherwise.
   */
  fun deleteSnapshot(snapshotId: Int): Boolean = transaction(database) {
    val snapshot = RepoSnapshot.findById(snapshotId) ?: return@transaction false
    RepoFileInstances.deleteWhere { RepoFileInstances.snapshot eq snapshot.id }
    RepoFolders.deleteWhere { RepoFolders.snapshot eq snapshot.id }
    RepoFiles.deleteWhere {
      RepoFiles.id notInSubQuery (RepoFileInstances.select(RepoFileInstances.file))
    }
    snapshot.delete()
    folderCache.asMap().keys.removeIf { it.first == snapshotId }
    true
  }

  /**
   * Finds a [RepoFileInstance] matching [path] within [snapshotId].
   * Handles absolute, relative, or snapshot root-relative path matching.
   */
  fun findFileInstance(snapshotId: Int, path: Path): RepoFileInstance? = transaction(database) {
    val absPathStr = path.toAbsolutePath().normalize().invariantSeparatorsPathString
    val relPathStr = path.normalize().invariantSeparatorsPathString
    val rawPathStr = path.invariantSeparatorsPathString

    var instance = RepoFileInstance.find {
      (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq absPathStr)
    }.firstOrNull()

    if (instance == null) {
      instance = RepoFileInstance.find {
        (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq relPathStr)
      }.firstOrNull()
    }

    if (instance == null) {
      instance = RepoFileInstance.find {
        (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq rawPathStr)
      }.firstOrNull()
    }

    if (instance == null) {
      val snapshot = RepoSnapshot.findById(snapshotId)
      if (snapshot != null) {
        val rootResolved = Path.of(snapshot.rootPath).resolve(path)
          .normalize().invariantSeparatorsPathString
        instance = RepoFileInstance.find {
          (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq rootResolved)
        }.firstOrNull()
      }
    }

    instance
  }

  /**
   * Counts the number of file instances in [snapshotId] referencing [fileId].
   */
  fun countFileDuplicates(snapshotId: Int, fileId: EntityID<Int>): Long = transaction(database) {
    RepoFileInstances.select(RepoFileInstances.id)
      .where { (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.file eq fileId) }
      .count()
  }

  /**
   * Removes a file instance from the database and disk within [snapshotId].
   * If the file has no duplicates, deletion fails unless [force] is true.
   */
  fun deleteFile(snapshotId: Int, path: Path, force: Boolean = false): FileDeletionResult =
    transaction(database) {
      val instance = findFileInstance(snapshotId, path)
        ?: throw IllegalArgumentException("File not found in database: $path")

      val fileEntityId = instance.file.id
      val dupCount = countFileDuplicates(snapshotId, fileEntityId)
      val hasDuplicates = dupCount > 1

      if (!hasDuplicates && !force) {
        throw IllegalStateException(
          "File does not have duplicates: $path. Use --force to delete anyway."
        )
      }

      val pathStr = instance.path
      val diskPath = Path.of(pathStr)
      val actualDiskPath = if (Files.exists(diskPath)) diskPath else path
      val deletedFromDisk = try {
        Files.deleteIfExists(actualDiskPath)
      } catch (_: Exception) {
        false
      }

      instance.delete()

      val remaining = RepoFileInstances.select(RepoFileInstances.id)
        .where { RepoFileInstances.file eq fileEntityId }
        .count()
      if (remaining == 0L) {
        RepoFiles.deleteWhere { RepoFiles.id eq fileEntityId }
      }

      FileDeletionResult(
        path = pathStr,
        deletedFromDisk = deletedFromDisk,
        deletedFromDb = true,
        hasDuplicates = hasDuplicates
      )
    }

  /**
   * Deletes multiple duplicate file instances by path from disk and database in [snapshotId].
   */
  fun deleteDuplicateFiles(snapshotId: Int, paths: List<String>): List<FileDeletionResult> =
    transaction(database) {
      val fileIdsToCheck = mutableSetOf<EntityID<Int>>()
      val results = paths.map { pathStr ->
        val p = Path.of(pathStr)
        val deletedFromDisk = try {
          Files.deleteIfExists(p)
        } catch (_: Exception) {
          false
        }

        val instance = RepoFileInstance.find {
          (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq pathStr)
        }.firstOrNull()

        val deletedFromDb = if (instance != null) {
          fileIdsToCheck.add(instance.file.id)
          instance.delete()
          true
        } else {
          false
        }

        FileDeletionResult(
          path = pathStr,
          deletedFromDisk = deletedFromDisk,
          deletedFromDb = deletedFromDb,
          hasDuplicates = true
        )
      }

      for (fileId in fileIdsToCheck) {
        val remaining = RepoFileInstances.select(RepoFileInstances.id)
          .where { RepoFileInstances.file eq fileId }
          .count()
        if (remaining == 0L) {
          RepoFiles.deleteWhere { RepoFiles.id eq fileId }
        }
      }

      results
    }

  /**
   * Retrieves all groups of duplicate file instances in [snapshotId] sharing content hash and size.
   */
  fun getDuplicateGroups(snapshotId: Int): List<DuplicateGroup> = transaction(database) {
    val conn = TransactionManager.current().connection.connection as Connection
    val sql = """
      SELECT fi.file_id, fl.hash, fl."size" AS sz, fi."path" AS file_path
      FROM file_instances fi
      JOIN files fl ON fi.file_id = fl.id
      JOIN (
        SELECT file_id
        FROM file_instances
        WHERE snapshot_id = ?
        GROUP BY file_id
        HAVING COUNT(*) > 1
      ) d ON fi.file_id = d.file_id
      WHERE fi.snapshot_id = ?
      ORDER BY fi.file_id
    """.trimIndent()

    val groups = mutableListOf<DuplicateGroup>()
    conn.prepareStatement(sql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.setInt(2, snapshotId)
      stmt.executeQuery().use { rs ->
        var currentFileId = -1
        var currentHash = ""
        var currentSize = 0L
        var currentPaths = mutableListOf<String>()

        while (rs.next()) {
          val fileId = rs.getInt("file_id")
          if (fileId != currentFileId) {
            if (currentPaths.isNotEmpty()) {
              groups.add(
                DuplicateGroup(
                  hash = currentHash,
                  size = currentSize,
                  copyCount = currentPaths.size,
                  paths = currentPaths
                )
              )
            }
            currentFileId = fileId
            currentHash = rs.getString("hash")
            currentSize = rs.getLong("sz")
            currentPaths = mutableListOf()
          }
          currentPaths.add(rs.getString("file_path"))
        }

        if (currentPaths.isNotEmpty()) {
          groups.add(
            DuplicateGroup(
              hash = currentHash,
              size = currentSize,
              copyCount = currentPaths.size,
              paths = currentPaths
            )
          )
        }
      }
    }
    groups
  }

  /**
   * Finds the duplicate group in [snapshotId] where most storage is wasted,
   * excluding any hashes in [excludedHashes].
   */
  fun findLargestDuplicateGroup(
    snapshotId: Int,
    excludedHashes: Set<String> = emptySet()
  ): DuplicateGroup? = transaction(database) {
    val conn = TransactionManager.current().connection.connection as Connection
    val limitClause = if (excludedHashes.isEmpty()) "LIMIT 1" else ""
    val sql = """
      SELECT g.file_id, fl.hash, fl."size" AS sz, g.cnt
      FROM (
        SELECT file_id, COUNT(*) AS cnt
        FROM file_instances
        WHERE snapshot_id = ?
        GROUP BY file_id
        HAVING COUNT(*) > 1
      ) g
      JOIN files fl ON g.file_id = fl.id
      ORDER BY
        ((g.cnt - 1) * fl."size") DESC,
        (g.cnt * fl."size") DESC,
        fl."size" DESC,
        fl.hash DESC
      $limitClause
    """.trimIndent()

    var chosenFileId: Int? = null
    var chosenHash = ""
    var chosenSize = 0L
    var chosenCount = 0

    conn.prepareStatement(sql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          val hash = rs.getString("hash")
          if (hash !in excludedHashes) {
            chosenFileId = rs.getInt("file_id")
            chosenHash = hash
            chosenSize = rs.getLong("sz")
            chosenCount = rs.getInt("cnt")
            break
          }
        }
      }
    }

    val fileId = chosenFileId ?: return@transaction null
    val paths = RepoFileInstances.select(RepoFileInstances.path)
      .where {
        (RepoFileInstances.snapshot eq snapshotId) and
          (RepoFileInstances.file eq EntityID(fileId, RepoFiles))
      }
      .map { it[RepoFileInstances.path] }

    DuplicateGroup(
      hash = chosenHash,
      size = chosenSize,
      copyCount = chosenCount,
      paths = paths
    )
  }

  /**
   * Retrieves all file instances recorded in [snapshotId] mapped by their normalized path.
   */
  fun getSnapshotFileEntries(snapshotId: Int): Map<String, SnapshotFileEntry> =
    transaction(database) {
      val join = RepoFileInstances innerJoin RepoFiles
      join.select(
        RepoFileInstances.id,
        RepoFileInstances.path,
        RepoFileInstances.timestamp,
        RepoFiles.id,
        RepoFiles.size,
        RepoFiles.hash
      ).where { RepoFileInstances.snapshot eq snapshotId }
        .associate { row ->
          val pathStr = row[RepoFileInstances.path]
          pathStr to SnapshotFileEntry(
            instanceId = row[RepoFileInstances.id],
            path = pathStr,
            size = row[RepoFiles.size],
            timestamp = row[RepoFileInstances.timestamp],
            hash = row[RepoFiles.hash],
            fileId = row[RepoFiles.id]
          )
        }
    }

  /**
   * Checks whether a discovered file matches an already recorded snapshot entry in path, name,
   * timestamp, and size without content modifications.
   */
  fun isUnchanged(discovered: DiscoveredFile, existing: SnapshotFileEntry): Boolean {
    val samePath = existing.path == discovered.path.invariantSeparatorsPathString
    val sameName = Path.of(existing.path).fileName?.toString() ==
      discovered.path.fileName?.toString()
    val sameSize = existing.size == discovered.size
    val sameTimestamp = if (existing.timestamp == null) {
      discovered.lastModifiedTime == null
    } else {
      discovered.lastModifiedTime != null &&
        existing.timestamp == discovered.lastModifiedTime.toMillis()
    }
    return samePath && sameName && sameSize && sameTimestamp
  }

  /**
   * Scans [rootPath], records each discovered file in the database in batches, and marks the snapshot complete.
   * If [resumeSnapshotId] is provided, resumes the incomplete snapshot by skipping already processed,
   * unchanged files and only hashing and recording new or modified files.
   */
  fun scanAndRecord(
    rootPath: Path,
    algorithm: String = "XXH3",
    resumeSnapshotId: Int? = null,
    maxBytes: Long? = null,
    onPhase: ((ScanPhase) -> Unit)? = null,
    onFileScanned: ((ScannedFile) -> Unit)? = null
  ): RepoSnapshot {
    if (deduplicateFiles && warmUpCache) {
      onPhase?.invoke(ScanPhase.WARMING_CACHE)
      cache.warmUp(database)
    }

    val snapshot = if (resumeSnapshotId != null) {
      val existing = findSnapshot(resumeSnapshotId)
        ?: throw IllegalArgumentException("Snapshot not found: $resumeSnapshotId")
      require(!existing.isCompleted) {
        "Snapshot $resumeSnapshotId is already completed. " +
          "Only incomplete snapshots can be continued."
      }
      existing
    } else {
      createSnapshot(rootPath)
    }

    val existingEntries = if (resumeSnapshotId != null) {
      onPhase?.invoke(ScanPhase.LOADING_EXISTING_SNAPSHOT)
      getSnapshotFileEntries(resumeSnapshotId)
    } else {
      emptyMap()
    }

    val shouldSkip: (DiscoveredFile) -> Boolean = { discovered ->
      val existing = existingEntries[discovered.path.invariantSeparatorsPathString]
      existing != null && isUnchanged(discovered, existing)
    }

    onPhase?.invoke(ScanPhase.SCANNING)
    val scannedFiles = scanner.scan(
      rootPath = rootPath,
      algorithm = algorithm,
      shouldSkip = if (resumeSnapshotId != null) shouldSkip else null,
      maxBytes = maxBytes
    ).onEach { file ->
      onFileScanned?.invoke(file)
    }

    if (resumeSnapshotId != null && existingEntries.isNotEmpty()) {
      recordFiles(snapshot, scannedFiles, existingEntriesToUpdate = existingEntries.keys)
    } else {
      recordFiles(snapshot, scannedFiles)
    }

    onPhase?.invoke(ScanPhase.UPDATING_FOLDER_STATISTICS)
    completeSnapshot(snapshot)
    onPhase?.invoke(ScanPhase.COMPLETED)
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
  fun queryFilesWithDuplicateRank(snapshotId: Int? = null): List<FileRecordWithDuplicateRank> =
    transaction(database) {
      val hashWindow = rowNumber().over().partitionBy(
        RepoFiles.hash
      ).orderBy(RepoFileInstances.path).alias("rn")
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
   * Streams duplicate file records (files whose hash is shared by more than one instance)
   * directly from SQL in O(1) JVM heap memory, invoking [onRecord] for each row.
   *
   * @return The total number of duplicate file instances streamed.
   */
  fun forEachDuplicate(
    snapshotId: Int? = null,
    onRecord: (FileRecordWithDuplicateRank) -> Unit
  ): Long = transaction(database) {
    val conn = TransactionManager.current().connection.connection as Connection
    val sql = if (snapshotId != null) {
      """
        SELECT
          fi.snapshot_id,
          fi."path" AS file_path,
          fl."size" AS sz,
          fl.hash,
          ROW_NUMBER() OVER (PARTITION BY fl.hash ORDER BY fi."path") AS rn
        FROM file_instances fi
        JOIN files fl ON fi.file_id = fl.id
        JOIN (
          SELECT fl2.hash
          FROM file_instances fi2
          JOIN files fl2 ON fi2.file_id = fl2.id
          WHERE fi2.snapshot_id = ?
          GROUP BY fl2.hash
          HAVING COUNT(*) > 1
        ) dh ON fl.hash = dh.hash
        WHERE fi.snapshot_id = ?
        ORDER BY fl.hash, fi."path"
      """.trimIndent()
    } else {
      """
        SELECT
          fi.snapshot_id,
          fi."path" AS file_path,
          fl."size" AS sz,
          fl.hash,
          ROW_NUMBER() OVER (PARTITION BY fl.hash ORDER BY fi."path") AS rn
        FROM file_instances fi
        JOIN files fl ON fi.file_id = fl.id
        JOIN (
          SELECT fl2.hash
          FROM file_instances fi2
          JOIN files fl2 ON fi2.file_id = fl2.id
          GROUP BY fl2.hash
          HAVING COUNT(*) > 1
        ) dh ON fl.hash = dh.hash
        ORDER BY fl.hash, fi."path"
      """.trimIndent()
    }

    var count = 0L
    conn.prepareStatement(sql).use { stmt ->
      if (snapshotId != null) {
        stmt.setInt(1, snapshotId)
        stmt.setInt(2, snapshotId)
      }
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          onRecord(
            FileRecordWithDuplicateRank(
              snapshotId = rs.getInt("snapshot_id"),
              path = rs.getString("file_path"),
              size = rs.getLong("sz"),
              hash = rs.getString("hash"),
              duplicateRank = rs.getLong("rn")
            )
          )
          count++
        }
      }
    }
    count
  }

  /**
   * Finds all duplicate files (files whose hash is shared by more than one file instance).
   */
  fun findDuplicates(snapshotId: Int? = null): List<FileRecordWithDuplicateRank> {
    val results = mutableListOf<FileRecordWithDuplicateRank>()
    forEachDuplicate(snapshotId) { results.add(it) }
    return results
  }

  /**
   * Prints query results matching the original output format.
   */
  fun printQueryResults(snapshotId: Int? = null, out: (String) -> Unit = ::println) {
    out("### ==== Selecting from DB ==== ###")
    queryFiles(snapshotId).forEach { record ->
      out(
        "snapshot=${record.snapshotId} size=${record.size} path=${record.path} hash=${record.hash}"
      )
    }

    queryFilesWithDuplicateRank(snapshotId).forEach { record ->
      out(
        "${record.snapshotId} ${record.size} ${record.path} ${record.duplicateRank} ${record.hash}"
      )
    }
  }

  /**
   * Computes comprehensive statistics about files scanned, duplication metrics,
   * largest duplicate examples, and decile percentile distribution of duplicate storage
   * using SQL aggregation and streaming queries (O(1) JVM heap memory).
   *
   * @param snapshotId The snapshot ID to analyze.
   * @param duration The total processing duration to include in the statistics.
   */
  fun computeDuplicateStatistics(snapshotId: Int, duration: Duration): DuplicateStatistics =
    transaction(database) {
      val conn = TransactionManager.current().connection.connection as Connection

      // 1. Aggregate all overview totals in a single SQL query (returns 1 row)
      val overviewSql = """
        SELECT
          COALESCE(SUM(g.cnt), 0) AS total_files,
          COALESCE(SUM(g.cnt * fl."size"), 0) AS total_bytes,
          COUNT(*) AS distinct_files,
          COALESCE(SUM(fl."size"), 0) AS distinct_bytes,
          COALESCE(SUM(CASE WHEN g.cnt > 1 THEN 1 ELSE 0 END), 0) AS dup_groups,
          COALESCE(SUM(CASE WHEN g.cnt > 1 THEN g.cnt ELSE 0 END), 0) AS dup_files,
          COALESCE(SUM(CASE WHEN g.cnt > 1 THEN (g.cnt - 1) * fl."size" ELSE 0 END), 0)
            AS wasted_bytes,
          COALESCE(SUM(CASE WHEN g.cnt > 1 THEN g.cnt * fl."size" ELSE 0 END), 0)
            AS total_dup_bytes
        FROM (
          SELECT file_id, COUNT(*) AS cnt
          FROM file_instances
          WHERE snapshot_id = ?
          GROUP BY file_id
        ) g
        JOIN files fl ON g.file_id = fl.id
      """.trimIndent()

      var totalFilesScanned = 0L
      var totalScannedBytes = 0L
      var distinctFiles = 0L
      var distinctBytes = 0L
      var duplicateGroupsCount = 0L
      var duplicateFileCount = 0L
      var wastedBytes = 0L
      var totalDuplicateBytes = 0L

      conn.prepareStatement(overviewSql).use { stmt ->
        stmt.setInt(1, snapshotId)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            totalFilesScanned = rs.getLong("total_files")
            totalScannedBytes = rs.getLong("total_bytes")
            distinctFiles = rs.getLong("distinct_files")
            distinctBytes = rs.getLong("distinct_bytes")
            duplicateGroupsCount = rs.getLong("dup_groups")
            duplicateFileCount = rs.getLong("dup_files")
            wastedBytes = rs.getLong("wasted_bytes")
            totalDuplicateBytes = rs.getLong("total_dup_bytes")
          }
        }
      }

      val wastedPercentage = if (totalScannedBytes > 0L) {
        (wastedBytes.toDouble() / totalScannedBytes.toDouble()) * 100.0
      } else {
        0.0
      }

      if (duplicateGroupsCount == 0L) {
        return@transaction DuplicateStatistics(
          totalFilesScanned = totalFilesScanned,
          totalScannedBytes = totalScannedBytes,
          distinctFiles = distinctFiles,
          distinctBytes = distinctBytes,
          duplicateFileCount = 0L,
          duplicateGroupsCount = 0L,
          wastedBytes = 0L,
          totalDuplicateBytes = 0L,
          wastedPercentage = 0.0,
          largestDuplicates = emptyList(),
          percentileBuckets = emptyPercentileBuckets(),
          processingDuration = duration
        )
      }

      // 2. Fetch top 10 largest duplicate groups in SQL, then load paths only for those 10 file IDs
      data class TopGroupRow(val fileId: Int, val hash: String, val size: Long, val copyCount: Int)
      val top10Sql = """
        SELECT g.file_id, fl.hash, fl."size" AS sz, g.cnt
        FROM (
          SELECT file_id, COUNT(*) AS cnt
          FROM file_instances
          WHERE snapshot_id = ?
          GROUP BY file_id
          HAVING COUNT(*) > 1
        ) g
        JOIN files fl ON g.file_id = fl.id
        ORDER BY
          (g.cnt * fl."size") DESC,
          fl."size" DESC,
          ((g.cnt - 1) * fl."size") DESC
        LIMIT 10
      """.trimIndent()

      val topRows = mutableListOf<TopGroupRow>()
      conn.prepareStatement(top10Sql).use { stmt ->
        stmt.setInt(1, snapshotId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            topRows.add(
              TopGroupRow(
                fileId = rs.getInt("file_id"),
                hash = rs.getString("hash"),
                size = rs.getLong("sz"),
                copyCount = rs.getInt("cnt")
              )
            )
          }
        }
      }

      val topFileIds = topRows.map { EntityID(it.fileId, RepoFiles) }
      val pathsByFileId = mutableMapOf<Int, MutableList<String>>()
      if (topFileIds.isNotEmpty()) {
        RepoFileInstances.select(RepoFileInstances.file, RepoFileInstances.path)
          .where {
            (RepoFileInstances.snapshot eq snapshotId) and
              (RepoFileInstances.file inList topFileIds)
          }
          .forEach { row ->
            pathsByFileId.getOrPut(row[RepoFileInstances.file].value) { mutableListOf() }
              .add(row[RepoFileInstances.path])
          }
      }

      val largest10 = topRows.map { row ->
        DuplicateGroup(
          hash = row.hash,
          size = row.size,
          copyCount = row.copyCount,
          paths = pathsByFileId[row.fileId] ?: emptyList()
        )
      }

      // 3. Stream (size, copy_count) ordered by size into 10 scalar decile accumulators (O(1) heap)
      val percentileSql = """
        SELECT fl."size" AS sz, g.cnt
        FROM (
          SELECT file_id, COUNT(*) AS cnt
          FROM file_instances
          WHERE snapshot_id = ?
          GROUP BY file_id
          HAVING COUNT(*) > 1
        ) g
        JOIN files fl ON g.file_id = fl.id
        ORDER BY fl."size" ASC
      """.trimIndent()

      val hasItems = BooleanArray(10)
      val minSizes = LongArray(10) { Long.MAX_VALUE }
      val maxSizes = LongArray(10) { Long.MIN_VALUE }
      val fileCounts = IntArray(10)
      val sizeBytesArr = LongArray(10)
      val duplicatedBytesArr = LongArray(10)
      val wastedBytesArr = LongArray(10)

      var idx = 0L
      conn.prepareStatement(percentileSql).use { stmt ->
        stmt.setInt(1, snapshotId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            val sz = rs.getLong("sz")
            val cnt = rs.getLong("cnt")
            val bucketIdx = minOf(9, ((idx * 10L) / duplicateGroupsCount).toInt())
            hasItems[bucketIdx] = true
            if (sz < minSizes[bucketIdx]) minSizes[bucketIdx] = sz
            if (sz > maxSizes[bucketIdx]) maxSizes[bucketIdx] = sz
            fileCounts[bucketIdx] += cnt.toInt()
            sizeBytesArr[bucketIdx] += sz
            duplicatedBytesArr[bucketIdx] += cnt * sz
            wastedBytesArr[bucketIdx] += (cnt - 1L) * sz
            idx++
          }
        }
      }

      val percentileBuckets = (0..9).map { index ->
        val start = index * 10
        val end = (index + 1) * 10
        val pct = if (wastedBytes > 0L) {
          (wastedBytesArr[index].toDouble() / wastedBytes.toDouble()) * 100.0
        } else {
          0.0
        }
        PercentileBucket(
          bucketIndex = index + 1,
          percentileRange = "$start% - $end%",
          minSize = if (hasItems[index]) minSizes[index] else 0L,
          maxSize = if (hasItems[index]) maxSizes[index] else 0L,
          fileCount = fileCounts[index],
          sizeBytes = sizeBytesArr[index],
          totalDuplicateBytes = duplicatedBytesArr[index],
          wastedBytes = wastedBytesArr[index],
          percentageOfTotalWasted = pct
        )
      }

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

  private fun emptyPercentileBuckets(): List<PercentileBucket> = (0..9).map { index ->
    val start = index * 10
    val end = (index + 1) * 10
    PercentileBucket(
      bucketIndex = index + 1,
      percentileRange = "$start% - $end%",
      minSize = 0L,
      maxSize = 0L,
      fileCount = 0,
      sizeBytes = 0L,
      totalDuplicateBytes = 0L,
      wastedBytes = 0L,
      percentageOfTotalWasted = 0.0
    )
  }

  /**
   * Formats the 10% percentile decile buckets into an aligned table rendered by Mordant.
   * Includes a column for total file size in each decile, and a footer row showing totals for
   * Files, Size, Duplicated Storage, Wasted Storage, and Share.
   */
  fun formatPercentileTable(stats: DuplicateStatistics, terminal: Terminal = Terminal()): String {
    val totalFiles = stats.percentileBuckets.sumOf { it.fileCount.toLong() }
    val totalSize = stats.percentileBuckets.sumOf { it.sizeBytes }
    val totalDuplicated = stats.percentileBuckets.sumOf { it.totalDuplicateBytes }
    val totalWasted = stats.percentileBuckets.sumOf { it.wastedBytes }
    val totalShare = stats.percentileBuckets.sumOf { it.percentageOfTotalWasted }

    val activeBuckets = stats.percentileBuckets.filter { it.fileCount > 0 }
    val overallRange = if (activeBuckets.isNotEmpty()) {
      "[${formatBytesShort(
        activeBuckets.minOf { it.minSize }
      )} - ${formatBytesShort(activeBuckets.maxOf { it.maxSize })}]"
    } else {
      "-"
    }

    val mordantTable = table {
      borderType = BorderType.SQUARE_DOUBLE_SECTION_SEPARATOR
      header {
        style(bold = true)
        row(
          "Percentile",
          "Size Range",
          "Files",
          "Size",
          "Duplicated Storage",
          "Wasted Storage",
          "Share (%)"
        )
      }
      body {
        for (bucket in stats.percentileBuckets) {
          val rangeStr = "[${formatBytesShort(
            bucket.minSize
          )} - ${formatBytesShort(bucket.maxSize)}]"
          row(
            bucket.percentileRange,
            rangeStr,
            String.format(Locale.US, "%,d", bucket.fileCount),
            formatBytesShort(bucket.sizeBytes),
            formatBytesShort(bucket.totalDuplicateBytes),
            formatBytesShort(bucket.wastedBytes),
            String.format(Locale.US, "%.2f%%", bucket.percentageOfTotalWasted)
          )
        }
      }
      footer {
        style(bold = true)
        row(
          "Total",
          overallRange,
          String.format(Locale.US, "%,d", totalFiles),
          formatBytesShort(totalSize),
          formatBytesShort(totalDuplicated),
          formatBytesShort(totalWasted),
          String.format(Locale.US, "%.2f%%", totalShare)
        )
      }
      column(0) { align = TextAlign.LEFT }
      column(1) { align = TextAlign.CENTER }
      column(2) { align = TextAlign.RIGHT }
      column(3) { align = TextAlign.RIGHT }
      column(4) { align = TextAlign.RIGHT }
      column(5) { align = TextAlign.RIGHT }
      column(6) { align = TextAlign.RIGHT }
    }
    val naturalWidth = mordantTable.measure(terminal, 1000).max
    val renderTerminal = if (terminal.size.width < naturalWidth) {
      Terminal(
        ansiLevel = terminal.terminalInfo.ansiLevel,
        theme = terminal.theme,
        width = naturalWidth,
        hyperlinks = terminal.terminalInfo.ansiHyperLinks,
        tabWidth = terminal.tabWidth
      )
    } else {
      terminal
    }
    return renderTerminal.render(mordantTable)
  }

  /**
   * Formats a list of [SnapshotSummary] items into an aligned table rendered by Mordant.
   */
  fun formatSnapshotsTable(
    snapshots: List<SnapshotSummary>,
    terminal: Terminal = Terminal()
  ): String {
    val mordantTable = table {
      borderType = BorderType.SQUARE_DOUBLE_SECTION_SEPARATOR
      header {
        style(bold = true)
        row("ID", "Root Path", "Files", "Total Size", "Status")
      }
      body {
        for (snapshot in snapshots) {
          val statusStr = if (snapshot.isCompleted) "Completed" else "In Progress"
          row(
            snapshot.id.toString(),
            snapshot.rootPath,
            String.format(Locale.US, "%,d", snapshot.fileCount),
            formatBytesShort(snapshot.totalBytes),
            statusStr
          )
        }
      }
      column(0) { align = TextAlign.RIGHT }
      column(1) { align = TextAlign.LEFT }
      column(2) { align = TextAlign.RIGHT }
      column(3) { align = TextAlign.RIGHT }
      column(4) { align = TextAlign.CENTER }
    }
    val naturalWidth = mordantTable.measure(terminal, 1000).max
    val renderTerminal = if (terminal.size.width < naturalWidth) {
      Terminal(
        ansiLevel = terminal.terminalInfo.ansiLevel,
        theme = terminal.theme,
        width = naturalWidth,
        hyperlinks = terminal.terminalInfo.ansiHyperLinks,
        tabWidth = terminal.tabWidth
      )
    } else {
      terminal
    }
    return renderTerminal.render(mordantTable)
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
    out(
      "  Total Files Scanned:       ${String.format(
        Locale.US,
        "%,d",
        stats.totalFilesScanned
      )} files"
    )
    out("  Total Storage Scanned:     ${formatBytes(stats.totalScannedBytes)}")
    out(
      "  Unique File Contents:      ${String.format(
        Locale.US,
        "%,d",
        stats.distinctFiles
      )} files"
    )
    out("  Unique Storage:            ${formatBytes(stats.distinctBytes)}")
    out("  Total Processing Time:     ${formatDuration(stats.processingDuration)}")
    out("")
    out("Duplication Overview:")
    out(
      "  Duplicate Content Groups:  ${String.format(
        Locale.US,
        "%,d",
        stats.duplicateGroupsCount
      )} unique files duplicated"
    )
    val redundantCopies = if (stats.duplicateFileCount > stats.duplicateGroupsCount) {
      stats.duplicateFileCount - stats.duplicateGroupsCount
    } else {
      0L
    }
    out(
      "  Duplicate File Instances:  ${String.format(
        Locale.US,
        "%,d",
        stats.duplicateFileCount
      )} files ($redundantCopies redundant copies)"
    )
    out("  Total Duplicated Storage:  ${formatBytes(stats.totalDuplicateBytes)}")
    out("  Wasted Storage:            ${formatBytes(stats.wastedBytes)}")
    out(
      "  Wasted Storage Share:      ${String.format(
        Locale.US,
        "%.2f%%",
        stats.wastedPercentage
      )} of total scanned storage"
    )
    out("")
    out(sep)
    out("Top 10 Largest Duplicate Files:")
    out(sep)
    if (stats.largestDuplicates.isEmpty()) {
      out("  No duplicate files found.")
    } else {
      stats.largestDuplicates.forEachIndexed { idx, group ->
        val num = (idx + 1).toString().padStart(2)
        out(
          "$num. ${formatBytes(group.size)} | ${group.copyCount} copies | " +
            "Combined: ${formatBytes(group.totalDuplicateBytes)} | " +
            "Wasted: ${formatBytes(group.wastedBytes)}"
        )
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
    formatPercentileTable(stats).lines().forEach { out(it) }
    out(sep)
    out("Total Wasted Storage: ${formatBytes(stats.wastedBytes)}")
    out(line)
    out("")
  }

  /**
   * Identifies top folder pairs sharing the most duplicate files within [snapshotId].
   *
   * @param snapshotId The snapshot ID to inspect.
   * @param limit The maximum number of top folder pairs to return.
   */
  fun findTopDuplicateFolderPairs(snapshotId: Int, limit: Int = 10): List<FolderDuplicatePair> =
    transaction(database) {
      val snapshot = RepoSnapshot.findById(snapshotId) ?: return@transaction emptyList()
      if (!snapshot.isCompleted) {
        updateFolderStatistics(snapshotId)
      }

      data class CandidatePair(
        val f1Id: Int,
        val f2Id: Int,
        val sameFiles: Long,
        val duplicatedStorage: Long
      )

      val candidateLimit = maxOf(limit * 3, 50)
      val candidates = mutableListOf<CandidatePair>()

      val candidateSql = """
        WITH file_folder_counts AS (
          SELECT file_id, COUNT(DISTINCT folder_id) AS folder_count
          FROM file_instances
          WHERE snapshot_id = $snapshotId
          GROUP BY file_id
          HAVING COUNT(DISTINCT folder_id) > 1
        ),
        folder_files AS (
          SELECT DISTINCT fi.folder_id, fi.file_id, fl."size"
          FROM file_instances fi
          JOIN files fl ON fi.file_id = fl.id
          JOIN file_folder_counts ffc ON fi.file_id = ffc.file_id
          WHERE fi.snapshot_id = $snapshotId
            AND (fl."size" > 0 OR ffc.folder_count <= 100)
            AND ffc.folder_count <= 500
        )
        SELECT
          ff1.folder_id AS f1_id,
          ff2.folder_id AS f2_id,
          COUNT(*) AS same_files,
          SUM(ff1."size") AS duplicated_storage
        FROM folder_files ff1
        JOIN folder_files ff2
          ON ff1.file_id = ff2.file_id
         AND ff1.folder_id < ff2.folder_id
        GROUP BY ff1.folder_id, ff2.folder_id
        ORDER BY same_files DESC, duplicated_storage DESC
        LIMIT $candidateLimit
      """.trimIndent()

      val conn = (TransactionManager.current().connection.connection as Connection)
      conn.prepareStatement(candidateSql).use { stmt ->
        val rs = stmt.executeQuery()
        while (rs.next()) {
          candidates.add(
            CandidatePair(
              f1Id = rs.getInt("f1_id"),
              f2Id = rs.getInt("f2_id"),
              sameFiles = rs.getLong("same_files"),
              duplicatedStorage = rs.getLong("duplicated_storage")
            )
          )
        }
      }

      if (candidates.isEmpty()) return@transaction emptyList()

      val candidateFolderIds = candidates.flatMap { listOf(it.f1Id, it.f2Id) }.distinct()
      data class FolderMeta(val path: String, val fileCount: Long, val totalBytes: Long)
      val folderMetaMap = RepoFolders.select(
        RepoFolders.id,
        RepoFolders.path,
        RepoFolders.fileCount,
        RepoFolders.totalBytes
      ).where { RepoFolders.id inList candidateFolderIds.map { EntityID(it, RepoFolders) } }
        .associate {
          it[RepoFolders.id].value to FolderMeta(
            path = it[RepoFolders.path],
            fileCount = it[RepoFolders.fileCount].toLong(),
            totalBytes = it[RepoFolders.totalBytes]
          )
        }

      data class PairCandidateWrapper(
        val actualF1Id: Int,
        val actualF2Id: Int,
        val folder1: String,
        val folder2: String,
        val sameFiles: Long,
        val differentFiles: Long,
        val totalFiles: Long,
        val duplicatedStorage: Long,
        val differentStorage: Long,
        val totalStorage: Long,
        val duplicateFilesPercentage: Double,
        val duplicateStoragePercentage: Double
      )

      val evaluatedPairs = candidates.mapNotNull { candidate ->
        val meta1 = folderMetaMap[candidate.f1Id] ?: return@mapNotNull null
        val meta2 = folderMetaMap[candidate.f2Id] ?: return@mapNotNull null

        val (folder1, folder2, f1Count, f2Count, f1Bytes, f2Bytes, actualF1Id, actualF2Id) =
          if (meta1.path <= meta2.path) {
            FolderPairOrder(
              meta1.path,
              meta2.path,
              meta1.fileCount,
              meta2.fileCount,
              meta1.totalBytes,
              meta2.totalBytes,
              candidate.f1Id,
              candidate.f2Id
            )
          } else {
            FolderPairOrder(
              meta2.path,
              meta1.path,
              meta2.fileCount,
              meta1.fileCount,
              meta2.totalBytes,
              meta1.totalBytes,
              candidate.f2Id,
              candidate.f1Id
            )
          }

        val sameFiles = candidate.sameFiles
        val differentFiles = maxOf(0L, (f1Count - sameFiles) + (f2Count - sameFiles))
        val totalFiles = sameFiles + differentFiles

        val duplicatedStorage = candidate.duplicatedStorage
        val differentStorage =
          maxOf(0L, (f1Bytes - duplicatedStorage) + (f2Bytes - duplicatedStorage))
        val totalStorage = duplicatedStorage + differentStorage

        val duplicateFilesPercentage = if (totalFiles > 0L) {
          (sameFiles.toDouble() / totalFiles * 100.0)
        } else {
          0.0
        }

        val duplicateStoragePercentage = if (totalStorage > 0L) {
          (duplicatedStorage.toDouble() / totalStorage * 100.0)
        } else {
          0.0
        }

        PairCandidateWrapper(
          actualF1Id = actualF1Id,
          actualF2Id = actualF2Id,
          folder1 = folder1,
          folder2 = folder2,
          sameFiles = sameFiles,
          differentFiles = differentFiles,
          totalFiles = totalFiles,
          duplicatedStorage = duplicatedStorage,
          differentStorage = differentStorage,
          totalStorage = totalStorage,
          duplicateFilesPercentage = duplicateFilesPercentage,
          duplicateStoragePercentage = duplicateStoragePercentage
        )
      }

      val topPairs = evaluatedPairs.sortedWith(
        compareByDescending<PairCandidateWrapper> { it.sameFiles }
          .thenByDescending { it.duplicatedStorage }
          .thenBy { it.folder1 }
          .thenBy { it.folder2 }
      ).take(limit)

      topPairs.map { pair ->
        val top3FilesSql = """
          SELECT fl.id, fl."size", fl.hash
          FROM file_instances fi1
          JOIN file_instances fi2 ON fi1.file_id = fi2.file_id
          JOIN files fl ON fi1.file_id = fl.id
          WHERE fi1.snapshot_id = ?
            AND fi1.folder_id = ?
            AND fi2.snapshot_id = ?
            AND fi2.folder_id = ?
          GROUP BY fl.id, fl."size", fl.hash
          ORDER BY fl."size" DESC, fl.hash ASC
          LIMIT 3
        """.trimIndent()

        data class TopFile(val fileId: Int, val size: Long, val hash: String)
        val topFiles = mutableListOf<TopFile>()
        conn.prepareStatement(top3FilesSql).use { stmt ->
          stmt.setInt(1, snapshotId)
          stmt.setInt(2, pair.actualF1Id)
          stmt.setInt(3, snapshotId)
          stmt.setInt(4, pair.actualF2Id)
          val rs = stmt.executeQuery()
          while (rs.next()) {
            topFiles.add(
              TopFile(
                fileId = rs.getInt("id"),
                size = rs.getLong("size"),
                hash = rs.getString("hash")
              )
            )
          }
        }

        val topDuplicateFiles = if (topFiles.isEmpty()) {
          emptyList()
        } else {
          val fileIds = topFiles.map { it.fileId }
          val pathsInF1 = mutableMapOf<Int, MutableList<String>>()
          val pathsInF2 = mutableMapOf<Int, MutableList<String>>()

          RepoFileInstances.select(
            RepoFileInstances.folder,
            RepoFileInstances.file,
            RepoFileInstances.path
          ).where {
            (RepoFileInstances.snapshot eq snapshotId) and
              (
                RepoFileInstances.folder inList listOf(
                  EntityID(pair.actualF1Id, RepoFolders),
                  EntityID(pair.actualF2Id, RepoFolders)
                )
                ) and
              (RepoFileInstances.file inList fileIds.map { EntityID(it, RepoFiles) })
          }.forEach { row ->
            val folderId = row[RepoFileInstances.folder].value
            val fileId = row[RepoFileInstances.file].value
            val path = row[RepoFileInstances.path]
            if (folderId == pair.actualF1Id) {
              pathsInF1.getOrPut(fileId) { mutableListOf() }.add(path)
            } else {
              pathsInF2.getOrPut(fileId) { mutableListOf() }.add(path)
            }
          }

          topFiles.mapNotNull { tf ->
            val f1Paths = pathsInF1[tf.fileId] ?: emptyList()
            val f2Paths = pathsInF2[tf.fileId] ?: emptyList()
            if (f1Paths.isEmpty() || f2Paths.isEmpty()) return@mapNotNull null
            val p1 = f1Paths.first()
            val p2 = f2Paths.first()
            val name1 = Path.of(p1).fileName?.toString() ?: p1
            val name2 = Path.of(p2).fileName?.toString() ?: p2
            val displayName = if (name1 == name2) name1 else "$name1 / $name2"

            DuplicateFileEntry(
              fileName = displayName,
              size = tf.size,
              hash = tf.hash,
              path1 = p1,
              path2 = p2,
              pathsInFolder1 = f1Paths,
              pathsInFolder2 = f2Paths
            )
          }
        }

        FolderDuplicatePair(
          folder1 = pair.folder1,
          folder2 = pair.folder2,
          sameFiles = pair.sameFiles,
          differentFiles = pair.differentFiles,
          totalFiles = pair.totalFiles,
          duplicatedStorage = pair.duplicatedStorage,
          differentStorage = pair.differentStorage,
          totalStorage = pair.totalStorage,
          duplicateFilesPercentage = pair.duplicateFilesPercentage,
          duplicateStoragePercentage = pair.duplicateStoragePercentage,
          topDuplicateFiles = topDuplicateFiles
        )
      }
    }

  /**
   * Prints the duplicate folder pair statistics to the specified [out] printer.
   */
  fun printFolderDuplicatePairs(
    pairs: List<FolderDuplicatePair>,
    out: (String) -> Unit = ::println
  ) {
    val line = "=".repeat(80)
    val sep = "-".repeat(80)

    out("")
    out(line)
    out("                       TOP DUPLICATE FOLDER PAIRS")
    out(line)

    if (pairs.isEmpty()) {
      out("No duplicate files found between folders.")
      out(line)
      out("")
      return
    }

    out("Found ${pairs.size} folder pair(s) with duplicate files:")
    out("")

    pairs.forEachIndexed { index, pair ->
      val num = (index + 1).toString().padStart(2)
      out("$num. ${pair.folder1} <-> ${pair.folder2}")
      out("  Same Files:                 ${String.format(Locale.US, "%,d", pair.sameFiles)} files")
      out(
        "  Different Files:            ${String.format(
          Locale.US,
          "%,d",
          pair.differentFiles
        )} files"
      )
      out("  Total Duplicated Storage:   ${formatBytes(pair.duplicatedStorage)}")
      out(
        "  Duplicate Files Share:      ${String.format(
          Locale.US,
          "%.2f%%",
          pair.duplicateFilesPercentage
        )}"
      )
      out(
        "  Duplicate Storage Share:    ${String.format(
          Locale.US,
          "%.2f%%",
          pair.duplicateStoragePercentage
        )}"
      )

      if (pair.topDuplicateFiles.isNotEmpty()) {
        val topCount = pair.topDuplicateFiles.size
        out("  Top $topCount Largest Duplicate File(s):")
        pair.topDuplicateFiles.forEachIndexed { fIdx, file ->
          out("    ${fIdx + 1}. ${file.fileName} (${formatBytes(file.size)})")
          out("       Folder 1: ${file.path1}")
          out("       Folder 2: ${file.path2}")
        }
      }

      if (index < pairs.lastIndex) {
        out(sep)
      }
    }
    out(line)
    out("")
  }
}

/**
 * Representation of an already recorded file instance inside a snapshot.
 */
data class SnapshotFileEntry(
  val instanceId: EntityID<Int>,
  val path: String,
  val size: Long,
  val timestamp: Long?,
  val hash: String,
  val fileId: EntityID<Int>
)

private data class FolderPairOrder(
  val folder1: String,
  val folder2: String,
  val f1Count: Long,
  val f2Count: Long,
  val f1Bytes: Long,
  val f2Bytes: Long,
  val actualF1Id: Int,
  val actualF2Id: Int
)
