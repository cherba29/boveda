package com.cherba29.boveda.scanner

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Cross-platform (Linux, macOS, Windows) helpers for inspecting symbolic links without
 * following them or reading target file contents.
 */
object SymlinkUtils {
  /**
   * Resolves the target of symbolic link [linkPath] to a normalized invariant-separator path
   * string (using `/` separators on all platforms including Windows).
   */
  fun resolveTarget(linkPath: Path): String? {
    return try {
      val rawTarget = Files.readSymbolicLink(linkPath)
      val resolved = if (rawTarget.isAbsolute) {
        rawTarget.normalize()
      } else {
        (linkPath.parent?.resolve(rawTarget) ?: rawTarget).normalize()
      }
      resolved.invariantSeparatorsPathString
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Computes a deterministic marker hash for a symlink based on its target path without opening
   * or reading the target file. Fits within the 64-character DB hash column.
   */
  fun computeSymlinkHash(target: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = (target ?: "").toByteArray(Charsets.UTF_8)
    val hex = digest.digest(bytes).joinToString("") { "%02x".format(it) }
    return "symlink:${hex.take(48)}"
  }
}
