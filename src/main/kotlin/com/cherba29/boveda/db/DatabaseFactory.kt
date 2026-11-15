package com.cherba29.boveda.db

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
     * Connects to a persistent file-based H2 database at [path] and initializes tables.
     */
    fun connectFile(path: String): Database {
        val normalizedPath = if (path.endsWith(".mv.db")) path.removeSuffix(".mv.db") else path
        val database = Database.connect(
            url = "jdbc:h2:file:$normalizedPath;DB_CLOSE_DELAY=-1",
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
            SchemaUtils.create(RepoSnapshots, RepoFiles, RepoFileInstances)
        }
    }
}
