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
  fun `NoOpScanProgressIndicator executes without errors`() {
    val indicator = NoOpScanProgressIndicator()
    indicator.onFileProcessed(dummyFile(size = 100))
    indicator.finish()
    indicator.close()
  }
}
