package com.aos.chatbot.parsers

import com.aos.chatbot.models.ParsedContent
import java.io.File

/**
 * Parses `.pdf` files using Apache PDFBox.
 *
 * Full implementation in Task 9.
 */
class PdfParser : DocumentParser {
    override fun parse(file: File): ParsedContent {
        TODO("PdfParser implementation is Task 9")
    }

    override fun supportedExtensions(): List<String> = listOf("pdf")
}
