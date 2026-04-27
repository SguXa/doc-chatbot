package com.aos.chatbot.routes.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class UserInfo(
    val username: String,
    val role: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val expiresIn: Long,
    val user: UserInfo
)

@Serializable
data class InvalidLoginResponse(
    val error: String = "invalid_credentials"
)

@Serializable
data class InvalidRequestResponse(
    val error: String = "invalid_request",
    val reason: String
)
