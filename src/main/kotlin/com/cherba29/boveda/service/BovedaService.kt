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
import com.cherba29.boveda.model.DuplicateFolderGroup
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.DuplicateStatistics
import com.cherba29.boveda.model.FileDeletionResult
import com.cherba29.boveda.model.FileRecord
import com.cherba29.boveda.model.FileRecordWithDuplicateRank
import com.cherba29.boveda.model.FolderDeletionResult
import com.cherba29.boveda.model.FolderDuplicatePair
import com.cherba29.boveda.model.PercentileBucket
import com.cherba29.boveda.model.SnapshotSummary
import com.cherba29.boveda.model.SymlinkEntry
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatBytesShort
import com.cherba29.boveda.model.formatDisplayPath
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
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
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
   * True when [cache] is known to hold every row currently present in [RepoFiles] (e.g. after
   * warming up a fresh or small DB without hitting [InMemoryDeduplicationCache.maxSize]).
   * Allows [recordBatch] to skip redundant `SELECT ... WHERE hash IN (...)` DB index probes.
   */
  @Volatile
  internal var cacheMirrorsAllDbFiles: Boolean = false

  /** Diagnostic counter tracking how many DB hash lookup queries were executed in [recordBatch]. */
  internal var dbHashLookupCount: Long = 0L
    private set

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
        this.isSymlink = scannedFile.isSymlink
        this.symlinkTarget = scannedFile.symlinkTarget
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
          this[RepoFileInstances.isSymlink] = file.isSymlink
          this[RepoFileInstances.symlinkTarget] = file.symlinkTarget
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

        // Skip DB hash lookup when cache is guaranteed to mirror every row in RepoFiles with zero
        // evictions (e.g. scanning into a fresh database before hitting the 250k LRU cap).
        val canSkipDbHashLookup = cacheMirrorsAllDbFiles && !cache.hasEvictions()
        if (!canSkipDbHashLookup) {
          val missingHashes = uniqueMissing.map { it.hash }.distinct()
          for (chunk in missingHashes.chunked(500)) {
            dbHashLookupCount++
            RepoFiles.select(RepoFiles.id, RepoFiles.size, RepoFiles.hash)
              .where { RepoFiles.hash inList chunk }
              .forEach { row ->
                val key = FileContentKey(row[RepoFiles.size], row[RepoFiles.hash])
                val entityId = row[RepoFiles.id]
                batchFileIds[key] = entityId
                cache.put(key.size, key.hash, entityId)
              }
          }
        }

        // Brand new files not in cache or DB
        val brandNewFiles = if (canSkipDbHashLookup) {
          uniqueMissing
        } else {
          uniqueMissing.filter {
            FileContentKey(it.size, it.hash) !in batchFileIds
          }
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
        if (cache.hasEvictions()) {
          cacheMirrorsAllDbFiles = false
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
        this[RepoFileInstances.isSymlink] = file.isSymlink
        this[RepoFileInstances.symlinkTarget] = file.symlinkTarget
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
            AND fi.is_symlink = FALSE
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
    cacheMirrorsAllDbFiles = false
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
   * Counts the number of non-symlink file instances in [snapshotId] referencing [fileId].
   */
  fun countFileDuplicates(snapshotId: Int, fileId: EntityID<Int>): Long = transaction(database) {
    RepoFileInstances.select(RepoFileInstances.id)
      .where {
        (RepoFileInstances.snapshot eq snapshotId) and
          (RepoFileInstances.file eq fileId) and
          (RepoFileInstances.isSymlink eq false)
      }
      .count()
  }

  /**
   * Finds all symlinks in [snapshotId] pointing to any path in [targetPaths].
   */
  private fun findSymlinksPointingTo(
    snapshotId: Int,
    targetPaths: Collection<String>
  ): List<SymlinkEntry> {
    if (targetPaths.isEmpty()) return emptyList()
    val results = mutableListOf<SymlinkEntry>()
    for (chunk in targetPaths.distinct().chunked(500)) {
      RepoFileInstances.select(RepoFileInstances.path, RepoFileInstances.symlinkTarget)
        .where {
          (RepoFileInstances.snapshot eq snapshotId) and
            (RepoFileInstances.isSymlink eq true) and
            (RepoFileInstances.symlinkTarget inList chunk)
        }
        .forEach { row ->
          val target = row[RepoFileInstances.symlinkTarget] ?: return@forEach
          results.add(SymlinkEntry(path = row[RepoFileInstances.path], target = target))
        }
    }
    return results.distinctBy { it.path }.sortedBy { it.path }
  }

  /**
   * Finds all symlinks in [snapshotId] either pointing to any folder in [folderPaths]
   * or residing inside any folder in [folderIds].
   */
  private fun findSymlinksForFolders(
    snapshotId: Int,
    folderIds: List<Int>,
    folderPaths: List<String>
  ): List<SymlinkEntry> {
    val byPath = linkedMapOf<String, SymlinkEntry>()

    for (entry in findSymlinksPointingTo(snapshotId, folderPaths)) {
      byPath[entry.path] = entry
    }

    if (folderIds.isNotEmpty()) {
      for (chunk in folderIds.distinct().chunked(500)) {
        RepoFileInstances.select(RepoFileInstances.path, RepoFileInstances.symlinkTarget)
          .where {
            (RepoFileInstances.snapshot eq snapshotId) and
              (RepoFileInstances.isSymlink eq true) and
              (RepoFileInstances.folder inList chunk.map { EntityID(it, RepoFolders) })
          }
          .forEach { row ->
            val p = row[RepoFileInstances.path]
            val target = row[RepoFileInstances.symlinkTarget] ?: ""
            byPath.putIfAbsent(p, SymlinkEntry(path = p, target = target))
          }
      }
    }

    return byPath.values.sortedBy { it.path }
  }

  private fun clearReadOnlyAttributes(path: Path) {
    try {
      val dosView = Files.getFileAttributeView(
        path,
        DosFileAttributeView::class.java,
        LinkOption.NOFOLLOW_LINKS
      )
      if (dosView != null) {
        val attrs = dosView.readAttributes()
        if (attrs.isReadOnly) {
          dosView.setReadOnly(false)
        }
        if (attrs.isSystem) {
          dosView.setSystem(false)
        }
      }
    } catch (_: Exception) {
      // Ignore on filesystems without DOS attributes
    }
    try {
      if (!Files.isSymbolicLink(path)) {
        path.toFile().setWritable(true)
      }
    } catch (_: Exception) {
      // Best-effort fallback
    }
  }

  private fun describeRemainingEntries(dir: Path): String {
    return try {
      val names = Files.list(dir).use { stream ->
        stream.limit(4).map { it.fileName?.toString() ?: it.toString() }.toList()
      }
      if (names.isEmpty()) {
        "directory not empty"
      } else {
        val shown = names.take(3).joinToString(", ")
        val more = if (names.size > 3) ", ..." else ""
        "directory not empty (remaining: $shown$more)"
      }
    } catch (_: Exception) {
      "directory not empty"
    }
  }

  /**
   * Deletes [path] (file, symlink, or empty directory) safely without following symlinks.
   * Clears Windows/DOS read-only/system attributes and retries briefly on transient OS locks
   * (e.g., Windows Explorer, Defender, or Search Indexer holding a handle after bulk deletions).
   */
  private fun deletePathWithRetry(path: Path, maxAttempts: Int = 4): Pair<Boolean, String?> {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return false to null
    }
    var lastError: String? = null
    for (attempt in 1..maxAttempts) {
      try {
        return Files.deleteIfExists(path) to null
      } catch (_: DirectoryNotEmptyException) {
        return false to describeRemainingEntries(path)
      } catch (e: IOException) {
        lastError = e.message ?: e::class.simpleName
        clearReadOnlyAttributes(path)
        if (attempt < maxAttempts) {
          try {
            Thread.sleep(50L * attempt)
          } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
          }
        }
      } catch (e: Exception) {
        return false to (e.message ?: e::class.simpleName)
      }
    }
    return false to lastError
  }

  private fun cleanupOrphanedFiles(fileIds: Collection<EntityID<Int>>) {
    if (fileIds.isEmpty()) return
    for (chunk in fileIds.distinct().chunked(500)) {
      val stillReferenced = RepoFileInstances.select(RepoFileInstances.file)
        .where { RepoFileInstances.file inList chunk }
        .withDistinct()
        .map { it[RepoFileInstances.file] }
        .toSet()
      val orphaned = chunk.filter { it !in stillReferenced }
      if (orphaned.isNotEmpty()) {
        RepoFiles.deleteWhere { RepoFiles.id inList orphaned }
      }
    }
  }

  /**
   * Removes a file or symlink instance from the database and disk within [snapshotId].
   * For regular files with no duplicates, deletion fails unless [force] is true.
   * For symlinks, deletes only the symlink itself (never the target file or folder).
   */
  fun deleteFile(snapshotId: Int, path: Path, force: Boolean = false): FileDeletionResult =
    transaction(database) {
      val instance = findFileInstance(snapshotId, path)
        ?: throw IllegalArgumentException("File not found in database: $path")

      val fileEntityId = instance.file.id
      val isSymlink = instance.isSymlink
      val hasDuplicates = if (isSymlink) {
        false
      } else {
        countFileDuplicates(snapshotId, fileEntityId) > 1
      }

      if (!isSymlink && !hasDuplicates && !force) {
        throw IllegalStateException(
          "File does not have duplicates: $path. Use --force to delete anyway."
        )
      }

      val pathStr = instance.path
      val diskPath = Path.of(pathStr)
      val actualDiskPath = if (Files.exists(diskPath, LinkOption.NOFOLLOW_LINKS)) {
        diskPath
      } else {
        path
      }
      val (deletedFromDisk, _) = deletePathWithRetry(actualDiskPath)

      instance.delete()
      cleanupOrphanedFiles(listOf(fileEntityId))

      FileDeletionResult(
        path = pathStr,
        deletedFromDisk = deletedFromDisk,
        deletedFromDb = true,
        hasDuplicates = hasDuplicates,
        isSymlink = isSymlink
      )
    }

  /**
   * Deletes multiple duplicate file or symlink instances by path from disk and database in
   * [snapshotId]. For symlinks, deletes only the symlink itself without affecting its target.
   */
  fun deleteDuplicateFiles(
    snapshotId: Int,
    paths: List<String>,
    onProgress: ((String) -> Unit)? = null
  ): List<FileDeletionResult> = transaction(database) {
    val fileIdsToCheck = mutableSetOf<EntityID<Int>>()
    val results = paths.mapIndexed { idx, pathStr ->
      onProgress?.invoke("Deleting file ${idx + 1}/${paths.size}: ${formatDisplayPath(pathStr)}")
      val (deletedFromDisk, _) = deletePathWithRetry(Path.of(pathStr))

      val instance = RepoFileInstance.find {
        (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.path eq pathStr)
      }.firstOrNull()

      val isSymlink = instance?.isSymlink ?: false
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
        hasDuplicates = !isSymlink,
        isSymlink = isSymlink
      )
    }

    onProgress?.invoke("Cleaning up orphaned database records...")
    cleanupOrphanedFiles(fileIdsToCheck)

    results
  }

  /**
   * Retrieves all groups of duplicate non-symlink file instances in [snapshotId] sharing content
   * hash and size, along with any symlinks pointing to those duplicate files.
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
          AND is_symlink = FALSE
        GROUP BY file_id
        HAVING COUNT(*) > 1
      ) d ON fi.file_id = d.file_id
      WHERE fi.snapshot_id = ?
        AND fi.is_symlink = FALSE
      ORDER BY fi.file_id
    """.trimIndent()

    val rawGroups = mutableListOf<DuplicateGroup>()
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
              rawGroups.add(
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
          rawGroups.add(
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

    rawGroups.map { group ->
      val symlinks = findSymlinksPointingTo(snapshotId, group.paths)
      if (symlinks.isEmpty()) group else group.copy(symlinkPaths = symlinks)
    }
  }

  /**
   * Finds the duplicate group in [snapshotId] where most storage is wasted (excluding symlinks
   * from duplicate counting), attaching any symlinks pointing to files in the group.
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
          AND is_symlink = FALSE
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
          (RepoFileInstances.file eq EntityID(fileId, RepoFiles)) and
          (RepoFileInstances.isSymlink eq false)
      }
      .map { it[RepoFileInstances.path] }

    val symlinks = findSymlinksPointingTo(snapshotId, paths)

    DuplicateGroup(
      hash = chosenHash,
      size = chosenSize,
      copyCount = chosenCount,
      paths = paths,
      symlinkPaths = symlinks
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
        RepoFileInstances.isSymlink,
        RepoFileInstances.symlinkTarget,
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
            fileId = row[RepoFiles.id],
            isSymlink = row[RepoFileInstances.isSymlink],
            symlinkTarget = row[RepoFileInstances.symlinkTarget]
          )
        }
    }

  /**
   * Checks whether a discovered file matches an already recorded snapshot entry in path, name,
   * timestamp, size, and symlink metadata without content modifications.
   */
  fun isUnchanged(discovered: DiscoveredFile, existing: SnapshotFileEntry): Boolean {
    val samePath = existing.path == discovered.path.invariantSeparatorsPathString
    val sameName = Path.of(existing.path).fileName?.toString() ==
      discovered.path.fileName?.toString()
    val sameSize = existing.size == discovered.size
    val sameSymlink = existing.isSymlink == discovered.isSymlink &&
      existing.symlinkTarget == discovered.symlinkTarget
    val sameTimestamp = if (existing.timestamp == null) {
      discovered.lastModifiedTime == null
    } else {
      discovered.lastModifiedTime != null &&
        existing.timestamp == discovered.lastModifiedTime.toMillis()
    }
    return samePath && sameName && sameSize && sameSymlink && sameTimestamp
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
    if (deduplicateFiles) {
      if (warmUpCache) {
        onPhase?.invoke(ScanPhase.WARMING_CACHE)
        val loaded = cache.warmUp(database)
        cacheMirrorsAllDbFiles = cache is InMemoryDeduplicationCache &&
          !cache.hasEvictions() &&
          loaded.toLong() < cache.maxSize
      } else {
        val dbFilesEmpty = transaction(database) {
          RepoFiles.select(RepoFiles.id).limit(1).empty()
        }
        cacheMirrorsAllDbFiles = dbFilesEmpty && !cache.hasEvictions()
      }
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
            AND fi2.is_symlink = FALSE
          GROUP BY fl2.hash
          HAVING COUNT(*) > 1
        ) dh ON fl.hash = dh.hash
        WHERE fi.snapshot_id = ?
          AND fi.is_symlink = FALSE
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
          WHERE fi2.is_symlink = FALSE
          GROUP BY fl2.hash
          HAVING COUNT(*) > 1
        ) dh ON fl.hash = dh.hash
        WHERE fi.is_symlink = FALSE
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
   * Streams symlinks in [snapshotId] pointing to any duplicate file, invoking [onRecord] for each.
   */
  fun forEachSymlinkToDuplicates(snapshotId: Int, onRecord: (FileRecord) -> Unit): Long =
    transaction(database) {
      val conn = TransactionManager.current().connection.connection as Connection
      val sql = """
        SELECT
          s.snapshot_id,
          s."path" AS sym_path,
          fl_sym."size" AS sz,
          fl_sym.hash,
          s.symlink_target
        FROM file_instances s
        JOIN files fl_sym ON s.file_id = fl_sym.id
        JOIN file_instances target_fi
          ON target_fi.snapshot_id = s.snapshot_id
         AND target_fi.is_symlink = FALSE
         AND target_fi."path" = s.symlink_target
        JOIN files fl_target ON target_fi.file_id = fl_target.id
        JOIN (
          SELECT fl2.hash
          FROM file_instances fi2
          JOIN files fl2 ON fi2.file_id = fl2.id
          WHERE fi2.snapshot_id = ?
            AND fi2.is_symlink = FALSE
          GROUP BY fl2.hash
          HAVING COUNT(*) > 1
        ) dh ON fl_target.hash = dh.hash
        WHERE s.snapshot_id = ?
          AND s.is_symlink = TRUE
        ORDER BY s."path"
      """.trimIndent()

      var count = 0L
      conn.prepareStatement(sql).use { stmt ->
        stmt.setInt(1, snapshotId)
        stmt.setInt(2, snapshotId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            onRecord(
              FileRecord(
                snapshotId = rs.getInt("snapshot_id"),
                path = rs.getString("sym_path"),
                size = rs.getLong("sz"),
                hash = rs.getString("hash"),
                isSymlink = true,
                symlinkTarget = rs.getString("symlink_target")
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
      val displayPath = formatDisplayPath(record.path)
      out(
        "snapshot=${record.snapshotId} size=${record.size} path=$displayPath hash=${record.hash}"
      )
    }

    queryFilesWithDuplicateRank(snapshotId).forEach { record ->
      val displayPath = formatDisplayPath(record.path)
      out(
        "${record.snapshotId} ${record.size} $displayPath ${record.duplicateRank} ${record.hash}"
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

      // 1. Aggregate all overview totals in a single SQL query (returns 1 row).
      //    Symlinks count toward total files & total bytes at their own actual symlink size,
      //    but are never counted as duplicate files or wasted storage.
      val overviewSql = """
        SELECT
          COALESCE(SUM(g.total_cnt), 0) AS total_files,
          COALESCE(SUM(g.total_cnt * fl."size"), 0) AS total_bytes,
          COUNT(*) AS distinct_files,
          COALESCE(SUM(fl."size"), 0) AS distinct_bytes,
          COALESCE(SUM(CASE WHEN g.real_cnt > 1 THEN 1 ELSE 0 END), 0) AS dup_groups,
          COALESCE(SUM(CASE WHEN g.real_cnt > 1 THEN g.real_cnt ELSE 0 END), 0) AS dup_files,
          COALESCE(SUM(CASE WHEN g.real_cnt > 1 THEN (g.real_cnt - 1) * fl."size" ELSE 0 END), 0)
            AS wasted_bytes,
          COALESCE(SUM(CASE WHEN g.real_cnt > 1 THEN g.real_cnt * fl."size" ELSE 0 END), 0)
            AS total_dup_bytes
        FROM (
          SELECT
            file_id,
            COUNT(*) AS total_cnt,
            SUM(CASE WHEN is_symlink = FALSE THEN 1 ELSE 0 END) AS real_cnt
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

      if (totalFilesScanned == 0L) {
        return@transaction DuplicateStatistics(
          totalFilesScanned = 0L,
          totalScannedBytes = 0L,
          distinctFiles = 0L,
          distinctBytes = 0L,
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
      val largest10 = if (duplicateGroupsCount == 0L) {
        emptyList()
      } else {
        data class TopGroupRow(
          val fileId: Int,
          val hash: String,
          val size: Long,
          val copyCount: Int
        )
        val top10Sql = """
          SELECT g.file_id, fl.hash, fl."size" AS sz, g.cnt
          FROM (
            SELECT file_id, COUNT(*) AS cnt
            FROM file_instances
            WHERE snapshot_id = ?
              AND is_symlink = FALSE
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
                (RepoFileInstances.file inList topFileIds) and
                (RepoFileInstances.isSymlink eq false)
            }
            .forEach { row ->
              pathsByFileId.getOrPut(row[RepoFileInstances.file].value) { mutableListOf() }
                .add(row[RepoFileInstances.path])
            }
        }

        topRows.map { row ->
          val groupPaths = pathsByFileId[row.fileId] ?: emptyList()
          val symlinks = findSymlinksPointingTo(snapshotId, groupPaths)
          DuplicateGroup(
            hash = row.hash,
            size = row.size,
            copyCount = row.copyCount,
            paths = groupPaths,
            symlinkPaths = symlinks
          )
        }
      }

      // 3. Stream (size, total_cnt, real_cnt) across ALL scanned entries ordered by size into 10
      //    scalar decile accumulators (O(1) heap). Symlinks contribute their actual size to
      //    fileCount/sizeBytes, and 0 to duplicated/wasted storage.
      val percentileSql = """
        SELECT fl."size" AS sz, g.total_cnt, g.real_cnt
        FROM (
          SELECT
            file_id,
            COUNT(*) AS total_cnt,
            SUM(CASE WHEN is_symlink = FALSE THEN 1 ELSE 0 END) AS real_cnt
          FROM file_instances
          WHERE snapshot_id = ?
          GROUP BY file_id
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

      var globalIdx = 0L
      conn.prepareStatement(percentileSql).use { stmt ->
        stmt.setInt(1, snapshotId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            val sz = rs.getLong("sz")
            val totalCnt = rs.getLong("total_cnt")
            val realCnt = rs.getLong("real_cnt")
            var remainingTotal = totalCnt
            var remainingReal = realCnt
            var isFirstRealCopy = true
            while (remainingTotal > 0L) {
              val bucketIdx = minOf(9, ((globalIdx * 10L) / totalFilesScanned).toInt())
              val bucketEnd = if (bucketIdx == 9) {
                totalFilesScanned
              } else {
                ((bucketIdx + 1L) * totalFilesScanned + 9L) / 10L
              }
              val take = minOf(remainingTotal, (bucketEnd - globalIdx).coerceAtLeast(1L))
              val takeReal = minOf(take, remainingReal)
              hasItems[bucketIdx] = true
              if (sz < minSizes[bucketIdx]) minSizes[bucketIdx] = sz
              if (sz > maxSizes[bucketIdx]) maxSizes[bucketIdx] = sz
              fileCounts[bucketIdx] += take.toInt()
              sizeBytesArr[bucketIdx] += take * sz
              if (realCnt > 1L && takeReal > 0L) {
                duplicatedBytesArr[bucketIdx] += takeReal * sz
                val redundantInSlice = if (isFirstRealCopy) takeReal - 1L else takeReal
                wastedBytesArr[bucketIdx] += redundantInSlice * sz
                isFirstRealCopy = false
              }
              remainingReal -= takeReal
              globalIdx += take
              remainingTotal -= take
            }
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
            formatDisplayPath(snapshot.rootPath),
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
          out("      - ${formatDisplayPath(path)}")
        }
        group.symlinkPaths.forEach { sym ->
          val symPath = formatDisplayPath(sym.path)
          val symTarget = formatDisplayPath(sym.target)
          out("      - $symPath [symlink -> $symTarget]")
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
            AND is_symlink = FALSE
          GROUP BY file_id
          HAVING COUNT(DISTINCT folder_id) > 1
        ),
        folder_files AS (
          SELECT DISTINCT fi.folder_id, fi.file_id, fl."size"
          FROM file_instances fi
          JOIN files fl ON fi.file_id = fl.id
          JOIN file_folder_counts ffc ON fi.file_id = ffc.file_id
          WHERE fi.snapshot_id = $snapshotId
            AND fi.is_symlink = FALSE
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
            AND fi1.is_symlink = FALSE
            AND fi2.snapshot_id = ?
            AND fi2.folder_id = ?
            AND fi2.is_symlink = FALSE
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
              (RepoFileInstances.isSymlink eq false) and
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

        val symlinks = findSymlinksForFolders(
          snapshotId = snapshotId,
          folderIds = listOf(pair.actualF1Id, pair.actualF2Id),
          folderPaths = listOf(pair.folder1, pair.folder2)
        )

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
          topDuplicateFiles = topDuplicateFiles,
          symlinks = symlinks
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
      out("$num. ${formatDisplayPath(pair.folder1)} <-> ${formatDisplayPath(pair.folder2)}")
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
          out("       Folder 1: ${formatDisplayPath(file.path1)}")
          out("       Folder 2: ${formatDisplayPath(file.path2)}")
        }
      }

      if (pair.symlinks.isNotEmpty()) {
        out("  Symlinks:")
        pair.symlinks.forEach { sym ->
          val symPath = formatDisplayPath(sym.path)
          val symTarget = formatDisplayPath(sym.target)
          out("    - $symPath [symlink -> $symTarget]")
        }
      }

      if (index < pairs.lastIndex) {
        out(sep)
      }
    }
    out(line)
    out("")
  }

  internal fun findParentFolderPaths(
    conn: Connection,
    snapshotId: Int,
    candidatePaths: Set<String>
  ): Set<String> {
    if (candidatePaths.isEmpty()) return emptySet()
    val hasChildren = HashSet<String>()
    val sql = """SELECT "path" FROM folders WHERE snapshot_id = ?"""
    conn.prepareStatement(sql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          val p = rs.getString(1)
          var slashIdx = p.lastIndexOf('/')
          while (slashIdx > 0) {
            val ancestor = p.substring(0, slashIdx)
            if (ancestor in candidatePaths) {
              hasChildren.add(ancestor)
            }
            if (slashIdx == 2 && p[1] == ':') {
              val driveRoot = p.substring(0, 3)
              if (driveRoot != p && driveRoot in candidatePaths) {
                hasChildren.add(driveRoot)
              }
            }
            slashIdx = ancestor.lastIndexOf('/')
          }
          if (p.length > 1 && p.startsWith('/') && "/" in candidatePaths) {
            hasChildren.add("/")
          }
        }
      }
    }
    return hasChildren
  }

  private fun computeFolderContentSignature(fileIds: List<Int>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buf = java.nio.ByteBuffer.allocate(Int.SIZE_BYTES)
    buf.putInt(fileIds.size)
    digest.update(buf.array())
    for (id in fileIds) {
      buf.clear()
      buf.putInt(id)
      digest.update(buf.array())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Finds a [RepoFolder] matching [path] within [snapshotId].
   * Handles absolute, relative, raw, or snapshot root-relative path matching.
   */
  fun findFolder(snapshotId: Int, path: Path): RepoFolder? = transaction(database) {
    val absPathStr = path.toAbsolutePath().normalize().invariantSeparatorsPathString
    val relPathStr = path.normalize().invariantSeparatorsPathString
    val rawPathStr = path.invariantSeparatorsPathString

    var folder = RepoFolder.find {
      (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq absPathStr)
    }.firstOrNull()

    if (folder == null) {
      folder = RepoFolder.find {
        (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq relPathStr)
      }.firstOrNull()
    }

    if (folder == null) {
      folder = RepoFolder.find {
        (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq rawPathStr)
      }.firstOrNull()
    }

    if (folder == null) {
      val snapshot = RepoSnapshot.findById(snapshotId)
      if (snapshot != null) {
        val rootResolved = Path.of(snapshot.rootPath).resolve(path)
          .normalize().invariantSeparatorsPathString
        folder = RepoFolder.find {
          (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq rootResolved)
        }.firstOrNull()
      }
    }

    folder
  }

  /**
   * Retrieves all groups of 100% matching folders in [snapshotId] that contain the exact same
   * multiset of files (and have no child subfolders in the snapshot).
   */
  fun getIdenticalFolderGroups(
    snapshotId: Int,
    onProgress: ((String) -> Unit)? = null
  ): List<DuplicateFolderGroup> = transaction(database) {
    val conn = TransactionManager.current().connection.connection as Connection

    data class CandidateFolder(
      val folderId: Int,
      val path: String,
      val fileCount: Int,
      val totalBytes: Long
    )

    onProgress?.invoke("Scanning database for candidate 100% matching folders...")
    val candidateSql = """
      SELECT folder_id, folder_path, inst_count, total_bytes
      FROM (
        SELECT
          fs.folder_id,
          fs.folder_path,
          fs.inst_count,
          fs.total_bytes,
          COUNT(*) OVER (PARTITION BY fs.inst_count, fs.total_bytes) AS bucket_size
        FROM (
          SELECT
            fi.folder_id,
            f."path" AS folder_path,
            COUNT(*) AS inst_count,
            COALESCE(SUM(fl."size"), 0) AS total_bytes
          FROM file_instances fi
          JOIN files fl ON fi.file_id = fl.id
          JOIN folders f ON fi.folder_id = f.id
          WHERE fi.snapshot_id = ?
            AND fi.is_symlink = FALSE
          GROUP BY fi.folder_id, f."path"
          HAVING COUNT(*) > 0
        ) fs
      ) ranked
      WHERE bucket_size > 1
      ORDER BY total_bytes DESC, inst_count DESC, folder_path ASC
    """.trimIndent()

    val candidates = mutableListOf<CandidateFolder>()
    conn.prepareStatement(candidateSql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          candidates.add(
            CandidateFolder(
              folderId = rs.getInt("folder_id"),
              path = rs.getString("folder_path"),
              fileCount = rs.getInt("inst_count"),
              totalBytes = rs.getLong("total_bytes")
            )
          )
        }
      }
    }

    if (candidates.isEmpty()) return@transaction emptyList()

    onProgress?.invoke("Filtering out folders with subfolders (${candidates.size} candidates)...")
    val candidatePathSet = candidates.mapTo(HashSet(candidates.size)) { it.path }
    val parentPaths = findParentFolderPaths(conn, snapshotId, candidatePathSet)
    val leafCandidates = candidates.filter { it.path !in parentPaths }
    if (leafCandidates.size < 2) return@transaction emptyList()

    onProgress?.invoke(
      "Comparing file contents across ${leafCandidates.size} leaf candidate folder(s)..."
    )

    data class RawFolderGroup(
      val signature: String,
      val fileCount: Int,
      val folderBytes: Long,
      val folderIds: List<Int>,
      val paths: List<String>
    )

    val rawGroups = mutableListOf<RawFolderGroup>()
    val byBucket = leafCandidates.groupBy { Pair(it.fileCount, it.totalBytes) }

    val eligibleBuckets = byBucket.filterValues { it.size >= 2 }
    val allEligibleFolders = eligibleBuckets.values.flatten()
    if (allEligibleFolders.isEmpty()) return@transaction emptyList()

    val fileIdsByFolder = HashMap<Int, MutableList<Int>>(allEligibleFolders.size)
    for (chunk in allEligibleFolders.chunked(500)) {
      val placeholders = chunk.joinToString(",") { "?" }
      val filesSql = """
        SELECT folder_id, file_id
        FROM file_instances
        WHERE snapshot_id = ?
          AND is_symlink = FALSE
          AND folder_id IN ($placeholders)
        ORDER BY folder_id, file_id
      """.trimIndent()
      conn.prepareStatement(filesSql).use { stmt ->
        stmt.setInt(1, snapshotId)
        chunk.forEachIndexed { idx, folder -> stmt.setInt(idx + 2, folder.folderId) }
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            val fId = rs.getInt("folder_id")
            val fileId = rs.getInt("file_id")
            fileIdsByFolder.getOrPut(fId) { mutableListOf() }.add(fileId)
          }
        }
      }
    }

    for ((bucketKey, leafFolders) in eligibleBuckets) {
      val bySignature = linkedMapOf<String, MutableList<CandidateFolder>>()
      for (folder in leafFolders) {
        val fileIds = fileIdsByFolder[folder.folderId] ?: continue
        if (fileIds.size != bucketKey.first) continue
        val signature = computeFolderContentSignature(fileIds)
        bySignature.getOrPut(signature) { mutableListOf() }.add(folder)
      }

      for ((signature, matchingFolders) in bySignature) {
        if (matchingFolders.size >= 2) {
          val sorted = matchingFolders.sortedBy { it.path }
          rawGroups.add(
            RawFolderGroup(
              signature = signature,
              fileCount = bucketKey.first,
              folderBytes = bucketKey.second,
              folderIds = sorted.map { it.folderId },
              paths = sorted.map { it.path }
            )
          )
        }
      }
    }

    if (rawGroups.isEmpty()) return@transaction emptyList()

    onProgress?.invoke("Loading associated symlinks for ${rawGroups.size} duplicate group(s)...")
    val allPaths = rawGroups.flatMap { it.paths }
    val allFolderIds = rawGroups.flatMap { it.folderIds }
    val symlinksByTarget = findSymlinksPointingTo(snapshotId, allPaths).groupBy { it.target }
    val symlinksByFolderId = HashMap<Int, MutableList<SymlinkEntry>>()
    for (chunk in allFolderIds.distinct().chunked(500)) {
      RepoFileInstances.select(
        RepoFileInstances.folder,
        RepoFileInstances.path,
        RepoFileInstances.symlinkTarget
      ).where {
        (RepoFileInstances.snapshot eq snapshotId) and
          (RepoFileInstances.isSymlink eq true) and
          (RepoFileInstances.folder inList chunk.map { EntityID(it, RepoFolders) })
      }.forEach { row ->
        val fId = row[RepoFileInstances.folder].value
        val entry = SymlinkEntry(
          path = row[RepoFileInstances.path],
          target = row[RepoFileInstances.symlinkTarget] ?: ""
        )
        symlinksByFolderId.getOrPut(fId) { mutableListOf() }.add(entry)
      }
    }

    rawGroups.map { rg ->
      val groupSymlinks = linkedMapOf<String, SymlinkEntry>()
      for (p in rg.paths) {
        symlinksByTarget[p]?.forEach { groupSymlinks[it.path] = it }
      }
      for (fId in rg.folderIds) {
        symlinksByFolderId[fId]?.forEach { groupSymlinks.putIfAbsent(it.path, it) }
      }
      DuplicateFolderGroup(
        signature = rg.signature,
        fileCount = rg.fileCount,
        folderBytes = rg.folderBytes,
        paths = rg.paths,
        symlinkPaths = groupSymlinks.values.sortedBy { it.path }
      )
    }.sortedWith(
      compareByDescending<DuplicateFolderGroup> { it.wastedBytes }
        .thenByDescending { it.totalDuplicateBytes }
        .thenByDescending { it.folderBytes }
        .thenByDescending { it.fileCount }
        .thenBy { it.paths.first() }
    )
  }

  /**
   * Populates up to 5 largest sample files for [group] within [snapshotId] for display context.
   */
  fun populateFolderGroupSamples(
    snapshotId: Int,
    group: DuplicateFolderGroup
  ): DuplicateFolderGroup = transaction(database) {
    if (group.paths.isEmpty()) return@transaction group
    val sampleFolderPath = group.paths.first()
    val folderEntity = RepoFolder.find {
      (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq sampleFolderPath)
    }.firstOrNull() ?: return@transaction group

    val conn = TransactionManager.current().connection.connection as Connection
    val sampleSql = """
      SELECT fi."path" AS file_path, fl."size" AS sz
      FROM file_instances fi
      JOIN files fl ON fi.file_id = fl.id
      WHERE fi.snapshot_id = ?
        AND fi.folder_id = ?
        AND fi.is_symlink = FALSE
      ORDER BY fl."size" DESC, fi."path" ASC
      LIMIT 5
    """.trimIndent()
    val samples = mutableListOf<Pair<String, Long>>()
    conn.prepareStatement(sampleSql).use { stmt ->
      stmt.setInt(1, snapshotId)
      stmt.setInt(2, folderEntity.id.value)
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          val filePath = rs.getString("file_path")
          val name = Path.of(filePath).fileName?.toString() ?: filePath
          samples.add(name to rs.getLong("sz"))
        }
      }
    }
    group.copy(sampleFiles = samples)
  }

  /**
   * Finds the 100% matching duplicate folder group in [snapshotId] where most storage is wasted,
   * excluding any groups whose signature is in [excludedSignatures].
   */
  fun findLargestIdenticalFolderGroup(
    snapshotId: Int,
    excludedSignatures: Set<String> = emptySet(),
    onProgress: ((String) -> Unit)? = null
  ): DuplicateFolderGroup? = transaction(database) {
    val candidate = getIdenticalFolderGroups(snapshotId, onProgress)
      .firstOrNull { it.signature !in excludedSignatures }
      ?: return@transaction null
    populateFolderGroupSamples(snapshotId, candidate)
  }

  private fun deleteFolderInternal(
    snapshotId: Int,
    folder: RepoFolder,
    hasMatchingDuplicate: Boolean,
    fallbackDiskPath: Path? = null,
    onProgress: ((String) -> Unit)? = null
  ): FolderDeletionResult {
    val folderPathStr = folder.path
    val folderId = folder.id
    val displayFolder = formatDisplayPath(folderPathStr)

    val dirPath = Path.of(folderPathStr)
    val actualDirPath = if (Files.exists(dirPath, LinkOption.NOFOLLOW_LINKS)) {
      dirPath
    } else {
      fallbackDiskPath ?: dirPath
    }
    if (Files.exists(actualDirPath, LinkOption.NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(actualDirPath)
    ) {
      clearReadOnlyAttributes(actualDirPath)
    }

    val instances = RepoFileInstances.select(RepoFileInstances.file, RepoFileInstances.path)
      .where {
        (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.folder eq folderId)
      }
      .map { it[RepoFileInstances.file] to it[RepoFileInstances.path] }

    val fileIdsToCheck = mutableSetOf<EntityID<Int>>()
    var filesDeletedFromDisk = 0
    val totalInstances = instances.size
    for ((idx, pair) in instances.withIndex()) {
      val (fileId, pathStr) = pair
      fileIdsToCheck.add(fileId)
      if (idx == 0 || (idx + 1) % 50 == 0 || idx + 1 == totalInstances) {
        onProgress?.invoke(
          "Deleting files in $displayFolder (${idx + 1}/$totalInstances)..."
        )
      }
      val (deleted, _) = deletePathWithRetry(Path.of(pathStr))
      if (deleted) filesDeletedFromDisk++
    }

    onProgress?.invoke("Updating database for $displayFolder...")
    val filesDeletedFromDb = RepoFileInstances.deleteWhere {
      (RepoFileInstances.snapshot eq snapshotId) and (RepoFileInstances.folder eq folderId)
    }

    RepoFolders.deleteWhere { RepoFolders.id eq folderId }
    folderCache.invalidate(Pair(snapshotId, folderPathStr))
    cleanupOrphanedFiles(fileIdsToCheck)

    onProgress?.invoke("Removing directory $displayFolder...")
    val (folderDeletedFromDisk, folderDeletionError) = deletePathWithRetry(actualDirPath)

    return FolderDeletionResult(
      path = folderPathStr,
      filesDeletedFromDisk = filesDeletedFromDisk,
      filesDeletedFromDb = filesDeletedFromDb,
      folderDeletedFromDisk = folderDeletedFromDisk,
      deletedFromDb = true,
      hasMatchingDuplicate = hasMatchingDuplicate,
      folderDeletionError = folderDeletionError
    )
  }

  private fun deleteSymlinkAsFolderResult(
    symlinkInstance: RepoFileInstance,
    fallbackDiskPath: Path? = null
  ): FolderDeletionResult {
    val pathStr = symlinkInstance.path
    val fileEntityId = symlinkInstance.file.id
    val diskPath = Path.of(pathStr)
    val actualDiskPath = if (Files.exists(diskPath, LinkOption.NOFOLLOW_LINKS)) {
      diskPath
    } else {
      fallbackDiskPath ?: diskPath
    }
    val (deletedFromDisk, deletionError) = deletePathWithRetry(actualDiskPath)

    symlinkInstance.delete()
    cleanupOrphanedFiles(listOf(fileEntityId))

    return FolderDeletionResult(
      path = pathStr,
      filesDeletedFromDisk = 0,
      filesDeletedFromDb = 1,
      folderDeletedFromDisk = deletedFromDisk,
      deletedFromDb = true,
      hasMatchingDuplicate = false,
      isSymlink = true,
      folderDeletionError = deletionError
    )
  }

  /**
   * Removes a folder and its recorded files (or a symlink itself, preserving its target) from
   * disk and database within [snapshotId].
   * Fails if a real folder does not have a 100% matching duplicate folder unless [force] is true.
   */
  fun deleteFolder(snapshotId: Int, path: Path, force: Boolean = false): FolderDeletionResult =
    transaction(database) {
      val symlinkInstance = findFileInstance(snapshotId, path)?.takeIf { it.isSymlink }
      if (symlinkInstance != null) {
        return@transaction deleteSymlinkAsFolderResult(symlinkInstance, fallbackDiskPath = path)
      }

      val folder = findFolder(snapshotId, path)
        ?: throw IllegalArgumentException("Folder not found in database: $path")

      val identicalGroups = getIdenticalFolderGroups(snapshotId)
      val hasMatchingDuplicate = identicalGroups.any { folder.path in it.paths }

      if (!hasMatchingDuplicate && !force) {
        throw IllegalStateException(
          "Folder does not have a 100% matching duplicate folder: $path. " +
            "Use --force to delete anyway."
        )
      }

      deleteFolderInternal(
        snapshotId = snapshotId,
        folder = folder,
        hasMatchingDuplicate = hasMatchingDuplicate,
        fallbackDiskPath = path
      )
    }

  /**
   * Deletes multiple duplicate folders or symlinks by normalized path from disk and database in
   * [snapshotId]. For symlinks, deletes only the symlink itself without affecting its target.
   */
  fun deleteDuplicateFolders(
    snapshotId: Int,
    paths: List<String>,
    onProgress: ((String) -> Unit)? = null
  ): List<FolderDeletionResult> = transaction(database) {
    paths.mapIndexed { idx, pathStr ->
      val prefix = "Deleting ${idx + 1}/${paths.size}: ${formatDisplayPath(pathStr)}"
      onProgress?.invoke("$prefix...")
      val symlinkInstance = RepoFileInstance.find {
        (RepoFileInstances.snapshot eq snapshotId) and
          (RepoFileInstances.path eq pathStr) and
          (RepoFileInstances.isSymlink eq true)
      }.firstOrNull()

      if (symlinkInstance != null) {
        deleteSymlinkAsFolderResult(symlinkInstance)
      } else {
        val folder = RepoFolder.find {
          (RepoFolders.snapshot eq snapshotId) and (RepoFolders.path eq pathStr)
        }.firstOrNull()

        if (folder != null) {
          deleteFolderInternal(
            snapshotId = snapshotId,
            folder = folder,
            hasMatchingDuplicate = true,
            onProgress = onProgress
          )
        } else {
          val (deletedFromDisk, deletionError) = deletePathWithRetry(Path.of(pathStr))
          FolderDeletionResult(
            path = pathStr,
            filesDeletedFromDisk = 0,
            filesDeletedFromDb = 0,
            folderDeletedFromDisk = deletedFromDisk,
            deletedFromDb = false,
            hasMatchingDuplicate = true,
            folderDeletionError = deletionError
          )
        }
      }
    }
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
  val fileId: EntityID<Int>,
  val isSymlink: Boolean = false,
  val symlinkTarget: String? = null
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
