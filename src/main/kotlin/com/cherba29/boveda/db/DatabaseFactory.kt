package com.cherba29.boveda.db

import java.sql.Connection
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Factory for creating and initializing database connections with Exposed and H2.
 */
object DatabaseFactory {

  /**
   * Connects to an in-memory H2 database with the given [dbName] and initializes tables.
   */
  fun connectInMemory(dbName: String = "boveda"): Database {
    val database = Database.connect(
      url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1",
      driver = "org.h2.Driver"
    )
    initSchema(database)
    return database
  }

  /**
   * Default H2 in-memory page cache limit in KB for queries (16 MB target).
   */
  const val DEFAULT_H2_CACHE_SIZE_KB = 16_384

  /**
   * Default H2 in-memory page cache limit in KB during bulk scanning (64 MB target), keeping
   * B-tree indexes hot in RAM longer on large directory trees.
   */
  const val DEFAULT_SCAN_H2_CACHE_SIZE_KB = 65_536

  /**
   * Connects to a persistent file-based H2 database at [path] and initializes tables.
   *
   * When [bulkInsert] is true (e.g. during `scan`), defaults to a 64 MB H2 page cache
   * ([DEFAULT_SCAN_H2_CACHE_SIZE_KB]) and increases H2's `WRITE_DELAY` to 2000ms to amortize
   * MVStore disk flushes and reduce antivirus rescan overhead on Windows; callers should invoke
   * [shutdown] when finished to flush all pages cleanly.
   */
  fun connectFile(path: String, cacheSizeKb: Int? = null, bulkInsert: Boolean = false): Database {
    val effectiveCacheSizeKb = cacheSizeKb ?: if (bulkInsert) {
      DEFAULT_SCAN_H2_CACHE_SIZE_KB
    } else {
      DEFAULT_H2_CACHE_SIZE_KB
    }
    require(effectiveCacheSizeKb >= 0) {
      "cacheSizeKb must be non-negative, but was $effectiveCacheSizeKb"
    }
    val normalizedPath = if (path.endsWith(".mv.db")) path.removeSuffix(".mv.db") else path
    val writeDelaySuffix = if (bulkInsert) ";WRITE_DELAY=2000" else ""
    val database = Database.connect(
      url = "jdbc:h2:file:$normalizedPath;DB_CLOSE_DELAY=-1;CACHE_SIZE=$effectiveCacheSizeKb" +
        writeDelaySuffix,
      driver = "org.h2.Driver"
    )
    initSchema(database)
    return database
  }

  /**
   * Creates all required tables if they don't already exist and migrates symlink columns
   * on pre-existing databases.
   */
  fun initSchema(database: Database) {
    transaction(database) {
      SchemaUtils.create(RepoSnapshots, RepoFolders, RepoFiles, RepoFileInstances)
      val conn = TransactionManager.current().connection.connection as Connection
      conn.createStatement().use { stmt ->
        stmt.execute(
          "ALTER TABLE file_instances ADD COLUMN IF NOT EXISTS " +
            "is_symlink BOOLEAN DEFAULT FALSE NOT NULL"
        )
        stmt.execute(
          "ALTER TABLE file_instances ADD COLUMN IF NOT EXISTS symlink_target VARCHAR(1024)"
        )
      }
    }
  }

  /**
   * Explicitly flushes dirty MVStore pages and shuts down an H2 file-backed [database],
   * avoiding an unexplained silent pause during JVM shutdown hooks.
   */
  fun shutdown(database: Database) {
    val exposedConn = database.connector()
    try {
      val conn = exposedConn.connection as Connection
      conn.autoCommit = true
      conn.createStatement().use { stmt ->
        stmt.execute("SHUTDOWN")
      }
    } finally {
      try {
        exposedConn.close()
      } catch (_: Exception) {
        // H2 closes the underlying connection as part of SHUTDOWN
      }
    }
  }
}
