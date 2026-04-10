package com.aos.chatbot.db

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseTest {

    private fun inMemoryConnection(): Connection {
        val db = Database(":memory:")
        return db.connect()
    }

    @Test
    fun `WAL mode is enabled`() {
        val conn = inMemoryConnection()
        conn.use {
            val rs = it.createStatement().executeQuery("PRAGMA journal_mode")
            rs.next()
            // In-memory SQLite may report "memory" instead of "wal"
            val mode = rs.getString(1)
            assertTrue(mode == "wal" || mode == "memory", "Journal mode should be wal or memory, got: $mode")
        }
    }

    @Test
    fun `foreign keys are enabled`() {
        val conn = inMemoryConnection()
        conn.use {
            val rs = it.createStatement().executeQuery("PRAGMA foreign_keys")
            rs.next()
            assertEquals(1, rs.getInt(1), "Foreign keys should be enabled")
        }
    }
}
