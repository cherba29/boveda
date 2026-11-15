package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.progress.MordantScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.ParallelDirectoryScanner
import com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
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
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

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
    "-d",
    "--db",
    help = "Target database file path."
  ).path(canBeFile = true, canBeDir = false)

  val algorithm: String by option(
    "-a",
    "--algorithm",
    help = "Cryptographic hashing algorithm (e.g. MD5, SHA-256, SHA-512). Default is MD5."
  ).default("MD5")

  val quiet: Boolean by option(
    "-q",
    "--quiet",
    help = "Suppress per-file progress output during directory scan."
  ).flag(default = false)

  val batchSize: Int by option(
    "-b",
    "--batch-size",
    help = "Number of files to batch per database transaction during scanning. Default is 1000."
  ).int().default(1000)

  val threads: Int by option(
    "-t",
    "--threads",
    help = "Number of worker threads for parallel file hashing (defaults to available CPU cores)."
  ).int().default(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

  val scannerType: String by option(
    "-s",
    "--scanner",
    help = "Directory scanner strategy: 'work-stealing' (default), " +
      "'parallel' (producer-consumer pipeline), or 'sequential'."
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
    "-l",
    "--log-level",
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
