package com.cherba29.boveda.db

import java.sql.Connection
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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
   * Default H2 in-memory page cache limit in KB (16 MB target, capping H2's JVM heap footprint).
   */
  const val DEFAULT_H2_CACHE_SIZE_KB = 16_384

  /**
   * Connects to a persistent file-based H2 database at [path] and initializes tables.
   */
  fun connectFile(path: String, cacheSizeKb: Int = DEFAULT_H2_CACHE_SIZE_KB): Database {
    require(cacheSizeKb >= 0) { "cacheSizeKb must be non-negative, but was $cacheSizeKb" }
    val normalizedPath = if (path.endsWith(".mv.db")) path.removeSuffix(".mv.db") else path
    val database = Database.connect(
      url = "jdbc:h2:file:$normalizedPath;DB_CLOSE_DELAY=-1;CACHE_SIZE=$cacheSizeKb",
      driver = "org.h2.Driver"
    )
    initSchema(database)
    return database
  }

  /**
   * Creates all required tables if they don't already exist.
   */
  fun initSchema(database: Database) {
    transaction(database) {
      SchemaUtils.create(RepoSnapshots, RepoFolders, RepoFiles, RepoFileInstances)
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
