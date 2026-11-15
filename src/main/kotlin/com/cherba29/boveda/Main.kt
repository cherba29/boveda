package com.cherba29.boveda

import ch.qos.logback.classic.Level
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
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.progress.MordantScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressState
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ParallelDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.nanoseconds
import org.jetbrains.exposed.v1.jdbc.Database

// Re-export core database declarations in com.cherba29.boveda for backward compatibility
typealias RepoSnapshots = com.cherba29.boveda.db.RepoSnapshots
typealias RepoFiles = com.cherba29.boveda.db.RepoFiles
typealias RepoFileInstances = com.cherba29.boveda.db.RepoFileInstances
typealias ReportSnapshot = com.cherba29.boveda.db.ReportSnapshot
typealias RepoFile = com.cherba29.boveda.db.RepoFile
typealias RepoFileInstance = com.cherba29.boveda.db.RepoFileInstance
typealias DuplicateStatistics = com.cherba29.boveda.model.DuplicateStatistics
typealias DuplicateGroup = com.cherba29.boveda.model.DuplicateGroup
typealias PercentileBucket = com.cherba29.boveda.model.PercentileBucket
typealias DeduplicationCache = com.cherba29.boveda.cache.DeduplicationCache
typealias InMemoryDeduplicationCache = com.cherba29.boveda.cache.InMemoryDeduplicationCache
typealias FileContentKey = com.cherba29.boveda.cache.FileContentKey
typealias CacheStatistics = com.cherba29.boveda.cache.CacheStatistics
typealias ScanProgressIndicator = com.cherba29.boveda.progress.ScanProgressIndicator
typealias MordantScanProgressIndicator = com.cherba29.boveda.progress.MordantScanProgressIndicator
typealias ScanProgressState = com.cherba29.boveda.progress.ScanProgressState
typealias ParallelDirectoryScanner = com.cherba29.boveda.scanner.ParallelDirectoryScanner
typealias FileTreeDirectoryScanner = com.cherba29.boveda.scanner.FileTreeDirectoryScanner

/**
 * Clikt command line interface for Boveda vault operations.
 *
 * @param serviceFactory Optional factory to supply custom [BovedaService] (useful for testing or custom configuration).
 * @param progressIndicatorFactory Optional factory to supply custom [ScanProgressIndicator] (useful for testing).
 */
class BovedaCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null,
    private val progressIndicatorFactory: ((com.github.ajalt.mordant.terminal.Terminal) -> ScanProgressIndicator)? = null
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

    val logLevel: Level by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE). Default is WARN."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    ).default(Level.WARN)

    val quiet: Boolean by option(
        "-q", "--quiet",
        help = "Suppress per-file progress output during directory scan."
    ).flag(default = false)

    val duplicatesOnly: Boolean by option(
        "--duplicates-only",
        help = "Only query and print files that have duplicate contents."
    ).flag(default = false)

    val batchSize: Int by option(
        "-b", "--batch-size",
        help = "Number of files to batch per database transaction during scanning. Default is 1000."
    ).int().default(1000)

    val threads: Int by option(
        "-t", "--threads",
        help = "Number of worker threads for parallel file hashing (defaults to available CPU cores)."
    ).int().default(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    val noCacheWarmup: Boolean by option(
        "--no-cache-warmup",
        help = "Disable pre-warming the in-memory deduplication cache from the database on startup."
    ).flag(default = false)

    override fun run() {
        val startTime = System.nanoTime()

        // Configure logging level from command-line argument
        LoggingConfig.setRootLevel(logLevel)

        val repoDb = if (dbPath != null) {
            DatabaseFactory.connectFile(dbPath.toString())
        } else {
            DatabaseFactory.connectInMemory("test")
        }

        val scanner: DirectoryScanner = if (threads == 1) {
            FileTreeDirectoryScanner()
        } else {
            ParallelDirectoryScanner(threadCount = threads)
        }

        val service = serviceFactory?.invoke(repoDb) ?: BovedaService(
            database = repoDb,
            scanner = scanner,
            batchSize = batchSize,
            warmUpCache = !noCacheWarmup
        )

        val progressIndicator: ScanProgressIndicator? = if (quiet) {
            progressIndicatorFactory?.invoke(terminal) ?: MordantScanProgressIndicator(terminal)
        } else {
            null
        }

        echo("Scanning directory: $directory")
        val snapshot = try {
            service.scanAndRecord(directory, algorithm = algorithm) { file ->
                if (!quiet) {
                    echo("${file.lastModifiedTime} ${file.size} ${file.path} ${file.hash}")
                } else {
                    progressIndicator?.onFileProcessed(file)
                }
            }
        } finally {
            progressIndicator?.finish()
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

            val elapsedNanos = System.nanoTime() - startTime
            val duration = elapsedNanos.nanoseconds
            val stats = service.computeDuplicateStatistics(snapshot.id.value, duration)
            service.printDuplicateStatistics(stats) { echo(it) }
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
