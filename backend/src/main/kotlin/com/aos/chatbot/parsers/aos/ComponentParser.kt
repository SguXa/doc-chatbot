package com.aos.chatbot.parsers.aos

import com.aos.chatbot.models.TextBlock

/**
 * Enriches table blocks with component metadata when the table content
 * contains component-related information (part numbers, specifications, etc.).
 *
 * imageRefs and pageNumber are preserved unchanged on every output block.
 */
class ComponentParser {

    /**
     * Scans [blocks] for table blocks containing component data and enriches them
     * with appropriate sectionId and heading metadata. Non-table blocks and tables
     * without component data pass through unchanged.
     */
    fun process(blocks: List<TextBlock>): List<TextBlock> {
        return blocks.map { block -> processBlock(block) }
    }

    private fun processBlock(block: TextBlock): TextBlock {
        if (block.type != "table") return block

        val content = block.content
        if (!isComponentTable(content)) return block

        val componentId = extractComponentId(content)
        val heading = block.heading ?: extractHeading(content)

        return block.copy(
            sectionId = componentId ?: block.sectionId,
            heading = heading
        )
    }

    private fun isComponentTable(content: String): Boolean {
        return COMPONENT_INDICATORS.any { indicator ->
            content.contains(indicator, ignoreCase = true)
        }
    }

    private fun extractComponentId(content: String): String? {
        val match = COMPONENT_ID_PATTERN.find(content)
        return match?.groupValues?.get(1)
    }

    private fun extractHeading(content: String): String {
        val lines = content.lines()
        // Use first non-empty line as heading if it looks like a header row
        val firstLine = lines.firstOrNull { it.isNotBlank() } ?: return "Component Table"
        return if (firstLine.contains('|')) "Component Table"
        else firstLine.take(80).trim()
    }

    companion object {
        private val COMPONENT_INDICATORS = listOf(
            "Part Number", "Teilenummer",
            "Component", "Komponente",
            "Specification", "Spezifikation",
            "Model", "Modell",
            "Article", "Artikel"
        )

        /** Matches common component/part number patterns. */
        private val COMPONENT_ID_PATTERN = Regex(
            """(?:Part\s*(?:Number|No\.?|#)|Teilenummer|Article\s*(?:No\.?|#)|Artikel\s*(?:Nr\.?))\s*[:\s]\s*([A-Z0-9][\w-]{2,20})""",
            RegexOption.IGNORE_CASE
        )
    }
}
