package com.cherba29.boveda.cmds.db.folder

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.cmds.resolveSnapshot
import com.cherba29.boveda.cmds.resolveSnapshotId
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Clikt subcommand for displaying top folder pairs with the most duplicate files between them.
 */
class PrintDuplicatesCommand(
  private val serviceFactory: ((Database) -> BovedaService)? = null
) : CliktCommand(
  name = "print-duplicates"
) {
  override fun help(context: Context): String =
    "Show top folder pairs with most duplicate files between them and duplicate statistics."

  val dbPath: Path? by argument(
    name = "DATABASE",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false).optional()

  val dbOption: Path? by option(
    "-d",
    "--db",
    help = "Path to persistent H2 database file."
  ).path(canBeFile = true, canBeDir = false)

  val snapshotIdOption: Int? by option(
    "-s",
    "--snapshot",
    help = "Optional snapshot ID to query. Defaults to the latest snapshot."
  ).int()

  val limit: Int by option(
    "-n",
    "--limit",
    help = "Number of top duplicate folder pairs to show. Default is 10."
  ).int().default(10)

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
    val effectiveSnapshotId = resolveSnapshotId(currentContext, snapshotIdOption)
    val snapshot = resolveSnapshot(service, effectiveSnapshotId)
    if (snapshot == null) {
      echo("No snapshots found in database.")
      return
    }

    val pairs = service.findTopDuplicateFolderPairs(snapshot.id.value, limit = limit)
    service.printFolderDuplicatePairs(pairs) { echo(it) }
  }
}

/**
 * Backward-compatible typealias for [PrintDuplicatesCommand].
 */
typealias FolderPrintDuplicatesCommand = PrintDuplicatesCommand
