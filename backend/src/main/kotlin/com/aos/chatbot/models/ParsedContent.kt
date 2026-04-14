package com.aos.chatbot.models

/**
 * Result of parsing a document file.
 *
 * Image linkage contract: every filename in [TextBlock.imageRefs] must appear as the
 * [ImageData.filename] of exactly one entry in [images], and vice-versa.
 * See ARCHITECTURE.md §8.4 for the full contract.
 */
data class ParsedContent(
    val textBlocks: List<TextBlock>,
    val images: List<ImageData>,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * A contiguous block of text extracted from a document.
 *
 * [pageNumber] follows the population policy in ARCHITECTURE.md §8.5:
 * PDF sets it to the 1-indexed page; Word sets it to null.
 */
data class TextBlock(
    val content: String,
    val type: String = "text",
    val pageNumber: Int? = null,
    val sectionId: String? = null,
    val heading: String? = null,
    val imageRefs: List<String> = emptyList()
)

/**
 * Raw image data extracted from a document, before persistence.
 *
 * [pageNumber] follows the population policy in ARCHITECTURE.md §8.5:
 * PDF sets it to the 1-indexed page; Word sets it to null.
 */
data class ImageData(
    val filename: String,
    val data: ByteArray,
    val pageNumber: Int? = null,
    val caption: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImageData) return false
        return filename == other.filename &&
            data.contentEquals(other.data) &&
            pageNumber == other.pageNumber &&
            caption == other.caption
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (pageNumber ?: 0)
        result = 31 * result + (caption?.hashCode() ?: 0)
        return result
    }
}
