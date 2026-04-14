package com.aos.chatbot

import com.aos.chatbot.config.AppConfig
import com.aos.chatbot.config.DatabaseConfig
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import com.aos.chatbot.routes.healthRoutes
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.module() {
    val appConfig = AppConfig.from(environment)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<Throwable> { call, cause ->
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            if (cause is Error) throw cause
            this@module.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    val dbConfig = DatabaseConfig(appConfig)
    val connection = dbConfig.initialize()

    environment.monitor.subscribe(ApplicationStopped) {
        connection.close()
    }

    routing {
        healthRoutes(connection)
    }

    log.info("AOS Chatbot started in ${appConfig.mode} mode on ${appConfig.host}:${appConfig.port}")
}
