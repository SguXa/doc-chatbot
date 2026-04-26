package com.aos.chatbot.routes.dto

import com.aos.chatbot.models.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
data class ChatBody(
    val message: String,
    val history: List<ChatMessage> = emptyList()
)

@Serializable
data class InvalidChatRequestResponse(
    val error: String = "invalid_request",
    val reason: String
)

@Serializable
data class NotReadyResponse(
    val error: String = "not_ready",
    val reason: String = "embedding_backfill_in_progress",
    val message: String = "System is initializing. Please retry shortly."
)

@Serializable
data class BackfillFailedResponse(
    val error: String = "not_ready",
    val reason: String = "embedding_backfill_failed",
    val message: String = "Embedding backfill failed. An operator must trigger a reindex via POST /api/admin/reindex."
)

@Serializable
data class QueueUnavailableResponse(
    val error: String = "queue_unavailable",
    val message: String = "Request queue is not reachable. Please retry shortly."
)
