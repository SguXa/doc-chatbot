package com.aos.chatbot.services

import com.aos.chatbot.models.Document

sealed class UploadResult {
    data class Created(val document: Document) : UploadResult()
    data class Duplicate(val document: Document) : UploadResult()
}
