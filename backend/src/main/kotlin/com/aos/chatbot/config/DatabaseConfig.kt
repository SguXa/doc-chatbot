package com.aos.chatbot.config

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

class DatabaseConfig(private val appConfig: AppConfig) {

    private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

    fun initialize(): Connection {
        val dbFile = File(appConfig.databasePath)
        val dataDir = dbFile.parentFile
        if (dataDir != null && !dataDir.exists()) {
            logger.info("Creating data directory: ${dataDir.absolutePath}")
            if (!dataDir.mkdirs()) {
                throw IllegalStateException("Failed to create data directory: ${dataDir.absolutePath}")
            }
        }

        val database = Database(appConfig.databasePath)
        val connection = database.connect()

        try {
            val migrations = Migrations(connection)
            migrations.apply()
        } catch (e: Exception) {
            connection.close()
            throw e
        }

        logger.info("Database initialized at ${appConfig.databasePath}")
        return connection
    }
}
