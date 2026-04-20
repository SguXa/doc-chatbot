package com.aos.chatbot.services

class EmptyDocumentException(
    message: String = "Document contains no extractable content"
) : RuntimeException(message)
