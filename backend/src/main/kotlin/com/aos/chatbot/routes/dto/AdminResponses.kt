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
    val reason: String = "empty_content",
    val message: String
)

@Serializable
data class DocumentListResponse(
    val documents: List<Document>,
    val total: Int
)
