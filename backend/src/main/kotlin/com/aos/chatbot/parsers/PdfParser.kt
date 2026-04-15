package com.aos.chatbot.parsers

import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Parses `.pdf` files using Apache PDFBox, producing [ParsedContent] that satisfies
 * the image linkage contract (ARCHITECTURE.md §8.4) and pageNumber policy (§8.5).
 *
 * All emitted [TextBlock] and [ImageData] have `pageNumber` set to the 1-indexed
 * page number from which they were extracted.
 */
class PdfParser : DocumentParser {

    private val logger = LoggerFactory.getLogger(PdfParser::class.java)

    override fun supportedExtensions(): List<String> = listOf("pdf")

    override fun parse(file: File): ParsedContent {
        val sanitizedName = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        try {
            if (file.length() == 0L) {
                throw IOException("Empty file")
            }
            val doc = Loader.loadPDF(RandomAccessReadBufferedFile(file))
            doc.use { return extractContent(it) }
        } catch (e: UnreadableDocumentException) {
            throw e
        } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            logger.info("Unreadable pdf (encrypted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.ENCRYPTED, "pdf", e)
        } catch (e: IOException) {
            logger.info("Unreadable pdf (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "pdf", e)
        } catch (e: Exception) {
            logger.info("Unreadable pdf (corrupted): {}", sanitizedName)
            throw UnreadableDocumentException(UnreadableReason.CORRUPTED, "pdf", e)
        }
    }

    private fun extractContent(doc: PDDocument): ParsedContent {
        if (doc.isEncrypted) {
            throw UnreadableDocumentException(UnreadableReason.ENCRYPTED, "pdf")
        }

        val allTextBlocks = mutableListOf<TextBlock>()
        val allImages = mutableListOf<ImageData>()
        val pageCount = doc.numberOfPages

        for (pageIndex in 0 until pageCount) {
            val pageNumber = pageIndex + 1

            // Extract text for this page
            val stripper = PDFTextStripper()
            stripper.startPage = pageNumber
            stripper.endPage = pageNumber
            val pageText = stripper.getText(doc).trim()

            // Extract images for this page
            val pageImages = extractImagesFromPage(doc, pageIndex, pageNumber)
            allImages.addAll(pageImages)
            val pageImageRefs = pageImages.map { it.filename }

            // Parse text into blocks with heading detection
            val textBlocks = parsePageText(pageText, pageNumber, pageImageRefs)
            allTextBlocks.addAll(textBlocks)
        }

        return ParsedContent(
            textBlocks = allTextBlocks,
            images = allImages,
            metadata = mapOf("pageCount" to pageCount.toString())
        )
    }

    private fun extractImagesFromPage(doc: PDDocument, pageIndex: Int, pageNumber: Int): List<ImageData> {
        val images = mutableListOf<ImageData>()
        val page = doc.getPage(pageIndex)
        val resources = page.resources ?: return images
        var imageSeq = 1

        for (name in resources.xObjectNames) {
            val xObject = resources.getXObject(name) ?: continue
            if (xObject is PDImageXObject) {
                val suffix = (xObject.suffix ?: "png").replace(Regex("[^a-zA-Z0-9]"), "")

                val data = try {
                    val baos = ByteArrayOutputStream()
                    val image = xObject.image
                    if (image != null) {
                        val written = ImageIO.write(image, suffix, baos)
                        if (written && baos.size() > 0) {
                            baos.toByteArray()
                        } else {
                            // ImageIO could not encode to this suffix; fall back to raw stream
                            xObject.createInputStream().use { it.readAllBytes() }
                        }
                    } else {
                        xObject.createInputStream().use { it.readAllBytes() }
                    }
                } catch (e: Exception) {
                    // Fall back to raw stream data
                    try {
                        xObject.createInputStream().use { it.readAllBytes() }
                    } catch (e2: Exception) {
                        // Skip images we can't extract
                        continue
                    }
                }

                if (data.isNotEmpty()) {
                    val filename = "img_p%d_%03d.%s".format(pageNumber, imageSeq, suffix)
                    imageSeq++
                    images.add(
                        ImageData(
                            filename = filename,
                            data = data,
                            pageNumber = pageNumber,
                            caption = null
                        )
                    )
                }
            }
        }

        return images
    }

    private fun parsePageText(text: String, pageNumber: Int, imageRefs: List<String>): List<TextBlock> {
        if (text.isEmpty() && imageRefs.isEmpty()) {
            return emptyList()
        }

        if (text.isEmpty()) {
            // Image-only page: synthetic empty TextBlock to carry image refs
            return listOf(
                TextBlock(
                    content = "",
                    type = "text",
                    pageNumber = pageNumber,
                    sectionId = null,
                    heading = null,
                    imageRefs = imageRefs
                )
            )
        }

        val lines = text.lines()
        val blocks = mutableListOf<TextBlock>()
        var currentHeading: String? = null
        var currentSectionId: String? = null
        var bodyLines = mutableListOf<String>()
        var imagesAssigned = false

        fun flushBlock() {
            val content = bodyLines.joinToString("\n").trim()
            if (content.isNotEmpty()) {
                val refs = if (!imagesAssigned) {
                    imagesAssigned = true
                    imageRefs
                } else {
                    emptyList()
                }
                blocks.add(
                    TextBlock(
                        content = content,
                        type = "text",
                        pageNumber = pageNumber,
                        sectionId = currentSectionId,
                        heading = currentHeading,
                        imageRefs = refs
                    )
                )
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (isHeading(trimmed)) {
                flushBlock()
                bodyLines = mutableListOf()
                currentHeading = trimmed
                currentSectionId = extractSectionId(trimmed)
            } else {
                bodyLines.add(trimmed)
            }
        }

        flushBlock()

        // If no blocks were created but we have images, create synthetic block
        if (blocks.isEmpty() && imageRefs.isNotEmpty()) {
            blocks.add(
                TextBlock(
                    content = "",
                    type = "text",
                    pageNumber = pageNumber,
                    sectionId = null,
                    heading = null,
                    imageRefs = imageRefs
                )
            )
        }

        // If images haven't been assigned yet (e.g. text was all headings with no body),
        // assign them to the first block or create a synthetic one
        if (!imagesAssigned && imageRefs.isNotEmpty()) {
            if (blocks.isNotEmpty()) {
                blocks[0] = blocks[0].copy(imageRefs = imageRefs)
            } else {
                blocks.add(
                    TextBlock(
                        content = "",
                        type = "text",
                        pageNumber = pageNumber,
                        sectionId = null,
                        heading = null,
                        imageRefs = imageRefs
                    )
                )
            }
        }

        return blocks
    }

    private val sectionNumberPattern = Regex("""^(\d+(?:\.\d+)*)\s+(.+)""")

    private fun extractSectionId(text: String): String? {
        return sectionNumberPattern.matchEntire(text)?.groupValues?.get(1)
    }

    /**
     * Heuristic heading detection: ALL CAPS short line (<=80 chars, >=2 chars)
     * that is not purely numeric/punctuation.
     */
    private fun isHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 2 || trimmed.length > 80) return false
        // Check if it's ALL CAPS with at least some letters
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        if (letters.all { it.isUpperCase() }) return true
        // Also detect numbered section headings like "3.2.1 Component Setup"
        if (sectionNumberPattern.matches(trimmed)) {
            // Only if it's relatively short
            return trimmed.length <= 80
        }
        return false
    }
}
