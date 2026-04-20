package com.aos.chatbot.models

data class ExtractedImage(
    val id: Long = 0,
    val documentId: Long = 0,
    val filename: String,
    val path: String,
    val pageNumber: Int? = null,
    val caption: String? = null,
    val description: String? = null,
    val embedding: ByteArray? = null,
    val createdAt: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedImage) return false
        return id == other.id &&
            documentId == other.documentId &&
            filename == other.filename &&
            path == other.path &&
            pageNumber == other.pageNumber &&
            caption == other.caption &&
            description == other.description &&
            embedding.contentEquals(other.embedding) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + (pageNumber ?: 0)
        result = 31 * result + (caption?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (createdAt?.hashCode() ?: 0)
        return result
    }
}
