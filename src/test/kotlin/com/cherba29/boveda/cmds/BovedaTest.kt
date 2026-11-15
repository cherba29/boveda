package com.cherba29.boveda.cmds

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BovedaTest {

    @AfterTest
    fun tearDown() {
        LoggingConfig.setRootLevel(Level.WARN)
    }

    @Test
    fun `boveda root command help displays usage, parameters, options, and subcommands`() {
        val command = BovedaCommand()
        val result = command.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("Usage: boveda"))
        assertTrue(result.output.contains("scan"))
        assertTrue(result.output.contains("db"))
        assertTrue(result.output.contains("--log-level"))
    }

    @Test
    fun `boveda root command sets log level properly`() {
        val command = BovedaCommand(serviceFactory = { BovedaService(it) })
        val result = command.test(listOf("-l", "DEBUG", "db"))

        assertEquals(0, result.statusCode)
        assertEquals(Level.DEBUG, LoggingConfig.getRootLevel())
    }

    @Test
    fun `boveda root command fails on invalid log level`() {
        val command = BovedaCommand()
        val result = command.test(listOf("--log-level", "NON_EXISTENT"))

        assertNotEquals(0, result.statusCode)
        assertTrue(result.output.contains("invalid choice: NON_EXISTENT"))
    }
}
