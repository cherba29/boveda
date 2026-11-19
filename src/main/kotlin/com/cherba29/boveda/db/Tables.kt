package com.cherba29.boveda.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Table representing directory snapshots.
 */
object RepoSnapshots : IntIdTable("snapshots") {
  val rootPath = varchar("root_path", 1024)
  val isCompleted = bool("completed").default(false)
}

/**
 * Table representing directory folders within a snapshot.
 */
object RepoFolders : IntIdTable("folders") {
  val snapshot = reference("snapshot_id", RepoSnapshots, onDelete = ReferenceOption.CASCADE)
  val path = varchar("path", 1024)
  val fileCount = integer("file_count").default(0)
  val totalBytes = long("total_bytes").default(0L)

  init {
    uniqueIndex(snapshot, path)
  }
}

/**
 * Table representing unique file contents (content-addressable storage model).
 */
object RepoFiles : IntIdTable("files") {
  val size = long("size")
  val hash = varchar("hash", 64)

  init {
    index(isUnique = false, hash)
  }
}

/**
 * Table representing instances of a file within a specific directory snapshot.
 */
object RepoFileInstances : IntIdTable("file_instances") {
  val snapshot = reference("snapshot_id", RepoSnapshots, onDelete = ReferenceOption.CASCADE)
  val folder = reference("folder_id", RepoFolders, onDelete = ReferenceOption.CASCADE)
  val file = reference("file_id", RepoFiles)
  val path = varchar("path", 1024)
  val timestamp = long("timestamp").nullable()

  init {
    index(isUnique = false, snapshot, file, folder)
    index(isUnique = false, snapshot, folder, file)
    index(isUnique = false, snapshot, path)
  }
}
