package com.aos.chatbot.db.repositories

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.models.Document
import com.aos.chatbot.models.ExtractedImage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageRepositoryTest {

    private lateinit var conn: Connection
    private lateinit var repo: ImageRepository
    private var documentId: Long = 0

    @BeforeEach
    fun setUp() {
        conn = Database(":memory:").connect()
        Migrations(conn).apply()

        val docRepo = DocumentRepository(conn)
        documentId = docRepo.insert(
            Document(filename = "test.docx", fileType = "docx", fileSize = 1024, fileHash = "hash1")
        ).id
        repo = ImageRepository(conn)
    }

    @AfterEach
    fun tearDown() {
        conn.close()
    }

    @Test
    fun `insert and findByDocumentId round-trip`() {
        val image = repo.insert(
            ExtractedImage(
                documentId = documentId,
                filename = "img_001.png",
                path = "/images/$documentId/img_001.png",
                pageNumber = 3,
                caption = "A diagram"
            )
        )
        assertTrue(image.id > 0)
        assertEquals("img_001.png", image.filename)
        assertEquals(3, image.pageNumber)
        assertEquals("A diagram", image.caption)
        assertNotNull(image.createdAt)

        val found = repo.findByDocumentId(documentId)
        assertEquals(1, found.size)
        assertEquals(image.id, found[0].id)
    }

    @Test
    fun `findByDocumentId returns results ordered by id ASC`() {
        repo.insert(ExtractedImage(documentId = documentId, filename = "c.png", path = "/c"))
        repo.insert(ExtractedImage(documentId = documentId, filename = "a.png", path = "/a"))
        repo.insert(ExtractedImage(documentId = documentId, filename = "b.png", path = "/b"))

        val found = repo.findByDocumentId(documentId)
        assertEquals(3, found.size)
        assertTrue(found[0].id < found[1].id)
        assertTrue(found[1].id < found[2].id)
        // Order by id, not by filename
        assertEquals("c.png", found[0].filename)
        assertEquals("a.png", found[1].filename)
        assertEquals("b.png", found[2].filename)
    }

    @Test
    fun `findByDocumentId returns empty list for non-existent document`() {
        assertEquals(emptyList(), repo.findByDocumentId(999))
    }

    @Test
    fun `deleteByDocumentId removes images and returns count`() {
        repo.insert(ExtractedImage(documentId = documentId, filename = "a.png", path = "/a"))
        repo.insert(ExtractedImage(documentId = documentId, filename = "b.png", path = "/b"))

        val deleted = repo.deleteByDocumentId(documentId)
        assertEquals(2, deleted)
        assertEquals(emptyList(), repo.findByDocumentId(documentId))
    }

    @Test
    fun `deleteByDocumentId returns 0 for non-existent document`() {
        assertEquals(0, repo.deleteByDocumentId(999))
    }

    @Test
    fun `nullable fields round-trip correctly`() {
        val image = repo.insert(
            ExtractedImage(
                documentId = documentId,
                filename = "img.png",
                path = "/img",
                pageNumber = null,
                caption = null,
                description = null,
                embedding = null
            )
        )
        assertEquals(null, image.pageNumber)
        assertEquals(null, image.caption)
        assertEquals(null, image.description)
        assertEquals(null, image.embedding)
    }

    @Test
    fun `embedding byte array round-trip`() {
        val embeddingData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val image = repo.insert(
            ExtractedImage(
                documentId = documentId,
                filename = "img.png",
                path = "/img",
                embedding = embeddingData
            )
        )
        assertTrue(embeddingData.contentEquals(image.embedding!!))

        val found = repo.findByDocumentId(documentId).first()
        assertTrue(embeddingData.contentEquals(found.embedding!!))
    }

    @Test
    fun `count returns row count`() {
        assertEquals(0L, repo.count())
        repo.insert(ExtractedImage(documentId = documentId, filename = "a.png", path = "/a"))
        repo.insert(ExtractedImage(documentId = documentId, filename = "b.png", path = "/b"))
        assertEquals(2L, repo.count())
        repo.deleteByDocumentId(documentId)
        assertEquals(0L, repo.count())
    }

    @Test
    fun `FK constraint violation for non-existent document_id`() {
        assertFailsWith<SQLException> {
            repo.insert(
                ExtractedImage(documentId = 999, filename = "orphan.png", path = "/orphan")
            )
        }
    }

    @Test
    fun `FK cascade deletes images when parent document deleted`() {
        repo.insert(ExtractedImage(documentId = documentId, filename = "a.png", path = "/a"))
        repo.insert(ExtractedImage(documentId = documentId, filename = "b.png", path = "/b"))
        assertEquals(2, repo.findByDocumentId(documentId).size)

        conn.createStatement().executeUpdate("DELETE FROM documents WHERE id = $documentId")
        assertEquals(emptyList(), repo.findByDocumentId(documentId))
    }
}
