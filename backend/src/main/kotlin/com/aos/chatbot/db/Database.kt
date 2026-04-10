package com.aos.chatbot.db

import java.sql.Connection
import java.sql.DriverManager

class Database(private val dbPath: String) {

    fun connect(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
        return connection
    }
}
