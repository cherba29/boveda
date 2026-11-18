package com.cherba29.boveda.cmds

import com.cherba29.boveda.db.DatabaseFactory
import com.cherba29.boveda.db.RepoSnapshot
import com.cherba29.boveda.service.BovedaService
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Validates that the specified H2 database file exists on disk.
 */
fun validateDatabaseFileExists(path: Path) {
  val pathStr = path.toString()
  val base = if (pathStr.endsWith(".mv.db")) pathStr.removeSuffix(".mv.db") else pathStr
  val fileExists = Files.exists(Path.of(base)) || Files.exists(Path.of("$base.mv.db"))
  if (!fileExists) {
    throw UsageError("Database file does not exist: $path")
  }
}

/**
 * Resolves the database file path by checking local arguments/options first,
 * and falling back to any ancestor command in the Clikt context hierarchy.
 */
fun resolveDatabasePath(
  context: Context,
  localPath: Path? = null,
  localOption: Path? = null
): Path? {
  if (localPath != null) return localPath
  if (localOption != null) return localOption
  var current: Context? = context.parent
  while (current != null) {
    val cmd = current.command
    if (cmd is com.cherba29.boveda.cmds.db.SnapshotsCommand) {
      val p = cmd.dbPath ?: cmd.dbOption
      if (p != null) return p
    }
    if (cmd is com.cherba29.boveda.cmds.db.FileCommand) {
      val p = cmd.dbPath ?: cmd.dbOption
      if (p != null) return p
    }
    if (cmd is com.cherba29.boveda.cmds.DbCommand) {
      val p = cmd.dbPath ?: cmd.dbOption
      if (p != null) return p
    }
    current = current.parent
  }
  return null
}

/**
 * Resolves the snapshot ID by checking local options first and falling back to ancestor DbCommand.
 */
fun resolveSnapshotId(context: Context, localSnapshotId: Int? = null): Int? {
  if (localSnapshotId != null) return localSnapshotId
  var current: Context? = context.parent
  while (current != null) {
    val cmd = current.command
    if (cmd is com.cherba29.boveda.cmds.DbCommand) {
      if (cmd.snapshotIdOption != null) return cmd.snapshotIdOption
    }
    current = current.parent
  }
  return null
}

/**
 * Connects to the database given a resolved path, or connects in-memory if a service factory is supplied.
 */
fun connectDatabase(
  resolvedDbPath: Path?,
  serviceFactory: ((Database) -> BovedaService)?
): Database {
  return when {
    resolvedDbPath != null -> {
      if (serviceFactory == null) validateDatabaseFileExists(resolvedDbPath)
      DatabaseFactory.connectFile(resolvedDbPath.toString())
    }
    serviceFactory != null -> DatabaseFactory.connectInMemory("test")
    else -> throw UsageError("Database must be specified as an argument or via --db")
  }
}

/**
 * Resolves a snapshot from the service by [snapshotId], or defaults to the latest snapshot if null.
 */
fun resolveSnapshot(service: BovedaService, snapshotId: Int?): RepoSnapshot? {
  return if (snapshotId != null) {
    service.findSnapshot(snapshotId)
  } else {
    service.getLatestSnapshot()
  }
}
