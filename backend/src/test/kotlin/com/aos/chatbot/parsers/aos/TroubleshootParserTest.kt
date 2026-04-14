package com.aos.chatbot.parsers.aos

import com.aos.chatbot.models.TextBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class TroubleshootParserTest {

    private val parser = TroubleshootParser()

    @Test
    fun `detects MA-XX code and extracts structured fields`() {
        val block = TextBlock(
            content = """
                MA-03: Connection Timeout
                Symptom: Device does not respond within 30 seconds.
                Cause: Network cable disconnected or firewall blocking.
                Solution: 1. Check network cable connection 2. Verify firewall rules 3. Restart the service
            """.trimIndent()
        )

        val result = parser.process(listOf(block))

        assertEquals(1, result.size)
        val out = result[0]
        assertEquals("troubleshoot", out.type)
        assertEquals("MA-03", out.sectionId)
        assert(out.content.contains("Connection Timeout"))
        assert(out.content.contains("Symptom:"))
        assert(out.content.contains("Cause:"))
        assert(out.content.contains("Solution:"))
    }

    @Test
    fun `detects MA-XXX three-digit code`() {
        val block = TextBlock(
            content = "MA-123: Overheating\nSymptom: Temperature exceeds threshold."
        )

        val result = parser.process(listOf(block))

        assertEquals("troubleshoot", result[0].type)
        assertEquals("MA-123", result[0].sectionId)
    }

    @Test
    fun `handles German labels`() {
        val block = TextBlock(
            content = """
                MA-05: Verbindungsfehler
                Symptom: Gerät antwortet nicht.
                Ursache: Netzwerkkabel nicht angeschlossen.
                Lösung: Kabel überprüfen und erneut verbinden.
            """.trimIndent()
        )

        val result = parser.process(listOf(block))

        assertEquals("troubleshoot", result[0].type)
        assertEquals("MA-05", result[0].sectionId)
        assert(result[0].content.contains("Cause:"))
        assert(result[0].content.contains("Solution:"))
    }

    @Test
    fun `non-troubleshoot block passes through unchanged`() {
        val block = TextBlock(
            content = "This is a normal paragraph about installation steps.",
            type = "text",
            sectionId = "3.2",
            heading = "Installation"
        )

        val result = parser.process(listOf(block))

        assertEquals(1, result.size)
        assertEquals(block, result[0])
    }

    @Test
    fun `preserves imageRefs on type conversion`() {
        val refs = listOf("img_001.png", "img_002.jpg")
        val block = TextBlock(
            content = "MA-07: Sensor Failure\nSymptom: No readings.\nCause: Broken sensor.\nSolution: Replace sensor.",
            imageRefs = refs
        )

        val result = parser.process(listOf(block))

        assertEquals("troubleshoot", result[0].type)
        assertEquals(refs, result[0].imageRefs)
    }

    @Test
    fun `preserves pageNumber unchanged`() {
        val block = TextBlock(
            content = "MA-10: Boot Failure\nSymptom: System does not start.",
            pageNumber = 5
        )

        val result = parser.process(listOf(block))

        assertEquals(5, result[0].pageNumber)
    }

    @Test
    fun `preserves null pageNumber unchanged`() {
        val block = TextBlock(
            content = "MA-10: Boot Failure\nSymptom: System does not start.",
            pageNumber = null
        )

        val result = parser.process(listOf(block))

        assertEquals(null, result[0].pageNumber)
    }

    @Test
    fun `processes multiple blocks mixed troubleshoot and normal`() {
        val blocks = listOf(
            TextBlock(content = "Normal text about components."),
            TextBlock(content = "MA-01: Error One\nSymptom: Something broke.\nCause: Unknown.\nSolution: Fix it."),
            TextBlock(content = "Another normal block."),
            TextBlock(content = "MA-02: Error Two\nSymptom: Another issue.\nCause: Config error.\nSolution: Update config.")
        )

        val result = parser.process(blocks)

        assertEquals(4, result.size)
        assertEquals("text", result[0].type)
        assertEquals("troubleshoot", result[1].type)
        assertEquals("MA-01", result[1].sectionId)
        assertEquals("text", result[2].type)
        assertEquals("troubleshoot", result[3].type)
        assertEquals("MA-02", result[3].sectionId)
    }

    @Test
    fun `MA code after newline in multi-line content is detected`() {
        val block = TextBlock(
            content = "Section: Troubleshooting\nMA-15: Memory Leak\nSymptom: RAM usage increases."
        )

        val result = parser.process(listOf(block))

        assertEquals("troubleshoot", result[0].type)
        assertEquals("MA-15", result[0].sectionId)
    }

    @Test
    fun `preserves imageRefs on non-matching blocks`() {
        val refs = listOf("img_001.png")
        val block = TextBlock(
            content = "Normal text with images.",
            imageRefs = refs
        )

        val result = parser.process(listOf(block))

        assertEquals(refs, result[0].imageRefs)
    }

    @Test
    fun `multi-line solution field is captured`() {
        val block = TextBlock(
            content = """
                MA-04: Disk Full
                Symptom: Write operations fail.
                Cause: Disk space exhausted.
                Solution: Delete temporary files.
                Run cleanup script.
                Verify free space.
            """.trimIndent()
        )

        val result = parser.process(listOf(block))

        assert(result[0].content.contains("Delete temporary files."))
        assert(result[0].content.contains("Run cleanup script."))
    }
}
