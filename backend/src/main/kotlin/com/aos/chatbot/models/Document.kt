package com.aos.chatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class Document(
    val id: Long = 0,
    val filename: String,
    val fileType: String,
    val fileSize: Long,
    val fileHash: String,
    val chunkCount: Int = 0,
    val imageCount: Int = 0,
    val indexedAt: String? = null,
    val createdAt: String? = null
)
