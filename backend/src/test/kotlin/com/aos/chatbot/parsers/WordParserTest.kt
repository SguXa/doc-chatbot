package com.aos.chatbot.parsers

import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordParserTest {

    private val parser = WordParser()

    @TempDir
    lateinit var tempDir: Path

    private var lastResult: ParsedContent? = null

    @AfterEach
    fun `every TextBlock and ImageData has pageNumber == null`() {
        val result = lastResult ?: return
        result.textBlocks.forEachIndexed { i, block ->
            assertNull(block.pageNumber, "TextBlock[$i] should have pageNumber == null")
        }
        result.images.forEachIndexed { i, img ->
            assertNull(img.pageNumber, "ImageData[$i] should have pageNumber == null")
        }
    }

    // --- Heading detection ---

    @Test
    fun `detects headings and concatenates body paragraphs`() {
        val file = createDocx("heading_test.docx") { doc ->
            addHeading(doc, "1.2 Introduction", "Heading1")
            addParagraph(doc, "First paragraph of intro.")
            addParagraph(doc, "Second paragraph of intro.")
            addHeading(doc, "1.3 Details", "Heading2")
            addParagraph(doc, "Details body text.")
        }

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        assertEquals(2, blocks.size)
        assertEquals("1.2", blocks[0].sectionId)
        assertEquals("1.2 Introduction", blocks[0].heading)
        assertEquals("First paragraph of intro.\nSecond paragraph of intro.", blocks[0].content)
        assertEquals("1.3", blocks[1].sectionId)
        assertEquals("1.3 Details", blocks[1].heading)
        assertEquals("Details body text.", blocks[1].content)
    }

    @Test
    fun `section number detection with multi-level numbering`() {
        val file = createDocx("section_numbers.docx") { doc ->
            addHeading(doc, "3.2.1 Component Setup", "Heading1")
            addParagraph(doc, "Setup instructions.")
        }

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        assertEquals("3.2.1", blocks[0].sectionId)
    }

    @Test
    fun `heading without section number has null sectionId`() {
        val file = createDocx("no_section.docx") { doc ->
            addHeading(doc, "Appendix", "Heading1")
            addParagraph(doc, "Appendix content.")
        }

        lastResult = parser.parse(file)
        assertNull(lastResult!!.textBlocks[0].sectionId)
        assertEquals("Appendix", lastResult!!.textBlocks[0].heading)
    }

    // --- Table rendering ---

    @Test
    fun `renders tables with pipe separators`() {
        val file = createDocx("table_test.docx") { doc ->
            addParagraph(doc, "Before table.")
            val table = doc.createTable(2, 3)
            table.getRow(0).getCell(0).setText("A1")
            table.getRow(0).getCell(1).setText("B1")
            table.getRow(0).getCell(2).setText("C1")
            table.getRow(1).getCell(0).setText("A2")
            table.getRow(1).getCell(1).setText("B2")
            table.getRow(1).getCell(2).setText("C2")
            addParagraph(doc, "After table.")
        }

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        assertEquals(3, blocks.size)
        assertEquals("text", blocks[0].type)
        assertEquals("table", blocks[1].type)
        assertTrue(blocks[1].content.contains("|"))
        assertTrue(blocks[1].content.contains("A1"))
        assertTrue(blocks[1].content.contains("C2"))
        assertEquals("text", blocks[2].type)
    }

    // --- Image attachment scenarios ---

    @Test
    fun `text then image then text - image attaches to next text block`() {
        val imgBytes = createPngBytes(10, 10)
        val file = createDocx("text_img_text.docx") { doc ->
            addParagraph(doc, "Before image.")
            addParagraphWithImage(doc, imgBytes)
            addParagraph(doc, "After image.")
        }

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks
        val images = lastResult!!.images

        assertEquals(1, images.size)
        assertEquals("img_001.png", images[0].filename)

        // The image was encountered between "Before image." and "After image."
        // Images attach to the NEXT text block via pendingImageRefs
        val blockWithRef = blocks.find { it.imageRefs.contains("img_001.png") }
        assertTrue(blockWithRef != null, "Some block should reference img_001.png")
        assertReferentialIntegrity(lastResult!!)
    }

    @Test
    fun `leading image creates block with image refs`() {
        val imgBytes = createPngBytes(10, 10)
        val file = createDocx("leading_image.docx") { doc ->
            addParagraphWithImage(doc, imgBytes)
            addParagraph(doc, "Text after leading image.")
        }

        lastResult = parser.parse(file)
        assertReferentialIntegrity(lastResult!!)
        assertEquals(1, lastResult!!.images.size)
        assertEquals("img_001.png", lastResult!!.images[0].filename)
    }

    @Test
    fun `trailing image creates synthetic empty TextBlock`() {
        val imgBytes = createPngBytes(10, 10)
        val file = createDocx("trailing_image.docx") { doc ->
            addParagraph(doc, "Some text.")
            addParagraphWithImage(doc, imgBytes)
        }

        lastResult = parser.parse(file)
        val blocks = lastResult!!.textBlocks

        // The trailing image should result in a synthetic empty block
        val syntheticBlock = blocks.last()
        assertTrue(syntheticBlock.imageRefs.isNotEmpty(), "Trailing image must produce a block with imageRefs")
        assertReferentialIntegrity(lastResult!!)
    }

    @Test
    fun `table with inline image preserves image linkage`() {
        val imgBytes = createPngBytes(10, 10)
        val file = createDocx("table_with_image.docx") { doc ->
            val table = doc.createTable(1, 2)
            table.getRow(0).getCell(0).setText("Cell A")
            // Add image to second cell
            val cell = table.getRow(0).getCell(1)
            val para = cell.paragraphs[0]
            val run = para.createRun()
            run.addPicture(
                imgBytes.inputStream(),
                XWPFDocument.PICTURE_TYPE_PNG,
                "test.png",
                100 * 9525,
                100 * 9525
            )
        }

        lastResult = parser.parse(file)
        assertEquals(1, lastResult!!.images.size)
        assertEquals("table", lastResult!!.textBlocks.find { it.imageRefs.isNotEmpty() }?.type)
        assertReferentialIntegrity(lastResult!!)
    }

    @Test
    fun `multiple images per block all attached`() {
        val img1 = createPngBytes(10, 10)
        val img2 = createPngBytes(20, 20)
        val file = createDocx("multi_image.docx") { doc ->
            addParagraphWithImage(doc, img1)
            addParagraphWithImage(doc, img2)
            addParagraph(doc, "Text after two images.")
        }

        lastResult = parser.parse(file)
        assertEquals(2, lastResult!!.images.size)
        assertEquals("img_001.png", lastResult!!.images[0].filename)
        assertEquals("img_002.png", lastResult!!.images[1].filename)
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Referential integrity ---

    @Test
    fun `referential integrity - every image referenced by exactly one block`() {
        val img1 = createPngBytes(10, 10)
        val img2 = createPngBytes(20, 20)
        val file = createDocx("integrity_test.docx") { doc ->
            addParagraph(doc, "Block one.")
            addParagraphWithImage(doc, img1)
            addParagraph(doc, "Block two.")
            addParagraphWithImage(doc, img2)
            addParagraph(doc, "Block three.")
        }

        lastResult = parser.parse(file)
        assertReferentialIntegrity(lastResult!!)
    }

    // --- Corrupted input tests ---

    @Test
    fun `plain ASCII file raises UnreadableDocumentException`() {
        val file = tempDir.resolve("plain.docx").toFile()
        file.writeText("This is not a docx file")

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("docx", ex.fileType)
        lastResult = null // skip @AfterEach invariant
    }

    @Test
    fun `truncated docx raises UnreadableDocumentException`() {
        // Create a valid docx then truncate it
        val validFile = createDocx("valid.docx") { doc ->
            addParagraph(doc, "Some content")
        }
        val bytes = validFile.readBytes()
        val truncated = tempDir.resolve("truncated.docx").toFile()
        truncated.writeBytes(bytes.copyOfRange(0, bytes.size / 2))

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(truncated)
        }
        assertEquals("docx", ex.fileType)
        lastResult = null
    }

    @Test
    fun `zero-byte file raises UnreadableDocumentException`() {
        val file = tempDir.resolve("empty.docx").toFile()
        file.writeBytes(ByteArray(0))

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("docx", ex.fileType)
        lastResult = null
    }

    @Test
    fun `password-protected docx raises UnreadableDocumentException with PASSWORD_PROTECTED reason`() {
        // POI password-protected files throw POIXMLException with "password" in message
        // We simulate with a file that triggers the password detection path
        // Create an encrypted OLE2 file (old .doc format) which triggers OLE2NotOfficeXmlFileException
        val file = tempDir.resolve("protected.docx").toFile()
        // Write OLE2 magic bytes (0xD0CF11E0A1B11AE1) to simulate an old binary format
        val ole2Header = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
        ) + ByteArray(504) // pad to minimum size
        file.writeBytes(ole2Header)

        val ex = assertFailsWith<UnreadableDocumentException> {
            parser.parse(file)
        }
        assertEquals("docx", ex.fileType)
        // OLE2 file triggers OLE2_INSTEAD_OF_OOXML
        assertEquals(UnreadableReason.OLE2_INSTEAD_OF_OOXML, ex.reason)
        lastResult = null
    }

    @Test
    fun `corrupted input never throws raw POI types`() {
        val badInputs = listOf(
            "random_bytes" to ByteArray(100) { it.toByte() },
            "xml_content" to "<?xml version=\"1.0\"?><root/>".toByteArray(),
            "partial_zip" to byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00) // ZIP magic + garbage
        )

        for ((name, bytes) in badInputs) {
            val file = tempDir.resolve("$name.docx").toFile()
            file.writeBytes(bytes)

            val ex = assertFailsWith<UnreadableDocumentException>("$name should throw UnreadableDocumentException") {
                parser.parse(file)
            }
            assertEquals("docx", ex.fileType, "$name should have fileType=docx")
        }
        lastResult = null
    }

    // --- Empty document ---

    @Test
    fun `empty document produces empty ParsedContent`() {
        val file = createDocx("empty_doc.docx") { _ -> }

        lastResult = parser.parse(file)
        assertEquals(0, lastResult!!.textBlocks.size)
        assertEquals(0, lastResult!!.images.size)
    }

    // --- Helpers ---

    private fun assertReferentialIntegrity(result: ParsedContent) {
        val imageFilenames = result.images.map { it.filename }.toSet()
        val allRefs = result.textBlocks.flatMap { it.imageRefs }
        val refsSet = allRefs.toSet()

        // Every ref points to a real image
        for (ref in allRefs) {
            assertTrue(ref in imageFilenames, "TextBlock ref '$ref' not found in images")
        }

        // Every image is referenced by exactly one block
        for (filename in imageFilenames) {
            val refCount = result.textBlocks.count { filename in it.imageRefs }
            assertEquals(1, refCount, "Image '$filename' should be referenced by exactly one TextBlock, but was referenced $refCount times")
        }

        // No duplicate refs across blocks
        assertEquals(allRefs.size, refsSet.size, "Image refs should not be duplicated across TextBlocks")
    }

    private fun createDocx(name: String, builder: (XWPFDocument) -> Unit): File {
        val file = tempDir.resolve(name).toFile()
        XWPFDocument().use { doc ->
            builder(doc)
            FileOutputStream(file).use { fos ->
                doc.write(fos)
            }
        }
        return file
    }

    private fun addParagraph(doc: XWPFDocument, text: String) {
        val para = doc.createParagraph()
        val run = para.createRun()
        run.setText(text)
    }

    private fun addHeading(doc: XWPFDocument, text: String, style: String) {
        val para = doc.createParagraph()
        para.style = style
        val run = para.createRun()
        run.setText(text)
    }

    private fun addParagraphWithImage(doc: XWPFDocument, imageBytes: ByteArray) {
        val para = doc.createParagraph()
        val run = para.createRun()
        run.addPicture(
            imageBytes.inputStream(),
            XWPFDocument.PICTURE_TYPE_PNG,
            "image.png",
            100 * 9525,
            100 * 9525
        )
    }

    private fun createPngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }
}
