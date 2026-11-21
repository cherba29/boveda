package com.cherba29.boveda.cmds.db.file

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.cmds.resolveSnapshot
import com.cherba29.boveda.cmds.resolveSnapshotId
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.formatDisplayPath
import com.cherba29.boveda.progress.StatusSpinner
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Clikt subcommand for deleting files from disk and database.
 * If a file is specified, deletes it (requiring duplicates or --force).
 * If no file is specified, enters interactive prompt to resolve duplicate storage.
 */
class DeleteCommand(
  private val serviceFactory: ((Database) -> BovedaService)? = null,
  private val selector: ((DuplicateGroup) -> List<String>?)? = null
) : CliktCommand(
  name = "delete"
) {
  override fun help(context: Context): String =
    "Delete duplicate files from the filesystem and database."

  val fileArgument: Path? by argument(
    name = "FILE",
    help = "Path to file to delete (optional; if omitted, starts interactive duplicate deletion)."
  ).path().optional()

  val dbPath: Path? by argument(
    name = "DATABASE",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false).optional()

  val fileOption: Path? by option(
    "-f",
    "--file",
    help = "Path to file to delete."
  ).path()

  val dbOption: Path? by option(
    "-d",
    "--db",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false)

  val force: Boolean by option(
    "--force",
    help = "Force deletion of file even if it does not have duplicates."
  ).flag(default = false)

  val snapshotIdOption: Int? by option(
    "-s",
    "--snapshot",
    help = "Optional snapshot ID to query. Defaults to the latest snapshot."
  ).int()

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

    val parentOrOptionDb = resolveDatabasePath(currentContext, null, dbOption)

    val (targetFile: Path?, resolvedDb: Path?) = when {
      fileOption != null -> {
        fileOption to resolveDatabasePath(currentContext, fileArgument ?: dbPath, dbOption)
      }
      fileArgument != null && dbPath != null -> {
        fileArgument to resolveDatabasePath(currentContext, dbPath, dbOption)
      }
      fileArgument != null && parentOrOptionDb != null -> {
        fileArgument to parentOrOptionDb
      }
      fileArgument != null && parentOrOptionDb == null -> {
        val pathStr = fileArgument.toString()
        val base = if (pathStr.endsWith(".mv.db")) pathStr.removeSuffix(".mv.db") else pathStr
        val isDb = !Files.isSymbolicLink(fileArgument) &&
          (Files.exists(Path.of(base)) || Files.exists(Path.of("$base.mv.db")))
        if (isDb) {
          null to fileArgument
        } else {
          fileArgument to null
        }
      }
      else -> {
        null to resolveDatabasePath(currentContext, dbPath, dbOption)
      }
    }

    val repoDb = connectDatabase(resolvedDb, serviceFactory)
    val service = serviceFactory?.invoke(repoDb) ?: BovedaService(database = repoDb)
    val effectiveSnapshotId = resolveSnapshotId(currentContext, snapshotIdOption)
    val snapshot = resolveSnapshot(service, effectiveSnapshotId)
    if (snapshot == null) {
      echo("No snapshots found in database.")
      return
    }

    if (targetFile != null) {
      val result = try {
        service.deleteFile(snapshot.id.value, targetFile, force = force)
      } catch (e: IllegalArgumentException) {
        throw UsageError(e.message ?: "File not found in database: $targetFile")
      } catch (e: IllegalStateException) {
        throw UsageError(
          e.message ?: "File does not have duplicates: $targetFile. Use --force to delete anyway."
        )
      }

      val displayPath = formatDisplayPath(result.path)
      when {
        result.isSymlink && result.deletedFromDisk -> {
          echo("Deleted symlink from disk and database (target preserved): $displayPath")
        }
        result.isSymlink -> {
          echo(
            "Deleted symlink from database (not found on disk; target preserved): $displayPath"
          )
        }
        result.deletedFromDisk -> {
          echo("Deleted file from disk and database: $displayPath")
        }
        else -> {
          echo("Deleted file from database (not found on disk): $displayPath")
        }
      }
    } else {
      val defaultSelector = InteractiveDuplicateSelector(currentContext.terminal)
      val skippedHashes = mutableSetOf<String>()

      while (true) {
        val largest = StatusSpinner(
          currentContext.terminal,
          "Finding largest duplicate file group..."
        ).use {
          service.findLargestDuplicateGroup(
            snapshot.id.value,
            excludedHashes = skippedHashes
          )
        }
        if (largest == null) {
          if (skippedHashes.isEmpty()) {
            echo("No duplicates found.")
          } else {
            echo("No more duplicate files found in database.")
          }
          break
        }

        val selectedPaths = if (selector != null) {
          selector.invoke(largest)
        } else {
          defaultSelector.selectDuplicates(largest)
        }

        if (selectedPaths == null) {
          echo("Quitting.")
          break
        }

        if (selectedPaths.isEmpty()) {
          echo("No files selected to delete. Skipping this duplicate group.")
          skippedHashes.add(largest.hash)
          continue
        }

        val results = StatusSpinner(
          currentContext.terminal,
          "Deleting ${selectedPaths.size} duplicate file(s)..."
        ).use { spinner ->
          service.deleteDuplicateFiles(snapshot.id.value, selectedPaths) { msg ->
            spinner.update(msg)
          }
        }
        echo("Deleted ${results.size} duplicate file(s):")
        for (res in results) {
          val displayPath = formatDisplayPath(res.path)
          when {
            res.isSymlink && res.deletedFromDisk -> {
              echo("  - $displayPath [symlink] (target preserved)")
            }
            res.isSymlink -> {
              echo("  - $displayPath [symlink] (from db only, not found on disk; target preserved)")
            }
            res.deletedFromDisk -> {
              echo("  - $displayPath")
            }
            else -> {
              echo("  - $displayPath (from db only, not found on disk)")
            }
          }
        }
      }
    }
  }
}

/**
 * Backward-compatible typealias for [DeleteCommand].
 */
typealias FileDeleteCommand = DeleteCommand
