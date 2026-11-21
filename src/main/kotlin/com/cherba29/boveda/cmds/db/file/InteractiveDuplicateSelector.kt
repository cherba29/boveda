package com.cherba29.boveda.cmds.db.file

import com.cherba29.boveda.model.DuplicateGroup
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatDisplayPath
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.input.isCtrlC
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import java.io.File

/**
 * Interactive selector for choosing duplicate files to delete from the terminal.
 * Supports arrow key navigation, multi-selection toggling, and escape cancellation.
 */
class InteractiveDuplicateSelector(
  private val terminal: Terminal = Terminal(),
  private val pathSeparator: Char = File.separatorChar
) {

  private data class SelectableItem(
    val path: String,
    val displayLabel: String
  )

  private fun buildItems(group: DuplicateGroup): List<SelectableItem> {
    val fileItems = group.paths.map { path ->
      SelectableItem(
        path = path,
        displayLabel = formatDisplayPath(path, pathSeparator)
      )
    }
    val symlinkItems = group.symlinkPaths.map { sym ->
      val symPath = formatDisplayPath(sym.path, pathSeparator)
      val symTarget = formatDisplayPath(sym.target, pathSeparator)
      SelectableItem(
        path = sym.path,
        displayLabel = "$symPath [symlink -> $symTarget]"
      )
    }
    return fileItems + symlinkItems
  }

  /**
   * Prompts the user to select one or more duplicate files (or symlinks) from [group] to delete.
   *
   * @return A list of paths chosen for deletion, an empty list if none chosen,
   *         or `null` if the user pressed Esc / cancelled.
   */
  fun selectDuplicates(group: DuplicateGroup): List<String>? {
    val items = buildItems(group)
    if (items.isEmpty()) return emptyList()

    if (!terminal.terminalInfo.inputInteractive) {
      return fallbackLinePrompt(group, items)
    }

    return try {
      interactivePrompt(group, items)
    } catch (_: Exception) {
      fallbackLinePrompt(group, items)
    }
  }

  private fun interactivePrompt(group: DuplicateGroup, items: List<SelectableItem>): List<String>? {
    val selected = BooleanArray(items.size) { false }
    var cursor = 0

    val headerText = buildString {
      appendLine("### ==== Largest Duplicate Group ==== ###")
      appendLine(
        "Size: ${formatBytes(group.size)} | ${group.copyCount} copies | " +
          "Combined: ${formatBytes(group.totalDuplicateBytes)} | " +
          "Wasted: ${formatBytes(group.wastedBytes)}"
      )
      appendLine("Hash: ${group.hash}")
      append("Select duplicates to delete:")
    }

    fun renderContent(): String {
      return buildString {
        appendLine(headerText)
        items.forEachIndexed { index, item ->
          val isCursor = index == cursor
          val isSel = selected[index]
          val cursorPrefix = if (isCursor) cyan(bold("> ")) else "  "
          val check = if (isSel) brightRed(bold("[x]")) else "[ ]"
          val label = item.displayLabel
          val styledPath = when {
            isSel && isCursor -> brightRed(bold(label))
            isSel -> brightRed(label)
            isCursor -> bold(label)
            else -> label
          }
          appendLine("$cursorPrefix$check $styledPath")
        }
        val instructions = "  (↑/↓: navigate, Space/x: toggle, a: all, " +
          "s: skip, Enter: delete, Esc: quit)"
        append(dim(instructions))
      }
    }

    val anim = terminal.animation<Unit> {
      Text(renderContent())
    }

    anim.update(Unit)

    terminal.enterRawMode(MouseTracking.Off).use { rawMode ->
      while (true) {
        val event = rawMode.readKey()
        when {
          event.isCtrlC || event.key == "Escape" || event.key.equals("q", ignoreCase = true) -> {
            anim.clear()
            return null
          }
          event.key == "ArrowUp" || event.key == "k" || event.key == "Up" -> {
            cursor = (cursor - 1).coerceAtLeast(0)
            anim.update(Unit)
          }
          event.key == "ArrowDown" || event.key == "j" || event.key == "Down" -> {
            cursor = (cursor + 1).coerceAtMost(items.lastIndex)
            anim.update(Unit)
          }
          event.key == " " || event.key.equals("x", ignoreCase = true) -> {
            selected[cursor] = !selected[cursor]
            anim.update(Unit)
          }
          event.key.equals("a", ignoreCase = true) -> {
            val allSelected = selected.all { it }
            selected.fill(!allSelected)
            anim.update(Unit)
          }
          event.key.equals("s", ignoreCase = true) ||
            event.key.equals("skip", ignoreCase = true) -> {
            anim.clear()
            return emptyList()
          }
          event.key == "Enter" || event.key == "\n" || event.key == "\r" -> {
            anim.clear()
            return items.filterIndexed { index, _ -> selected[index] }.map { it.path }
          }
        }
      }
    }
  }

  private fun fallbackLinePrompt(
    group: DuplicateGroup,
    items: List<SelectableItem>
  ): List<String>? {
    terminal.println("### ==== Largest Duplicate Group ==== ###")
    terminal.println(
      "Size: ${formatBytes(group.size)} | ${group.copyCount} copies | " +
        "Combined: ${formatBytes(group.totalDuplicateBytes)} | " +
        "Wasted: ${formatBytes(group.wastedBytes)}"
    )
    terminal.println("Hash: ${group.hash}")
    terminal.println("Duplicate files:")
    items.forEachIndexed { index, item ->
      terminal.println("  ${index + 1}. ${item.displayLabel}")
    }
    terminal.print(
      "Enter numbers to delete (e.g. 1, 2), 'all', 's'/'skip' to skip, or 'esc'/'q' to quit: "
    )
    val line = terminal.readLineOrNull(hideInput = false)?.trim() ?: return null
    if (line.equals("esc", ignoreCase = true) ||
      line.equals("q", ignoreCase = true) ||
      line.equals("quit", ignoreCase = true)
    ) {
      return null
    }
    if (line.equals("all", ignoreCase = true)) {
      return items.map { it.path }
    }
    if (line.isEmpty() ||
      line.equals("s", ignoreCase = true) ||
      line.equals("skip", ignoreCase = true)
    ) {
      return emptyList()
    }
    val parts = line.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
    val selected = mutableListOf<String>()
    for (part in parts) {
      val num = part.toIntOrNull()
      if (num != null && num in 1..items.size) {
        val path = items[num - 1].path
        if (path !in selected) {
          selected.add(path)
        }
      }
    }
    return selected
  }
}
