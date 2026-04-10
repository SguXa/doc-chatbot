package com.aos.chatbot.db

import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals

class DatabaseTest {

    private fun inMemoryConnection(): Connection {
        val db = Database(":memory:")
        return db.connect()
    }

    @Test
    fun `WAL mode is enabled`() {
        val tmpFile = File.createTempFile("aos-test-", ".db")
        try {
            val conn = Database(tmpFile.absolutePath).connect()
            conn.use {
                val rs = it.createStatement().executeQuery("PRAGMA journal_mode")
                rs.next()
                assertEquals("wal", rs.getString(1), "Journal mode should be wal")
            }
        } finally {
            tmpFile.delete()
            File(tmpFile.absolutePath + "-wal").delete()
            File(tmpFile.absolutePath + "-shm").delete()
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
