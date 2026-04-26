package com.aos.chatbot.routes.dto

import com.aos.chatbot.models.Document
import kotlinx.serialization.Serializable

@Serializable
data class DuplicateDocumentResponse(
    val error: String = "duplicate_document",
    val message: String,
    val existing: Document
)

@Serializable
data class InvalidUploadResponse(
    val error: String = "invalid_upload",
    val reason: String,
    val message: String
)

@Serializable
data class UnreadableDocumentResponse(
    val error: String = "unreadable_document",
    val reason: String,
    val message: String
)

@Serializable
data class EmptyDocumentResponse(
    val error: String = "empty_content",
    val reason: String = "no_extractable_content",
    val message: String
)

@Serializable
data class DocumentListResponse(
    val documents: List<Document>,
    val total: Int
)

@Serializable
data class ReindexStartedResponse(val status: String = "started")

@Serializable
data class ReindexAlreadyRunningResponse(val status: String = "already_running")

@Serializable
data class ReindexInProgressResponse(
    val error: String = "reindex_in_progress",
    val message: String
)

@Serializable
data class OllamaUnavailableResponse(
    val error: String = "ollama_unavailable",
    val message: String = "Embedding service (Ollama) is unavailable; upload cannot be indexed. Please retry shortly."
)
