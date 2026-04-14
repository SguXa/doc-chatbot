package com.aos.chatbot.config

import io.ktor.server.application.ApplicationEnvironment

enum class AppMode {
    FULL, ADMIN, CLIENT;

    companion object {
        fun fromString(value: String): AppMode {
            return when (value.lowercase()) {
                "full" -> FULL
                "admin" -> ADMIN
                "client" -> CLIENT
                else -> throw IllegalArgumentException("Invalid mode: '$value'. Must be one of: full, admin, client")
            }
        }
    }
}

data class AppConfig(
    val mode: AppMode,
    val port: Int,
    val host: String,
    val databasePath: String,
    val dataPath: String,
    val documentsPath: String,
    val imagesPath: String
) {
    companion object {
        fun from(environment: ApplicationEnvironment): AppConfig {
            val config = environment.config
            return AppConfig(
                mode = AppMode.fromString(config.property("app.mode").getString()),
                port = config.property("ktor.deployment.port").getString().toIntOrNull()
                    ?: throw IllegalArgumentException("PORT must be a valid integer"),
                host = config.property("ktor.deployment.host").getString(),
                databasePath = config.property("app.database.path").getString(),
                dataPath = config.property("app.data.path").getString(),
                documentsPath = config.property("app.paths.documents").getString(),
                imagesPath = config.property("app.paths.images").getString()
            )
        }
    }
}
