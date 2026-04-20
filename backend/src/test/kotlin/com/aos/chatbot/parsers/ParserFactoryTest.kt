package com.aos.chatbot.parsers

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserFactoryTest {
    private val factory = ParserFactory()

    @Test
    fun `getParser returns WordParser for docx extension`() {
        val parser = factory.getParser("report.docx")
        assertIs<WordParser>(parser)
    }

    @Test
    fun `getParser returns PdfParser for pdf extension`() {
        val parser = factory.getParser("manual.pdf")
        assertIs<PdfParser>(parser)
    }

    @Test
    fun `getParser is case-insensitive for docx`() {
        val parser = factory.getParser("report.DOCX")
        assertIs<WordParser>(parser)
    }

    @Test
    fun `getParser is case-insensitive for pdf`() {
        val parser = factory.getParser("manual.PDF")
        assertIs<PdfParser>(parser)
    }

    @Test
    fun `getParser throws IllegalArgumentException for unsupported extension`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            factory.getParser("image.png")
        }
        assertTrue(ex.message!!.contains("png"))
    }

    @Test
    fun `getParser throws IllegalArgumentException for txt files`() {
        assertFailsWith<IllegalArgumentException> {
            factory.getParser("notes.txt")
        }
    }

    @Test
    fun `getParser throws IllegalArgumentException for doc files`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            factory.getParser("legacy.doc")
        }
        assertTrue(ex.message!!.contains("doc"))
    }

    @Test
    fun `getParser throws IllegalArgumentException for file without extension`() {
        assertFailsWith<IllegalArgumentException> {
            factory.getParser("noextension")
        }
    }

    @Test
    fun `getParser handles filename with multiple dots`() {
        val parser = factory.getParser("my.report.v2.docx")
        assertIs<WordParser>(parser)
    }

    @Test
    fun `getParser returns same parser type for repeated calls`() {
        val first = factory.getParser("a.pdf")
        val second = factory.getParser("b.pdf")
        assertIs<PdfParser>(first)
        assertIs<PdfParser>(second)
    }
}
