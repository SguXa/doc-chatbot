package com.aos.chatbot.db.repositories

import com.aos.chatbot.models.Document
import java.sql.Connection

/**
 * Repository for document CRUD operations.
 *
 * Instances are operation-scoped and must not outlive the injected [Connection].
 * The caller owns the connection lifecycle.
 */
class DocumentRepository(private val conn: Connection) {

    fun insert(document: Document): Document {
        val sql = """
            INSERT INTO documents (filename, file_type, file_size, file_hash, chunk_count, image_count, indexed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, document.filename)
            stmt.setString(2, document.fileType)
            stmt.setLong(3, document.fileSize)
            stmt.setString(4, document.fileHash)
            stmt.setInt(5, document.chunkCount)
            stmt.setInt(6, document.imageCount)
            stmt.setString(7, document.indexedAt)
            stmt.executeUpdate()
        }
        val id = conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT last_insert_rowid()")
            rs.next()
            rs.getLong(1)
        }
        return findById(id)!!
    }

    fun findById(id: Long): Document? {
        val sql = "SELECT * FROM documents WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, id)
            val rs = stmt.executeQuery()
            return if (rs.next()) mapRow(rs) else null
        }
    }

    /**
     * Returns all documents ordered by `created_at DESC, id DESC` (newest first, deterministic tie-break).
     */
    fun findAll(): List<Document> {
        val sql = "SELECT * FROM documents ORDER BY created_at DESC, id DESC"
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)
            val results = mutableListOf<Document>()
            while (rs.next()) {
                results.add(mapRow(rs))
            }
            return results
        }
    }

    fun findByHash(hash: String): Document? {
        val sql = "SELECT * FROM documents WHERE file_hash = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, hash)
            val rs = stmt.executeQuery()
            return if (rs.next()) mapRow(rs) else null
        }
    }

    fun updateChunkCount(id: Long, chunkCount: Int, imageCount: Int) {
        val sql = "UPDATE documents SET chunk_count = ?, image_count = ? WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, chunkCount)
            stmt.setInt(2, imageCount)
            stmt.setLong(3, id)
            stmt.executeUpdate()
        }
    }

    fun updateIndexedAt(id: Long) {
        val sql = "UPDATE documents SET indexed_at = CURRENT_TIMESTAMP WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, id)
            stmt.executeUpdate()
        }
    }

    fun delete(id: Long): Boolean {
        val sql = "DELETE FROM documents WHERE id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, id)
            return stmt.executeUpdate() > 0
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): Document {
        return Document(
            id = rs.getLong("id"),
            filename = rs.getString("filename"),
            fileType = rs.getString("file_type"),
            fileSize = rs.getLong("file_size"),
            fileHash = rs.getString("file_hash") ?: "",
            chunkCount = rs.getInt("chunk_count"),
            imageCount = rs.getInt("image_count"),
            indexedAt = rs.getString("indexed_at"),
            createdAt = rs.getString("created_at")
        )
    }
}
