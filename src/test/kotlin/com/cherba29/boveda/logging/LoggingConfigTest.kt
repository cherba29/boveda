package com.cherba29.boveda.logging

import ch.qos.logback.classic.Level
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class LoggingConfigTest {

    private val originalLevel = LoggingConfig.getRootLevel() ?: Level.WARN

    @BeforeTest
    fun setUp() {
        LoggingConfig.setRootLevel(Level.WARN)
    }

    @AfterTest
    fun tearDown() {
        LoggingConfig.setRootLevel(originalLevel)
    }

    @Test
    fun `setRootLevel changes log level and getRootLevel reflects change`() {
        LoggingConfig.setRootLevel(Level.DEBUG)
        assertEquals(Level.DEBUG, LoggingConfig.getRootLevel())

        LoggingConfig.setRootLevel(Level.INFO)
        assertEquals(Level.INFO, LoggingConfig.getRootLevel())

        LoggingConfig.setRootLevel(Level.WARN)
        assertEquals(Level.WARN, LoggingConfig.getRootLevel())

        LoggingConfig.setRootLevel(Level.ERROR)
        assertEquals(Level.ERROR, LoggingConfig.getRootLevel())
    }

    @Test
    fun `default WARN level suppresses DEBUG logs but emits WARN logs to stderr`() {
        val originalErr = System.err
        val errStream = ByteArrayOutputStream()

        try {
            System.setErr(PrintStream(errStream))
            LoggingConfig.setRootLevel(Level.WARN)

            val logger = LoggerFactory.getLogger("TestLogger")
            logger.debug("This debug message should NOT appear in stderr")
            logger.warn("This warning message SHOULD appear in stderr")
        } finally {
            System.setErr(originalErr)
        }

        val errOutput = errStream.toString()
        assertFalse(errOutput.contains("This debug message should NOT appear"))
        assertTrue(errOutput.contains("This warning message SHOULD appear in stderr"))
    }

    @Test
    fun `DEBUG level emits DEBUG logs to stderr`() {
        val originalErr = System.err
        val errStream = ByteArrayOutputStream()

        try {
            System.setErr(PrintStream(errStream))
            LoggingConfig.setRootLevel(Level.DEBUG)

            val logger = LoggerFactory.getLogger("TestLogger")
            logger.debug("This debug message SHOULD appear in stderr when level is DEBUG")
        } finally {
            System.setErr(originalErr)
        }

        val errOutput = errStream.toString()
        assertTrue(errOutput.contains("This debug message SHOULD appear in stderr when level is DEBUG"))
    }
}
