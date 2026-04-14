package com.aos.chatbot.parsers.aos

import com.aos.chatbot.models.TextBlock

/**
 * Detects MA-XX troubleshooting codes in text blocks and extracts structured
 * troubleshoot blocks with code, symptom, cause, and solution fields.
 *
 * Supports both English (Symptom/Cause/Solution) and German (Symptom/Ursache/Lösung) labels.
 */
class TroubleshootParser {

    /**
     * Scans [blocks] for MA-XX patterns and converts matching blocks to type="troubleshoot".
     * Non-matching blocks pass through unchanged.
     * imageRefs and pageNumber are preserved unchanged on every output block.
     */
    fun process(blocks: List<TextBlock>): List<TextBlock> {
        return blocks.map { block -> processBlock(block) }
    }

    private fun processBlock(block: TextBlock): TextBlock {
        val maMatch = MA_CODE_PATTERN.find(block.content) ?: return block

        val code = maMatch.groupValues[1]
        val title = extractTitle(block.content, maMatch)
        val fields = extractFields(block.content)

        val structuredContent = buildString {
            append("$code: $title")
            fields["symptom"]?.let { append("\nSymptom: $it") }
            fields["cause"]?.let { append("\nCause: $it") }
            fields["solution"]?.let { append("\nSolution: $it") }
        }

        return block.copy(
            content = structuredContent,
            type = "troubleshoot",
            sectionId = code
        )
    }

    private fun extractTitle(content: String, maMatch: MatchResult): String {
        val afterCode = content.substring(maMatch.range.last + 1).trimStart()
        val colonIndex = afterCode.indexOf(':')
        val titlePart = if (colonIndex >= 0) {
            afterCode.substring(colonIndex + 1).trimStart()
        } else {
            afterCode
        }
        // Title is up to the first newline
        val newlineIndex = titlePart.indexOf('\n')
        return if (newlineIndex >= 0) titlePart.substring(0, newlineIndex).trim()
        else titlePart.trim()
    }

    private fun extractFields(content: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val lines = content.lines()

        var currentField: String? = null
        val currentValue = StringBuilder()

        for (line in lines) {
            val fieldMatch = FIELD_PATTERN.find(line.trim())
            if (fieldMatch != null) {
                // Save previous field
                if (currentField != null) {
                    fields[currentField] = currentValue.toString().trim()
                }
                currentField = normalizeFieldName(fieldMatch.groupValues[1])
                currentValue.clear()
                currentValue.append(fieldMatch.groupValues[2].trim())
            } else if (currentField != null) {
                if (currentValue.isNotEmpty()) currentValue.append(" ")
                currentValue.append(line.trim())
            }
        }

        // Save last field
        if (currentField != null) {
            fields[currentField] = currentValue.toString().trim()
        }

        return fields
    }

    private fun normalizeFieldName(label: String): String {
        return when (label.lowercase().trimEnd(':')) {
            "symptom" -> "symptom"
            "cause", "ursache" -> "cause"
            "solution", "lösung" -> "solution"
            else -> label.lowercase().trimEnd(':')
        }
    }

    companion object {
        /** Matches MA-XX or MA-XXX codes at the start of content or after a newline. */
        val MA_CODE_PATTERN = Regex("""(?:^|\n)\s*(MA-\d{2,3})\b""")

        /** Matches field labels in English and German. */
        private val FIELD_PATTERN = Regex(
            """^(Symptom|Cause|Ursache|Solution|Lösung)\s*:\s*(.*)$""",
            RegexOption.IGNORE_CASE
        )
    }
}
