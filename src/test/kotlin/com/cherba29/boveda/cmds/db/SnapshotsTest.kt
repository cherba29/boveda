package com.cherba29.boveda.cmds.db

import ch.qos.logback.classic.Level
import com.cherba29.boveda.logging.LoggingConfig
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.testing.test
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotsTest {

  @AfterTest
  fun tearDown() {
    LoggingConfig.setRootLevel(Level.WARN)
  }

  @Test
  fun `snapshots command help displays usage, options, and subcommands`() {
    val command = SnapshotsCommand()
    val result = command.test("--help")

    assertEquals(0, result.statusCode)
    assertTrue(result.output.contains("Usage: snapshots"))
    assertTrue(result.output.contains("<database>"))
    assertTrue(result.output.contains("--db"))
    assertTrue(result.output.contains("--log-level"))
    assertTrue(result.output.contains("list"))
    assertTrue(result.output.contains("delete"))
  }

  @Test
  fun `snapshots command configures log level without error`() {
    val command = SnapshotsCommand(serviceFactory = { BovedaService(it) })
    command.test(listOf("-l", "INFO", "list"))

    assertEquals(Level.INFO, LoggingConfig.getRootLevel())
  }
}
