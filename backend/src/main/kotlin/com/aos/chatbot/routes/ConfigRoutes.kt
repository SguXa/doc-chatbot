package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.ConfigRepository
import com.aos.chatbot.routes.dto.InvalidConfigRequestResponse
import com.aos.chatbot.routes.dto.SystemPromptResponse
import com.aos.chatbot.routes.dto.UpdateSystemPromptRequest
import com.aos.chatbot.services.ChatService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val MAX_PROMPT_LENGTH = 8000

/**
 * Admin-protected config endpoints (ARCHITECTURE.md §7.3, §11.2).
 *
 * The `system_prompt` value is stored as a JSON-encoded string in
 * `config.value` (V004 layout) so that newlines and quote characters survive
 * the SQLite TEXT column without escaping mishaps. GET decodes; PUT validates
 * and re-encodes.
 */
fun Route.configRoutes(database: Database) {
    route("/api/config") {
        get("/system-prompt") {
            val entry = withContext(Dispatchers.IO) {
                database.connect().use { conn ->
                    ConfigRepository(conn).getWithUpdatedAt(ChatService.SYSTEM_PROMPT_KEY)
                }
            }
            if (entry == null) {
                // V004 seeds this row idempotently — reaching this branch means
                // someone manually deleted it (dev scenario) or the migration
                // failed silently. Fail loud rather than render a blank editor.
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "config_missing"))
                return@get
            }
            val prompt = Json.decodeFromString(String.serializer(), entry.value)
            call.respond(
                HttpStatusCode.OK,
                SystemPromptResponse(prompt = prompt, updatedAt = entry.updatedAt)
            )
        }

        put("/system-prompt") {
            val body = try {
                call.receive<UpdateSystemPromptRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidConfigRequestResponse(reason = "malformed_body")
                )
                return@put
            }

            if (body.prompt.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidConfigRequestResponse(reason = "empty_prompt")
                )
                return@put
            }

            if (body.prompt.length > MAX_PROMPT_LENGTH) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    InvalidConfigRequestResponse(reason = "prompt_too_long")
                )
                return@put
            }

            val encoded = Json.encodeToString(String.serializer(), body.prompt)
            val entry = withContext(Dispatchers.IO) {
                database.connect().use { conn ->
                    val repo = ConfigRepository(conn)
                    repo.put(ChatService.SYSTEM_PROMPT_KEY, encoded)
                    repo.getWithUpdatedAt(ChatService.SYSTEM_PROMPT_KEY)
                }
            }
            if (entry == null) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "config_missing"))
                return@put
            }
            call.respond(
                HttpStatusCode.OK,
                SystemPromptResponse(prompt = body.prompt, updatedAt = entry.updatedAt)
            )
        }
    }
}
