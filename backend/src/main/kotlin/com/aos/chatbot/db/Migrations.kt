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
            connection.createStatement().use { stmt ->
                for (sql in migration.sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                    stmt.execute(sql)
                }
            }
            recordMigration(migration.version, migration.name)
            logger.info("Migration ${migration.version} applied successfully")
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

        val resourceUrl = classLoader.getResource(resourceDir) ?: return migrations

        val files = when (resourceUrl.protocol) {
            "file" -> {
                java.io.File(resourceUrl.toURI()).listFiles()
                    ?.filter { it.name.endsWith(".sql") }
                    ?.map { it.name }
                    ?: emptyList()
            }
            "jar" -> {
                val jarPath = resourceUrl.path.substringBefore("!").removePrefix("file:")
                java.util.jar.JarFile(jarPath).use { jar ->
                    jar.entries().asSequence()
                        .filter { it.name.startsWith("$resourceDir/") && it.name.endsWith(".sql") }
                        .map { it.name.substringAfterLast("/") }
                        .toList()
                }
            }
            else -> {
                logger.warn("Unknown resource protocol '${resourceUrl.protocol}' for migration loading")
                emptyList()
            }
        }

        for (filename in files) {
            val match = MIGRATION_PATTERN.matchEntire(filename) ?: continue
            val version = match.groupValues[1].toInt()
            val name = match.groupValues[2]
            val sql = classLoader.getResourceAsStream("$resourceDir/$filename")
                ?.bufferedReader()?.readText() ?: continue
            migrations.add(Migration(version, name, sql))
        }

        return migrations
    }

    data class Migration(val version: Int, val name: String, val sql: String)

    companion object {
        private val MIGRATION_PATTERN = Regex("""V(\d+)__(.+)\.sql""")
    }
}
