package com.cherba29.boveda.progress

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstimatingProgressIndicatorTest {

  @Test
  fun `EstimatingProgressState formats zero, single, and multiple files and bytes correctly`() {
    val zeroState = EstimatingProgressState(
      filesScanned = 0L,
      bytesScanned = 0L,
      startTimeNanos = 0L
    )
    assertEquals("0 files scanned", zeroState.formattedFiles)
    assertEquals("0 B scanned", zeroState.formattedStorage)

    val singleState = EstimatingProgressState(
      filesScanned = 1L,
      bytesScanned = 100L,
      startTimeNanos = 0L
    )
    assertEquals("1 file scanned", singleState.formattedFiles)
    assertEquals("100 B scanned", singleState.formattedStorage)

    val multiState = EstimatingProgressState(
      filesScanned = 1500L,
      bytesScanned = 10_485_760L,
      startTimeNanos = 0L
    )
    assertEquals("1,500 files scanned", multiState.formattedFiles)
    assertEquals("10.00 MB scanned", multiState.formattedStorage)
  }

  @Test
  fun `EstimatingProgressState computes files per second and storage per second accurately`() {
    val zeroElapsed = EstimatingProgressState(
      filesScanned = 0L,
      bytesScanned = 0L,
      startTimeNanos = 1_000_000L,
      timeProvider = { 1_000_000L }
    )
    assertEquals(0.0, zeroElapsed.elapsedSeconds)
    assertEquals(0.0, zeroElapsed.filesPerSecond)
    assertEquals(0.0, zeroElapsed.bytesPerSecond)
    assertEquals(0.0, zeroElapsed.megaBytesPerSecond)
    assertEquals("0.0 files/s, 0 B/s", zeroElapsed.formattedSpeed)

    // 3,000 files and 30 MB scanned in 2.0 seconds -> 1,500.0 files/s, 15.00 MB/s
    val activeState = EstimatingProgressState(
      filesScanned = 3_000L,
      bytesScanned = 30L * 1024L * 1024L,
      startTimeNanos = 0L,
      timeProvider = { 2_000_000_000L }
    )
    assertEquals(2.0, activeState.elapsedSeconds)
    assertEquals(1_500.0, activeState.filesPerSecond)
    assertEquals(15.0, activeState.megaBytesPerSecond)
    assertEquals("1,500.0 files/s, 15.00 MB/s", activeState.formattedSpeed)

    // Multi-GB throughput: 4 GB scanned in 2.0 seconds -> 2.00 GB/s
    val fastState = EstimatingProgressState(
      filesScanned = 20_000L,
      bytesScanned = 4L * 1024L * 1024L * 1024L,
      startTimeNanos = 0L,
      timeProvider = { 2_000_000_000L }
    )
    assertEquals("10,000.0 files/s, 2.00 GB/s", fastState.formattedSpeed)
  }

  @Test
  fun `NoOpEstimatingProgressIndicator does not throw on operations`() {
    val indicator = NoOpEstimatingProgressIndicator()
    indicator.onProgress(10L, 1024L)
    indicator.finish()
    indicator.close()
  }

  @Test
  fun `MordantEstimatingProgressIndicator works and cleans up in non-interactive mode`() {
    val recorder = TerminalRecorder(outputInteractive = false)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantEstimatingProgressIndicator(
      terminal = terminal,
      updateIntervalMillis = 500L,
      timeProvider = { currentTime }
    )

    indicator.onProgress(100L, 1_000_000L)
    currentTime += 500_000_000L
    indicator.onProgress(200L, 2_000_000L)

    indicator.finish()
    indicator.finish()
    indicator.close()
  }

  @Test
  fun `MordantEstimatingProgressIndicator animates and throttles in interactive mode`() {
    val recorder = TerminalRecorder(outputInteractive = true, ansiLevel = AnsiLevel.TRUECOLOR)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantEstimatingProgressIndicator(
      terminal = terminal,
      updateIntervalMillis = 500L,
      timeProvider = { currentTime }
    )

    indicator.onProgress(10L, 5000L)

    // Advance time by 500ms and report progress to trigger update
    currentTime += 500_000_000L
    indicator.onProgress(50L, 25_000L)

    Thread.sleep(100)
    indicator.finish()

    val output = recorder.output()
    assertTrue(
      output.contains("Estimating:"),
      "Expected Estimating prefix in output, got:\n$output"
    )
    assertTrue(output.contains("files"), "Expected file count in output, got:\n$output")
    assertTrue(output.contains("files/s"), "Expected files/s rate in output, got:\n$output")
    assertTrue(output.contains("/s)"), "Expected storage/s rate in output, got:\n$output")
  }
}
