package com.aos.chatbot.parsers.aos

import com.aos.chatbot.models.ParsedContent

/**
 * Orchestrates AOS-specific post-processing of [ParsedContent] produced by
 * WordParser or PdfParser.
 *
 * Runs [TroubleshootParser] (MA-XX code detection) followed by [ComponentParser]
 * (table enrichment). Both processors preserve imageRefs and pageNumber unchanged
 * per ARCHITECTURE.md §8.4 and §8.5.
 *
 * [ParsedContent.images] passes through unchanged — AOS post-processing only
 * transforms text blocks.
 */
class AosParser(
    private val troubleshootParser: TroubleshootParser = TroubleshootParser(),
    private val componentParser: ComponentParser = ComponentParser()
) {

    /**
     * Applies AOS-specific transformations to [parsedContent].
     *
     * @return a new [ParsedContent] with transformed text blocks and the original images list.
     */
    fun process(parsedContent: ParsedContent): ParsedContent {
        val blocks = parsedContent.textBlocks
        val afterTroubleshoot = troubleshootParser.process(blocks)
        val afterComponent = componentParser.process(afterTroubleshoot)

        return parsedContent.copy(textBlocks = afterComponent)
    }
}
