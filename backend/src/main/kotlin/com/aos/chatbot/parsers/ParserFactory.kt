package com.aos.chatbot.parsers

/**
 * Maps file extensions to the appropriate [DocumentParser] implementation.
 */
class ParserFactory {
    private val wordParser = WordParser()
    private val pdfParser = PdfParser()

    private val parsersByExtension: Map<String, DocumentParser> = buildMap {
        for (ext in wordParser.supportedExtensions()) put(ext, wordParser)
        for (ext in pdfParser.supportedExtensions()) put(ext, pdfParser)
    }

    fun getParser(filename: String): DocumentParser {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return parsersByExtension[ext]
            ?: throw IllegalArgumentException("Unsupported file extension: .$ext")
    }
}
