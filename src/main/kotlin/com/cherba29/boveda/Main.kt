package com.cherba29.boveda

import com.cherba29.boveda.cmds.BovedaCommand
import com.cherba29.boveda.cmds.DbCommand
import com.cherba29.boveda.cmds.ScanCommand
import com.cherba29.boveda.cmds.connectDatabase
import com.cherba29.boveda.cmds.db.PrintDuplicatesCommand
import com.cherba29.boveda.cmds.db.SnapshotsCommand
import com.cherba29.boveda.cmds.db.snapshots.DeleteCommand
import com.cherba29.boveda.cmds.db.snapshots.DeleteSnapshotCommand
import com.cherba29.boveda.cmds.db.snapshots.ListCommand
import com.cherba29.boveda.cmds.db.snapshots.ListSnapshotsCommand
import com.cherba29.boveda.cmds.resolveDatabasePath
import com.cherba29.boveda.cmds.resolveSnapshot
import com.cherba29.boveda.cmds.validateDatabaseFileExists
import com.github.ajalt.clikt.core.main
import java.nio.file.Path

// Re-export core database declarations in com.cherba29.boveda for backward compatibility
typealias RepoSnapshots = com.cherba29.boveda.db.RepoSnapshots
typealias RepoFiles = com.cherba29.boveda.db.RepoFiles
typealias RepoFileInstances = com.cherba29.boveda.db.RepoFileInstances
typealias ReportSnapshot = com.cherba29.boveda.db.ReportSnapshot
typealias RepoFile = com.cherba29.boveda.db.RepoFile
typealias RepoFileInstance = com.cherba29.boveda.db.RepoFileInstance
typealias DuplicateStatistics = com.cherba29.boveda.model.DuplicateStatistics
typealias DuplicateGroup = com.cherba29.boveda.model.DuplicateGroup
typealias PercentileBucket = com.cherba29.boveda.model.PercentileBucket
typealias SnapshotSummary = com.cherba29.boveda.model.SnapshotSummary
typealias DeduplicationCache = com.cherba29.boveda.cache.DeduplicationCache
typealias InMemoryDeduplicationCache = com.cherba29.boveda.cache.InMemoryDeduplicationCache
typealias FileContentKey = com.cherba29.boveda.cache.FileContentKey
typealias CacheStatistics = com.cherba29.boveda.cache.CacheStatistics
typealias ScanProgressIndicator = com.cherba29.boveda.progress.ScanProgressIndicator
typealias MordantScanProgressIndicator = com.cherba29.boveda.progress.MordantScanProgressIndicator
typealias ScanProgressState = com.cherba29.boveda.progress.ScanProgressState
typealias ParallelDirectoryScanner = com.cherba29.boveda.scanner.ParallelDirectoryScanner
typealias WorkStealingDirectoryScanner = com.cherba29.boveda.scanner.WorkStealingDirectoryScanner
typealias FileTreeDirectoryScanner = com.cherba29.boveda.scanner.FileTreeDirectoryScanner
typealias DiscoveredFile = com.cherba29.boveda.scanner.DiscoveredFile

// Re-export CLI commands in com.cherba29.boveda for backward compatibility
typealias BovedaCommand = com.cherba29.boveda.cmds.BovedaCommand
typealias ScanCommand = com.cherba29.boveda.cmds.ScanCommand
typealias DbCommand = com.cherba29.boveda.cmds.DbCommand
typealias PrintDuplicatesCommand = com.cherba29.boveda.cmds.db.PrintDuplicatesCommand
typealias SnapshotsCommand = com.cherba29.boveda.cmds.db.SnapshotsCommand
typealias ListCommand = com.cherba29.boveda.cmds.db.snapshots.ListCommand
typealias ListSnapshotsCommand = com.cherba29.boveda.cmds.db.snapshots.ListSnapshotsCommand
typealias DeleteCommand = com.cherba29.boveda.cmds.db.snapshots.DeleteCommand
typealias DeleteSnapshotCommand = com.cherba29.boveda.cmds.db.snapshots.DeleteSnapshotCommand

/**
 * Entry point for scanning a directory, recording files into the database, and running queries.
 *
 * @param args Command-line arguments parsed by [BovedaCommand].
 */
fun main(args: Array<String>) {
    BovedaCommand().main(args)
}

/**
 * Parameterless main function allowing standard JVM / Kotlin execution without arguments.
 */
fun main() = main(emptyArray())
