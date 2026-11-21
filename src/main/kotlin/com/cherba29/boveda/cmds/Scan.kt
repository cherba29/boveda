package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.hash.parseByteSize
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.formatDisplayPath
import com.cherba29.boveda.model.formatDuration
import com.cherba29.boveda.progress.EstimatingProgressIndicator
import com.cherba29.boveda.progress.MordantEstimatingProgressIndicator
import com.cherba29.boveda.progress.MordantScanProgressIndicator
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.scanner.DirectoryMetadataScanner
import com.cherba29.boveda.scanner.DirectoryScanner
import com.cherba29.boveda.scanner.FileTreeDirectoryScanner
import com.cherba29.boveda.scanner.FileTreeMetadataScanner
import com.cherba29.boveda.scanner.ParallelDirectoryScanner
import com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
import com.cherba29.boveda.scanner.WorkStealingMetadataScanner
import com.cherba29.boveda.service.BovedaService
import com.cherba29.boveda.service.ScanPhase
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.nanoseconds
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Clikt subcommand for scanning a directory and populating the target database.
 */
class ScanCommand(
  private val serviceFactory: ((Database) -> BovedaService)? = null,
  private val progressIndicatorFactory: ((Terminal) -> ScanProgressIndicator)? = null,
  private val metadataScannerFactory: ((Int) -> DirectoryMetadataScanner)? = null,
  private val estimatingProgressIndicatorFactory: (
    (
      Terminal
    ) -> EstimatingProgressIndicator
  )? = null
) : CliktCommand(
  name = "scan"
) {
  override fun help(context: Context): String =
    "Scan directory and populate the target database with file fingerprints."

  val continueSnapshotId: Int? by option(
    "-c",
    "--continue",
    help = "Resume an incomplete directory scan for the given snapshot ID."
  ).int()

  val directory: Path? by argument(
    name = "DIRECTORY",
    help = "Directory path to scan (optional when --continue is provided)."
  ).path().optional()

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
    help = "File hashing algorithm (e.g. XXH3, CRC32, MD5, SHA-256, SHA-512). Default is XXH3."
  ).default("XXH3")

  val maxHashBytesOption: String? by option(
    "-m",
    "--max-hash-bytes",
    "--max-hash-size",
    "--hash-prefix-size",
    "--hash-bytes",
    help = "Maximum initial bytes to hash per file as well as the file size " +
      "(e.g. 64Kb, 128Kb, 256Kb, 512Kb, 1Mb, full). Default is full file."
  )

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
    help = "Number of worker threads for parallel file hashing " +
      "(defaults to available CPU cores, capped at 8)."
  ).int().default(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))

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

  val noEstimate: Boolean by option(
    "--no-estimate",
    help = "Skip the initial directory traversal that estimates total file count and size."
  ).flag(default = false)

  val dbCacheMb: Int by option(
    "--db-cache-mb",
    help = "H2 in-memory page cache size in MB during scanning. Default is 64."
  ).int().default(64)

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

  private fun validateScanDirectory(path: Path): Path {
    if (!Files.exists(path)) {
      throw UsageError("Invalid value for \"<directory>\": directory \"$path\" does not exist.")
    }
    if (!Files.isDirectory(path)) {
      throw UsageError("Invalid value for \"<directory>\": directory \"$path\" is a file.")
    }
    if (!Files.isReadable(path)) {
      throw UsageError("Invalid value for \"<directory>\": directory \"$path\" is not readable.")
    }
    return path.normalize()
  }

  override fun run() {
    logLevel?.let { LoggingConfig.setRootLevel(it) }

    val continueId = continueSnapshotId
    val (rawDirectory, rawDbPath) = if (continueId != null) {
      if (dbOption != null) {
        directory to dbOption
      } else if (directory != null && dbPath != null) {
        directory to dbPath
      } else if (directory != null && dbPath == null) {
        null to directory
      } else {
        null to null
      }
    } else {
      directory to (dbPath ?: dbOption)
    }

    if (dbCacheMb < 0) {
      throw UsageError("Invalid value for --db-cache-mb: must be non-negative, got $dbCacheMb")
    }

    val repoDb = when {
      rawDbPath != null -> DatabaseFactory.connectFile(
        path = rawDbPath.toString(),
        cacheSizeKb = dbCacheMb * 1024,
        bulkInsert = true
      )
      serviceFactory != null -> DatabaseFactory.connectInMemory("test")
      else -> throw UsageError("Target database must be specified as an argument or via --db")
    }

    val maxHashBytes = try {
      parseByteSize(maxHashBytesOption)
    } catch (e: Exception) {
      throw UsageError("Invalid value for --max-hash-bytes: ${e.message}")
    }

    val effectiveScannerType = if (workStealing) "work-stealing" else scannerType

    val scanner: DirectoryScanner = when {
      threads == 1 || effectiveScannerType.equals("sequential", ignoreCase = true) -> {
        FileTreeDirectoryScanner(maxBytes = maxHashBytes)
      }
      effectiveScannerType.equals("parallel", ignoreCase = true) -> {
        ParallelDirectoryScanner(threadCount = threads, maxBytes = maxHashBytes)
      }
      else -> {
        WorkStealingDirectoryScanner(parallelism = threads, maxBytes = maxHashBytes)
      }
    }

    val service = serviceFactory?.invoke(repoDb) ?: BovedaService(
      database = repoDb,
      scanner = scanner,
      batchSize = batchSize,
      warmUpCache = !noCacheWarmup
    )

    val targetDirectory: Path = if (continueId != null) {
      val snapshot = service.findSnapshot(continueId)
        ?: throw UsageError("Snapshot not found: $continueId")
      if (snapshot.isCompleted) {
        throw UsageError(
          "Snapshot $continueId is already completed. Only incomplete snapshots can be continued."
        )
      }
      val rootDir = rawDirectory ?: Path.of(snapshot.rootPath)
      validateScanDirectory(rootDir)
    } else {
      val dir = rawDirectory
        ?: throw UsageError("DIRECTORY must be specified when not continuing an existing snapshot.")
      validateScanDirectory(dir)
    }

    echo("Scanning directory: $targetDirectory")
    val metadata = if (noEstimate) {
      null
    } else {
      val metadataScanner: DirectoryMetadataScanner = metadataScannerFactory?.invoke(threads)
        ?: if (threads == 1 || effectiveScannerType.equals("sequential", ignoreCase = true)) {
          FileTreeMetadataScanner
        } else {
          WorkStealingMetadataScanner(parallelism = threads)
        }
      val estimatingIndicator = estimatingProgressIndicatorFactory?.invoke(terminal)
        ?: MordantEstimatingProgressIndicator(terminal = terminal)
      try {
        metadataScanner.scan(targetDirectory) { filesScanned, bytesScanned ->
          estimatingIndicator.onProgress(filesScanned, bytesScanned)
        }
      } finally {
        estimatingIndicator.finish()
      }
    }

    val existingEntries = if (continueId != null) {
      echo("Loading existing snapshot entries...")
      service.getSnapshotFileEntries(continueId)
    } else {
      emptyMap()
    }
    val initialFiles = existingEntries.size.toLong()
    val initialBytes = existingEntries.values.sumOf { it.size }

    val progressIndicator: ScanProgressIndicator? = if (quiet) {
      val indicator = progressIndicatorFactory?.invoke(terminal)
        ?: MordantScanProgressIndicator(
          terminal = terminal,
          totalFiles = metadata?.fileCount,
          totalBytes = metadata?.totalBytes,
          initialFiles = initialFiles,
          initialBytes = initialBytes,
          maxHashBytes = maxHashBytes
        )
      indicator.apply {
        setTotals(
          totalFiles = metadata?.fileCount,
          totalBytes = metadata?.totalBytes,
          initialFiles = initialFiles,
          initialBytes = initialBytes
        )
      }
    } else {
      null
    }

    var finalizeStartNanos = 0L
    try {
      service.scanAndRecord(
        rootPath = targetDirectory,
        algorithm = algorithm,
        resumeSnapshotId = continueId,
        maxBytes = maxHashBytes,
        onPhase = { phase ->
          when (phase) {
            ScanPhase.WARMING_CACHE -> {
              if (quiet) {
                progressIndicator?.setStatus("Warming up cache:")
              } else {
                echo("Warming up deduplication cache...")
              }
            }
            ScanPhase.LOADING_EXISTING_SNAPSHOT -> {
              progressIndicator?.setStatus("Loading snapshot:")
            }
            ScanPhase.SCANNING -> {
              progressIndicator?.setStatus("Scanning & ingesting:")
            }
            ScanPhase.UPDATING_FOLDER_STATISTICS -> {
              progressIndicator?.finish()
              finalizeStartNanos = System.nanoTime()
              echo("Updating folder statistics and finalizing snapshot...")
            }
            ScanPhase.COMPLETED -> {
              if (finalizeStartNanos > 0L) {
                val finalizeDuration = (System.nanoTime() - finalizeStartNanos).nanoseconds
                echo("Snapshot finalized in ${formatDuration(finalizeDuration)}.")
              }
            }
          }
        }
      ) { file ->
        if (!quiet) {
          if (file.isSymlink) {
            val targetInfo = formatDisplayPath(file.symlinkTarget ?: "")
            echo("${file.lastModifiedTime} ${file.size} ${file.path} [symlink -> $targetInfo]")
          } else {
            echo("${file.lastModifiedTime} ${file.size} ${file.path} ${file.hash}")
          }
        } else {
          progressIndicator?.onFileProcessed(file)
        }
      }
    } finally {
      progressIndicator?.finish()
    }

    if (rawDbPath != null) {
      echo("Flushing and closing database...")
      val closeStartNanos = System.nanoTime()
      DatabaseFactory.shutdown(repoDb)
      val closeDuration = (System.nanoTime() - closeStartNanos).nanoseconds
      echo("Database closed in ${formatDuration(closeDuration)}.")
    }
  }
}
