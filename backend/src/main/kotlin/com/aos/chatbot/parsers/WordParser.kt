package com.aos.chatbot.parsers

import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipException

/**
 * Parses `.docx` files using Apache POI, producing [ParsedContent] that satisfies
 * the image linkage contract (ARCHITECTURE.md section 8.4) and pageNumber policy (section 8.5).
 *
 * All emitted [TextBlock] and [ImageData] have `pageNumber = null` because
 * Apache POI does not expose reliable rendered page numbers for Word documents.
 */
class WordParser : DocumentParser {

    private val logger = LoggerFactory.getLogger(WordParser::class.java)

    private val sectionNumberPattern = Regex("""^(\d+(?:\.\d+)*)\s+(.+)""")

    override fun supportedExtensions(): List<String> = listOf("docx")

    override fun parse(file: File): ParsedContent {
        val sanitizedName = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        try {
            FileInputStream(file).use { fis ->
                XWPFDocument(fis).use { doc ->
                    return extractContent(doc)
                }
            }
        } catch (e: UnreadableDocumentException) {
            throw e
        } catch (e: OLE2NotOfficeXmlFileException) {
            logger.info("Unreadable docx (ole2_instead_of_ooxml): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.OLE2_INSTEAD_OF_OOXML, "docx", e)
        } catch (e: InvalidFormatException) {
            logger.info("Unreadable docx (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "docx", e)
        } catch (e: POIXMLException) {
            val reason = classifyPoiException(e)
            logger.info("Unreadable docx ({}): {}", reason.code, sanitizedName)
            throw UnreadableDocumentException(reason, "docx", e)
        } catch (e: ZipException) {
            logger.info("Unreadable docx (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "docx", e)
        } catch (e: IOException) {
            logger.info("Unreadable docx (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "docx", e)
        } catch (e: IllegalArgumentException) {
            logger.info("Unreadable docx (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "docx", e)
        }
    }

    private fun classifyPoiException(e: POIXMLException): UnreadableReason {
        val message = (e.message ?: "") + (e.cause?.message ?: "")
        return when {
            message.contains("password", ignoreCase = true) ||
                message.contains("encrypt", ignoreCase = true) -> UnreadableReason.PASSWORD_PROTECTED
            else -> UnreadableReason.CORRUPTED
        }
    }

    private fun extractContent(doc: XWPFDocument): ParsedContent {
        val textBlocks = mutableListOf<TextBlock>()
        val images = mutableListOf<ImageData>()
        var imageSequence = 1

        var currentHeading: String? = null
        var currentSectionId: String? = null
        var currentParagraphs = mutableListOf<String>()
        var pendingImageRefs = mutableListOf<String>()

        fun flushCurrentBlock() {
            val content = currentParagraphs.joinToString("\n").trim()
            if (content.isNotEmpty() || pendingImageRefs.isNotEmpty()) {
                textBlocks.add(
                    TextBlock(
                        content = content,
                        type = "text",
                        pageNumber = null,
                        sectionId = currentSectionId,
                        heading = currentHeading,
                        imageRefs = pendingImageRefs.toList()
                    )
                )
                pendingImageRefs = mutableListOf()
            }
        }

        for (element in doc.bodyElements) {
            when (element) {
                is XWPFParagraph -> {
                    val styleName = element.style ?: ""
                    val isHeading = styleName.startsWith("Heading", ignoreCase = true) ||
                        styleName.startsWith("heading", ignoreCase = true)

                    if (isHeading) {
                        // Flush the previous block BEFORE extracting heading images,
                        // so that images in the heading attach to the next block
                        // under the new heading, not the previous block.
                        flushCurrentBlock()
                        val headingText = element.text.trim()
                        val match = sectionNumberPattern.matchEntire(headingText)
                        currentSectionId = match?.groupValues?.get(1)
                        currentHeading = headingText
                        currentParagraphs = mutableListOf()
                    }

                    // Extract inline images from runs
                    for (run in element.runs) {
                        for (pic in run.embeddedPictures) {
                            val picData = pic.pictureData
                            val ext = (picData.suggestFileExtension() ?: "png").replace(Regex("[^a-zA-Z0-9]"), "")
                            val filename = "img_%03d.%s".format(imageSequence, ext)
                            imageSequence++
                            images.add(
                                ImageData(
                                    filename = filename,
                                    data = picData.data,
                                    pageNumber = null,
                                    caption = null
                                )
                            )
                            pendingImageRefs.add(filename)
                        }
                    }

                    if (!isHeading) {
                        val text = element.text.trim()
                        if (text.isNotEmpty()) {
                            currentParagraphs.add(text)
                        }
                    }
                }

                is XWPFTable -> {
                    flushCurrentBlock()
                    currentParagraphs = mutableListOf()

                    // Extract images from table cells
                    for (row in element.rows) {
                        for (cell in row.tableCells) {
                            for (paragraph in cell.paragraphs) {
                                for (run in paragraph.runs) {
                                    for (pic in run.embeddedPictures) {
                                        val picData = pic.pictureData
                                        val ext = (picData.suggestFileExtension() ?: "png").replace(Regex("[^a-zA-Z0-9]"), "")
                                        val filename = "img_%03d.%s".format(imageSequence, ext)
                                        imageSequence++
                                        images.add(
                                            ImageData(
                                                filename = filename,
                                                data = picData.data,
                                                pageNumber = null,
                                                caption = null
                                            )
                                        )
                                        pendingImageRefs.add(filename)
                                    }
                                }
                            }
                        }
                    }

                    val tableText = renderTable(element)
                    textBlocks.add(
                        TextBlock(
                            content = tableText,
                            type = "table",
                            pageNumber = null,
                            sectionId = currentSectionId,
                            heading = currentHeading,
                            imageRefs = pendingImageRefs.toList()
                        )
                    )
                    pendingImageRefs = mutableListOf()
                }
            }
        }

        // Flush remaining paragraphs
        flushCurrentBlock()

        // If there are still pending image refs after all content, create a synthetic empty block
        if (pendingImageRefs.isNotEmpty()) {
            textBlocks.add(
                TextBlock(
                    content = "",
                    type = "text",
                    pageNumber = null,
                    sectionId = currentSectionId,
                    heading = currentHeading,
                    imageRefs = pendingImageRefs.toList()
                )
            )
        }

        return ParsedContent(
            textBlocks = textBlocks,
            images = images,
            metadata = emptyMap()
        )
    }

    private fun renderTable(table: XWPFTable): String {
        val rows = table.rows.map { row ->
            row.tableCells.joinToString(" | ") { cell -> cell.text.trim() }
        }
        return rows.joinToString("\n") { "| $it |" }
    }

}
