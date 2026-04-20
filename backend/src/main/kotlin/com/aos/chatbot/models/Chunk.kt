package com.aos.chatbot.models

import kotlinx.serialization.Serializable

@Serializable
data class Chunk(
    val id: Long = 0,
    val documentId: Long = 0,
    val content: String,
    val contentType: String,
    val pageNumber: Int? = null,
    val sectionId: String? = null,
    val heading: String? = null,
    val embedding: ByteArray? = null,
    val imageRefs: List<String> = emptyList(),
    val createdAt: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return id == other.id &&
            documentId == other.documentId &&
            content == other.content &&
            contentType == other.contentType &&
            pageNumber == other.pageNumber &&
            sectionId == other.sectionId &&
            heading == other.heading &&
            embedding.contentEquals(other.embedding) &&
            imageRefs == other.imageRefs &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (pageNumber ?: 0)
        result = 31 * result + (sectionId?.hashCode() ?: 0)
        result = 31 * result + (heading?.hashCode() ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + imageRefs.hashCode()
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        return result
    }
}
