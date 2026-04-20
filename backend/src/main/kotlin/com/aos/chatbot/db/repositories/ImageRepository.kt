package com.aos.chatbot.db.repositories

import com.aos.chatbot.models.ExtractedImage
import java.sql.Connection

/**
 * Repository for extracted image CRUD operations.
 *
 * Instances are operation-scoped and must not outlive the injected [Connection].
 * The caller owns the connection lifecycle.
 */
class ImageRepository(private val conn: Connection) {

    fun insert(image: ExtractedImage): ExtractedImage {
        val sql = """
            INSERT INTO images (document_id, filename, path, page_number, caption, description, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        val id: Long
        conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
            stmt.setLong(1, image.documentId)
            stmt.setString(2, image.filename)
            stmt.setString(3, image.path)
            if (image.pageNumber != null) stmt.setInt(4, image.pageNumber) else stmt.setNull(4, java.sql.Types.INTEGER)
            stmt.setString(5, image.caption)
            stmt.setString(6, image.description)
            if (image.embedding != null) stmt.setBytes(7, image.embedding) else stmt.setNull(7, java.sql.Types.BLOB)
            stmt.executeUpdate()
            val keys = stmt.generatedKeys
            keys.next()
            id = keys.getLong(1)
        }
        return findById(id)!!
    }

    /**
     * Returns images for the given document ordered by `id ASC`.
     */
    fun findByDocumentId(documentId: Long): List<ExtractedImage> {
        val sql = "SELECT * FROM images WHERE document_id = ? ORDER BY id ASC"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, documentId)
            val rs = stmt.executeQuery()
            val results = mutableListOf<ExtractedImage>()
            while (rs.next()) {
                results.add(mapRow(rs))
            }
            return results
        }
    }

    fun deleteByDocumentId(documentId: Long): Int {
        val sql = "DELETE FROM images WHERE document_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, documentId)
            return stmt.executeUpdate()
        }
    }

    private fun findById(id: Long): ExtractedImage? {
        val sql = "SELECT * FROM images WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, id)
            val rs = stmt.executeQuery()
            return if (rs.next()) mapRow(rs) else null
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): ExtractedImage {
        return ExtractedImage(
            id = rs.getLong("id"),
            documentId = rs.getLong("document_id"),
            filename = rs.getString("filename"),
            path = rs.getString("path"),
            pageNumber = rs.getObject("page_number")?.let { (it as Number).toInt() },
            caption = rs.getString("caption"),
            description = rs.getString("description"),
            embedding = rs.getBytes("embedding"),
            createdAt = rs.getString("created_at")
        )
    }
}
