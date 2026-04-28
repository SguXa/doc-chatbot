package com.aos.chatbot.routes

import com.aos.chatbot.routes.dto.InvalidLoginResponse
import com.aos.chatbot.routes.dto.InvalidRequestResponse
import com.aos.chatbot.routes.dto.LoginRequest
import com.aos.chatbot.routes.dto.LoginResponse
import com.aos.chatbot.routes.dto.UserInfo
import com.aos.chatbot.services.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Public auth routes.
 *
 * `POST /api/auth/login` validates `password` against the in-memory bcrypt hash
 * owned by [AuthService] and returns a signed JWT on success. The `username`
 * field of [LoginRequest] is read but ignored server-side — it is preserved on
 * the wire for forward-compatibility with future multi-user support
 * (ARCHITECTURE.md §7.4, ADR 0007).
 *
 * `POST /api/auth/logout` is stateless and always returns 204; JWTs naturally
 * expire after their TTL and there is no server-side revocation list.
 */
fun Route.authRoutes(authService: AuthService) {
    route("/api/auth") {
        post("/login") {
            val body = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidRequestResponse(reason = "malformed_body")
                )
                return@post
            }

            if (body.password.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidRequestResponse(reason = "empty_password")
                )
                return@post
            }

            val token = authService.login(body.password)
            if (token == null) {
                call.respond(HttpStatusCode.Unauthorized, InvalidLoginResponse())
                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    token = token,
                    expiresIn = authService.tokenTtlSeconds,
                    user = UserInfo(username = "admin", role = "admin")
                )
            )
        }

        post("/logout") {
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
