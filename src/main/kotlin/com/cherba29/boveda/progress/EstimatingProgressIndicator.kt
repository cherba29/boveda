package com.cherba29.boveda.progress

import com.cherba29.boveda.model.formatBytesShort
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Snapshot of progress metrics while estimating total files and storage.
 *
 * @property filesScanned Total number of files scanned so far.
 * @property bytesScanned Total bytes across all files scanned so far.
 * @property startTimeNanos The timestamp in nanoseconds when estimation started.
 * @property action The status description prefix (e.g. "Estimating files to scan:").
 * @property timeProvider Supplier of current timestamp in nanoseconds.
 */
data class EstimatingProgressState(
  val filesScanned: Long = 0L,
  val bytesScanned: Long = 0L,
  val startTimeNanos: Long = System.nanoTime(),
  val action: String = "Estimating files to scan:",
  val timeProvider: () -> Long = { System.nanoTime() }
) {
  val formattedFiles: String
    get() = if (filesScanned == 1L) {
      "1 file scanned"
    } else {
      String.format(Locale.US, "%,d files scanned", filesScanned)
    }

  val formattedStorage: String
    get() = "${formatBytesShort(bytesScanned)} scanned"
}

/**
 * Progress indicator interface for tracking initial metadata estimation before hashing.
 */
interface EstimatingProgressIndicator : AutoCloseable {
  /** Called periodically or as files and bytes are scanned during estimation. */
  fun onProgress(filesScanned: Long, bytesScanned: Long)

  /** Finalizes progress reporting and cleans up resources. */
  fun finish()

  override fun close() = finish()
}

/**
 * Mordant-powered animated indicator for the initial scan/estimation phase.
 *
 * In interactive terminals, displays an animated spinner with files scanned,
 * storage scanned, and elapsed time, updating periodically (e.g. every 500ms).
 * Clears cleanly when estimation is complete so the next phase can take over the terminal.
 */
class MordantEstimatingProgressIndicator(
  private val terminal: Terminal,
  private val updateIntervalMillis: Long = 500L,
  private val timeProvider: () -> Long = { System.nanoTime() }
) : EstimatingProgressIndicator {
  private val filesCount = AtomicLong(0L)
  private val bytesCount = AtomicLong(0L)
  private val startNanos = timeProvider()
  private val lastUpdateNanos = AtomicLong(0L)
  private val updateIntervalNanos = updateIntervalMillis * 1_000_000L
  private val finished = AtomicBoolean(false)

  private val isInteractive: Boolean = terminal.terminalInfo.outputInteractive
  private var executor: ExecutorService? = null
  private var scheduler: ScheduledExecutorService? = null
  private var animator: ThreadProgressTaskAnimator<EstimatingProgressState>? = null

  init {
    if (isInteractive) {
      val layout = progressBarContextLayout<EstimatingProgressState> {
        spinner(Spinner.Dots())
        text { "${context.action} " }
        text { context.formattedFiles }
        text { ", ${context.formattedStorage} " }
        timeElapsed()
      }

      val exec = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "boveda-estimating-animator").apply { isDaemon = true }
      }
      this.executor = exec

      val anim = layout.animateOnThread(
        terminal = terminal,
        context = currentState(),
        maker = MultiProgressBarWidgetMaker
      )
      this.animator = anim
      anim.execute(exec)

      if (updateIntervalMillis > 0L) {
        val sched = Executors.newSingleThreadScheduledExecutor { runnable ->
          Thread(runnable, "boveda-estimating-scheduler").apply { isDaemon = true }
        }
        this.scheduler = sched
        sched.scheduleAtFixedRate(
          { updateAnimation() },
          updateIntervalMillis,
          updateIntervalMillis,
          TimeUnit.MILLISECONDS
        )
      }
    }
  }

  private fun currentState(action: String = "Estimating files to scan:"): EstimatingProgressState {
    return EstimatingProgressState(
      filesScanned = filesCount.get(),
      bytesScanned = bytesCount.get(),
      startTimeNanos = startNanos,
      action = action,
      timeProvider = timeProvider
    )
  }

  private fun updateAnimation() {
    if (finished.get() || !isInteractive) return
    animator?.update {
      context = currentState()
      completed = filesCount.get()
    }
  }

  override fun onProgress(filesScanned: Long, bytesScanned: Long) {
    if (finished.get()) return
    filesCount.accumulateAndGet(filesScanned) { current, new -> maxOf(current, new) }
    bytesCount.accumulateAndGet(bytesScanned) { current, new -> maxOf(current, new) }

    if (isInteractive) {
      val now = timeProvider()
      val last = lastUpdateNanos.get()
      if (now - last >= updateIntervalNanos) {
        if (lastUpdateNanos.compareAndSet(last, now)) {
          updateAnimation()
        }
      }
    }
  }

  override fun finish() {
    if (!finished.compareAndSet(false, true)) return
    if (isInteractive) {
      scheduler?.shutdownNow()
      animator?.clear()
      executor?.shutdownNow()
    }
  }
}

/**
 * No-operation progress indicator for non-interactive mode or testing.
 */
class NoOpEstimatingProgressIndicator : EstimatingProgressIndicator {
  override fun onProgress(filesScanned: Long, bytesScanned: Long) = Unit
  override fun finish() = Unit
}
