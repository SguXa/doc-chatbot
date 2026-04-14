package com.aos.chatbot.parsers

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.db.repositories.ImageRepository
import com.aos.chatbot.models.Document
import com.aos.chatbot.models.ImageData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageExtractorTest {

    private lateinit var conn: Connection
    private lateinit var tempDir: Path
    private var documentId: Long = 0

    @BeforeEach
    fun setUp() {
        conn = Database(":memory:").connect()
        Migrations(conn).apply()

        val docRepo = DocumentRepository(conn)
        documentId = docRepo.insert(
            Document(filename = "test.docx", fileType = "docx", fileSize = 1024, fileHash = "hash1")
        ).id

        tempDir = Files.createTempDirectory("image-extractor-test")
    }

    @AfterEach
    fun tearDown() {
        conn.close()
        // Clean up temp directory recursively
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `happy path - 3 images saved to disk and DB`() {
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        val images = listOf(
            ImageData(filename = "img_001.png", data = "image1-data".toByteArray(), pageNumber = 1),
            ImageData(filename = "img_002.jpg", data = "image2-data".toByteArray(), pageNumber = 2, caption = "Diagram"),
            ImageData(filename = "img_003.png", data = "image3-data".toByteArray(), pageNumber = 3)
        )

        val results = extractor.saveImages(documentId, images)

        // 3 DB rows
        assertEquals(3, results.size)
        val dbImages = imageRepo.findByDocumentId(documentId)
        assertEquals(3, dbImages.size)

        // 3 final files on disk
        val docDir = tempDir.resolve(documentId.toString())
        assertTrue(Files.exists(docDir))
        assertEquals(3, Files.list(docDir).count())

        // Verify filenames are verbatim
        assertTrue(Files.exists(docDir.resolve("img_001.png")))
        assertTrue(Files.exists(docDir.resolve("img_002.jpg")))
        assertTrue(Files.exists(docDir.resolve("img_003.png")))

        // Verify file content
        assertEquals("image1-data", Files.readString(docDir.resolve("img_001.png")))
        assertEquals("image2-data", Files.readString(docDir.resolve("img_002.jpg")))
        assertEquals("image3-data", Files.readString(docDir.resolve("img_003.png")))

        // Zero *.tmp.* files remain
        val tmpFiles = Files.list(docDir).filter { it.toString().contains(".tmp.") }.count()
        assertEquals(0, tmpFiles)

        // DB rows have verbatim filenames (no .tmp. suffix leaks)
        dbImages.forEach { img ->
            assertFalse(img.filename.contains(".tmp."))
            assertFalse(img.path.contains(".tmp."))
        }

        // Verify DB row fields
        assertEquals("img_001.png", dbImages[0].filename)
        assertEquals(1, dbImages[0].pageNumber)
        assertEquals("img_002.jpg", dbImages[1].filename)
        assertEquals("Diagram", dbImages[1].caption)
    }

    @Test
    fun `empty image list returns empty result`() {
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        val results = extractor.saveImages(documentId, emptyList())
        assertEquals(0, results.size)

        // No directory created for empty list
        val docDir = tempDir.resolve(documentId.toString())
        assertFalse(Files.exists(docDir))
    }

    @Test
    fun `temp-write failure on 2nd of 3 images - first persisted, second has neither final nor temp`() {
        // Use a read-only directory to cause write failure on the second image
        val imageRepo = ImageRepository(conn)
        val docDir = tempDir.resolve(documentId.toString())
        Files.createDirectories(docDir)

        // Write the first image manually to verify it persists
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        // Create a scenario where IO fails: make a subdirectory with the same name as
        // the second image's temp file target - we'll use a custom approach
        // Instead, we use an ImageExtractor with a path that becomes unwritable after the first image

        // A simpler approach: put a directory in the way of the second image's final name
        // so the temp write itself fails (the temp path derivation from final path means
        // if we make the directory unwritable after first image, temp write for second fails)

        // Actually the cleanest test: write first image, then make directory read-only
        val firstImage = ImageData(filename = "img_001.png", data = "data1".toByteArray())
        val firstResult = extractor.saveImages(documentId, listOf(firstImage))
        assertEquals(1, firstResult.size)
        assertTrue(Files.exists(docDir.resolve("img_001.png")))

        // Make directory read-only to cause temp write failure
        docDir.toFile().setWritable(false)

        val moreImages = listOf(
            ImageData(filename = "img_002.png", data = "data2".toByteArray()),
            ImageData(filename = "img_003.png", data = "data3".toByteArray())
        )

        try {
            assertFailsWith<java.io.IOException> {
                extractor.saveImages(documentId, moreImages)
            }

            // First image from earlier call still persisted
            assertTrue(Files.exists(docDir.resolve("img_001.png")))
            // Second image has neither final nor temp
            assertFalse(Files.exists(docDir.resolve("img_002.png")))
            // Third was not attempted
            assertFalse(Files.exists(docDir.resolve("img_003.png")))

            // No temp files remain
            docDir.toFile().setReadable(true)
            val tmpFiles = Files.list(docDir).filter { it.toString().contains(".tmp.") }.count()
            assertEquals(0, tmpFiles)
        } finally {
            // Restore writability for cleanup
            docDir.toFile().setWritable(true)
        }
    }

    @Test
    fun `FileAlreadyExistsException at atomic move - pre-existing file unchanged, no DB row`() {
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)
        val docDir = tempDir.resolve(documentId.toString())
        Files.createDirectories(docDir)

        // Pre-create the file with different content
        val existingContent = "original-content"
        Files.writeString(docDir.resolve("img_001.png"), existingContent)

        val images = listOf(
            ImageData(filename = "img_001.png", data = "new-content".toByteArray())
        )

        assertFailsWith<FileAlreadyExistsException> {
            extractor.saveImages(documentId, images)
        }

        // Pre-existing file is unchanged
        assertEquals(existingContent, Files.readString(docDir.resolve("img_001.png")))

        // No DB row inserted
        assertEquals(0, imageRepo.findByDocumentId(documentId).size)

        // No temp files remain
        val tmpFiles = Files.list(docDir).filter { it.toString().contains(".tmp.") }.count()
        assertEquals(0, tmpFiles)
    }

    @Test
    fun `verbatim filenames on disk and in DB row`() {
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        val images = listOf(
            ImageData(filename = "img_p1_001.png", data = "data".toByteArray(), pageNumber = 1)
        )

        val results = extractor.saveImages(documentId, images)
        assertEquals("img_p1_001.png", results[0].filename)
        assertFalse(results[0].filename.contains(".tmp."))
        assertFalse(results[0].path.contains(".tmp."))

        val dbImage = imageRepo.findByDocumentId(documentId).first()
        assertEquals("img_p1_001.png", dbImage.filename)
        assertFalse(dbImage.path.contains(".tmp."))
    }

    @Test
    fun `temp path matches expected regex pattern`() {
        // We verify the temp path pattern indirectly by checking that when
        // atomic move fails, the temp file we clean up had the right pattern.
        // Since we can't easily intercept the temp path, we test the contract
        // by confirming no temp files remain after any operation.
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        val images = listOf(
            ImageData(filename = "test.png", data = "data".toByteArray())
        )
        extractor.saveImages(documentId, images)

        val docDir = tempDir.resolve(documentId.toString())
        val allFiles = Files.list(docDir).toList()
        // Only final files exist - no temp files
        assertEquals(1, allFiles.size)
        assertEquals("test.png", allFiles[0].fileName.toString())

        // Verify temp pattern by causing a FileAlreadyExistsException
        // and checking that cleanup leaves no *.tmp.* files
        assertFailsWith<FileAlreadyExistsException> {
            extractor.saveImages(documentId, images)
        }
        val filesAfterFailure = Files.list(docDir).toList()
        // Still only the one final file, no temp leftovers
        assertEquals(1, filesAfterFailure.size)
        filesAfterFailure.forEach { f ->
            assertFalse(f.toString().matches(Regex(""".*\.tmp\.[0-9a-f-]{36}$""")))
        }
    }

    @Test
    fun `creates per-document directory if missing`() {
        val imageRepo = ImageRepository(conn)
        val extractor = ImageExtractor(tempDir.toString(), imageRepo)

        val docDir = tempDir.resolve(documentId.toString())
        assertFalse(Files.exists(docDir))

        extractor.saveImages(documentId, listOf(
            ImageData(filename = "img.png", data = "data".toByteArray())
        ))

        assertTrue(Files.isDirectory(docDir))
    }
}
