package com.cherba29.boveda.db

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * Exposed DAO entity representing a snapshot record.
 */
class RepoSnapshot(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RepoSnapshot>(RepoSnapshots)

    var rootPath by RepoSnapshots.rootPath
    var isCompleted by RepoSnapshots.isCompleted
    val instances by RepoFileInstance referrersOn RepoFileInstances.snapshot
}

/**
 * Typealias for backward compatibility with existing code referring to [ReportSnapshot].
 */
typealias ReportSnapshot = RepoSnapshot

/**
 * Exposed DAO entity representing a file content record.
 */
class RepoFile(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RepoFile>(RepoFiles)

    var size by RepoFiles.size
    var hash by RepoFiles.hash
    val instances by RepoFileInstance referrersOn RepoFileInstances.file
}

/**
 * Exposed DAO entity representing a file instance link within a snapshot.
 */
class RepoFileInstance(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RepoFileInstance>(RepoFileInstances)

    var snapshot by RepoSnapshot referencedOn RepoFileInstances.snapshot
    var file by RepoFile referencedOn RepoFileInstances.file
    var path by RepoFileInstances.path
}
