package com.cherba29.boveda

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoFile
import com.cherba29.boveda.db.RepoFileInstance
import com.cherba29.boveda.db.RepoFileInstances
import com.cherba29.boveda.db.RepoFiles
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.db.RepoSnapshots
import com.cherba29.boveda.db.ReportSnapshot
import com.cherba29.boveda.hash.FileHasher
import com.cherba29.boveda.hash.MessageDigestFileHasher
import com.cherba29.boveda.hash.computeFileHash
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.service.BovedaService
import java.io.File
import kotlin.io.path.Path

// Re-export core database declarations in com.cherba29.boveda for backward compatibility
typealias RepoSnapshots = com.cherba29.boveda.db.RepoSnapshots
typealias RepoFiles = com.cherba29.boveda.db.RepoFiles
typealias RepoFileInstances = com.cherba29.boveda.db.RepoFileInstances
typealias ReportSnapshot = com.cherba29.boveda.db.ReportSnapshot
typealias RepoFile = com.cherba29.boveda.db.RepoFile
typealias RepoFileInstance = com.cherba29.boveda.db.RepoFileInstance

/**
 * Entry point for scanning a directory, recording files into the database, and running queries.
 *
 * @param args Optional command-line arguments. args[0] is the directory path (defaults to ".").
 */
fun main(args: Array<String>) {
    val directoryPathString = args.firstOrNull() ?: "."
    val runRootPath = Path(directoryPathString)

    // Connect to in-memory H2 database with tables initialized
    val repoDb = DatabaseFactory.connectInMemory("test")
    val service = BovedaService(database = repoDb)

    println("Scanning directory: $runRootPath")
    val snapshot = service.scanAndRecord(runRootPath) { file ->
        println("${file.lastModifiedTime} ${file.size} ${file.path} ${file.hash}")
    }

    // Run queries and print results
    service.printQueryResults(snapshot.id.value)
}

/**
 * Parameterless main function allowing standard JVM / Kotlin execution without arguments.
 */
fun main() = main(emptyArray())
