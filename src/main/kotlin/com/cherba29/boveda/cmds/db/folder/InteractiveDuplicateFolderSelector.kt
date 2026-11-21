package com.cherba29.boveda.cmds.db.folder

import com.cherba29.boveda.model.DuplicateFolderGroup
import com.cherba29.boveda.model.formatBytes
import com.cherba29.boveda.model.formatBytesShort
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

/**
 * Interactive selector for choosing 100% matching duplicate folders to delete from the terminal.
 * Supports arrow key navigation, multi-selection toggling, and escape cancellation.
 */
class InteractiveDuplicateFolderSelector(
  private val terminal: Terminal = Terminal()
) {

  /**
   * Prompts the user to select one or more identical folders from [group] to delete.
   *
   * @return A list of folder paths chosen for deletion, an empty list if none chosen (skip),
   *         or `null` if the user pressed Esc / cancelled.
   */
  fun selectDuplicateFolders(group: DuplicateFolderGroup): List<String>? {
    val items = group.paths
    if (items.isEmpty()) return emptyList()

    if (!terminal.terminalInfo.inputInteractive) {
      return fallbackLinePrompt(group)
    }

    return try {
      interactivePrompt(group)
    } catch (_: Exception) {
      fallbackLinePrompt(group)
    }
  }

  private fun formatSampleFiles(group: DuplicateFolderGroup): String? {
    if (group.sampleFiles.isEmpty()) return null
    val joined = group.sampleFiles.joinToString(", ") { (name, sz) ->
      "$name (${formatBytesShort(sz)})"
    }
    val suffix = if (group.fileCount > group.sampleFiles.size) ", ..." else ""
    return "Files: $joined$suffix"
  }

  private fun interactivePrompt(group: DuplicateFolderGroup): List<String>? {
    val items = group.paths
    val selected = BooleanArray(items.size) { false }
    var cursor = 0

    val filesLabel = if (group.fileCount == 1) "1 file" else "${group.fileCount} files"
    val sampleLine = formatSampleFiles(group)
    val headerText = buildString {
      appendLine("### ==== Largest 100% Matching Duplicate Folder Group ==== ###")
      appendLine(
        "Folder Size: ${formatBytes(group.folderBytes)} ($filesLabel) | " +
          "${group.copyCount} identical folders | " +
          "Combined: ${formatBytes(group.totalDuplicateBytes)} | " +
          "Wasted: ${formatBytes(group.wastedBytes)}"
      )
      if (sampleLine != null) {
        appendLine(sampleLine)
      }
      append("Select duplicate folders to delete:")
    }

    fun renderContent(): String {
      return buildString {
        appendLine(headerText)
        items.forEachIndexed { index, path ->
          val isCursor = index == cursor
          val isSel = selected[index]
          val cursorPrefix = if (isCursor) cyan(bold("> ")) else "  "
          val check = if (isSel) brightRed(bold("[x]")) else "[ ]"
          val styledPath = when {
            isSel && isCursor -> brightRed(bold(path))
            isSel -> brightRed(path)
            isCursor -> bold(path)
            else -> path
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
            return items.filterIndexed { index, _ -> selected[index] }
          }
        }
      }
    }
  }

  private fun fallbackLinePrompt(group: DuplicateFolderGroup): List<String>? {
    val items = group.paths
    val filesLabel = if (group.fileCount == 1) "1 file" else "${group.fileCount} files"
    terminal.println("### ==== Largest 100% Matching Duplicate Folder Group ==== ###")
    terminal.println(
      "Folder Size: ${formatBytes(group.folderBytes)} ($filesLabel) | " +
        "${group.copyCount} identical folders | " +
        "Combined: ${formatBytes(group.totalDuplicateBytes)} | " +
        "Wasted: ${formatBytes(group.wastedBytes)}"
    )
    formatSampleFiles(group)?.let { terminal.println(it) }
    terminal.println("Identical folders:")
    items.forEachIndexed { index, path ->
      terminal.println("  ${index + 1}. $path")
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
      return items
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
        val path = items[num - 1]
        if (path !in selected) {
          selected.add(path)
        }
      }
    }
    return selected
  }
}
