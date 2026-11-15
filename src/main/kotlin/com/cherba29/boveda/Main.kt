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
import com.cherba29.boveda.model.SnapshotSummary
import com.cherba29.boveda.progress.MordantScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressState
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.DiscoveredFile
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ParallelDirectoryScanner
import com.cherba29.boveda.scanner.ScannedFile
import com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
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
typealias WorkStealingDirectoryScanner = com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
typealias FileTreeDirectoryScanner = com.cherba29.boveda.scanner.FileTreeDirectoryScanner
typealias DiscoveredFile = com.cherba29.boveda.scanner.DiscoveredFile
typealias SnapshotSummary = com.cherba29.boveda.model.SnapshotSummary

internal fun validateDatabaseFileExists(path: Path) {
    val pathStr = path.toString()
    val base = if (pathStr.endsWith(".mv.db")) pathStr.removeSuffix(".mv.db") else pathStr
    val fileExists = Files.exists(Path.of(base)) || Files.exists(Path.of("$base.mv.db"))
    if (!fileExists) {
        throw UsageError("Database file does not exist: $path")
    }
}

internal fun resolveDatabasePath(context: Context, localPath: Path? = null, localOption: Path? = null): Path? {
    if (localPath != null) return localPath
    if (localOption != null) return localOption
    var current: Context? = context.parent
    while (current != null) {
        val cmd = current.command
        if (cmd is SnapshotsCommand) {
            val p = cmd.dbPath ?: cmd.dbOption
            if (p != null) return p
        }
        if (cmd is DbCommand) {
            val p = cmd.dbPath ?: cmd.dbOption
            if (p != null) return p
        }
        current = current.parent
    }
    return null
}

internal fun connectDatabase(
    resolvedDbPath: Path?,
    serviceFactory: ((Database) -> BovedaService)?
): Database {
    return when {
        resolvedDbPath != null -> {
            if (serviceFactory == null) validateDatabaseFileExists(resolvedDbPath)
            DatabaseFactory.connectFile(resolvedDbPath.toString())
        }
        serviceFactory != null -> DatabaseFactory.connectInMemory("test")
        else -> throw UsageError("Database must be specified as an argument or via --db")
    }
}

internal fun resolveSnapshot(service: BovedaService, snapshotId: Int?): RepoSnapshot? {
    return if (snapshotId != null) {
        service.findSnapshot(snapshotId)
    } else {
        service.getLatestSnapshot()
    }
}

/**
 * Root Clikt command for Boveda operations, providing 'scan' and 'db' subcommands.
 *
 * @param serviceFactory Optional factory to supply custom [BovedaService] (useful for testing or custom configuration).
 * @param progressIndicatorFactory Optional factory to supply custom [ScanProgressIndicator] (useful for testing).
 */
class BovedaCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null,
    private val progressIndicatorFactory: ((Terminal) -> ScanProgressIndicator)? = null
) : CliktCommand(
    name = "boveda"
) {
    override fun help(context: Context): String =
        "Boveda: File vault fingerprinting, deduplication scanner, and analytical database querying."

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

    init {
        subcommands(
            ScanCommand(serviceFactory, progressIndicatorFactory),
            DbCommand(serviceFactory)
        )
    }

    override fun run() {
        LoggingConfig.setRootLevel(logLevel)
    }
}

/**
 * Clikt subcommand for scanning a directory and populating the target database.
 */
class ScanCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null,
    private val progressIndicatorFactory: ((Terminal) -> ScanProgressIndicator)? = null
) : CliktCommand(
    name = "scan"
) {
    override fun help(context: Context): String =
        "Scan directory and populate the target database with file fingerprints."

    val directory: Path by argument(
        name = "DIRECTORY",
        help = "Directory path to scan."
    ).path(
        mustExist = true,
        canBeFile = false,
        canBeDir = true,
        mustBeReadable = true
    )

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Target database file path."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Target database file path."
    ).path(canBeFile = true, canBeDir = false)

    val algorithm: String by option(
        "-a", "--algorithm",
        help = "Cryptographic hashing algorithm (e.g. MD5, SHA-256, SHA-512). Default is MD5."
    ).default("MD5")

    val quiet: Boolean by option(
        "-q", "--quiet",
        help = "Suppress per-file progress output during directory scan."
    ).flag(default = false)

    val batchSize: Int by option(
        "-b", "--batch-size",
        help = "Number of files to batch per database transaction during scanning. Default is 1000."
    ).int().default(1000)

    val threads: Int by option(
        "-t", "--threads",
        help = "Number of worker threads for parallel file hashing (defaults to available CPU cores)."
    ).int().default(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    val scannerType: String by option(
        "-s", "--scanner",
        help = "Directory scanner strategy: 'work-stealing' (default, multi-threaded tree exploration), 'parallel' (producer-consumer pipeline), or 'sequential' (single-threaded)."
    ).choice(
        "work-stealing" to "work-stealing",
        "parallel" to "parallel",
        "sequential" to "sequential",
        ignoreCase = true
    ).default("work-stealing")

    val workStealing: Boolean by option(
        "--work-stealing",
        help = "Enable work-stealing parallel directory traversal using ForkJoinPool."
    ).flag(default = false)

    val noCacheWarmup: Boolean by option(
        "--no-cache-warmup",
        help = "Disable pre-warming the in-memory deduplication cache from the database on startup."
    ).flag(default = false)

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    override fun run() {
        logLevel?.let { LoggingConfig.setRootLevel(it) }

        val resolvedDbPath = dbPath ?: dbOption
        val repoDb = when {
            resolvedDbPath != null -> DatabaseFactory.connectFile(resolvedDbPath.toString())
            serviceFactory != null -> DatabaseFactory.connectInMemory("test")
            else -> throw UsageError("Target database must be specified as an argument or via --db")
        }

        val effectiveScannerType = if (workStealing) "work-stealing" else scannerType

        val scanner: DirectoryScanner = when {
            threads == 1 || effectiveScannerType.equals("sequential", ignoreCase = true) -> {
                FileTreeDirectoryScanner()
            }
            effectiveScannerType.equals("parallel", ignoreCase = true) -> {
                ParallelDirectoryScanner(threadCount = threads)
            }
            else -> {
                WorkStealingDirectoryScanner(parallelism = threads)
            }
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
        try {
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
    }
}

/**
 * Clikt subcommand for querying an already existing/computed database.
 */
class DbCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "db"
) {
    override val invokeWithoutSubcommand: Boolean = true

    override fun help(context: Context): String =
        "Query an already existing/computed database."

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false)

    val snapshotIdOption: Int? by option(
        "-s", "--snapshot",
        help = "Optional snapshot ID to query. Defaults to the latest snapshot."
    ).int()

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    init {
        subcommands(
            PrintDuplicatesCommand(serviceFactory),
            SnapshotsCommand(serviceFactory)
        )
    }

    override fun run() {
        logLevel?.let { LoggingConfig.setRootLevel(it) }

        if (currentContext.invokedSubcommand == null) {
            val resolvedDbPath = resolveDatabasePath(currentContext, dbPath, dbOption)
            val repoDb = connectDatabase(resolvedDbPath, serviceFactory)

            val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)
            val snapshot = resolveSnapshot(service, snapshotIdOption)
            if (snapshot == null) {
                echo("No snapshots found in database.")
                return
            }

            service.printQueryResults(snapshot.id.value) { echo(it) }
        }
    }
}

