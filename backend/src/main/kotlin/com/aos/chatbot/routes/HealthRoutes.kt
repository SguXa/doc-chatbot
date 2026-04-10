package com.aos.chatbot.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.sql.Connection

fun Route.healthRoutes(connection: Connection) {
    route("/api/health") {
        get {
            call.respond(mapOf("status" to "healthy"))
        }

        get("/ready") {
            try {
                val ready = connection.isValid(2)
                if (ready) {
                    call.respond(mapOf("status" to "ready"))
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
                }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
            }
        }
    }
}
