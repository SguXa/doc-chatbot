package com.aos.chatbot.parsers

import com.aos.chatbot.models.ParsedContent
import java.io.File

/**
 * Contract for document format parsers.
 *
 * Implementations must produce a [ParsedContent] that satisfies the image linkage
 * contract (ARCHITECTURE.md §8.4) and pageNumber policy (§8.5).
 */
interface DocumentParser {
    fun parse(file: File): ParsedContent
    fun supportedExtensions(): List<String>
}
