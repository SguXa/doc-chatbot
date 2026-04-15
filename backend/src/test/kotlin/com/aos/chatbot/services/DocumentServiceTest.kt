package com.aos.chatbot.services

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.db.repositories.ImageRepository
import com.aos.chatbot.models.ImageData
import com.aos.chatbot.models.ParsedContent
import com.aos.chatbot.models.TextBlock
import com.aos.chatbot.parsers.ChunkingService
import com.aos.chatbot.parsers.DocumentParser
import com.aos.chatbot.parsers.ParserFactory
import com.aos.chatbot.parsers.UnreadableDocumentException
import com.aos.chatbot.parsers.UnreadableReason
import com.aos.chatbot.parsers.aos.AosParser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentServiceTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: String
    private lateinit var documentsPath: String
    private lateinit var imagesPath: String
    private lateinit var database: Database
    private lateinit var parserFactory: ParserFactory
    private lateinit var aosParser: AosParser
    private lateinit var chunkingService: ChunkingService
    private lateinit var service: DocumentService

    private val sampleContent = ParsedContent(
        textBlocks = listOf(
            TextBlock(content = "Hello world. This is a test document.", type = "text", pageNumber = 1)
        ),
        images = emptyList()
    )

    private val sampleContentWithImages = ParsedContent(
        textBlocks = listOf(
            TextBlock(
                content = "Text with image reference.",
                type = "text",
                pageNumber = 1,
                imageRefs = listOf("img_001.png")
            )
        ),
        images = listOf(
            ImageData(filename = "img_001.png", data = "image-data".toByteArray(), pageNumber = 1)
        )
    )

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("doc-service-test")
        dbPath = tempDir.resolve("test.db").toString()
        documentsPath = tempDir.resolve("documents").toString()
        imagesPath = tempDir.resolve("images").toString()

        database = Database(dbPath)
        // Initialize schema
        database.connect().use { conn ->
            Migrations(conn).apply()
        }

        parserFactory = mockk()
        aosParser = mockk()
        chunkingService = mockk()

        // Default mock behavior: parser returns sampleContent, aosParser passes through, chunking passes through
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } returns sampleContent
        every { parserFactory.getParser(any()) } returns mockParser
        every { aosParser.process(any()) } answers { firstArg() }
        every { chunkingService.chunk(any()) } answers { firstArg() }

        service = DocumentService(database, parserFactory, aosParser, chunkingService, documentsPath, imagesPath)
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    // --- Happy paths ---

    @Test
    fun `docx upload returns Created with correct document fields`() = runBlocking {
        val bytes = "docx-content".toByteArray()
        val result = service.processDocument("test.docx", bytes)

        assertIs<UploadResult.Created>(result)
        val doc = result.document
        assertEquals("test.docx", doc.filename)
        assertEquals("docx", doc.fileType)
        assertEquals(bytes.size.toLong(), doc.fileSize)
        assertNotNull(doc.fileHash)
        assertEquals(1, doc.chunkCount)
        assertEquals(0, doc.imageCount)
        assertNotNull(doc.indexedAt)
        assertNotNull(doc.createdAt)

        // Verify DB state
        database.connect().use { conn ->
            val docs = DocumentRepository(conn).findAll()
            assertEquals(1, docs.size)
            val chunks = ChunkRepository(conn).findByDocumentId(docs[0].id)
            assertEquals(1, chunks.size)
            assertEquals("Hello world. This is a test document.", chunks[0].content)
        }
    }

    @Test
    fun `pdf upload returns Created`() = runBlocking {
        val bytes = "pdf-content".toByteArray()
        val result = service.processDocument("report.pdf", bytes)

        assertIs<UploadResult.Created>(result)
        assertEquals("report.pdf", result.document.filename)
        assertEquals("pdf", result.document.fileType)
    }

    @Test
    fun `upload with images persists chunks and images correctly`() = runBlocking {
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } returns sampleContentWithImages
        every { parserFactory.getParser(any()) } returns mockParser

        val bytes = "content-with-images".toByteArray()
        val result = service.processDocument("test.docx", bytes)

        assertIs<UploadResult.Created>(result)
        assertEquals(1, result.document.chunkCount)
        assertEquals(1, result.document.imageCount)

        // Verify image on disk
        database.connect().use { conn ->
            val images = ImageRepository(conn).findByDocumentId(result.document.id)
            assertEquals(1, images.size)
            assertEquals("img_001.png", images[0].filename)
            assertTrue(Files.exists(Path.of(images[0].path)))
        }
    }

    @Test
    fun `source file written to documentsPath with hash-based name`() = runBlocking {
        val bytes = "test-file-content".toByteArray()
        service.processDocument("test.docx", bytes)

        val docsDir = Path.of(documentsPath)
        val files = Files.list(docsDir).toList()
        assertEquals(1, files.size)
        assertTrue(files[0].fileName.toString().endsWith(".docx"))
        // No temp files
        assertFalse(files[0].toString().contains(".tmp."))
    }

    // --- Dedup ---

    @Test
    fun `pre-check duplicate returns Duplicate on second upload of same bytes`() = runBlocking {
        val bytes = "duplicate-content".toByteArray()
        val first = service.processDocument("first.docx", bytes)
        assertIs<UploadResult.Created>(first)

        val second = service.processDocument("second.docx", bytes)
        assertIs<UploadResult.Duplicate>(second)
        assertEquals(first.document.id, second.document.id)

        // Only one document row
        database.connect().use { conn ->
            assertEquals(1, DocumentRepository(conn).findAll().size)
        }
    }

    @Test
    fun `race duplicate returns Duplicate when concurrent insert causes uniqueness violation`() = runBlocking {
        // Simulate race: after hash check passes (no dup found), another thread inserts the same hash.
        // We do this by using a parserFactory that inserts a row with the same hash during parse.
        val bytes = "race-content".toByteArray()
        val hash = sha256(bytes)

        val raceParser = mockk<DocumentParser>()
        every { raceParser.parse(any()) } answers {
            // Insert a row with the same hash (simulating a concurrent upload)
            database.connect().use { conn ->
                DocumentRepository(conn).insert(
                    com.aos.chatbot.models.Document(
                        filename = "concurrent.docx",
                        fileType = "docx",
                        fileSize = bytes.size.toLong(),
                        fileHash = hash
                    )
                )
            }
            sampleContent
        }
        every { parserFactory.getParser(any()) } returns raceParser

        val result = service.processDocument("test.docx", bytes)
        assertIs<UploadResult.Duplicate>(result)
        assertEquals("concurrent.docx", result.document.filename)
    }

    @Test
    fun `race duplicate with existing target file still returns Duplicate`() = runBlocking {
        // Simulate a race where another thread already moved the hash-named file into
        // place AND inserted its DB row AFTER this thread's hash pre-check passed but
        // BEFORE this thread's own move + insert complete.
        // On Linux/JDK, ATOMIC_MOVE + REPLACE_EXISTING typically succeeds even when the
        // target exists, so this test reliably exercises the DB UNIQUE-constraint handler
        // rather than the move-failure catch. On filesystems where ATOMIC_MOVE rejects an
        // existing target, the move-failure handler is also exercised.
        val bytes = "move-race-content".toByteArray()
        val hash = sha256(bytes)

        // Pre-place the target file on disk (simulating a concurrent thread that already moved it)
        val docsDir = Path.of(documentsPath)
        Files.createDirectories(docsDir)
        val targetFile = docsDir.resolve("$hash.docx")
        Files.write(targetFile, bytes)

        // Insert the DB row during parsing — after the hash pre-check has already passed
        // but before the service's own DB insert, simulating the concurrent thread
        // completing between those two points.
        val raceParser = mockk<DocumentParser>()
        every { raceParser.parse(any()) } answers {
            database.connect().use { conn ->
                DocumentRepository(conn).insert(
                    com.aos.chatbot.models.Document(
                        filename = "winner.docx",
                        fileType = "docx",
                        fileSize = bytes.size.toLong(),
                        fileHash = hash
                    )
                )
            }
            sampleContent
        }
        every { parserFactory.getParser(any()) } returns raceParser

        val result = service.processDocument("latecomer.docx", bytes)
        assertIs<UploadResult.Duplicate>(result)
        assertEquals("winner.docx", result.document.filename)
    }

    // --- Validation errors ---

    @Test
    fun `unsupported extension throws InvalidUploadException`() = runBlocking {
        val ex = assertFailsWith<InvalidUploadException> {
            service.processDocument("test.txt", "content".toByteArray())
        }
        assertEquals("unsupported_extension", ex.reason)
    }

    @Test
    fun `empty file throws InvalidUploadException`() = runBlocking {
        val ex = assertFailsWith<InvalidUploadException> {
            service.processDocument("test.docx", ByteArray(0))
        }
        assertEquals("empty_file", ex.reason)
    }

    @Test
    fun `missing filename throws InvalidUploadException`() = runBlocking {
        val ex = assertFailsWith<InvalidUploadException> {
            service.processDocument("", "content".toByteArray())
        }
        assertEquals("missing_filename", ex.reason)
    }

    @Test
    fun `blank filename throws InvalidUploadException`() = runBlocking {
        val ex = assertFailsWith<InvalidUploadException> {
            service.processDocument("   ", "content".toByteArray())
        }
        assertEquals("missing_filename", ex.reason)
    }

    // --- UnreadableDocumentException propagation ---

    @Test
    fun `corrupted document propagates UnreadableDocumentException with no row inserted`() = runBlocking {
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } throws UnreadableDocumentException(
            UnreadableReason.CORRUPTED, "docx", RuntimeException("bad file")
        )
        every { parserFactory.getParser(any()) } returns mockParser

        assertFailsWith<UnreadableDocumentException> {
            service.processDocument("broken.docx", "bad-content".toByteArray())
        }

        // No document row
        database.connect().use { conn ->
            assertEquals(0, DocumentRepository(conn).findAll().size)
        }
        // No source file
        if (Files.exists(Path.of(documentsPath))) {
            assertEquals(0, Files.list(Path.of(documentsPath)).count())
        }
    }

    // --- EmptyDocumentException ---

    @Test
    fun `empty document raises EmptyDocumentException with no row inserted`() = runBlocking {
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } returns ParsedContent(
            textBlocks = emptyList(), images = emptyList()
        )
        every { parserFactory.getParser(any()) } returns mockParser

        assertFailsWith<EmptyDocumentException> {
            service.processDocument("empty.docx", "some-content".toByteArray())
        }

        database.connect().use { conn ->
            assertEquals(0, DocumentRepository(conn).findAll().size)
        }
    }

    // --- Rollback on chunk insert failure ---

    @Test
    fun `failure during persist rolls back document row, source file, and image directory`() = runBlocking {
        // Use content with images so ImageExtractor is invoked during persist.
        // Make the images path point to a regular file so directory creation fails inside persist.
        val mockParser2 = mockk<DocumentParser>()
        every { mockParser2.parse(any()) } returns sampleContentWithImages
        every { parserFactory.getParser(any()) } returns mockParser2

        val bytes = "rollback-test-content".toByteArray()

        val unwritableImagesPath = tempDir.resolve("unwritable_images").toString()
        Files.createDirectories(Path.of(unwritableImagesPath))
        Files.delete(Path.of(unwritableImagesPath))
        Files.writeString(Path.of(unwritableImagesPath), "not a directory")

        val failService = DocumentService(
            database, parserFactory, aosParser, chunkingService,
            documentsPath, unwritableImagesPath
        )

        assertFailsWith<Exception> {
            failService.processDocument("fail.docx", bytes)
        }

        // No document row
        database.connect().use { conn ->
            assertEquals(0, DocumentRepository(conn).findAll().size)
        }
        // No source file
        val docsDir = Path.of(documentsPath)
        if (Files.exists(docsDir)) {
            val sourceFiles = Files.list(docsDir).filter { !it.toString().contains(".tmp.") }.count()
            assertEquals(0, sourceFiles)
        }
    }

    // --- Image linkage validation failure ---

    @Test
    fun `broken image linkage throws and no row inserted`() = runBlocking {
        // Create content where chunk refs point to nonexistent image
        val brokenContent = ParsedContent(
            textBlocks = listOf(
                TextBlock(
                    content = "Text referencing missing image.",
                    type = "text",
                    imageRefs = listOf("nonexistent.png")
                )
            ),
            images = emptyList()
        )
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } returns brokenContent
        every { parserFactory.getParser(any()) } returns mockParser

        assertFailsWith<IllegalStateException> {
            service.processDocument("broken-refs.docx", "content".toByteArray())
        }

        database.connect().use { conn ->
            assertEquals(0, DocumentRepository(conn).findAll().size)
        }
    }

    @Test
    fun `unreferenced images fail linkage validation`() = runBlocking {
        val brokenContent = ParsedContent(
            textBlocks = listOf(
                TextBlock(content = "Text without refs.", type = "text")
            ),
            images = listOf(
                ImageData(filename = "orphan.png", data = "data".toByteArray())
            )
        )
        val mockParser = mockk<DocumentParser>()
        every { mockParser.parse(any()) } returns brokenContent
        every { parserFactory.getParser(any()) } returns mockParser

        assertFailsWith<IllegalStateException> {
            service.processDocument("orphan-img.docx", "content".toByteArray())
        }

        database.connect().use { conn ->
            assertEquals(0, DocumentRepository(conn).findAll().size)
        }
    }

    // --- Helper ---

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
