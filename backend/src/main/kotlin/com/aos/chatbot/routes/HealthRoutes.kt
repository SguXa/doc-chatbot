package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.healthRoutes(database: Database) {
    route("/api/health") {
        get {
            call.respond(mapOf("status" to "healthy"))
        }

        get("/ready") {
            try {
                val ready = withContext(Dispatchers.IO) {
                    database.connect().use { conn ->
                        conn.isValid(2)
                    }
                }
                if (ready) {
                    call.respond(mapOf("status" to "ready"))
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
            }
        }
    }
}
