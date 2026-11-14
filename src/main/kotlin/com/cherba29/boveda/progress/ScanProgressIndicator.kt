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
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.spinner
import com.github.ajalt.mordant.widgets.progress.text
import com.github.ajalt.mordant.widgets.progress.timeElapsed
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Snapshot of progress metrics while scanning and ingesting files.
 *
 * @property filesProcessed The total number of files scanned and ingested so far.
 * @property bytesProcessed The total byte size of files processed so far.
 * @property startTimeNanos The timestamp in nanoseconds when scanning started.
 * @property action The status description prefix (e.g. "Scanning & ingesting:").
 * @property timeProvider Supplier of current timestamp in nanoseconds (customizable for deterministic testing).
 */
data class ScanProgressState(
    val filesProcessed: Long = 0L,
    val bytesProcessed: Long = 0L,
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
        get() = if (elapsedSeconds > 0.000001) (bytesProcessed / (1024.0 * 1024.0)) / elapsedSeconds else 0.0

    /** Human-readable string representation of processed files. */
    val formattedFiles: String
        get() = if (filesProcessed == 1L) "1 file" else String.format(Locale.US, "%,d files", filesProcessed)

    /** Human-readable string representation of average ingestion speeds. */
    val formattedSpeed: String
        get() = String.format(Locale.US, "%.1f files/s, %.2f MB/s", filesPerSecond, megaBytesPerSecond)
}

/**
 * Progress indicator interface for tracking file scan and database ingestion.
 */
interface ScanProgressIndicator : AutoCloseable {
    /** Called whenever a file has been discovered and processed. */
    fun onFileProcessed(file: ScannedFile)

    /** Finalizes progress reporting and displays the completed summary. */
    fun finish()

    override fun close() = finish()
}

/**
 * Mordant-powered progress indicator displaying an animated spinner, processed file count,
 * and average ingestion speeds (files/second and megabytes/second).
 *
 * In interactive terminals, it updates dynamically in-place using Mordant's progress bar animation.
 * When completed (or in non-interactive environments), it prints a clean final summary line.
 *
 * @param terminal The Mordant [Terminal] instance.
 * @param timeProvider Clock provider returning nanoseconds, defaulting to [System.nanoTime].
 */
class MordantScanProgressIndicator(
    private val terminal: Terminal,
    private val timeProvider: () -> Long = { System.nanoTime() }
) : ScanProgressIndicator {
    private val filesCount = AtomicLong(0)
    private val bytesCount = AtomicLong(0)
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
                text { context.formattedFiles }
                text { "(${context.formattedSpeed})" }
                timeElapsed()
            }

            val exec = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "boveda-progress-animator").apply { isDaemon = true }
            }
            this.executor = exec

            val anim = layout.animateOnThread(
                terminal = terminal,
                context = currentState(),
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
            startTimeNanos = startNanos,
            action = action,
            timeProvider = timeProvider
        )
    }

    override fun onFileProcessed(file: ScannedFile) {
        if (finished.get()) return
        val count = filesCount.incrementAndGet()
        val bytes = bytesCount.addAndGet(file.size)

        if (isInteractive) {
            animator?.update {
                context = ScanProgressState(
                    filesProcessed = count,
                    bytesProcessed = bytes,
                    startTimeNanos = startNanos,
                    action = "Scanning & ingesting:",
                    timeProvider = timeProvider
                )
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

        val prefix = if (isInteractive) terminal.theme.success("✔ ") else ""
        terminal.println(
            "${prefix}Scanned ${state.formattedFiles} (${formatBytesShort(state.bytesProcessed)}) in ${formatDuration(duration)} (${state.formattedSpeed})"
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
