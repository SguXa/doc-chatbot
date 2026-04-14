package com.aos.chatbot.db.repositories

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.models.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentRepositoryTest {

    private lateinit var conn: Connection
    private lateinit var repo: DocumentRepository

    @BeforeEach
    fun setUp() {
        conn = Database(":memory:").connect()
        Migrations(conn).apply()
        repo = DocumentRepository(conn)
    }

    @AfterEach
    fun tearDown() {
        conn.close()
    }

    private fun sampleDocument(
        filename: String = "test.docx",
        fileType: String = "docx",
        fileSize: Long = 1024,
        fileHash: String = "abc123"
    ) = Document(
        filename = filename,
        fileType = fileType,
        fileSize = fileSize,
        fileHash = fileHash
    )

    @Test
    fun `insert and findById round-trip`() {
        val inserted = repo.insert(sampleDocument())
        assertTrue(inserted.id > 0)
        assertEquals("test.docx", inserted.filename)
        assertEquals("docx", inserted.fileType)
        assertEquals(1024L, inserted.fileSize)
        assertEquals("abc123", inserted.fileHash)
        assertNotNull(inserted.createdAt)

        val found = repo.findById(inserted.id)
        assertNotNull(found)
        assertEquals(inserted.id, found.id)
        assertEquals(inserted.filename, found.filename)
    }

    @Test
    fun `findById returns null for non-existent id`() {
        assertNull(repo.findById(999))
    }

    @Test
    fun `findByHash returns matching document`() {
        repo.insert(sampleDocument(fileHash = "unique_hash"))
        val found = repo.findByHash("unique_hash")
        assertNotNull(found)
        assertEquals("unique_hash", found.fileHash)
    }

    @Test
    fun `findByHash returns null for non-existent hash`() {
        assertNull(repo.findByHash("no_such_hash"))
    }

    @Test
    fun `findAll returns documents ordered by created_at DESC, id DESC`() {
        // Insert in a specific order; all will have same created_at (CURRENT_TIMESTAMP granularity)
        val doc1 = repo.insert(sampleDocument(filename = "first.docx", fileHash = "h1"))
        val doc2 = repo.insert(sampleDocument(filename = "second.docx", fileHash = "h2"))
        val doc3 = repo.insert(sampleDocument(filename = "third.docx", fileHash = "h3"))

        val all = repo.findAll()
        assertEquals(3, all.size)
        // Same timestamp, so tie-break is id DESC
        assertEquals(doc3.id, all[0].id)
        assertEquals(doc2.id, all[1].id)
        assertEquals(doc1.id, all[2].id)
    }

    @Test
    fun `findAll returns empty list when no documents`() {
        assertEquals(emptyList(), repo.findAll())
    }

    @Test
    fun `updateChunkCount updates counts`() {
        val doc = repo.insert(sampleDocument())
        assertEquals(0, doc.chunkCount)
        assertEquals(0, doc.imageCount)

        repo.updateChunkCount(doc.id, 5, 3)

        val updated = repo.findById(doc.id)!!
        assertEquals(5, updated.chunkCount)
        assertEquals(3, updated.imageCount)
    }

    @Test
    fun `updateIndexedAt sets timestamp`() {
        val doc = repo.insert(sampleDocument())
        assertNull(doc.indexedAt)

        repo.updateIndexedAt(doc.id)

        val updated = repo.findById(doc.id)!!
        assertNotNull(updated.indexedAt)
    }

    @Test
    fun `delete removes document and returns true`() {
        val doc = repo.insert(sampleDocument())
        assertTrue(repo.delete(doc.id))
        assertNull(repo.findById(doc.id))
    }

    @Test
    fun `delete returns false for non-existent id`() {
        assertEquals(false, repo.delete(999))
    }

    @Test
    fun `delete cascades to chunks`() {
        val doc = repo.insert(sampleDocument())
        val chunkRepo = ChunkRepository(conn)
        chunkRepo.insertBatch(
            listOf(
                com.aos.chatbot.models.Chunk(
                    documentId = doc.id,
                    content = "test",
                    contentType = "text"
                )
            )
        )
        assertEquals(1L, chunkRepo.count())

        repo.delete(doc.id)
        assertEquals(0L, chunkRepo.count())
    }

    @Test
    fun `delete cascades to images`() {
        val doc = repo.insert(sampleDocument())
        val imageRepo = ImageRepository(conn)
        imageRepo.insert(
            com.aos.chatbot.models.ExtractedImage(
                documentId = doc.id,
                filename = "img_001.png",
                path = "/images/${doc.id}/img_001.png"
            )
        )

        repo.delete(doc.id)
        assertEquals(emptyList(), imageRepo.findByDocumentId(doc.id))
    }
}
