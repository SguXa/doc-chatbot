package com.aos.chatbot.parsers

import com.aos.chatbot.models.ParsedContent
import java.io.File

/**
 * Parses `.docx` files using Apache POI.
 *
 * Full implementation in Task 8.
 */
class WordParser : DocumentParser {
    override fun parse(file: File): ParsedContent {
        TODO("WordParser implementation is Task 8")
    }

    override fun supportedExtensions(): List<String> = listOf("docx")
}
