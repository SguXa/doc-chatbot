package com.aos.chatbot.services

class InvalidUploadException(
    val reason: String,
    message: String
) : RuntimeException(message)
