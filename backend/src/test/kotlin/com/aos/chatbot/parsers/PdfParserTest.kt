package com.aos.chatbot.parsers

import com.aos.chatbot.models.ParsedContent
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfParserTest {

    private val parser = PdfParser()

    @TempDir
    lateinit var tempDir: Path

    private var lastResult: ParsedContent? = null

    @AfterEach
    fun `every TextBlock and ImageData has pageNumber != null`() {
        val result = lastResult ?: return
        result.textBlocks.forEachIndexed { i, block ->
            assertNotNull(block.pageNumber, "TextBlock[$i] should have non-null pageNumber")
        }
        result.images.forEachIndexed { i, img ->
            assertNotNull(img.pageNumber, "ImageData[$i] should have non-null pageNumber")
        }
    }

    // --- Single page text + image ---

    @Test
    fun `single page with text and image`() {
        val file = createPdfWithTextAndImages("text_and_image.pdf", listOf(
            PageContent(
                lines = listOf("INTRODUCTION", "This is the body text of the document."),
                imageCount = 1
            )
        ))

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks
        val images = lastResult!!.images

        assertTrue(blocks.isNotEmpty(), "Should have at least one text block")
        assertTrue(images.size == 1, "Should have one image")
        assertEquals(1, images[0].pageNumber)
        assertEquals("img_p1_001.png", images[0].filename)
        assertTrue(blocks.all { it.pageNumber == 1 })
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Two pages with sequence reset ---

    @Test
    fun `two pages - image sequence resets per page`() {
        val file = createPdfWithTextAndImages("two_pages.pdf", listOf(
            PageContent(
                lines = listOf("PAGE ONE HEADER", "Page one body text."),
                imageCount = 2
            ),
            PageContent(
                lines = listOf("PAGE TWO HEADER", "Page two body text."),
                imageCount = 1
            )
        ))

        lastResult = parser.parse(file)
        val images = lastResult!!.images

        assertEquals(3, images.size)
        // Page 1 images
        assertEquals("img_p1_001.png", images[0].filename)
        assertEquals(1, images[0].pageNumber)
        assertEquals("img_p1_002.png", images[1].filename)
        assertEquals(1, images[1].pageNumber)
        // Page 2 image - sequence resets
        assertEquals("img_p2_001.png", images[2].filename)
        assertEquals(2, images[2].pageNumber)

        // Verify page numbers on text blocks
        val page1Blocks = lastResult!!.textBlocks.filter { it.pageNumber == 1 }
        val page2Blocks = lastResult!!.textBlocks.filter { it.pageNumber == 2 }
        assertTrue(page1Blocks.isNotEmpty())
        assertTrue(page2Blocks.isNotEmpty())
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Image-only page ---

    @Test
    fun `image-only page creates synthetic empty TextBlock`() {
        val file = createPdfWithTextAndImages("image_only.pdf", listOf(
            PageContent(lines = emptyList(), imageCount = 2)
        ))

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks
        val images = lastResult!!.images

        assertEquals(2, images.size)
        // Should have a synthetic block carrying image refs
        assertTrue(blocks.isNotEmpty(), "Image-only page should produce a synthetic TextBlock")
        val syntheticBlock = blocks.find { it.imageRefs.isNotEmpty() }
        assertNotNull(syntheticBlock, "Should have a block with image refs")
        assertEquals("", syntheticBlock.content)
        assertEquals(1, syntheticBlock.pageNumber)
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Multi-image page ---

    @Test
    fun `multi-image page - all images referenced`() {
        val file = createPdfWithTextAndImages("multi_image.pdf", listOf(
            PageContent(
                lines = listOf("DOCUMENT TITLE", "Some body text here."),
                imageCount = 3
            )
        ))

        lastResult = parser.parse(file)
        val images = lastResult!!.images

        assertEquals(3, images.size)
        assertEquals("img_p1_001.png", images[0].filename)
        assertEquals("img_p1_002.png", images[1].filename)
        assertEquals("img_p1_003.png", images[2].filename)
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Text-only page ---

    @Test
    fun `text-only page has imageRefs = emptyList`() {
        val file = createPdfWithTextAndImages("text_only.pdf", listOf(
            PageContent(
                lines = listOf("SOME HEADING", "Body text without any images."),
                imageCount = 0
            )
        ))

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        assertTrue(blocks.isNotEmpty())
        assertTrue(lastResult!!.images.isEmpty())
        assertTrue(blocks.all { it.imageRefs.isEmpty() })
    }

    // --- Heading detection ---

    @Test
    fun `ALL CAPS lines detected as headings`() {
        val file = createPdfWithText("headings.pdf", listOf(
            listOf("INTRODUCTION", "This is the introduction paragraph.", "More text here.")
        ))

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        assertTrue(blocks.isNotEmpty())
        val headingBlock = blocks.find { it.heading == "INTRODUCTION" }
        assertNotNull(headingBlock, "Should detect INTRODUCTION as heading")
        assertTrue(headingBlock.content.contains("This is the introduction paragraph."))
    }

    @Test
    fun `section number detection in headings`() {
        val file = createPdfWithText("section_headings.pdf", listOf(
            listOf("3.2.1 Component Setup", "Setup instructions here.")
        ))

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        val block = blocks.find { it.heading?.contains("Component Setup") == true }
        assertNotNull(block)
        assertEquals("3.2.1", block.sectionId)
    }

    // --- Referential integrity ---

    @Test
    fun `referential integrity - every image referenced by exactly one block`() {
        val file = createPdfWithTextAndImages("integrity.pdf", listOf(
            PageContent(lines = listOf("FIRST PAGE", "Body text."), imageCount = 1),
            PageContent(lines = listOf("SECOND PAGE", "More text."), imageCount = 2)
        ))

        lastResult = parser.parse(file)
        assertReferentialIntegrity(lastResult!!)
    }

    @Test
    fun `filename convention matches img_pPAGE_NNN_ext pattern`() {
        val file = createPdfWithTextAndImages("filename_convention.pdf", listOf(
            PageContent(lines = listOf("Page one."), imageCount = 2),
            PageContent(lines = listOf("Page two."), imageCount = 1)
        ))

        lastResult = parser.parse(file)
        val filenamePattern = Regex("""img_p\d+_\d{3}\.\w+""")
        for (img in lastResult!!.images) {
            assertTrue(filenamePattern.matches(img.filename),
                "Filename '${img.filename}' does not match convention img_pPAGE_NNN.ext")
        }
    }

    // --- Corrupted input tests ---

    @Test
    fun `plain text as PDF raises UnreadableDocumentException`() {
        val file = tempDir.resolve("plain.pdf").toFile()
        file.writeText("This is not a PDF file")

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("pdf", ex.fileType)
        assertEquals(UnreadableReason.CORRUPTED, ex.reason)
        lastResult = null
    }

    @Test
    fun `truncated PDF raises UnreadableDocumentException`() {
        val validFile = createPdfWithText("valid.pdf", listOf(listOf("Some content")))
        val bytes = validFile.readBytes()
        val truncated = tempDir.resolve("truncated.pdf").toFile()
        truncated.writeBytes(bytes.copyOfRange(0, bytes.size / 2))

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(truncated)
        }
        assertEquals("pdf", ex.fileType)
        lastResult = null
    }

    @Test
    fun `heavily corrupted PDF raises UnreadableDocumentException`() {
        // Create a file with valid PDF header but thoroughly corrupted body
        val corrupted = "%PDF-1.4\n".toByteArray() + ByteArray(500) { 0xFF.toByte() }
        val file = tempDir.resolve("corrupted_body.pdf").toFile()
        file.writeBytes(corrupted)

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("pdf", ex.fileType)
        lastResult = null
    }

    @Test
    fun `zero-byte file raises UnreadableDocumentException`() {
        val file = tempDir.resolve("empty.pdf").toFile()
        file.writeBytes(ByteArray(0))

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("pdf", ex.fileType)
        lastResult = null
    }

    @Test
    fun `password-protected PDF raises UnreadableDocumentException with ENCRYPTED reason`() {
        val file = createPasswordProtectedPdf("protected.pdf")

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("pdf", ex.fileType)
        assertEquals(UnreadableReason.ENCRYPTED, ex.reason)
        lastResult = null
    }

    @Test
    fun `corrupted input never throws raw PDFBox types`() {
        val badInputs = listOf(
            "random_bytes" to ByteArray(200) { it.toByte() },
            "xml_content" to "<?xml version=\"1.0\"?><root/>".toByteArray(),
            "partial_pdf_header" to "%PDF-1.4\n%%garbage data here".toByteArray()
        )

        for ((name, bytes) in badInputs) {
            val file = tempDir.resolve("$name.pdf").toFile()
            file.writeBytes(bytes)

            val ex = assertFailsWith<UnreadableDocumentException>("$name should throw UnreadableDocumentException") {
                parser.parse(file)
            }
            assertEquals("pdf", ex.fileType, "$name should have fileType=pdf")
        }
        lastResult = null
    }

    // --- Empty document ---

    @Test
    fun `empty PDF with no pages produces empty ParsedContent`() {
        val file = tempDir.resolve("empty_doc.pdf").toFile()
        PDDocument().use { doc ->
            doc.save(file)
        }

        lastResult = parser.parse(file)
        assertEquals(0, lastResult!!.textBlocks.size)
        assertEquals(0, lastResult!!.images.size)
    }

    // --- Helpers ---

    data class PageContent(
        val lines: List<String> = emptyList(),
        val imageCount: Int = 0
    )

    private fun assertReferentialIntegrity(result: ParsedContent) {
        val imageFilenames = result.images.map { it.filename }.toSet()
        val allRefs = result.textBlocks.flatMap { it.imageRefs }
        val refsSet = allRefs.toSet()

        // Every ref points to a real image
        for (ref in allRefs) {
            assertTrue(ref in imageFilenames, "TextBlock ref '$ref' not found in images")
        }

        // Every image is referenced by at least one block
        for (filename in imageFilenames) {
            val refCount = result.textBlocks.count { filename in it.imageRefs }
            assertTrue(refCount >= 1, "Image '$filename' should be referenced by at least one TextBlock")
        }

        // No duplicate refs across blocks
        assertEquals(allRefs.size, refsSet.size, "Image refs should not be duplicated across TextBlocks")
    }

    private fun createPdfWithText(name: String, pages: List<List<String>>): File {
        val file = tempDir.resolve(name).toFile()
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (pageLines in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 12f)
                    cs.newLineAtOffset(50f, 750f)
                    for (line in pageLines) {
                        cs.showText(line)
                        cs.newLineAtOffset(0f, -20f)
                    }
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun createPdfWithTextAndImages(name: String, pages: List<PageContent>): File {
        val file = tempDir.resolve(name).toFile()
        PDDocument().use { doc ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (pageContent in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // Write text
                    if (pageContent.lines.isNotEmpty()) {
                        cs.beginText()
                        cs.setFont(font, 12f)
                        cs.newLineAtOffset(50f, 750f)
                        for (line in pageContent.lines) {
                            cs.showText(line)
                            cs.newLineAtOffset(0f, -20f)
                        }
                        cs.endText()
                    }

                    // Add images
                    for (i in 0 until pageContent.imageCount) {
                        val imgBytes = createPngBytes(20 + i * 5, 20 + i * 5)
                        val pdImage = PDImageXObject.createFromByteArray(doc, imgBytes, "img_$i.png")
                        cs.drawImage(pdImage, 50f + i * 60f, 500f, 50f, 50f)
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun createPasswordProtectedPdf(name: String): File {
        val file = tempDir.resolve(name).toFile()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(font, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("Secret content")
                cs.endText()
            }
            val ap = org.apache.pdfbox.pdmodel.encryption.AccessPermission()
            val spp = org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user", ap)
            spp.encryptionKeyLength = 128
            doc.protect(spp)
            doc.save(file)
        }
        return file
    }

    private fun createPngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.fillRect(0, 0, width, height)
        g.dispose()
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }
}
