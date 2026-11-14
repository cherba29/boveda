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
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import org.jetbrains.exposed.v1.jdbc.Database

// Re-export core database declarations in com.cherba29.boveda for backward compatibility
typealias RepoSnapshots = com.cherba29.boveda.db.RepoSnapshots
typealias RepoFiles = com.cherba29.boveda.db.RepoFiles
typealias RepoFileInstances = com.cherba29.boveda.db.RepoFileInstances
typealias ReportSnapshot = com.cherba29.boveda.db.ReportSnapshot
typealias RepoFile = com.cherba29.boveda.db.RepoFile
typealias RepoFileInstance = com.cherba29.boveda.db.RepoFileInstance

/**
 * Clikt command line interface for Boveda vault operations.
 *
 * @param serviceFactory Optional factory to supply custom [BovedaService] (useful for testing or custom configuration).
 */
class BovedaCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "boveda"
) {
    override fun help(context: Context): String =
        "Scan directories, record file fingerprints in database, and query results."

    val directory: Path by argument(
        name = "DIRECTORY",
        help = "Directory path to scan (defaults to current directory '.')"
    ).path(
        mustExist = true,
        canBeFile = false,
        canBeDir = true,
        mustBeReadable = true
    ).default(Path("."))

    val algorithm: String by option(
        "-a", "--algorithm",
        help = "Cryptographic hashing algorithm (e.g. MD5, SHA-256, SHA-512). Default is MD5."
    ).default("MD5")

    val dbPath: Path? by option(
        "-d", "--db",
        help = "Optional file path for persistent H2 database. Defaults to an in-memory database."
    ).path(canBeFile = true, canBeDir = false)

    val quiet: Boolean by option(
        "-q", "--quiet",
        help = "Suppress per-file progress output during directory scan."
    ).flag(default = false)

    val duplicatesOnly: Boolean by option(
        "--duplicates-only",
        help = "Only query and print files that have duplicate contents."
    ).flag(default = false)

    override fun run() {
        val repoDb = if (dbPath != null) {
            DatabaseFactory.connectFile(dbPath.toString())
        } else {
            DatabaseFactory.connectInMemory("test")
        }

        val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)

        echo("Scanning directory: $directory")
        val snapshot = service.scanAndRecord(directory, algorithm = algorithm) { file ->
            if (!quiet) {
                echo("${file.lastModifiedTime} ${file.size} ${file.path} ${file.hash}")
            }
        }

        if (duplicatesOnly) {
            echo("### ==== Duplicate Files in DB ==== ###")
            val duplicates = service.findDuplicates(snapshot.id.value)
            if (duplicates.isEmpty()) {
                echo("No duplicates found.")
            } else {
                duplicates.forEach { record ->
                    echo("snapshot=${record.snapshotId} size=${record.size} path=${record.path} rank=${record.duplicateRank} hash=${record.hash}")
                }
            }
        } else {
            service.printQueryResults(snapshot.id.value) { echo(it) }
        }
    }
}

/**
 * Entry point for scanning a directory, recording files into the database, and running queries.
 *
 * @param args Command-line arguments parsed by [BovedaCommand].
 */
fun main(args: Array<String>) {
    BovedaCommand().main(args)
}

/**
 * Parameterless main function allowing standard JVM / Kotlin execution without arguments.
 */
fun main() = main(emptyArray())
