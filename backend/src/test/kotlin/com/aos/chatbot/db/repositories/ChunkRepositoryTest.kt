package com.aos.chatbot.db.repositories

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkRepositoryTest {

    private lateinit var conn: Connection
    private lateinit var repo: ChunkRepository
    private var documentId: Long = 0

    @BeforeEach
    fun setUp() {
        conn = Database(":memory:").connect()
        Migrations(conn).apply()

        // Insert a parent document for FK satisfaction
        val docRepo = DocumentRepository(conn)
        documentId = docRepo.insert(
            Document(filename = "test.docx", fileType = "docx", fileSize = 1024, fileHash = "hash1")
        ).id
        repo = ChunkRepository(conn)
    }

    @AfterEach
    fun tearDown() {
        conn.close()
    }

    @Test
    fun `insertBatch and findByDocumentId round-trip`() {
        val chunks = listOf(
            Chunk(documentId = documentId, content = "first chunk", contentType = "text"),
            Chunk(documentId = documentId, content = "second chunk", contentType = "heading")
        )
        repo.insertBatch(chunks)

        val found = repo.findByDocumentId(documentId)
        assertEquals(2, found.size)
        assertEquals("first chunk", found[0].content)
        assertEquals("second chunk", found[1].content)
    }

    @Test
    fun `findByDocumentId returns results ordered by id ASC`() {
        // Insert in reverse conceptual order; ids are auto-incremented
        repo.insertBatch(
            listOf(
                Chunk(documentId = documentId, content = "A", contentType = "text"),
                Chunk(documentId = documentId, content = "B", contentType = "text"),
                Chunk(documentId = documentId, content = "C", contentType = "text")
            )
        )

        val found = repo.findByDocumentId(documentId)
        assertEquals(3, found.size)
        assertTrue(found[0].id < found[1].id)
        assertTrue(found[1].id < found[2].id)
        assertEquals("A", found[0].content)
        assertEquals("B", found[1].content)
        assertEquals("C", found[2].content)
    }

    @Test
    fun `findAll returns results ordered by id ASC`() {
        repo.insertBatch(
            listOf(
                Chunk(documentId = documentId, content = "X", contentType = "text"),
                Chunk(documentId = documentId, content = "Y", contentType = "text")
            )
        )

        val all = repo.findAll()
        assertEquals(2, all.size)
        assertTrue(all[0].id < all[1].id)
    }

    @Test
    fun `findAll returns empty list when no chunks`() {
        assertEquals(emptyList(), repo.findAll())
    }

    @Test
    fun `count returns correct number`() {
        assertEquals(0L, repo.count())
        repo.insertBatch(
            listOf(
                Chunk(documentId = documentId, content = "a", contentType = "text"),
                Chunk(documentId = documentId, content = "b", contentType = "text")
            )
        )
        assertEquals(2L, repo.count())
    }

    @Test
    fun `deleteByDocumentId removes chunks and returns count`() {
        repo.insertBatch(
            listOf(
                Chunk(documentId = documentId, content = "a", contentType = "text"),
                Chunk(documentId = documentId, content = "b", contentType = "text")
            )
        )
        val deleted = repo.deleteByDocumentId(documentId)
        assertEquals(2, deleted)
        assertEquals(0L, repo.count())
    }

    @Test
    fun `deleteByDocumentId returns 0 for non-existent document`() {
        assertEquals(0, repo.deleteByDocumentId(999))
    }

    @Test
    fun `imageRefs JSON round-trip with non-empty list`() {
        repo.insertBatch(
            listOf(
                Chunk(
                    documentId = documentId,
                    content = "text with images",
                    contentType = "text",
                    imageRefs = listOf("img_001.png", "img_002.png")
                )
            )
        )

        val found = repo.findByDocumentId(documentId)
        assertEquals(1, found.size)
        assertEquals(listOf("img_001.png", "img_002.png"), found[0].imageRefs)
    }

    @Test
    fun `imageRefs empty list stored as NULL and read back as emptyList`() {
        repo.insertBatch(
            listOf(
                Chunk(
                    documentId = documentId,
                    content = "no images",
                    contentType = "text",
                    imageRefs = emptyList()
                )
            )
        )

        val found = repo.findByDocumentId(documentId)
        assertEquals(1, found.size)
        assertEquals(emptyList(), found[0].imageRefs)

        // Verify it's actually NULL in the database
        val rs = conn.createStatement().executeQuery("SELECT image_refs FROM chunks WHERE document_id = $documentId")
        rs.next()
        rs.getString("image_refs")
        assertTrue(rs.wasNull(), "Empty imageRefs should be stored as SQL NULL")
    }

    @Test
    fun `nullable fields round-trip correctly`() {
        repo.insertBatch(
            listOf(
                Chunk(
                    documentId = documentId,
                    content = "full chunk",
                    contentType = "heading",
                    pageNumber = 5,
                    sectionId = "3.2.1",
                    heading = "Component Setup",
                    embedding = byteArrayOf(0x0A, 0x0B, 0x0C)
                )
            )
        )

        val found = repo.findByDocumentId(documentId).first()
        assertEquals(5, found.pageNumber)
        assertEquals("3.2.1", found.sectionId)
        assertEquals("Component Setup", found.heading)
        assertTrue(byteArrayOf(0x0A, 0x0B, 0x0C).contentEquals(found.embedding!!))
    }

    @Test
    fun `null pageNumber and sectionId round-trip`() {
        repo.insertBatch(
            listOf(
                Chunk(
                    documentId = documentId,
                    content = "minimal",
                    contentType = "text",
                    pageNumber = null,
                    sectionId = null,
                    heading = null,
                    embedding = null
                )
            )
        )

        val found = repo.findByDocumentId(documentId).first()
        assertEquals(null, found.pageNumber)
        assertEquals(null, found.sectionId)
        assertEquals(null, found.heading)
        assertEquals(null, found.embedding)
    }

    @Test
    fun `FK constraint violation for non-existent document_id`() {
        assertFailsWith<SQLException> {
            repo.insertBatch(
                listOf(
                    Chunk(documentId = 999, content = "orphan", contentType = "text")
                )
            )
        }
    }

    @Test
    fun `FK cascade deletes chunks when parent document deleted`() {
        repo.insertBatch(
            listOf(
                Chunk(documentId = documentId, content = "will be cascaded", contentType = "text")
            )
        )
        assertEquals(1L, repo.count())

        conn.createStatement().executeUpdate("DELETE FROM documents WHERE id = $documentId")
        assertEquals(0L, repo.count())
    }
}
