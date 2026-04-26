package com.aos.chatbot.db.repositories

import java.sql.Connection

/**
 * Repository for the `config` key-value table.
 *
 * Values are stored as JSON-in-TEXT (see V001 schema, V004 seed). Callers read
 * the raw string and decode it themselves.
 *
 * Instances are operation-scoped and must not outlive the injected [Connection].
 * The caller owns the connection lifecycle.
 */
class ConfigRepository(private val conn: Connection) {

    /**
     * Returns the raw `value` column for [key], or `null` if no row exists.
     * The returned string is the verbatim JSON text.
     */
    fun get(key: String): String? {
        val sql = "SELECT value FROM config WHERE key = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, key)
            val rs = stmt.executeQuery()
            return if (rs.next()) rs.getString("value") else null
        }
    }

    /**
     * Upserts [value] for [key]. On conflict, `value` is replaced and `updated_at`
     * is bumped to `CURRENT_TIMESTAMP`.
     */
    fun put(key: String, value: String) {
        val sql = """
            INSERT INTO config (key, value, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
    }
}
