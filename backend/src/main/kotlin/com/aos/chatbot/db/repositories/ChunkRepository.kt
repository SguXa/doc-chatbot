package com.aos.chatbot.db.repositories

import com.aos.chatbot.models.Chunk
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection

/**
 * Repository for chunk CRUD operations.
 *
 * Instances are operation-scoped and must not outlive the injected [Connection].
 * The caller owns the connection lifecycle.
 *
 * JSON serialization for `imageRefs` is handled internally:
 * - On insert: `List<String>` is serialized to a JSON array string; empty list maps to SQL `NULL`.
 * - On read: SQL `NULL` maps to `emptyList()`.
 */
class ChunkRepository(private val conn: Connection) {

    fun insertBatch(chunks: List<Chunk>) {
        val sql = """
            INSERT INTO chunks (document_id, content, content_type, page_number, section_id, heading, embedding, image_refs)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            for (chunk in chunks) {
                stmt.setLong(1, chunk.documentId)
                stmt.setString(2, chunk.content)
                stmt.setString(3, chunk.contentType)
                if (chunk.pageNumber != null) stmt.setInt(4, chunk.pageNumber) else stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.setString(5, chunk.sectionId)
                stmt.setString(6, chunk.heading)
                if (chunk.embedding != null) stmt.setBytes(7, chunk.embedding) else stmt.setNull(7, java.sql.Types.BLOB)
                stmt.setString(8, serializeImageRefs(chunk.imageRefs))
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    /**
     * Returns chunks for the given document ordered by `id ASC` (preserves parser traversal order).
     */
    fun findByDocumentId(documentId: Long): List<Chunk> {
        val sql = "SELECT * FROM chunks WHERE document_id = ? ORDER BY id ASC"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, documentId)
            val rs = stmt.executeQuery()
            val results = mutableListOf<Chunk>()
            while (rs.next()) {
                results.add(mapRow(rs))
            }
            return results
        }
    }

    /**
     * Returns all chunks ordered by `id ASC` (preserves parser traversal order).
     */
    fun findAll(): List<Chunk> {
        val sql = "SELECT * FROM chunks ORDER BY id ASC"
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)
            val results = mutableListOf<Chunk>()
            while (rs.next()) {
                results.add(mapRow(rs))
            }
            return results
        }
    }

    fun deleteByDocumentId(documentId: Long): Int {
        val sql = "DELETE FROM chunks WHERE document_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, documentId)
            return stmt.executeUpdate()
        }
    }

    fun count(): Long {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT COUNT(*) FROM chunks")
            rs.next()
            return rs.getLong(1)
        }
    }

    private fun serializeImageRefs(refs: List<String>): String? {
        if (refs.isEmpty()) return null
        return Json.encodeToString(ListSerializer(String.serializer()), refs)
    }

    private fun deserializeImageRefs(json: String?): List<String> {
        if (json == null) return emptyList()
        val array = Json.parseToJsonElement(json).jsonArray
        return array.map { it.jsonPrimitive.content }
    }

    private fun mapRow(rs: java.sql.ResultSet): Chunk {
        return Chunk(
            id = rs.getLong("id"),
            documentId = rs.getLong("document_id"),
            content = rs.getString("content"),
            contentType = rs.getString("content_type"),
            pageNumber = rs.getObject("page_number")?.let { (it as Number).toInt() },
            sectionId = rs.getString("section_id"),
            heading = rs.getString("heading"),
            embedding = rs.getBytes("embedding"),
            imageRefs = deserializeImageRefs(rs.getString("image_refs")),
            createdAt = rs.getString("created_at")
        )
    }
}
