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

  /**
   * Current completion percentage (0.0 to 100.0) based on file count (falling back to bytes if
   * file count is unavailable), or null if indeterminate.
   */
  val percentage: Double?
    get() = when {
      totalFiles != null && totalFiles > 0L -> {
        (filesProcessed.toDouble() / totalFiles * 100.0).coerceIn(0.0, 100.0)
      }
      totalFiles != null && totalFiles == 0L -> 100.0
      totalBytes != null && totalBytes > 0L -> {
        (bytesProcessed.toDouble() / totalBytes * 100.0).coerceIn(0.0, 100.0)
      }
      totalBytes != null && totalBytes == 0L -> 100.0
      else -> null
    }

  /**
   * Estimated remaining time in seconds based on file processing speed (falling back to byte
   * processing speed if file count is unavailable), or null if indeterminate.
   */
  val etaSeconds: Double?
    get() = when {
      totalFiles != null -> {
        val remainingFiles = (totalFiles - filesProcessed).coerceAtLeast(0L)
        if (remainingFiles == 0L) {
          0.0
        } else {
          if (filesPerSecond > 0.0) remainingFiles / filesPerSecond else null
        }
      }
      totalBytes != null -> {
        val remainingBytes = (totalBytes - bytesProcessed).coerceAtLeast(0L)
        if (remainingBytes == 0L) {
          0.0
        } else {
          val bytesPerSec = if (elapsedSeconds > 0.000001) bytesProcessed / elapsedSeconds else 0.0
          if (bytesPerSec > 0.0) remainingBytes / bytesPerSec else null
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

  /** Configures or updates the pre-computed totals and initial counts for files and bytes. */
  fun setTotals(totalFiles: Long?, totalBytes: Long?, initialFiles: Long, initialBytes: Long) {
    setTotals(totalFiles, totalBytes)
  }

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
 * @param initialFiles Initial number of files already processed (e.g. from a resumed scan).
 * @param initialBytes Initial byte size of files already processed (e.g. from a resumed scan).
 * @param timeProvider Clock provider returning nanoseconds, defaulting to [System.nanoTime].
 */
class MordantScanProgressIndicator(
  private val terminal: Terminal,
  totalFiles: Long? = null,
  totalBytes: Long? = null,
  initialFiles: Long = 0L,
  initialBytes: Long = 0L,
  private val timeProvider: () -> Long = { System.nanoTime() },
  updateIntervalMillis: Long = DEFAULT_UPDATE_INTERVAL_MILLIS
) : ScanProgressIndicator {

  companion object {
    const val DEFAULT_UPDATE_INTERVAL_MILLIS = 100L
  }

  private val filesCount = AtomicLong(initialFiles)
  private val bytesCount = AtomicLong(initialBytes)
  private val totalFilesRef = AtomicReference<Long?>(totalFiles)
  private val totalBytesRef = AtomicReference<Long?>(totalBytes)
  private val startNanos = timeProvider()
  private val lastUpdateNanos = AtomicLong(0L)
  private val updateIntervalNanos = updateIntervalMillis * 1_000_000L
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

      val (initialCompleted, initialTotal) = resolveProgress(
        files = initialFiles,
        bytes = initialBytes,
        totalFiles = totalFiles,
        totalBytes = totalBytes
      )
      val anim = layout.animateOnThread(
        terminal = terminal,
        context = currentState(),
        total = initialTotal,
        completed = initialCompleted,
        maker = MultiProgressBarWidgetMaker
      )
      this.animator = anim
      anim.execute(exec)
    }
  }

  private fun resolveProgress(
    files: Long,
    bytes: Long,
    totalFiles: Long?,
    totalBytes: Long?
  ): Pair<Long, Long?> = when {
    totalFiles != null && totalFiles > 0L -> files to totalFiles
    totalBytes != null && totalBytes > 0L -> bytes to totalBytes
    else -> files to null
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

  override fun setTotals(totalFiles: Long?, totalBytes: Long?) =
    setTotals(totalFiles, totalBytes, initialFiles = 0L, initialBytes = 0L)

  override fun setTotals(
    totalFiles: Long?,
    totalBytes: Long?,
    initialFiles: Long,
    initialBytes: Long
  ) {
    if (initialFiles > 0L) filesCount.set(initialFiles)
    if (initialBytes > 0L) bytesCount.set(initialBytes)
    totalFilesRef.set(totalFiles)
    totalBytesRef.set(totalBytes)
    if (isInteractive) {
      val (currentCompleted, currentTotal) = resolveProgress(
        files = filesCount.get(),
        bytes = bytesCount.get(),
        totalFiles = totalFiles,
        totalBytes = totalBytes
      )
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
      val shouldUpdate = if (updateIntervalNanos <= 0L) {
        true
      } else {
        val now = timeProvider()
        val last = lastUpdateNanos.get()
        (last == 0L || now - last >= updateIntervalNanos) &&
          lastUpdateNanos.compareAndSet(last, now)
      }
      if (shouldUpdate) {
        val tBytes = totalBytesRef.get()
        val tFiles = totalFilesRef.get()
        val (currentCompleted, currentTotal) = resolveProgress(
          files = count,
          bytes = bytes,
          totalFiles = tFiles,
          totalBytes = tBytes
        )

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
