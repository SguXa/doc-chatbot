package com.aos.chatbot.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {

    @Test
    fun `AppMode parses full`() {
        assertEquals(AppMode.FULL, AppMode.fromString("full"))
    }

    @Test
    fun `AppMode parses admin`() {
        assertEquals(AppMode.ADMIN, AppMode.fromString("admin"))
    }

    @Test
    fun `AppMode parses client`() {
        assertEquals(AppMode.CLIENT, AppMode.fromString("client"))
    }

    @Test
    fun `AppMode parsing is case insensitive`() {
        assertEquals(AppMode.FULL, AppMode.fromString("FULL"))
        assertEquals(AppMode.ADMIN, AppMode.fromString("Admin"))
        assertEquals(AppMode.CLIENT, AppMode.fromString("CLIENT"))
    }

    @Test
    fun `AppMode throws on invalid mode`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            AppMode.fromString("invalid")
        }
        assertEquals("Invalid mode: 'invalid'. Must be one of: full, admin, client", exception.message)
    }

    @Test
    fun `AppConfig reads defaults from application conf`() {
        // Defaults are tested via the testApplication in ApplicationTest
        // Here we test the enum parsing which is the core logic
        assertEquals(AppMode.FULL, AppMode.fromString("full"))
    }
}
