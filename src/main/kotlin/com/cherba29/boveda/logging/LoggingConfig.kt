package com.cherba29.boveda.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Utility for dynamically configuring Logback logging levels at runtime.
 */
object LoggingConfig {

    /**
     * Sets the root logger level in Logback.
     *
     * @param level The target [Level] to set (e.g. [Level.WARN], [Level.DEBUG]).
     */
    fun setRootLevel(level: Level) {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.level = level
    }

    /**
     * Returns the current root logger level from Logback.
     */
    fun getRootLevel(): Level? {
        val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
        return loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).level
    }
}
