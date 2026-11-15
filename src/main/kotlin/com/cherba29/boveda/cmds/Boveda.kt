package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.progress.ScanProgressIndicator
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import org.jetbrains.exposed.v1.jdbc.Database

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
