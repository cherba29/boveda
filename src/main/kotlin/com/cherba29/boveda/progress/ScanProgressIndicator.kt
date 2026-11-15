package com.cherba29.boveda.progress

import com.cherba29.boveda.model.formatBytesShort
import com.cherba29.boveda.model.formatDuration
import com.cherba29.boveda.scanner.ScannedFile
import com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.MultiProgressBarWidgetMaker
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeElapsed
import com.github.ajalt.mordant.widgets.progress.timeRemaining
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Snapshot of progress metrics while scanning and ingesting files.
 *
 * @property filesProcessed The total number of files scanned and ingested so far.
 * @property bytesProcessed The total byte size of files processed so far.
 * @property totalFiles The expected total number of files discovered beforehand, or null if unknown.
 * @property totalBytes The expected total byte size discovered beforehand, or null if unknown.
 * @property startTimeNanos The timestamp in nanoseconds when scanning started.
 * @property action The status description prefix (e.g. "Scanning & ingesting:").
 * @property timeProvider Supplier of current timestamp in nanoseconds (customizable for deterministic testing).
 */
data class ScanProgressState(
  val filesProcessed: Long = 0L,
  val bytesProcessed: Long = 0L,
  val totalFiles: Long? = null,
  val totalBytes: Long? = null,
  val startTimeNanos: Long = System.nanoTime(),
  val action: String = "Scanning & ingesting:",
  val timeProvider: () -> Long = { System.nanoTime() }
) {
  val elapsedNanos: Long
    get() = (timeProvider() - startTimeNanos).coerceAtLeast(0L)

  val elapsedSeconds: Double
    get() = elapsedNanos / 1_000_000_000.0

  /** Average ingestion speed in files per second. */
  val filesPerSecond: Double
    get() = if (elapsedSeconds > 0.000001) filesProcessed / elapsedSeconds else 0.0

  /** Average ingestion speed in megabytes per second (using binary megabyte: 1,048,576 bytes). */
  val megaBytesPerSecond: Double
    get() = if (elapsedSeconds > 0.000001) {
      (bytesProcessed / (1024.0 * 1024.0)) / elapsedSeconds
    } else {
      0.0
    }

  /** Current completion percentage (0.0 to 100.0) based on bytes (or files), or null if indeterminate. */
  val percentage: Double?
    get() = when {
      totalBytes != null && totalBytes > 0L -> {
        (bytesProcessed.toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0)
      }
      totalBytes != null && totalBytes == 0L -> 100.0
      totalFiles != null && totalFiles > 0L -> {
        (filesProcessed.toDouble() / totalFiles * 100.0).coerceIn(0.0, 100.0)
      }
      totalFiles != null && totalFiles == 0L -> 100.0
      else -> null
    }

  /** Estimated remaining time in seconds based on processing speed, or null if indeterminate. */
  val etaSeconds: Double?
    get() = when {
      totalBytes != null -> {
        val remainingBytes = (totalBytes - bytesProcessed).coerceAtLeast(0L)
        if (remainingBytes == 0L) {
          0.0
        } else {
          val bytesPerSec = if (elapsedSeconds > 0.000001) bytesProcessed / elapsedSeconds else 0.0
          if (bytesPerSec > 0.0) remainingBytes / bytesPerSec else null
        }
      }
      totalFiles != null -> {
        val remainingFiles = (totalFiles - filesProcessed).coerceAtLeast(0L)
        if (remainingFiles == 0L) {
          0.0
        } else {
          if (filesPerSecond > 0.0) remainingFiles / filesPerSecond else null
        }
      }
      else -> null
    }

  /** Human-readable representation of estimated remaining time. */
  val formattedEta: String
    get() {
      val eta = etaSeconds ?: return "eta --:--"
      val totalSeconds = eta.toLong()
      val hours = totalSeconds / 3600
      val minutes = (totalSeconds % 3600) / 60
      val seconds = totalSeconds % 60
      return if (hours > 0) {
        String.format(Locale.US, "eta %d:%02d:%02d", hours, minutes, seconds)
      } else {
        String.format(Locale.US, "eta %02d:%02d", minutes, seconds)
      }
    }

  /** Human-readable string representation of processed storage vs total storage. */
  val formattedStorage: String
    get() = if (totalBytes != null) {
      "${formatBytesShort(bytesProcessed)} / ${formatBytesShort(totalBytes)}"
    } else {
      formatBytesShort(bytesProcessed)
    }

  /** Human-readable string representation of processed files. */
  val formattedFiles: String
    get() = if (totalFiles != null) {
      String.format(
        Locale.US,
        "%,d / %,d files",
        filesProcessed,
        totalFiles
      )
    } else if (filesProcessed == 1L) {
      "1 file"
    } else {
      String.format(
        Locale.US,
        "%,d files",
        filesProcessed
      )
    }

  /** Human-readable string representation of average ingestion speeds. */
  val formattedSpeed: String
    get() = String.format(
      Locale.US,
      "%.1f files/s, %.2f MB/s",
      filesPerSecond,
      megaBytesPerSecond
    )
}

/**
 * Progress indicator interface for tracking file scan and database ingestion.
 */
interface ScanProgressIndicator : AutoCloseable {
  /** Configures or updates the pre-computed totals for files and bytes. */
  fun setTotals(totalFiles: Long?, totalBytes: Long?) {}

