package com.cherba29.boveda.db

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * Table representing directory snapshots.
 */
object RepoSnapshots : IntIdTable("snapshots") {
    val rootPath = varchar("root_path", 1024)
    val isCompleted = bool("completed").default(false)
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
    val snapshot = reference("snapshot_id", RepoSnapshots)
    val file = reference("file_id", RepoFiles)
    val path = varchar("path", 1024)
}
