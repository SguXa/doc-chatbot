package com.aos.chatbot.models

import kotlinx.serialization.Serializable

/**
 * JSON payload carried over Artemis on the `aos.chat.requests` queue.
 *
 * Produced by the SSE route at `POST /api/chat` and consumed by the in-process
 * chat orchestrator. The `correlationId` keys the response bus where streamed
 * tokens are routed back to the waiting SSE handler.
 *
 * `history` is the full prior conversation list provided by the client — the
 * backend persists nothing, so every request ships the complete history.
 */
@Serializable
data class ChatRequest(
    val correlationId: String,
    val message: String,
    val history: List<ChatMessage> = emptyList(),
    val enqueuedAt: String
)
