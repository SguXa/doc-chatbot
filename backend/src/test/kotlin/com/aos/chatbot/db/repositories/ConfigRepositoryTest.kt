package com.aos.chatbot.db.repositories

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigRepositoryTest {

    private lateinit var conn: Connection
    private lateinit var repo: ConfigRepository

    @BeforeEach
    fun setUp() {
        conn = Database(":memory:").connect()
        Migrations(conn).apply()
        repo = ConfigRepository(conn)
    }

    @AfterEach
    fun tearDown() {
        conn.close()
    }

    @Test
    fun `get system_prompt returns non-null JSON string after migration`() {
        val raw = repo.get("system_prompt")
        assertNotNull(raw)
        assertTrue(raw.isNotEmpty())
        // Raw value is a JSON string literal, so it starts and ends with a quote
        assertTrue(raw.startsWith("\""), "expected JSON-encoded string, got: $raw")
        assertTrue(raw.endsWith("\""))
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(repo.get("missing_key"))
    }

    @Test
    fun `put and get round-trip raw string`() {
        repo.put("custom_key", "\"hello world\"")
        assertEquals("\"hello world\"", repo.get("custom_key"))
    }

    @Test
    fun `put on existing key updates value and bumps updated_at`() {
        repo.put("custom_key", "\"first\"")
        val firstUpdatedAt = readUpdatedAt("custom_key")
        assertNotNull(firstUpdatedAt)

        // SQLite CURRENT_TIMESTAMP granularity is one second; sleep past it so the
        // updated_at comparison is meaningful.
        Thread.sleep(1100)

        repo.put("custom_key", "\"second\"")
        assertEquals("\"second\"", repo.get("custom_key"))

        val secondUpdatedAt = readUpdatedAt("custom_key")
        assertNotNull(secondUpdatedAt)
        assertNotEquals(firstUpdatedAt, secondUpdatedAt)
        assertTrue(secondUpdatedAt > firstUpdatedAt)
    }

    @Test
    fun `getWithUpdatedAt returns row for seeded system_prompt`() {
        val entry = repo.getWithUpdatedAt("system_prompt")
        assertNotNull(entry)
        assertTrue(entry.value.startsWith("\""), "value should be JSON-encoded string: ${entry.value}")
        assertTrue(entry.updatedAt.isNotEmpty(), "updatedAt must be a non-empty timestamp")
    }

    @Test
    fun `getWithUpdatedAt returns null for missing key`() {
        assertNull(repo.getWithUpdatedAt("missing_key"))
    }

    @Test
    fun `getWithUpdatedAt reflects put writes value and timestamp`() {
        repo.put("custom_key", "\"hello\"")
        val entry = repo.getWithUpdatedAt("custom_key")
        assertNotNull(entry)
        assertEquals("\"hello\"", entry.value)
        assertTrue(entry.updatedAt.isNotEmpty())
    }

    @Test
    fun `getWithUpdatedAt timestamp updates after second put`() {
        repo.put("custom_key", "\"first\"")
        val first = repo.getWithUpdatedAt("custom_key")
        assertNotNull(first)

        Thread.sleep(1100)

        repo.put("custom_key", "\"second\"")
        val second = repo.getWithUpdatedAt("custom_key")
        assertNotNull(second)
        assertEquals("\"second\"", second.value)
        assertNotEquals(first.updatedAt, second.updatedAt)
        assertTrue(second.updatedAt > first.updatedAt)
    }

    private fun readUpdatedAt(key: String): String? {
        conn.prepareStatement("SELECT updated_at FROM config WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("updated_at") else null
        }
    }
}
