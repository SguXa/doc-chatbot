package com.aos.chatbot.models

import kotlinx.serialization.Serializable

/**
 * One entry of the SSE stream delivered to a chat client.
 *
 * Mirrors ARCHITECTURE.md §10.2. Produced by the in-process chat consumer and
 * routed by [com.aos.chatbot.services.ChatResponseBus] to the matching SSE
 * handler via `correlationId`.
 */
@Serializable
sealed class QueueEvent {
    @Serializable
    data class Queued(val position: Int, val estimatedWait: Int) : QueueEvent()

    @Serializable
    data class Processing(val status: String) : QueueEvent()

    @Serializable
    data class Token(val text: String) : QueueEvent()

    @Serializable
    data class Sources(val sources: List<Source>) : QueueEvent()

    @Serializable
    data class Done(val totalTokens: Int) : QueueEvent()

    @Serializable
    data class Error(val message: String) : QueueEvent()
}

/**
 * Citation returned alongside a completed chat answer. Rendered by the client
 * as a footnote-style reference to an indexed document chunk.
 */
@Serializable
data class Source(
    val documentId: Long,
    val documentName: String,
    val section: String?,
    val page: Int?,
    val snippet: String
)