/**
 * Clikt subcommand for querying the database for the latest snapshot and printing duplicate files and statistics.
 */
class PrintDuplicatesCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "print-duplicates"
) {
    override fun help(context: Context): String =
        "Query the provided database for the latest snapshot and print duplicate files and statistics."

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false)

    val snapshotIdOption: Int? by option(
        "-s", "--snapshot",
        help = "Optional snapshot ID to query. Defaults to the latest snapshot."
    ).int()

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    override fun run() {
        val startTime = System.nanoTime()
        logLevel?.let { LoggingConfig.setRootLevel(it) }

        val resolvedDbPath = resolveDatabasePath(currentContext, dbPath, dbOption)
        val repoDb = connectDatabase(resolvedDbPath, serviceFactory)

        val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)
        val snapshot = resolveSnapshot(service, snapshotIdOption)
        if (snapshot == null) {
            echo("No snapshots found in database.")
            return
        }

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
    }
}

/**
 * Clikt subcommand for managing database snapshots (list, delete).
 */
class SnapshotsCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "snapshots"
) {
    override fun help(context: Context): String =
        "Manage database snapshots (list, delete)."

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false)

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    init {
        subcommands(
            ListSnapshotsCommand(serviceFactory),
            DeleteSnapshotCommand(serviceFactory)
        )
    }

    override fun run() {
        logLevel?.let { LoggingConfig.setRootLevel(it) }
    }
}

/**
 * Clikt subcommand for listing all snapshots stored in the database.
 */
class ListSnapshotsCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "list"
) {
    override fun help(context: Context): String =
        "List all snapshots stored in the database."

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false)

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    override fun run() {
        logLevel?.let { LoggingConfig.setRootLevel(it) }

        val resolvedDbPath = resolveDatabasePath(currentContext, dbPath, dbOption)
        val repoDb = connectDatabase(resolvedDbPath, serviceFactory)

        val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)
        val snapshots = service.listSnapshots()
        if (snapshots.isEmpty()) {
            echo("No snapshots found in database.")
        } else {
            echo(service.formatSnapshotsTable(snapshots, terminal))
        }
    }
}

/**
 * Clikt subcommand for deleting a snapshot from the database by its snapshot ID.
 */
class DeleteSnapshotCommand(
    private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
    name = "delete"
) {
    override fun help(context: Context): String =
        "Delete a snapshot from the database by its snapshot ID."

    val snapshotId: Int by argument(
        name = "SNAPSHOT_ID",
        help = "ID of the snapshot to delete."
    ).int()

    val dbPath: Path? by argument(
        name = "DATABASE",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false).optional()

    val dbOption: Path? by option(
        "-d", "--db",
        help = "Path to persistent H2 database file."
    ).path(canBeFile = true, canBeDir = false)

    val logLevel: Level? by option(
        "-l", "--log-level",
        help = "Logging level (OFF, ERROR, WARN, INFO, DEBUG, TRACE)."
    ).choice(
        "OFF" to Level.OFF,
        "ERROR" to Level.ERROR,
        "WARN" to Level.WARN,
        "WARNING" to Level.WARN,
        "INFO" to Level.INFO,
        "DEBUG" to Level.DEBUG,
        "TRACE" to Level.TRACE,
        ignoreCase = true
    )

    override fun run() {
        logLevel?.let { LoggingConfig.setRootLevel(it) }

        val resolvedDbPath = resolveDatabasePath(currentContext, dbPath, dbOption)
        val repoDb = connectDatabase(resolvedDbPath, serviceFactory)

        val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)
        val deleted = service.deleteSnapshot(snapshotId)
        if (!deleted) {
            throw UsageError("Snapshot with ID $snapshotId does not exist.")
        } else {
            echo("Snapshot $snapshotId deleted successfully.")
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
