package com.aos.chatbot.config

import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.ktor.server.application.ApplicationEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {

    private fun buildEnvironment(vararg pairs: Pair<String, String>): ApplicationEnvironment {
        val config = MapApplicationConfig(*pairs)
        val env = mockk<ApplicationEnvironment>()
        every { env.config } returns config
        return env
    }

    private fun defaultEnv(vararg overrides: Pair<String, String>): ApplicationEnvironment {
        val defaults = mapOf(
            "ktor.deployment.port" to "8080",
            "ktor.deployment.host" to "0.0.0.0",
            "app.mode" to "full",
            "app.database.path" to ":memory:",
            "app.data.path" to "./data",
            "app.paths.documents" to "./data/documents",
            "app.paths.images" to "./data/images"
        )
        val merged = defaults + overrides.toMap()
        return buildEnvironment(*merged.map { it.key to it.value }.toTypedArray())
    }

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
    fun `AppMode throws on empty string`() {
        assertFailsWith<IllegalArgumentException> {
            AppMode.fromString("")
        }
    }

    @Test
    fun `defaults only derives documentsPath and imagesPath from dataPath`() {
        val appConfig = AppConfig.from(defaultEnv())
        assertEquals("./data/documents", appConfig.documentsPath)
        assertEquals("./data/images", appConfig.imagesPath)
    }

    @Test
    fun `only DATA_PATH set derives both paths correctly`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.data.path" to "/custom/data",
            "app.paths.documents" to "/custom/data/documents",
            "app.paths.images" to "/custom/data/images"
        ))
        assertEquals("/custom/data", appConfig.dataPath)
        assertEquals("/custom/data/documents", appConfig.documentsPath)
        assertEquals("/custom/data/images", appConfig.imagesPath)
    }

    @Test
    fun `DOCUMENTS_PATH overrides documents independently of images`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.paths.documents" to "/mnt/docs"
        ))
        assertEquals("/mnt/docs", appConfig.documentsPath)
        assertEquals("./data/images", appConfig.imagesPath)
    }

    @Test
    fun `IMAGES_PATH overrides images independently of documents`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.paths.images" to "/mnt/images"
        ))
        assertEquals("./data/documents", appConfig.documentsPath)
        assertEquals("/mnt/images", appConfig.imagesPath)
    }

    @Test
    fun `both overrides honored together`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.paths.documents" to "/vol1/docs",
            "app.paths.images" to "/vol2/imgs"
        ))
        assertEquals("/vol1/docs", appConfig.documentsPath)
        assertEquals("/vol2/imgs", appConfig.imagesPath)
    }
}
