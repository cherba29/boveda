package com.cherba29.boveda.cmds.db

import ch.qos.logback.classic.Level
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.cmds.resolveSnapshot
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.time.Duration.Companion.nanoseconds
import org.jetbrains.exposed.v1.jdbc.Database

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
