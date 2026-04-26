package com.aos.chatbot.services

class OllamaUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
