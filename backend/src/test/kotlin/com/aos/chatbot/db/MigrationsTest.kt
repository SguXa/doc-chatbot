package com.aos.chatbot.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigrationsTest {

    private fun inMemoryConnection(): Connection {
        val db = Database(":memory:")
        return db.connect()
    }

    @Test
    fun `migration applies cleanly on fresh database`() {
        val conn = inMemoryConnection()
        conn.use {
            assertDoesNotThrow {
                Migrations(it).apply()
            }
        }
    }

    @Test
    fun `migration is idempotent`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()
            assertDoesNotThrow {
                Migrations(it).apply()
            }
        }
    }

    @Test
    fun `all expected tables exist after migration`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val expectedTables = setOf("users", "documents", "chunks", "images", "config", "schema_version")
            val actualTables = mutableSetOf<String>()

            val rs = it.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
            while (rs.next()) {
                actualTables.add(rs.getString("name"))
            }

            for (table in expectedTables) {
                assertTrue(table in actualTables, "Expected table '$table' to exist, found: $actualTables")
            }
        }
    }

    @Test
    fun `all expected indexes exist after migration`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val expectedIndexes = setOf(
                "idx_chunks_document",
                "idx_chunks_content_type",
                "idx_chunks_section",
                "idx_images_document",
                "idx_documents_file_hash_unique"
            )
            val actualIndexes = mutableSetOf<String>()

            val rs = it.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index'"
            )
            while (rs.next()) {
                actualIndexes.add(rs.getString("name"))
            }

            for (index in expectedIndexes) {
                assertTrue(index in actualIndexes, "Expected index '$index' to exist, found: $actualIndexes")
            }
        }
    }

    @Test
    fun `foreign keys are enforced - chunks reference documents`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            // Inserting a chunk with a non-existent document_id should fail
            val exception = assertFailsWith<SQLException> {
                it.createStatement().executeUpdate(
                    """
                    INSERT INTO chunks (document_id, content, content_type, embedding)
                    VALUES (999, 'test content', 'text', X'00')
                    """.trimIndent()
                )
            }
            assertTrue(
                exception.message?.contains("FOREIGN KEY") == true,
                "Expected foreign key constraint violation, got: ${exception.message}"
            )
        }
    }

    @Test
    fun `foreign keys are enforced - images reference documents`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val exception = assertFailsWith<SQLException> {
                it.createStatement().executeUpdate(
                    """
                    INSERT INTO images (document_id, filename, path)
                    VALUES (999, 'test.png', '/data/images/999/test.png')
                    """.trimIndent()
                )
            }
            assertTrue(
                exception.message?.contains("FOREIGN KEY") == true,
                "Expected foreign key constraint violation, got: ${exception.message}"
            )
        }
    }

    @Test
    fun `cascade delete removes chunks when document is deleted`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            // Insert a document
            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type) VALUES ('test.docx', 'docx')"
            )

            // Insert a chunk referencing that document
            it.createStatement().executeUpdate(
                """
                INSERT INTO chunks (document_id, content, content_type, embedding)
                VALUES (1, 'test content', 'text', X'00')
                """.trimIndent()
            )

            // Verify chunk was inserted
            val verifyRs = it.createStatement().executeQuery("SELECT COUNT(*) FROM chunks WHERE document_id = 1")
            verifyRs.next()
            assertEquals(1, verifyRs.getInt(1), "Chunk should exist before delete")

            // Delete the document
            it.createStatement().executeUpdate("DELETE FROM documents WHERE id = 1")

            // Chunk should be gone
            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM chunks WHERE document_id = 1")
            rs.next()
            assertEquals(0, rs.getInt(1), "Chunks should be cascade deleted")
        }
    }

    @Test
    fun `cascade delete removes images when document is deleted`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            // Insert a document
            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type) VALUES ('test.docx', 'docx')"
            )

            // Insert an image referencing that document
            it.createStatement().executeUpdate(
                """
                INSERT INTO images (document_id, filename, path)
                VALUES (1, 'img_001.png', '/data/images/1/img_001.png')
                """.trimIndent()
            )

            // Verify image was inserted
            val verifyRs = it.createStatement().executeQuery("SELECT COUNT(*) FROM images WHERE document_id = 1")
            verifyRs.next()
            assertEquals(1, verifyRs.getInt(1), "Image should exist before delete")

            // Delete the document
            it.createStatement().executeUpdate("DELETE FROM documents WHERE id = 1")

            // Image should be gone
            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM images WHERE document_id = 1")
            rs.next()
            assertEquals(0, rs.getInt(1), "Images should be cascade deleted")
        }
    }

    @Test
    fun `schema version is recorded after migration`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery("SELECT version, name FROM schema_version")
            assertTrue(rs.next(), "Expected at least one migration recorded")
            assertEquals(1, rs.getInt("version"))
            assertEquals("initial_schema", rs.getString("name"))
        }
    }

    // --- V002 tests: chunks.embedding nullable ---

    @Test
    fun `V002 - chunk with null embedding succeeds`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type) VALUES ('test.docx', 'docx')"
            )

            // Insert chunk with NULL embedding
            it.createStatement().executeUpdate(
                """
                INSERT INTO chunks (document_id, content, content_type, embedding)
                VALUES (1, 'test content', 'text', NULL)
                """.trimIndent()
            )

            val rs = it.createStatement().executeQuery("SELECT embedding FROM chunks WHERE id = 1")
            assertTrue(rs.next(), "Chunk should exist")
            rs.getBytes("embedding")
            assertTrue(rs.wasNull(), "Embedding should be NULL")
        }
    }

    @Test
    fun `V002 - chunk with non-null embedding succeeds`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type) VALUES ('test.docx', 'docx')"
            )

            it.createStatement().executeUpdate(
                """
                INSERT INTO chunks (document_id, content, content_type, embedding)
                VALUES (1, 'test content', 'text', X'DEADBEEF')
                """.trimIndent()
            )

            val rs = it.createStatement().executeQuery("SELECT embedding FROM chunks WHERE id = 1")
            assertTrue(rs.next(), "Chunk should exist")
            val blob = rs.getBytes("embedding")
            assertTrue(blob != null && blob.isNotEmpty(), "Embedding should be non-null")
        }
    }

    @Test
    fun `V002 - FK violation raised for chunk with non-existent document_id`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val exception = assertFailsWith<SQLException> {
                it.createStatement().executeUpdate(
                    """
                    INSERT INTO chunks (document_id, content, content_type, embedding)
                    VALUES (999, 'test content', 'text', NULL)
                    """.trimIndent()
                )
            }
            assertTrue(
                exception.message?.contains("FOREIGN KEY") == true,
                "Expected foreign key constraint violation, got: ${exception.message}"
            )
        }
    }

    @Test
    fun `V002 - cascade delete removes chunks with null embedding`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type) VALUES ('test.docx', 'docx')"
            )

            // Insert chunk with NULL embedding
            it.createStatement().executeUpdate(
                """
                INSERT INTO chunks (document_id, content, content_type, embedding)
                VALUES (1, 'null embedding chunk', 'text', NULL)
                """.trimIndent()
            )

            // Insert chunk with non-null embedding
            it.createStatement().executeUpdate(
                """
                INSERT INTO chunks (document_id, content, content_type, embedding)
                VALUES (1, 'blob embedding chunk', 'text', X'DEADBEEF')
                """.trimIndent()
            )

            val verifyRs = it.createStatement().executeQuery("SELECT COUNT(*) FROM chunks WHERE document_id = 1")
            verifyRs.next()
            assertEquals(2, verifyRs.getInt(1), "Both chunks should exist before delete")

            it.createStatement().executeUpdate("DELETE FROM documents WHERE id = 1")

            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM chunks WHERE document_id = 1")
            rs.next()
            assertEquals(0, rs.getInt(1), "All chunks should be cascade deleted")
        }
    }

    @Test
    fun `V002 - schema_version records version 2`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT version, name FROM schema_version WHERE version = 2"
            )
            assertTrue(rs.next(), "Expected version 2 to be recorded")
            assertEquals(2, rs.getInt("version"))
            assertEquals("chunks_embedding_nullable", rs.getString("name"))
        }
    }

    // --- V003 tests: UNIQUE index on documents.file_hash ---

    @Test
    fun `V003 - idx_documents_file_hash_unique exists in sqlite_master`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_documents_file_hash_unique'"
            )
            assertTrue(rs.next(), "Expected idx_documents_file_hash_unique index to exist")
        }
    }

    @Test
    fun `V003 - duplicate file_hash raises SQLException containing UNIQUE`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type, file_hash) VALUES ('a.docx', 'docx', 'abc123')"
            )

            val exception = assertFailsWith<SQLException> {
                it.createStatement().executeUpdate(
                    "INSERT INTO documents (filename, file_type, file_hash) VALUES ('b.docx', 'docx', 'abc123')"
                )
            }
            assertTrue(
                exception.message?.contains("UNIQUE") == true,
                "Expected UNIQUE constraint violation, got: ${exception.message}"
            )
        }
    }

    @Test
    fun `V003 - rows with different file_hash values coexist`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type, file_hash) VALUES ('a.docx', 'docx', 'hash_aaa')"
            )
            it.createStatement().executeUpdate(
                "INSERT INTO documents (filename, file_type, file_hash) VALUES ('b.docx', 'docx', 'hash_bbb')"
            )

            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM documents")
            rs.next()
            assertEquals(2, rs.getInt(1), "Both rows with distinct hashes should coexist")
        }
    }

    @Test
    fun `V003 - schema_version records version 3`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT version, name FROM schema_version WHERE version = 3"
            )
            assertTrue(rs.next(), "Expected version 3 to be recorded")
            assertEquals(3, rs.getInt("version"))
            assertEquals("documents_file_hash_unique", rs.getString("name"))
        }
    }

    // --- V004 tests: seed system_prompt ---

    @Test
    fun `V004 - system_prompt row is present and non-empty after migration`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT value FROM config WHERE key = 'system_prompt'"
            )
            assertTrue(rs.next(), "Expected system_prompt row to exist")
            val value = rs.getString("value")
            assertNotNull(value, "system_prompt value should not be null")
            assertTrue(value.isNotEmpty(), "system_prompt value should not be empty")
        }
    }

    @Test
    fun `V004 - system_prompt value is a JSON string containing the default prompt text`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT value FROM config WHERE key = 'system_prompt'"
            )
            assertTrue(rs.next())
            val raw = rs.getString("value")

            val element = Json.parseToJsonElement(raw)
            val primitive = element as? JsonPrimitive
            assertNotNull(primitive, "Expected JSON primitive (string), got: $element")
            assertTrue(primitive.isString, "Expected JSON string, got a non-string primitive")

            val decoded = primitive.jsonPrimitive.content
            assertTrue(
                decoded.contains("AOS Documentation Assistant"),
                "Decoded prompt should mention 'AOS Documentation Assistant', got: $decoded"
            )
        }
    }

    @Test
    fun `V004 - schema_version records version 4`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT version, name FROM schema_version WHERE version = 4"
            )
            assertTrue(rs.next(), "Expected version 4 to be recorded")
            assertEquals(4, rs.getInt("version"))
            assertEquals("seed_system_prompt", rs.getString("name"))
        }
    }

    @Test
    fun `V004 - running migrations twice does not insert a second system_prompt row`() {
        val conn = inMemoryConnection()
        conn.use {
            Migrations(it).apply()
            Migrations(it).apply()

            val rs = it.createStatement().executeQuery(
                "SELECT COUNT(*) FROM config WHERE key = 'system_prompt'"
            )
            rs.next()
            assertEquals(1, rs.getInt(1), "system_prompt row should exist exactly once after a double apply()")
        }
    }
}
