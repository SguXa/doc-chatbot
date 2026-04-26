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
            "app.paths.images" to "./data/images",
            "app.ollama.url" to "http://ollama:11434",
            "app.ollama.llmModel" to "qwen2.5:7b-instruct-q4_K_M",
            "app.ollama.embedModel" to "bge-m3",
            "app.artemis.brokerUrl" to "tcp://artemis:61616",
            "app.artemis.user" to "",
            "app.artemis.password" to ""
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

    @Test
    fun `ollama defaults resolve`() {
        val appConfig = AppConfig.from(defaultEnv())
        assertEquals("http://ollama:11434", appConfig.ollama.url)
        assertEquals("qwen2.5:7b-instruct-q4_K_M", appConfig.ollama.llmModel)
        assertEquals("bge-m3", appConfig.ollama.embedModel)
    }

    @Test
    fun `artemis defaults resolve`() {
        val appConfig = AppConfig.from(defaultEnv())
        assertEquals("tcp://artemis:61616", appConfig.artemis.brokerUrl)
        assertEquals("", appConfig.artemis.user)
        assertEquals("", appConfig.artemis.password)
    }

    @Test
    fun `OLLAMA_URL overrides ollama url independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.ollama.url" to "http://custom-ollama:9999"
        ))
        assertEquals("http://custom-ollama:9999", appConfig.ollama.url)
        assertEquals("qwen2.5:7b-instruct-q4_K_M", appConfig.ollama.llmModel)
        assertEquals("bge-m3", appConfig.ollama.embedModel)
    }

    @Test
    fun `OLLAMA_LLM_MODEL overrides llm model independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.ollama.llmModel" to "llama3:8b"
        ))
        assertEquals("http://ollama:11434", appConfig.ollama.url)
        assertEquals("llama3:8b", appConfig.ollama.llmModel)
        assertEquals("bge-m3", appConfig.ollama.embedModel)
    }

    @Test
    fun `OLLAMA_EMBED_MODEL overrides embed model independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.ollama.embedModel" to "nomic-embed-text"
        ))
        assertEquals("http://ollama:11434", appConfig.ollama.url)
        assertEquals("qwen2.5:7b-instruct-q4_K_M", appConfig.ollama.llmModel)
        assertEquals("nomic-embed-text", appConfig.ollama.embedModel)
    }

    @Test
    fun `ARTEMIS_BROKER_URL overrides broker url independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.artemis.brokerUrl" to "tcp://custom-broker:61616"
        ))
        assertEquals("tcp://custom-broker:61616", appConfig.artemis.brokerUrl)
        assertEquals("", appConfig.artemis.user)
        assertEquals("", appConfig.artemis.password)
    }

    @Test
    fun `ARTEMIS_USER overrides user independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.artemis.user" to "admin"
        ))
        assertEquals("tcp://artemis:61616", appConfig.artemis.brokerUrl)
        assertEquals("admin", appConfig.artemis.user)
        assertEquals("", appConfig.artemis.password)
    }

    @Test
    fun `ARTEMIS_PASSWORD overrides password independently`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.artemis.password" to "s3cret"
        ))
        assertEquals("tcp://artemis:61616", appConfig.artemis.brokerUrl)
        assertEquals("", appConfig.artemis.user)
        assertEquals("s3cret", appConfig.artemis.password)
    }

    @Test
    fun `empty ARTEMIS_USER and ARTEMIS_PASSWORD produce empty strings not nulls`() {
        val appConfig = AppConfig.from(defaultEnv(
            "app.artemis.user" to "",
            "app.artemis.password" to ""
        ))
        assertEquals("", appConfig.artemis.user)
        assertEquals("", appConfig.artemis.password)
    }
}
