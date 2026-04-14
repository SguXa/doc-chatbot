package com.aos.chatbot.db

import org.slf4j.LoggerFactory
import java.sql.Connection

class Migrations(private val connection: Connection) {

    private val logger = LoggerFactory.getLogger(Migrations::class.java)

    fun apply() {
        ensureSchemaVersionTable()
        val applied = getAppliedVersions()
        val pending = loadMigrations().filter { it.version !in applied }

        for (migration in pending.sortedBy { it.version }) {
            logger.info("Applying migration ${migration.version}: ${migration.name}")
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { stmt ->
                    for (sql in splitSqlStatements(migration.sql)) {
                        stmt.execute(sql)
                    }
                }
                recordMigration(migration.version, migration.name)
                connection.commit()
                logger.info("Migration ${migration.version} applied successfully")
            } catch (e: Exception) {
                connection.rollback()
                logger.error("Migration ${migration.version} failed, rolled back: ${e.message}")
                throw e
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }

        if (pending.isEmpty()) {
            logger.info("Database is up to date, no pending migrations")
        }
    }

    private fun ensureSchemaVersionTable() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
        }
    }

    private fun getAppliedVersions(): Set<Int> {
        val versions = mutableSetOf<Int>()
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT version FROM schema_version")
            while (rs.next()) {
                versions.add(rs.getInt("version"))
            }
        }
        return versions
    }

    private fun recordMigration(version: Int, name: String) {
        connection.prepareStatement("INSERT INTO schema_version (version, name) VALUES (?, ?)").use { stmt ->
            stmt.setInt(1, version)
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    private fun loadMigrations(): List<Migration> {
        val migrations = mutableListOf<Migration>()
        val classLoader = this::class.java.classLoader
        val resourceDir = "db/migration"

        val resourceUrl = classLoader.getResource(resourceDir)
            ?: throw IllegalStateException("Migration resource directory '$resourceDir' not found on classpath")

        val files = when (resourceUrl.protocol) {
            "file" -> {
                val dir = java.io.File(resourceUrl.toURI())
                val listed = dir.listFiles()
                    ?: throw IllegalStateException(
                        "Failed to list migration files in '${dir.absolutePath}' (not a directory or I/O error)"
                    )
                listed.filter { it.name.endsWith(".sql") }.map { it.name }
            }
            "jar" -> {
                val jarPath = java.net.URI(resourceUrl.path.substringBefore("!")).path
                java.util.jar.JarFile(jarPath).use { jar ->
                    jar.entries().asSequence()
                        .filter { it.name.startsWith("$resourceDir/") && it.name.endsWith(".sql") }
                        .map { it.name.substringAfterLast("/") }
                        .toList()
                }
            }
            else -> {
                throw IllegalStateException(
                    "Unsupported resource protocol '${resourceUrl.protocol}' for migration loading"
                )
            }
        }

        for (filename in files) {
            val match = MIGRATION_PATTERN.matchEntire(filename)
                ?: throw IllegalStateException(
                    "Migration file '$filename' does not match expected pattern V<number>__<name>.sql — fix the filename or remove it from the migration directory"
                )
            val version = match.groupValues[1].toInt()
            val name = match.groupValues[2]
            val sql = classLoader.getResourceAsStream("$resourceDir/$filename")
                ?.bufferedReader()?.readText()
                ?: throw IllegalStateException(
                    "Migration file '$filename' was discovered but could not be read from classpath"
                )
            migrations.add(Migration(version, name, sql))
        }

        return migrations
    }

    data class Migration(val version: Int, val name: String, val sql: String)

    companion object {
        private val MIGRATION_PATTERN = Regex("""V(\d+)__(.+)\.sql""")

        /**
         * Splits SQL text into individual statements, respecting string literals
         * (single-quoted) and comments (-- line comments, /* block comments */).
         */
        fun splitSqlStatements(sql: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    // Single-quoted string literal: consume until closing quote
                    c == '\'' -> {
                        current.append(c)
                        i++
                        var closed = false
                        while (i < sql.length) {
                            current.append(sql[i])
                            if (sql[i] == '\'' && (i + 1 >= sql.length || sql[i + 1] != '\'')) {
                                closed = true
                                break
                            }
                            if (sql[i] == '\'' && i + 1 < sql.length && sql[i + 1] == '\'') {
                                i++
                                current.append(sql[i])
                            }
                            i++
                        }
                        if (!closed) throw IllegalStateException("Unterminated string literal in migration SQL")
                        i++
                    }
                    // Line comment: skip to end of line
                    c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                        while (i < sql.length && sql[i] != '\n') i++
                    }
                    // Block comment: skip to closing */
                    c == '/' && i + 1 < sql.length && sql[i + 1] == '*' -> {
                        i += 2
                        var closed = false
                        while (i + 1 < sql.length) {
                            if (sql[i] == '*' && sql[i + 1] == '/') {
                                closed = true
                                break
                            }
                            i++
                        }
                        if (!closed) throw IllegalStateException("Unterminated block comment in migration SQL")
                        i += 2
                    }
                    // Statement terminator
                    c == ';' -> {
                        val stmt = current.toString().trim()
                        if (stmt.isNotEmpty()) statements.add(stmt)
                        current.clear()
                        i++
                    }
                    else -> {
                        current.append(c)
                        i++
                    }
                }
            }
            val remaining = current.toString().trim()
            if (remaining.isNotEmpty()) statements.add(remaining)
            return statements
        }
    }
}