  /** Called whenever a file has been discovered and processed. */
  fun onFileProcessed(file: ScannedFile)

  /** Finalizes progress reporting and displays the completed summary. */
  fun finish()

  override fun close() = finish()
}

/**
 * Mordant-powered progress indicator displaying an animated spinner, progress bar,
 * percentage, ETA, processed file count, and average ingestion speeds.
 *
 * In interactive terminals, it updates dynamically in-place using Mordant's progress bar animation.
 * When completed (or in non-interactive environments), it prints a clean final summary line.
 *
 * @param terminal The Mordant [Terminal] instance.
 * @param totalFiles Pre-computed total number of regular files discovered beforehand.
 * @param totalBytes Pre-computed total byte size discovered beforehand.
 * @param timeProvider Clock provider returning nanoseconds, defaulting to [System.nanoTime].
 */
class MordantScanProgressIndicator(
  private val terminal: Terminal,
  totalFiles: Long? = null,
  totalBytes: Long? = null,
  private val timeProvider: () -> Long = { System.nanoTime() }
) : ScanProgressIndicator {
  private val filesCount = AtomicLong(0)
  private val bytesCount = AtomicLong(0)
  private val totalFilesRef = AtomicReference<Long?>(totalFiles)
  private val totalBytesRef = AtomicReference<Long?>(totalBytes)
  private val startNanos = timeProvider()
  private val finished = AtomicBoolean(false)

  private val isInteractive: Boolean = terminal.terminalInfo.outputInteractive
  private var executor: ExecutorService? = null
  private var animator: ThreadProgressTaskAnimator<ScanProgressState>? = null

  init {
    if (isInteractive) {
      val layout = progressBarContextLayout<ScanProgressState> {
        spinner(Spinner.Dots())
        text { "${context.action} " }
        progressBar()
        percentage()
        text { context.formattedFiles }
        text { "(${context.formattedSpeed})" }
        timeRemaining(prefix = "eta ")
        timeElapsed()
      }

      val exec = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "boveda-progress-animator").apply { isDaemon = true }
      }
      this.executor = exec

      val initialTotal = totalBytes?.takeIf { it > 0L } ?: totalFiles?.takeIf { it > 0L }
      val anim = layout.animateOnThread(
        terminal = terminal,
        context = currentState(),
        total = initialTotal,
        completed = 0L,
        maker = MultiProgressBarWidgetMaker
      )
      this.animator = anim
      anim.execute(exec)
    }
  }

  private fun currentState(action: String = "Scanning & ingesting:"): ScanProgressState {
    return ScanProgressState(
      filesProcessed = filesCount.get(),
      bytesProcessed = bytesCount.get(),
      totalFiles = totalFilesRef.get(),
      totalBytes = totalBytesRef.get(),
      startTimeNanos = startNanos,
      action = action,
      timeProvider = timeProvider
    )
  }

  override fun setTotals(totalFiles: Long?, totalBytes: Long?) {
    totalFilesRef.set(totalFiles)
    totalBytesRef.set(totalBytes)
    if (isInteractive) {
      val (currentCompleted, currentTotal) = when {
        totalBytes != null && totalBytes > 0L -> bytesCount.get() to totalBytes
        totalFiles != null && totalFiles > 0L -> filesCount.get() to totalFiles
        else -> bytesCount.get() to null
      }
      animator?.update {
        context = currentState()
        completed = currentCompleted
        total = currentTotal
      }
    }
  }

  override fun onFileProcessed(file: ScannedFile) {
    if (finished.get()) return
    val count = filesCount.incrementAndGet()
    val bytes = bytesCount.addAndGet(file.size)

    if (isInteractive) {
      val tBytes = totalBytesRef.get()
      val tFiles = totalFilesRef.get()
      val (currentCompleted, currentTotal) = when {
        tBytes != null && tBytes > 0L -> bytes to tBytes
        tFiles != null && tFiles > 0L -> count to tFiles
        else -> bytes to null
      }

      animator?.update {
        context = ScanProgressState(
          filesProcessed = count,
          bytesProcessed = bytes,
          totalFiles = tFiles,
          totalBytes = tBytes,
          startTimeNanos = startNanos,
          action = "Scanning & ingesting:",
          timeProvider = timeProvider
        )
        completed = currentCompleted
        total = currentTotal
      }
    }
  }

  override fun finish() {
    if (!finished.compareAndSet(false, true)) return

    val state = currentState(action = "Scanned:")
    val duration = (timeProvider() - startNanos).coerceAtLeast(0L).nanoseconds

    if (isInteractive) {
      animator?.clear()
      executor?.shutdownNow()
    }

    val filesSummary = if (state.filesProcessed == 1L) {
      "1 file"
    } else {
      String.format(Locale.US, "%,d files", state.filesProcessed)
    }

    val prefix = if (isInteractive) terminal.theme.success("✔ ") else ""
    terminal.println(
      "${prefix}Scanned $filesSummary (${formatBytesShort(
        state.bytesProcessed
      )}) in ${formatDuration(duration)} (${state.formattedSpeed})"
    )
  }
}

/**
 * No-operation progress indicator for non-quiet mode or testing.
 */
class NoOpScanProgressIndicator : ScanProgressIndicator {
  override fun onFileProcessed(file: ScannedFile) = Unit
  override fun finish() = Unit
}
