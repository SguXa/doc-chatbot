package com.aos.chatbot.parsers.aos

import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class AosParserTest {

    private val parser = AosParser()

    // --- Typical AOS document fixtures ---

    @Test
    fun `processes typical AOS document with troubleshoot and component blocks`() {
        val imageData = listOf(
            ImageData("img_001.png", byteArrayOf(1, 2, 3), pageNumber = 1),
            ImageData("img_002.png", byteArrayOf(4, 5, 6), pageNumber = 2)
        )
        val blocks = listOf(
            TextBlock(
                content = "3.1 System Overview\nThe system consists of multiple modules.",
                type = "text",
                pageNumber = 1,
                sectionId = "3.1",
                heading = "System Overview",
                imageRefs = listOf("img_001.png")
            ),
            TextBlock(
                content = "MA-03: Connection Timeout\nSymptom: Device does not respond.\nCause: Cable disconnected.\nSolution: Check cable.",
                type = "text",
                pageNumber = 2,
                imageRefs = listOf("img_002.png")
            ),
            TextBlock(
                content = "| Part Number | Component | Specification |\n| PN-001 | Sensor | 10V |",
                type = "table",
                pageNumber = 3
            )
        )

        val input = ParsedContent(blocks, imageData, mapOf("pages" to "3"))
        val result = parser.process(input)

        // Normal text block unchanged
        assertEquals("text", result.textBlocks[0].type)
        assertEquals("3.1", result.textBlocks[0].sectionId)

        // Troubleshoot block detected
        assertEquals("troubleshoot", result.textBlocks[1].type)
        assertEquals("MA-03", result.textBlocks[1].sectionId)

        // Component table enriched
        assertEquals("table", result.textBlocks[2].type)
        assertEquals("Component Table", result.textBlocks[2].heading)
    }

    // --- imageRefs passthrough tests ---

    @Test
    fun `imageRefs preserved through type conversion`() {
        val refs = listOf("img_001.png", "img_002.jpg")
        val block = TextBlock(
            content = "MA-07: Error\nSymptom: Failure.\nCause: Unknown.\nSolution: Fix.",
            imageRefs = refs
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals("troubleshoot", result.textBlocks[0].type)
        assertEquals(refs, result.textBlocks[0].imageRefs)
    }

    @Test
    fun `imageRefs preserved through enrichment`() {
        val refs = listOf("img_p1_001.png")
        val block = TextBlock(
            content = "| Part Number | Description |\n| PN-100 | Widget |",
            type = "table",
            imageRefs = refs
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(refs, result.textBlocks[0].imageRefs)
    }

    @Test
    fun `imageRefs preserved on passthrough blocks`() {
        val refs = listOf("img_003.png")
        val block = TextBlock(
            content = "Regular paragraph.",
            imageRefs = refs
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(refs, result.textBlocks[0].imageRefs)
    }

    // --- ParsedContent.images identity ---

    @Test
    fun `ParsedContent images pass through unchanged`() {
        val imageData = listOf(
            ImageData("img_001.png", byteArrayOf(1, 2, 3)),
            ImageData("img_002.jpg", byteArrayOf(4, 5, 6))
        )
        val blocks = listOf(
            TextBlock(content = "MA-01: Error\nSymptom: X.", imageRefs = listOf("img_001.png")),
            TextBlock(content = "Normal text.", imageRefs = listOf("img_002.jpg"))
        )

        val input = ParsedContent(blocks, imageData)
        val result = parser.process(input)

        assertSame(input.images, result.images)
        assertEquals(imageData, result.images)
    }

    @Test
    fun `empty images list passes through`() {
        val input = ParsedContent(
            listOf(TextBlock(content = "Text.")),
            emptyList()
        )
        val result = parser.process(input)

        assertSame(input.images, result.images)
    }

    // --- pageNumber preservation ---

    @Test
    fun `pageNumber preserved on troubleshoot conversion`() {
        val block = TextBlock(
            content = "MA-10: Error\nSymptom: Fail.",
            pageNumber = 5
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(5, result.textBlocks[0].pageNumber)
    }

    @Test
    fun `null pageNumber preserved on troubleshoot conversion`() {
        val block = TextBlock(
            content = "MA-10: Error\nSymptom: Fail.",
            pageNumber = null
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(null, result.textBlocks[0].pageNumber)
    }

    @Test
    fun `pageNumber preserved on component enrichment`() {
        val block = TextBlock(
            content = "| Part Number | Name |\n| X-100 | Gear |",
            type = "table",
            pageNumber = 3
        )

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(3, result.textBlocks[0].pageNumber)
    }

    @Test
    fun `pageNumber preserved on merge - first input wins`() {
        // AosParser doesn't merge blocks, but verify blocks maintain their pageNumber
        val blocks = listOf(
            TextBlock(content = "Block 1.", pageNumber = 1),
            TextBlock(content = "Block 2.", pageNumber = 2)
        )

        val result = parser.process(ParsedContent(blocks, emptyList()))

        assertEquals(1, result.textBlocks[0].pageNumber)
        assertEquals(2, result.textBlocks[1].pageNumber)
    }

    // --- metadata preservation ---

    @Test
    fun `metadata passes through unchanged`() {
        val meta = mapOf("pages" to "10", "author" to "Test")
        val input = ParsedContent(
            listOf(TextBlock(content = "Text.")),
            emptyList(),
            meta
        )

        val result = parser.process(input)

        assertEquals(meta, result.metadata)
    }

    // --- empty input ---

    @Test
    fun `empty text blocks produce empty output`() {
        val result = parser.process(ParsedContent(emptyList(), emptyList()))

        assertEquals(0, result.textBlocks.size)
        assertEquals(0, result.images.size)
    }

    // --- synthetic empty blocks survive ---

    @Test
    fun `synthetic empty block with imageRefs survives processing`() {
        val refs = listOf("img_p1_001.png")
        val block = TextBlock(content = "", imageRefs = refs, pageNumber = 1)

        val result = parser.process(ParsedContent(listOf(block), emptyList()))

        assertEquals(1, result.textBlocks.size)
        assertEquals("", result.textBlocks[0].content)
        assertEquals(refs, result.textBlocks[0].imageRefs)
    }
}
