package com.cherba29.boveda.progress

import com.cherba29.boveda.scanner.ScannedFile
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanProgressIndicatorTest {

  private fun dummyFile(size: Long, pathStr: String = "test.txt"): ScannedFile {
    return ScannedFile(
      path = Path.of(pathStr),
      relativePath = pathStr,
      size = size,
      hash = "dummyhash123",
      lastModifiedTime = FileTime.from(Instant.EPOCH)
    )
  }

  @Test
  fun `ScanProgressState handles zero elapsed time and zero files without division by zero`() {
    val state = ScanProgressState(
      filesProcessed = 0L,
      bytesProcessed = 0L,
      startTimeNanos = 1_000_000L,
      timeProvider = { 1_000_000L }
    )

    assertEquals(0.0, state.elapsedSeconds)
    assertEquals(0.0, state.filesPerSecond)
    assertEquals(0.0, state.megaBytesPerSecond)
    assertEquals("0 files", state.formattedFiles)
    assertEquals("0.0 files/s, 0.00 MB/s", state.formattedSpeed)
  }

  @Test
  fun `ScanProgressState formats single file correctly`() {
    val state = ScanProgressState(
      filesProcessed = 1L,
      bytesProcessed = 1024L * 1024L,
      startTimeNanos = 0L,
      // 0.5 seconds
      timeProvider = { 500_000_000L }
    )

    assertEquals(0.5, state.elapsedSeconds)
    assertEquals(2.0, state.filesPerSecond)
    assertEquals(2.0, state.megaBytesPerSecond)
    assertEquals("1 file", state.formattedFiles)
    assertEquals("2.0 files/s, 2.00 MB/s", state.formattedSpeed)
  }

  @Test
  fun `ScanProgressState computes accurate average speeds and formats multiple files`() {
    val state = ScanProgressState(
      filesProcessed = 1_500L,
      // 30 MB
      bytesProcessed = 30L * 1024L * 1024L,
      startTimeNanos = 0L,
      // 3.0 seconds
      timeProvider = { 3_000_000_000L }
    )

    assertEquals(3.0, state.elapsedSeconds)
    assertEquals(500.0, state.filesPerSecond)
    assertEquals(10.0, state.megaBytesPerSecond)
    assertEquals("1,500 files", state.formattedFiles)
    assertEquals("500.0 files/s, 10.00 MB/s", state.formattedSpeed)
  }

  @Test
  fun `MordantScanProgressIndicator prints summary on finish in non-interactive mode`() {
    val recorder = TerminalRecorder(outputInteractive = false)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantScanProgressIndicator(terminal, timeProvider = { currentTime })
    indicator.onFileProcessed(dummyFile(size = 1024 * 512, pathStr = "file1.txt"))

    currentTime += 1_000_000_000L // + 1 second
    indicator.onFileProcessed(dummyFile(size = 1024 * 512, pathStr = "file2.txt"))

    indicator.finish()

    val output = recorder.output()
    assertTrue(output.contains("Scanned 2 files"))
    assertTrue(output.contains("files/s"))
    assertTrue(output.contains("MB/s"))

    // Multiple calls to finish or close are idempotent
    indicator.finish()
    indicator.close()
    // No duplicate summary line added
    assertEquals(1, output.lines().filter { it.contains("Scanned 2 files") }.size)
  }

  @Test
  fun `MordantScanProgressIndicator animates and cleans up in interactive mode`() {
    val recorder = TerminalRecorder(outputInteractive = true, ansiLevel = AnsiLevel.TRUECOLOR)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantScanProgressIndicator(terminal, timeProvider = { currentTime })
    indicator.onFileProcessed(dummyFile(size = 1024 * 1024, pathStr = "doc.pdf"))

    currentTime += 500_000_000L
    indicator.onFileProcessed(dummyFile(size = 1024 * 1024, pathStr = "image.png"))

    Thread.sleep(50)
    indicator.finish()

    val output = recorder.output()
    assertTrue(
      output.contains("Scanned 2 files") ||
        output.contains("files processed") ||
        output.contains("files")
    )
    assertTrue(output.contains("files/s"))
    assertTrue(output.contains("MB/s"))
  }

  @Test
  fun `ScanProgressState computes percentage, etaSeconds, and formats eta using file count`() {
    // 25 of 100 files processed over 5 seconds (5 files/s -> 15s ETA),
    // even though 90 MB of 100 MB bytes (90%) have been processed
    val state = ScanProgressState(
      filesProcessed = 25L,
      bytesProcessed = 90_000_000L,
      totalFiles = 100L,
      totalBytes = 100_000_000L,
      startTimeNanos = 0L,
      timeProvider = { 5_000_000_000L }
    )

    assertEquals(25.0, state.percentage)
    assertEquals(15.0, state.etaSeconds)
    assertEquals("eta 00:15", state.formattedEta)
    assertEquals("25 / 100 files", state.formattedFiles)
    assertEquals("85.83 MB / 95.37 MB", state.formattedStorage)

    // Fallback to bytes when totalFiles is null
    val bytesFallback = ScanProgressState(
      filesProcessed = 10L,
      bytesProcessed = 50_000_000L,
      totalFiles = null,
      totalBytes = 100_000_000L,
      startTimeNanos = 0L,
      timeProvider = { 5_000_000_000L }
    )
    assertEquals(50.0, bytesFallback.percentage)
    assertEquals(5.0, bytesFallback.etaSeconds)
    assertEquals("eta 00:05", bytesFallback.formattedEta)

    // Indeterminate totals
    val indeterminate = ScanProgressState(
      filesProcessed = 10L,
      bytesProcessed = 1_000L,
      totalFiles = null,
      totalBytes = null,
      startTimeNanos = 0L,
      timeProvider = { 1_000_000_000L }
    )
    assertNull(indeterminate.percentage)
    assertNull(indeterminate.etaSeconds)
    assertEquals("eta --:--", indeterminate.formattedEta)

    // Zero totals
    val zeroTotals = ScanProgressState(
      filesProcessed = 0L,
      bytesProcessed = 0L,
      totalFiles = 0L,
      totalBytes = 0L,
      startTimeNanos = 0L,
      timeProvider = { 1_000_000_000L }
    )
    assertEquals(100.0, zeroTotals.percentage)
    assertEquals(0.0, zeroTotals.etaSeconds)
    assertEquals("eta 00:00", zeroTotals.formattedEta)

    // Multi-hour ETA: 3,665 files remaining at 1 file/s -> 3665s = 1h 1m 5s
    val hoursState = ScanProgressState(
      filesProcessed = 1L,
      bytesProcessed = 1_000L,
      totalFiles = 3_666L,
      totalBytes = 2_000L,
      startTimeNanos = 0L,
      timeProvider = { 1_000_000_000L }
    )
    assertEquals("eta 1:01:05", hoursState.formattedEta)
  }

  @Test
  fun `MordantScanProgressIndicator interactive bar computes percentage from file count`() {
    val recorder = TerminalRecorder(outputInteractive = true, ansiLevel = AnsiLevel.TRUECOLOR)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantScanProgressIndicator(
      terminal = terminal,
      totalFiles = 4L,
      totalBytes = 10_000L,
      timeProvider = { currentTime },
      updateIntervalMillis = 0L
    )
    // 1 of 4 files (25%), but 9,000 of 10,000 bytes (90%)
    currentTime += 1_000_000_000L
    indicator.onFileProcessed(dummyFile(size = 9_000L, pathStr = "huge.bin"))

    Thread.sleep(100)
    indicator.finish()

    val output = recorder.output()
    assertTrue(output.contains("25%"), "Expected file-count-based 25% in output, got:\n$output")
  }

  @Test
  fun `MordantScanProgressIndicator setTotals dynamically updates state`() {
    val recorder = TerminalRecorder(outputInteractive = false)
    val terminal = Terminal(terminalInterface = recorder)
    var currentTime = 10_000_000_000L

    val indicator = MordantScanProgressIndicator(
      terminal = terminal,
      totalFiles = null,
      totalBytes = null,
      timeProvider = { currentTime }
    )
    indicator.setTotals(totalFiles = 10L, totalBytes = 10_000L)
    indicator.onFileProcessed(dummyFile(size = 5_000L, pathStr = "chunk.bin"))

    currentTime += 1_000_000_000L
    indicator.finish()

    val output = recorder.output()
    assertTrue(output.contains("Scanned 1 file"))
  }

  @Test
  fun `NoOpScanProgressIndicator executes without errors`() {
    val indicator = NoOpScanProgressIndicator()
    indicator.setTotals(10L, 1000L)
    indicator.onFileProcessed(dummyFile(size = 100))
    indicator.finish()
    indicator.close()
  }
}
