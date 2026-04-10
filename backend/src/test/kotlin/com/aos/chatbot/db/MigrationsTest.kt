package com.aos.chatbot.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                "idx_images_document"
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

            // Delete the document
            it.createStatement().executeUpdate("DELETE FROM documents WHERE id = 1")

            // Chunk should be gone
            val rs = it.createStatement().executeQuery("SELECT COUNT(*) FROM chunks WHERE document_id = 1")
            rs.next()
            assertEquals(0, rs.getInt(1), "Chunks should be cascade deleted")
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
}
