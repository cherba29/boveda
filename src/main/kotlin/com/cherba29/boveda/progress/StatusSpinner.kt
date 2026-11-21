package com.cherba29.boveda.progress

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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight Mordant spinner displaying live status messages and elapsed time during long-running
 * database queries or filesystem operations in interactive terminals.
 *
 * Clears itself cleanly on [close]/[finish] so subsequent prompts or summaries render cleanly.
 * Acts as a no-op when [terminal] output is not interactive.
 */
class StatusSpinner(
  terminal: Terminal,
  initialMessage: String
) : AutoCloseable {
  private val finished = AtomicBoolean(false)
  private val isInteractive: Boolean = terminal.terminalInfo.outputInteractive
  private var executor: ExecutorService? = null
  private var animator: ThreadProgressTaskAnimator<String>? = null

  init {
    if (isInteractive) {
      val layout = progressBarContextLayout<String> {
        spinner(Spinner.Dots())
        text { context }
        timeElapsed()
      }
      val exec = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "boveda-status-spinner").apply { isDaemon = true }
      }
      this.executor = exec
      val anim = layout.animateOnThread(
        terminal = terminal,
        context = initialMessage,
        maker = MultiProgressBarWidgetMaker
      )
      this.animator = anim
      anim.execute(exec)
    }
  }

  /** Updates the displayed status message while the spinner is active. */
  fun update(message: String) {
    if (finished.get() || !isInteractive) return
    animator?.update {
      context = message
    }
  }

  /** Stops and clears the spinner from the terminal. */
  fun finish() {
    if (!finished.compareAndSet(false, true)) return
    if (isInteractive) {
      animator?.clear()
      executor?.shutdownNow()
    }
  }

  override fun close() = finish()
}
