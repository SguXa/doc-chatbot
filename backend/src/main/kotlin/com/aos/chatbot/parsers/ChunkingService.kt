package com.aos.chatbot.parsers

import com.aos.chatbot.models.TextBlock

/**
 * Splits [TextBlock]s into chunks suitable for embedding and retrieval.
 *
 * Token estimation uses whitespace-split word count as a proxy.
 * Special block types (`table`, `troubleshoot`) are never split regardless of size.
 */
class ChunkingService(
    private val maxChunkTokens: Int = 500,
    private val overlapTokens: Int = 50,
    private val minChunkTokens: Int = 100
) {

    fun chunk(textBlocks: List<TextBlock>): List<TextBlock> {
        val result = mutableListOf<TextBlock>()

        for (block in textBlocks) {
            val chunks = splitBlock(block)
            result.addAll(chunks)
        }

        return mergeTrailingSmallChunks(result)
    }

    private fun splitBlock(block: TextBlock): List<TextBlock> {
        // Synthetic empty blocks with imageRefs must survive
        if (block.content.isBlank() && block.imageRefs.isNotEmpty()) {
            return listOf(block)
        }

        // Never split special types
        if (block.type in NON_SPLITTABLE_TYPES) {
            return listOf(block)
        }

        val tokenCount = estimateTokens(block.content)
        if (tokenCount <= maxChunkTokens) {
            return listOf(block)
        }

        // Split on sentence boundaries
        val sentences = splitIntoSentences(block.content)
        val chunks = mutableListOf<TextBlock>()
        var currentSentences = mutableListOf<String>()
        var currentTokens = 0

        for (sentence in sentences) {
            val sentenceTokens = estimateTokens(sentence)

            if (currentSentences.isNotEmpty() && currentTokens + sentenceTokens > maxChunkTokens) {
                // Emit current chunk
                chunks.add(block.copy(content = currentSentences.joinToString(" ")))

                // Apply overlap: keep trailing sentences that fit within overlapTokens
                val overlapSentences = computeOverlap(currentSentences)
                currentSentences = overlapSentences.toMutableList()
                currentTokens = estimateTokens(currentSentences.joinToString(" "))
            }

            currentSentences.add(sentence)
            currentTokens += sentenceTokens
        }

        // Emit remaining
        if (currentSentences.isNotEmpty()) {
            chunks.add(block.copy(content = currentSentences.joinToString(" ")))
        }

        // Replicate parent's full imageRefs onto every output chunk
        return chunks.map { it.copy(imageRefs = block.imageRefs) }
    }

    private fun computeOverlap(sentences: List<String>): List<String> {
        if (overlapTokens <= 0) return emptyList()

        val result = mutableListOf<String>()
        var tokens = 0

        for (sentence in sentences.reversed()) {
            val sentenceTokens = estimateTokens(sentence)
            if (tokens + sentenceTokens > overlapTokens && result.isNotEmpty()) break
            result.add(0, sentence)
            tokens += sentenceTokens
        }

        return result
    }

    private fun mergeTrailingSmallChunks(chunks: List<TextBlock>): List<TextBlock> {
        if (chunks.size <= 1) return chunks

        val result = chunks.toMutableList()
        var i = result.size - 1

        while (i > 0) {
            val current = result[i]
            // Don't merge synthetic empty blocks that carry imageRefs
            if (current.content.isBlank() && current.imageRefs.isNotEmpty()) {
                i--
                continue
            }

            if (estimateTokens(current.content) < minChunkTokens) {
                val prev = result[i - 1]
                // Only merge chunks from the same parent (same pageNumber, type, sectionId, heading)
                if (prev.pageNumber == current.pageNumber &&
                    prev.type == current.type &&
                    prev.sectionId == current.sectionId &&
                    prev.heading == current.heading
                ) {
                    val mergedContent = if (prev.content.isBlank()) current.content
                    else if (current.content.isBlank()) prev.content
                    else "${prev.content} ${current.content}"

                    // Union imageRefs, preserve order, dedupe
                    val mergedRefs = (prev.imageRefs + current.imageRefs).distinct()

                    result[i - 1] = prev.copy(content = mergedContent, imageRefs = mergedRefs)
                    result.removeAt(i)
                }
            }
            i--
        }

        return result
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Split on sentence-ending punctuation followed by whitespace
        val sentences = mutableListOf<String>()
        val pattern = Regex("""(?<=[.!?])\s+""")
        val parts = text.split(pattern)

        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                sentences.add(trimmed)
            }
        }

        return sentences
    }

    private fun estimateTokens(text: String): Int {
        return text.split(Regex("""\s+""")).count { it.isNotEmpty() }
    }

    companion object {
        private val NON_SPLITTABLE_TYPES = setOf("table", "troubleshoot")
    }
}
