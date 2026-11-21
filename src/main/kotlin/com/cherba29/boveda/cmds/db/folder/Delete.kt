package com.cherba29.boveda.cmds.db.folder

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.cmds.resolveSnapshot
import com.cherba29.boveda.cmds.resolveSnapshotId
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateFolderGroup
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
 * Clikt subcommand for deleting 100% matching duplicate folders from disk and database.
 * If a folder path is specified, deletes it (requiring a 100% matching duplicate or --force).
 * If no folder path is specified, enters an interactive prompt showing only 100% matching folders.
 */
class DeleteCommand(
  private val serviceFactory: ((Database) -> BovedaService)? = null,
  private val folderSelector: ((DuplicateFolderGroup) -> List<String>?)? = null
) : CliktCommand(
  name = "delete"
) {
  override fun help(context: Context): String =
    "Delete 100% matching duplicate folders from the filesystem and database."

  val folderArgument: Path? by argument(
    name = "FOLDER",
    help = "Path to folder to delete (optional; if omitted, starts interactive duplicate deletion)."
  ).path().optional()

  val dbPath: Path? by argument(
    name = "DATABASE",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false).optional()

  val folderOption: Path? by option(
    "-f",
    "--folder",
    help = "Path to folder to delete."
  ).path()

  val dbOption: Path? by option(
    "-d",
    "--db",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false)

  val force: Boolean by option(
    "--force",
    help = "Force deletion of folder even if it does not have a 100% matching duplicate."
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

    val (targetFolder: Path?, resolvedDb: Path?) = when {
      folderOption != null -> {
        folderOption to resolveDatabasePath(currentContext, folderArgument ?: dbPath, dbOption)
      }
      folderArgument != null && dbPath != null -> {
        folderArgument to resolveDatabasePath(currentContext, dbPath, dbOption)
      }
      folderArgument != null && parentOrOptionDb != null -> {
        folderArgument to parentOrOptionDb
      }
      folderArgument != null && parentOrOptionDb == null -> {
        val pathStr = folderArgument.toString()
        val base = if (pathStr.endsWith(".mv.db")) pathStr.removeSuffix(".mv.db") else pathStr
        val isDb = !Files.isDirectory(folderArgument) &&
          !Files.isSymbolicLink(folderArgument) &&
          (Files.exists(Path.of(base)) || Files.exists(Path.of("$base.mv.db")))
        if (isDb) {
          null to folderArgument
        } else {
          folderArgument to null
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

    if (targetFolder != null) {
      val result = StatusSpinner(
        currentContext.terminal,
        "Deleting folder ${formatDisplayPath(targetFolder.toString())}..."
      ).use {
        try {
          service.deleteFolder(snapshot.id.value, targetFolder, force = force)
        } catch (e: IllegalArgumentException) {
          throw UsageError(e.message ?: "Folder not found in database: $targetFolder")
        } catch (e: IllegalStateException) {
          throw UsageError(
            e.message
              ?: "Folder does not have a 100% matching duplicate folder: $targetFolder. " +
              "Use --force to delete anyway."
          )
        }
      }

      val displayPath = formatDisplayPath(result.path)
      when {
        result.isSymlink && result.folderDeletedFromDisk -> {
          echo("Deleted symlink from disk and database (target preserved): $displayPath")
        }
        result.isSymlink -> {
          echo(
            "Deleted symlink from database (not found on disk; target preserved): $displayPath"
          )
        }
        result.folderDeletedFromDisk -> {
          echo(
            "Deleted folder from disk and database: $displayPath " +
              "(${result.filesDeletedFromDisk} file(s) deleted from disk, " +
              "${result.filesDeletedFromDb} from database)"
          )
        }
        else -> {
          val reason = result.folderDeletionError?.let { ": $it" } ?: ""
          echo(
            "Deleted folder from database: $displayPath " +
              "(${result.filesDeletedFromDisk} file(s) deleted from disk, " +
              "${result.filesDeletedFromDb} from database; " +
              "directory not removed from disk$reason)"
          )
        }
      }
    } else {
      val defaultSelector = InteractiveDuplicateFolderSelector(currentContext.terminal)
      val skippedSignatures = mutableSetOf<String>()
      val pendingGroups = ArrayDeque<DuplicateFolderGroup>()

      val initialGroups = StatusSpinner(
        currentContext.terminal,
        "Finding 100% matching duplicate folders..."
      ).use { spinner ->
        service.getIdenticalFolderGroups(snapshot.id.value) { msg -> spinner.update(msg) }
      }
      pendingGroups.addAll(initialGroups)

      var anyRealFolderDeleted = false
      var promptedAtLeastOnce = false

      while (true) {
        if (pendingGroups.isEmpty() && anyRealFolderDeleted) {
          anyRealFolderDeleted = false
          val refreshed = StatusSpinner(
            currentContext.terminal,
            "Checking for newly exposed 100% matching duplicate folders..."
          ).use { spinner ->
            service.getIdenticalFolderGroups(snapshot.id.value) { msg -> spinner.update(msg) }
          }.filter { it.signature !in skippedSignatures }
          pendingGroups.addAll(refreshed)
        }

        if (pendingGroups.isEmpty()) {
          if (!promptedAtLeastOnce && skippedSignatures.isEmpty()) {
            echo("No 100% matching duplicate folders found.")
          } else {
            echo("No more 100% matching duplicate folders found in database.")
          }
          break
        }

        val rawGroup = pendingGroups.removeFirst()
        if (rawGroup.signature in skippedSignatures || rawGroup.paths.size < 2) {
          continue
        }

        val currentGroup = if (rawGroup.sampleFiles.isEmpty()) {
          StatusSpinner(
            currentContext.terminal,
            "Loading sample files for next duplicate folder group..."
          ).use {
            service.populateFolderGroupSamples(snapshot.id.value, rawGroup)
          }
        } else {
          rawGroup
        }

        promptedAtLeastOnce = true
        val selectedPaths = if (folderSelector != null) {
          folderSelector.invoke(currentGroup)
        } else {
          defaultSelector.selectDuplicateFolders(currentGroup)
        }

        if (selectedPaths == null) {
          echo("Quitting.")
          break
        }

        if (selectedPaths.isEmpty()) {
          echo("No folders selected to delete. Skipping this duplicate folder group.")
          skippedSignatures.add(currentGroup.signature)
          continue
        }

        val results = StatusSpinner(
          currentContext.terminal,
          "Deleting ${selectedPaths.size} duplicate folder(s)..."
        ).use { spinner ->
          service.deleteDuplicateFolders(snapshot.id.value, selectedPaths) { msg ->
            spinner.update(msg)
          }
        }
        echo("Deleted ${results.size} duplicate folder(s):")
        for (res in results) {
          val displayPath = formatDisplayPath(res.path)
          when {
            res.isSymlink && res.folderDeletedFromDisk -> {
              echo("  - $displayPath [symlink] (target preserved)")
            }
            res.isSymlink -> {
              echo("  - $displayPath [symlink] (from db only, not found on disk; target preserved)")
            }
            res.folderDeletedFromDisk -> {
              echo(
                "  - $displayPath (${res.filesDeletedFromDisk} file(s) removed)"
              )
            }
            else -> {
              val reason = res.folderDeletionError?.let { ": $it" } ?: ""
              echo(
                "  - $displayPath (${res.filesDeletedFromDisk} file(s) removed; " +
                  "directory not removed from disk$reason)"
              )
            }
          }
        }

        val deletedRealPaths = results.filter { !it.isSymlink && it.deletedFromDb }
          .map { it.path }.toSet()
        val deletedAllPaths = results.filter { it.deletedFromDb }.map { it.path }.toSet()
        if (deletedRealPaths.isNotEmpty()) {
          anyRealFolderDeleted = true
        }

        val remainingPaths = currentGroup.paths.filter { it !in deletedRealPaths }
        if (remainingPaths.size >= 2) {
          val remainingSymlinks = currentGroup.symlinkPaths.filter { sym ->
            sym.path !in deletedAllPaths &&
              deletedRealPaths.none { dir -> sym.path.startsWith("$dir/") }
          }
          pendingGroups.addFirst(
            currentGroup.copy(
              paths = remainingPaths,
              symlinkPaths = remainingSymlinks
            )
          )
        }
      }
    }
  }
}

/**
 * Backward-compatible typealias for [DeleteCommand].
 */
typealias FolderDeleteCommand = DeleteCommand
