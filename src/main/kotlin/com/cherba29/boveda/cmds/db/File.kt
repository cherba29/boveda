package com.cherba29.boveda.cmds.db

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.db.file.DeleteCommand
import com.cherba29.boveda.cmds.db.file.PrintDuplicatesCommand
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Clikt subcommand for managing and analyzing files in the database (print-duplicates, delete).
 */
class FileCommand(
  private val serviceFactory: ((Database) -> BovedaService)? = null,
  private val selector: ((DuplicateGroup) -> List<String>?)? = null
) : CliktCommand(
  name = "file"
) {
  override fun help(context: Context): String =
    "Manage and analyze database files (print-duplicates, delete)."

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

  init {
    subcommands(
      PrintDuplicatesCommand(serviceFactory),
      DeleteCommand(serviceFactory, selector)
    )
  }

  override fun run() {
    logLevel?.let { LoggingConfig.setRootLevel(it) }
  }
}
