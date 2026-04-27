package com.aos.chatbot.routes.dto

import kotlinx.serialization.Serializable

@Serializable
data class SystemPromptResponse(
    val prompt: String,
    val updatedAt: String
)

@Serializable
data class UpdateSystemPromptRequest(
    val prompt: String
)

@Serializable
data class InvalidConfigRequestResponse(
    val error: String = "invalid_request",
    val reason: String
)
