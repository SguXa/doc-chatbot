package com.aos.chatbot.routes

import com.aos.chatbot.models.ChatRequest
import com.aos.chatbot.models.QueueEvent
import com.aos.chatbot.routes.dto.BackfillFailedResponse
import com.aos.chatbot.routes.dto.ChatBody
import com.aos.chatbot.routes.dto.InvalidChatRequestResponse
import com.aos.chatbot.routes.dto.NotReadyResponse
import com.aos.chatbot.routes.dto.QueueUnavailableResponse
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.ChatResponseBus
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.QueueService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

private const val MAX_HISTORY_SIZE = 20
private const val MAX_MESSAGE_LENGTH = 4000
private const val MAX_HISTORY_ENTRY_LENGTH = 4000
private val ALLOWED_HISTORY_ROLES = setOf("user", "assistant")
private const val ESTIMATED_WAIT_PER_SLOT_SEC = 30
private const val BACKFILL_RETRY_AFTER_SEC = 10
private val logger = LoggerFactory.getLogger("ChatRoutes")
private val sseJson = Json { encodeDefaults = true }

/**
 * Registers `POST /api/chat`, the SSE producer for chat responses.
 *
 * Flow per request:
 *  1. Validate body (`message` non-blank, `history` <= [MAX_HISTORY_SIZE]).
 *  2. Refuse with 503 if the embedding backfill has not completed — gates on
 *     [EmbeddingBackfillJob.status]. The check runs BEFORE enqueue so the
 *     failure is fast and no Artemis / bus resources are allocated.
 *  3. Generate a `correlationId`, open the [ChatResponseBus] channel BEFORE
 *     enqueuing the request. The channel uses `Channel.UNLIMITED` so events
 *     emitted by the consumer between enqueue and the route's collect loop
 *     are buffered, not dropped (see ADR 0006).
 *  4. Enqueue via [QueueService.enqueue]; on `IllegalStateException` (Artemis
 *     unreachable), close the bus entry and return 503 `queue_unavailable`
 *     BEFORE opening SSE.
 *  5. Start the SSE stream with a synthetic `queued` event derived from
 *     [QueueService.getPosition] (a raw position of -1 means the worker has
 *     already consumed the message; we clamp to 0).
 *  6. Forward every [QueueEvent] from the bus as a named SSE event, closing
 *     the stream on [QueueEvent.Done] or [QueueEvent.Error].
 *  7. In `finally`, call [ChatResponseBus.close] so the consumer sees
 *     `isOrphaned` on its next check if the client disconnected mid-stream.
 *     The in-flight Ollama call is NOT cancelled (documented limitation,
 *     ADR 0006).
 */
fun Route.chatRoutes(
    queueService: QueueService,
    responseBus: ChatResponseBus,
    backfillJob: EmbeddingBackfillJob
) {
    post("/api/chat") {
        val body = try {
            call.receive<ChatBody>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "malformed_body")
            )
            return@post
        }

        if (body.message.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "empty_message")
            )
            return@post
        }

        if (body.message.length > MAX_MESSAGE_LENGTH) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "message_too_long")
            )
            return@post
        }

        if (body.history.size > MAX_HISTORY_SIZE) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "history_too_long")
            )
            return@post
        }

        if (body.history.any { it.role !in ALLOWED_HISTORY_ROLES }) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "invalid_history_role")
            )
            return@post
        }

        if (body.history.any { it.content.length > MAX_HISTORY_ENTRY_LENGTH }) {
            call.respond(
                HttpStatusCode.BadRequest,
                InvalidChatRequestResponse(reason = "history_entry_too_long")
            )
            return@post
        }

        val status = backfillJob.status()
        if (status is BackfillStatus.Failed) {
            // Terminal failure — retrying won't help; only a manual reindex clears this.
            call.respond(HttpStatusCode.ServiceUnavailable, BackfillFailedResponse())
            return@post
        }
        if (status !is BackfillStatus.Completed || backfillJob.isRunning()) {
            call.response.header(HttpHeaders.RetryAfter, BACKFILL_RETRY_AFTER_SEC.toString())
            call.respond(HttpStatusCode.ServiceUnavailable, NotReadyResponse())
            return@post
        }

        val correlationId = UUID.randomUUID().toString()
        val enqueuedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val chatRequest = ChatRequest(
            correlationId = correlationId,
            message = body.message,
            history = body.history,
            enqueuedAt = enqueuedAt
        )

        val receiver = responseBus.open(correlationId)
        try {
            try {
                queueService.enqueue(chatRequest)
            } catch (e: IllegalStateException) {
                logger.warn("Artemis enqueue failed for correlationId={}: {}", correlationId, e.message)
                call.respond(HttpStatusCode.ServiceUnavailable, QueueUnavailableResponse())
                return@post
            }

            val rawPosition = queueService.getPosition(correlationId)
            val position = max(rawPosition, 0)

            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header("X-Accel-Buffering", "no")

            call.respondBytesWriter(
                contentType = ContentType.Text.EventStream,
                status = HttpStatusCode.OK
            ) {
                writeStringUtf8(
                    formatSseEvent(
                        "queued",
                        sseJson.encodeToString(
                            QueueEvent.Queued.serializer(),
                            QueueEvent.Queued(
                                position = position,
                                estimatedWait = position * ESTIMATED_WAIT_PER_SLOT_SEC
                            )
                        )
                    )
                )
                flush()

                try {
                    for (event in receiver) {
                        writeStringUtf8(renderSseEvent(event))
                        flush()
                        if (event is QueueEvent.Done || event is QueueEvent.Error) break
                    }
                } catch (e: ClosedReceiveChannelException) {
                    // Channel closed by producer with no terminal event; exit cleanly.
                }
            }
        } finally {
            // Single close site guards against leaks if enqueue throws a
            // non-IllegalStateException (cancellation, etc.) or if getPosition
            // / header setup fails between open() and respondBytesWriter.
            responseBus.close(correlationId)
        }
    }
}

private fun renderSseEvent(event: QueueEvent): String {
    return when (event) {
        is QueueEvent.Queued -> formatSseEvent(
            "queued",
            sseJson.encodeToString(QueueEvent.Queued.serializer(), event)
        )
        is QueueEvent.Processing -> formatSseEvent(
            "processing",
            sseJson.encodeToString(QueueEvent.Processing.serializer(), event)
        )
        is QueueEvent.Token -> formatSseEvent(
            "token",
            sseJson.encodeToString(QueueEvent.Token.serializer(), event)
        )
        is QueueEvent.Sources -> formatSseEvent(
            "sources",
            sseJson.encodeToString(QueueEvent.Sources.serializer(), event)
        )
        is QueueEvent.Done -> formatSseEvent(
            "done",
            sseJson.encodeToString(QueueEvent.Done.serializer(), event)
        )
        is QueueEvent.Error -> formatSseEvent(
            "error",
            sseJson.encodeToString(QueueEvent.Error.serializer(), event)
        )
    }
}

private fun formatSseEvent(name: String, json: String): String =
    "event: $name\ndata: $json\n\n"
