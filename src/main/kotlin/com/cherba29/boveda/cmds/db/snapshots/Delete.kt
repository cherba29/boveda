package com.cherba29.boveda.cmds.db.snapshots

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Clikt subcommand for deleting a snapshot from the database by its snapshot ID.
 */
class DeleteCommand(
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
    "-d",
    "--db",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false)

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
 * Backward-compatible typealias for [DeleteCommand].
 */
typealias DeleteSnapshotCommand = DeleteCommand
