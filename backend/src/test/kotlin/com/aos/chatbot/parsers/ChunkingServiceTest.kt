package com.aos.chatbot.parsers

import com.aos.chatbot.models.TextBlock
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingServiceTest {

    private val service = ChunkingService(
        maxChunkTokens = 500,
        overlapTokens = 50,
        minChunkTokens = 100
    )

    // --- Sentence-boundary splitting ---

    @Test
    fun `splits long text block at sentence boundaries`() {
        // Create text with enough sentences to exceed 500 tokens
        val sentences = (1..100).map { "This is sentence number $it in a reasonably long document that has many words." }
        val text = sentences.joinToString(" ")
        val block = TextBlock(content = text, type = "text")

        val result = service.chunk(listOf(block))

        assertTrue(result.size > 1, "Expected multiple chunks, got ${result.size}")
        // Each chunk should end at a sentence boundary (end with period)
        result.forEach { chunk ->
            if (chunk.content.isNotBlank()) {
                assertTrue(
                    chunk.content.trimEnd().endsWith("."),
                    "Chunk should end at sentence boundary: '${chunk.content.takeLast(30)}'"
                )
            }
        }
    }

    @Test
    fun `splits oversized sentence by words as fallback`() {
        // One very long sentence that exceeds max tokens — word-level fallback kicks in
        val longSentence = (1..600).joinToString(" ") { "word$it" } + "."
        val block = TextBlock(content = longSentence, type = "text")

        val result = service.chunk(listOf(block))

        assertTrue(result.size > 1, "Expected oversized sentence to be split by words")
        // Every chunk must respect the token cap
        result.forEach { chunk ->
            val tokens = estimateTokens(chunk.content)
            assertTrue(
                tokens <= 500,
                "Chunk exceeds maxChunkTokens ($tokens > 500): '${chunk.content.take(50)}...'"
            )
        }
        // All words must be preserved
        val allContent = result.joinToString(" ") { it.content }
        assertTrue(allContent.contains("word1"))
        assertTrue(allContent.contains("word600"))
    }

    // --- Short-text passthrough ---

    @Test
    fun `short text block passes through unchanged`() {
        val block = TextBlock(
            content = "This is a short paragraph.",
            type = "text",
            pageNumber = 3,
            sectionId = "1.2",
            heading = "Introduction",
            imageRefs = listOf("img_001.png")
        )

        val result = service.chunk(listOf(block))

        assertEquals(1, result.size)
        assertEquals(block, result[0])
    }

    // --- Overlap correctness ---

    @Test
    fun `applies overlap between consecutive chunks`() {
        // Use smaller limits for easier testing
        val smallService = ChunkingService(maxChunkTokens = 20, overlapTokens = 10, minChunkTokens = 5)
        val sentences = listOf(
            "First sentence here.",
            "Second sentence here.",
            "Third sentence here.",
            "Fourth sentence here.",
            "Fifth sentence here.",
            "Sixth sentence here.",
            "Seventh sentence here.",
            "Eighth sentence here."
        )
        val block = TextBlock(content = sentences.joinToString(" "), type = "text")

        val result = smallService.chunk(listOf(block))

        assertTrue(result.size >= 2, "Expected at least 2 chunks")

        // Verify overlap: trailing words of chunk[i] should appear near the start of chunk[i+1]
        for (i in 0 until result.size - 1) {
            val currentWords = result[i].content.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val nextContent = result[i + 1].content
            // The last few words of current chunk should appear in the next chunk
            val trailingPhrase = currentWords.takeLast(3).joinToString(" ")
            assertTrue(
                nextContent.contains(trailingPhrase),
                "Expected trailing phrase '$trailingPhrase' of chunk $i to appear in chunk ${i + 1}, but next chunk was: '${nextContent.take(100)}'"
            )
        }
    }

    // --- Special-type non-splitting ---

    @Test
    fun `does not split table type even if exceeds max size`() {
        val longTable = (1..600).joinToString(" ") { "cell$it" }
        val block = TextBlock(content = longTable, type = "table")

        val result = service.chunk(listOf(block))

        assertEquals(1, result.size)
        assertEquals("table", result[0].type)
        assertEquals(longTable, result[0].content)
    }

    @Test
    fun `does not split troubleshoot type even if exceeds max size`() {
        val longTroubleshoot = (1..600).joinToString(" ") { "step$it" }
        val block = TextBlock(content = longTroubleshoot, type = "troubleshoot")

        val result = service.chunk(listOf(block))

        assertEquals(1, result.size)
        assertEquals("troubleshoot", result[0].type)
    }

    // --- Min-chunk merging ---

    @Test
    fun `merges trailing chunk below minChunkTokens into previous`() {
        val smallService = ChunkingService(maxChunkTokens = 20, overlapTokens = 0, minChunkTokens = 10)
        // Create content that will produce a small trailing chunk
        val sentences = listOf(
            "This is a first longer sentence with many words in it to fill tokens.",
            "Second sentence also has many words to reach the threshold.",
            "Short end."
        )
        val block = TextBlock(content = sentences.joinToString(" "), type = "text")

        val result = smallService.chunk(listOf(block))

        // The trailing chunk should have been merged if it was below minChunkTokens
        result.forEach { chunk ->
            val tokens = chunk.content.split(Regex("\\s+")).count { it.isNotEmpty() }
            // Either it's the only chunk, or it should meet the minimum
            if (result.size > 1) {
                assertTrue(
                    tokens >= 10 || chunk == result[0],
                    "Trailing chunk should be merged: '$chunk' has $tokens tokens"
                )
            }
        }
    }

    // --- imageRefs: long block splits into 3 chunks all carrying same refs ---

    @Test
    fun `all chunks from split block carry full imageRefs`() {
        val sentences = (1..100).map { "Sentence number $it in the long document with extra words added." }
        val refs = listOf("img_001.png", "img_002.png")
        val block = TextBlock(
            content = sentences.joinToString(" "),
            type = "text",
            imageRefs = refs
        )

        val result = service.chunk(listOf(block))

        assertTrue(result.size >= 2, "Expected multiple chunks")
        result.forEach { chunk ->
            assertEquals(refs, chunk.imageRefs, "Every chunk must carry full parent imageRefs")
        }
    }

    // --- imageRefs: short block passthrough ---

    @Test
    fun `short block with imageRefs passes through unchanged`() {
        val refs = listOf("img_001.png", "img_002.png", "img_003.png")
        val block = TextBlock(content = "Short content.", type = "text", imageRefs = refs)

        val result = service.chunk(listOf(block))

        assertEquals(1, result.size)
        assertEquals(refs, result[0].imageRefs)
    }

    // --- imageRefs: merge with overlapping refs ---

    @Test
    fun `merge unions imageRefs and deduplicates`() {
        val smallService = ChunkingService(maxChunkTokens = 500, overlapTokens = 0, minChunkTokens = 100)
        // Two blocks that will produce small chunks that get merged
        val block1 = TextBlock(
            content = "First block content.",
            type = "text",
            pageNumber = 1,
            sectionId = "1.0",
            heading = "H1",
            imageRefs = listOf("img_001.png", "img_002.png")
        )
        val block2 = TextBlock(
            content = "Second block content.",
            type = "text",
            pageNumber = 1,
            sectionId = "1.0",
            heading = "H1",
            imageRefs = listOf("img_002.png", "img_003.png")
        )

        val result = smallService.chunk(listOf(block1, block2))

        // Both are small, should merge since they share same metadata
        assertEquals(1, result.size)
        // Union of refs with dedup: img_001, img_002, img_003
        assertEquals(
            listOf("img_001.png", "img_002.png", "img_003.png"),
            result[0].imageRefs
        )
    }

    // --- imageRefs: synthetic empty block survives ---

    @Test
    fun `synthetic empty block with imageRefs survives chunking`() {
        val block = TextBlock(
            content = "",
            type = "text",
            imageRefs = listOf("img_001.png")
        )

        val result = service.chunk(listOf(block))

        assertEquals(1, result.size)
        assertEquals("", result[0].content)
        assertEquals(listOf("img_001.png"), result[0].imageRefs)
    }

    @Test
    fun `synthetic empty block not merged away even when adjacent to small chunk`() {
        val smallService = ChunkingService(maxChunkTokens = 500, overlapTokens = 0, minChunkTokens = 100)
        val block1 = TextBlock(content = "Some content.", type = "text", imageRefs = emptyList())
        val syntheticBlock = TextBlock(content = "", type = "text", imageRefs = listOf("img_trailing.png"))

        val result = smallService.chunk(listOf(block1, syntheticBlock))

        // Synthetic block must survive
        val syntheticResult = result.find { it.imageRefs.contains("img_trailing.png") }
        assertTrue(syntheticResult != null, "Synthetic empty block with imageRefs must survive")
    }

    // --- End-to-end imageRefs union equality ---

    @Test
    fun `end-to-end imageRefs union equality - input set equals output set`() {
        val blocks = listOf(
            TextBlock(
                content = (1..100).joinToString(" ") { "Word$it is a longer sentence here." },
                type = "text",
                imageRefs = listOf("img_001.png", "img_002.png")
            ),
            TextBlock(
                content = "Short block.",
                type = "text",
                imageRefs = listOf("img_003.png")
            ),
            TextBlock(
                content = "",
                type = "text",
                imageRefs = listOf("img_004.png")
            )
        )

        val inputRefs = blocks.flatMap { it.imageRefs }.toSet()
        val result = service.chunk(blocks)
        val outputRefs = result.flatMap { it.imageRefs }.toSet()

        assertEquals(inputRefs, outputRefs, "Input imageRef set must equal output imageRef set")
    }

    // --- pageNumber preserved ---

    @Test
    fun `pageNumber preserved on every output chunk for non-null input`() {
        val sentences = (1..100).map { "Sentence $it in page five content with extra words." }
        val block = TextBlock(
            content = sentences.joinToString(" "),
            type = "text",
            pageNumber = 5
        )

        val result = service.chunk(listOf(block))

        assertTrue(result.size > 1, "Expected multiple chunks")
        result.forEach { chunk ->
            assertEquals(5, chunk.pageNumber, "pageNumber must be preserved")
        }
    }

    @Test
    fun `pageNumber preserved as null on every output chunk for null input`() {
        val sentences = (1..100).map { "Sentence $it in word document content with extra words." }
        val block = TextBlock(
            content = sentences.joinToString(" "),
            type = "text",
            pageNumber = null
        )

        val result = service.chunk(listOf(block))

        assertTrue(result.size > 1, "Expected multiple chunks")
        result.forEach { chunk ->
            assertEquals(null, chunk.pageNumber, "pageNumber null must be preserved")
        }
    }

    // --- Multiple blocks ---

    @Test
    fun `processes multiple blocks independently`() {
        val block1 = TextBlock(content = "First block.", type = "text", pageNumber = 1)
        val block2 = TextBlock(content = "Second block.", type = "text", pageNumber = 2)
        val longBlock = TextBlock(
            content = (1..100).joinToString(" ") { "Sentence $it here with extra padding words." },
            type = "text",
            pageNumber = 3
        )

        val result = service.chunk(listOf(block1, block2, longBlock))

        // First two pass through, third splits
        assertTrue(result.size >= 3, "Expected at least 3 chunks")
        // Verify page numbers are preserved correctly
        assertTrue(result.any { it.pageNumber == 1 })
        assertTrue(result.any { it.pageNumber == 2 })
        assertTrue(result.any { it.pageNumber == 3 })
    }

    @Test
    fun `empty input produces empty output`() {
        val result = service.chunk(emptyList())
        assertTrue(result.isEmpty())
    }

    // --- Helper ---

    private fun estimateTokens(text: String): Int {
        return text.split(Regex("""\s+""")).count { it.isNotEmpty() }
    }

    // --- Overlap dedup does not strip legitimate repetition ---

    @Test
    fun `mergeWithOverlapDedup preserves legitimately repeated sentence across blocks`() {
        // Two blocks from the same section where block B legitimately starts with
        // the same sentence block A ends with. With overlapTokens=0, no synthetic
        // overlap exists, so nothing should be stripped.
        val noOverlapService = ChunkingService(maxChunkTokens = 500, overlapTokens = 0, minChunkTokens = 100)
        val block1 = TextBlock(
            content = "First paragraph content. Install the software.",
            type = "text", pageNumber = 1, sectionId = "1.0", heading = "Setup"
        )
        val block2 = TextBlock(
            content = "Install the software. Then configure it.",
            type = "text", pageNumber = 1, sectionId = "1.0", heading = "Setup"
        )

        val result = noOverlapService.chunk(listOf(block1, block2))

        // Both blocks are small and share metadata, so they get merged.
        // The repeated sentence "Install the software." should appear TWICE
        // because it's legitimate content, not synthetic overlap.
        assertEquals(1, result.size)
        val merged = result[0].content
        val count = Regex(Regex.escape("Install the software.")).findAll(merged).count()
        assertEquals(2, count, "Legitimately repeated sentence must appear twice, got: $merged")
    }

    @Test
    fun `mergeWithOverlapDedup preserves legitimately repeated sentence with default overlap`() {
        // With default overlapTokens=50, a repeated sentence under 50 tokens must still
        // survive cross-block merging — it is legitimate content, not synthetic overlap.
        val defaultService = ChunkingService(maxChunkTokens = 500, overlapTokens = 50, minChunkTokens = 100)
        val block1 = TextBlock(
            content = "First paragraph content. Install the software.",
            type = "text", pageNumber = 1, sectionId = "1.0", heading = "Setup"
        )
        val block2 = TextBlock(
            content = "Install the software. Then configure it.",
            type = "text", pageNumber = 1, sectionId = "1.0", heading = "Setup"
        )

        val result = defaultService.chunk(listOf(block1, block2))

        assertEquals(1, result.size)
        val merged = result[0].content
        val count = Regex(Regex.escape("Install the software.")).findAll(merged).count()
        assertEquals(2, count, "Legitimately repeated sentence must appear twice with default overlap, got: $merged")
    }

    @Test
    fun `word-split tail is not merged back to exceed maxChunkTokens`() {
        // A 520-word sentence splits into 500+20. The 20-word tail must NOT be merged
        // back to recreate a 520-token chunk.
        val svc = ChunkingService(maxChunkTokens = 500, overlapTokens = 0, minChunkTokens = 100)
        val longSentence = (1..520).joinToString(" ") { "word$it" } + "."
        val block = TextBlock(content = longSentence, type = "text")

        val result = svc.chunk(listOf(block))

        result.forEach { chunk ->
            val tokens = estimateTokens(chunk.content)
            assertTrue(
                tokens <= 500,
                "Merged chunk exceeds maxChunkTokens ($tokens > 500)"
            )
        }
    }

    // --- Metadata preservation ---

    @Test
    fun `sectionId and heading preserved through splitting`() {
        val sentences = (1..100).map { "Long sentence number $it in section with extra words." }
        val block = TextBlock(
            content = sentences.joinToString(" "),
            type = "text",
            sectionId = "3.2.1",
            heading = "Component Setup"
        )

        val result = service.chunk(listOf(block))

        assertTrue(result.size > 1)
        result.forEach { chunk ->
            assertEquals("3.2.1", chunk.sectionId)
            assertEquals("Component Setup", chunk.heading)
        }
    }
}
