package com.cherba29.bo

import java.io.File
import java.security.MessageDigest
import kotlin.collections.forEach
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.walk
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lastValue
import org.jetbrains.exposed.v1.core.rank
import org.jetbrains.exposed.v1.core.rowNumber
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Computes the hash of a file using a specified algorithm.
 *
 * @param file The file to hash.
 * @param algorithm The hashing algorithm (e.g., "SHA-256", "MD5", "SHA-512").
 * @return The hash as a hexadecimal string.
 */
fun computeFileHash(file: File, algorithm: String = "MD5"): String {
  // Get the MessageDigest instance for the specified algorithm
  val messageDigest = MessageDigest.getInstance(algorithm)

  // Use an input stream to read the file in chunks
  file.inputStream().use { inputStream ->
    val buffer = ByteArray(1024*1024)
    var bytesRead = inputStream.read(buffer)
    while (bytesRead != -1) {
      // Update the digest with the read bytes
      messageDigest.update(buffer, 0, bytesRead)
      bytesRead = inputStream.read(buffer)
    }
  }
  return messageDigest.digest().toHexString()
}

fun main() {
  val directoryPathString = "." // Scans the current directory
  val runRootPath = Path(directoryPathString)

  val repoDb = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")


  val runSnapshot: ReportSnapshot = transaction(repoDb) {
    SchemaUtils.create(RepoSnapshots, RepoFileInstances, RepoFiles)
    ReportSnapshot.new {
      rootPath = runRootPath.toString()
      isCompleted = false
    }
  }


  println("Scanning directory: $runRootPath")

  // Iterate through all entries (files and directories) using walk()
  runRootPath.walk()
    .forEach {
      val filePath = it.normalize()
      val fileHash = computeFileHash(filePath.toFile())
      println("${filePath.getLastModifiedTime()} ${filePath.fileSize()} $filePath $fileHash")
      transaction(repoDb) {
        val repoFile = RepoFile.new {
          size = filePath.fileSize()
          hash = fileHash
        }
        RepoFileInstance.new {
          snapshot = runSnapshot
          file = repoFile
          path = filePath.invariantSeparatorsPathString
        }
      }
    }

  println("### ==== Selecting from DB ==== ###")
  transaction(repoDb) {
    (RepoSnapshots leftJoin RepoFileInstances leftJoin RepoFiles)
      .select(
      RepoFileInstances.snapshot,
      RepoFileInstances.path,
        RepoFiles.size,
        RepoFiles.hash,
    )
      .orderBy(RepoFiles.hash)
      .forEach { row: ResultRow ->
        val fileSize = row[RepoFiles.size]
        val fileHash = row[RepoFiles.hash]
        val snapshotId = row[RepoFileInstances.snapshot]
        val filePath = row[RepoFileInstances.path]
        println("snapshot=$snapshotId size=$fileSize path=$filePath hash=$fileHash")
      }
  }
//  transaction(repoDb) {
//    val hashWindow = rowNumber().over().partitionBy(RepoFiles.hash).alias("RowNum")
//      (RepoSnapshots leftJoin RepoFileInstances leftJoin RepoFiles)
//      .select(
//        RepoFileInstances.snapshot,
//        RepoFileInstances.path,
//        RepoFiles.size,
//        RepoFiles.hash,
//        hashWindow
//      )
//      // .where { ""}
//      .orderBy(RepoFiles.hash)
//      .forEach { row: ResultRow ->
//        val fileSize = row[RepoFiles.size]
//        val fileHash = row[RepoFiles.hash]
//        val snapshotId = row[RepoFileInstances.snapshot]
//        val filePath = row[RepoFileInstances.path]
//        val hashRowNumber = row[hashWindow]
//        println("$snapshotId $fileSize $filePath $hashRowNumber $fileHash")
//      }
//  }
}


object RepoSnapshots : IntIdTable("snapshots") {
  val rootPath = varchar("root_path", 1024)
  val isCompleted = bool("completed").default(false)
}

object RepoFiles : IntIdTable("files") {
  val size = long("size")
  val hash = varchar("hash", 64)
}

object RepoFileInstances : IntIdTable("file_instances") {
  val snapshot = reference("snapshot_id", RepoSnapshots)
  val file = reference("file_id", RepoFiles)
  val path = varchar("path", 1024)
}

class ReportSnapshot(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<ReportSnapshot>(RepoSnapshots)
  var rootPath by RepoSnapshots.rootPath
  var isCompleted by RepoSnapshots.isCompleted
  val instances by RepoFileInstance referrersOn RepoFileInstances.snapshot
}

class RepoFile(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<RepoFile>(RepoFiles)
  var size by RepoFiles.size
  var hash by RepoFiles.hash
  val instances by RepoFileInstance referrersOn RepoFileInstances.file
}

class RepoFileInstance(id: EntityID<Int>) : IntEntity(id) {
  companion object : IntEntityClass<RepoFileInstance>(RepoFileInstances)
  var snapshot by ReportSnapshot referencedOn RepoFileInstances.snapshot
  var file by RepoFile referencedOn RepoFileInstances
  var path by RepoFileInstances.path
}
