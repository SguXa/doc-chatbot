package com.aos.chatbot.integration

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.embeddingToBytes
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.Document
import com.aos.chatbot.module
import com.aos.chatbot.services.EmbeddingService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end smoke test that wires the full Phase 3 object graph against a
 * real Ollama, an embedded Artemis broker, and a real SQLite file in a
 * [TempDir]. Tagged `@Tag("integration")` so it is excluded from the default
 * `./gradlew test` suite.
 *
 * How to run:
 * ```
 * OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest
 * ```
 *
 * Expected runtime: ~60 s (cold model load dominates — warmup + one real chat).
 *
 * Models required on the target Ollama instance:
 *  - `bge-m3` (embedding model)
 *  - `qwen2.5:7b-instruct-q4_K_M` (LLM)
 *
 * Seeding bypasses [com.aos.chatbot.services.DocumentService] and writes a
 * single document plus one chunk (with a real embedding from Ollama) directly
 * via repositories. The application's startup backfill observes the chunk as
 * already-embedded, so it only refreshes the in-memory [com.aos.chatbot.services.SearchService]
 * index before flipping to `Completed`.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_URL", matches = ".*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatE2eIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var embedded: EmbeddedActiveMQ
    private lateinit var brokerUrl: String
    private lateinit var ollamaUrl: String
    private lateinit var llmModel: String
    private lateinit var embedModel: String
    private lateinit var dbPath: String

    @BeforeAll
    fun setUp() {
        ollamaUrl = System.getenv("OLLAMA_TEST_URL")
            ?: error("OLLAMA_TEST_URL must be set (e.g., http://localhost:11434)")
        llmModel = System.getenv("OLLAMA_TEST_LLM_MODEL") ?: "qwen2.5:7b-instruct-q4_K_M"
        embedModel = System.getenv("OLLAMA_TEST_EMBED_MODEL") ?: "bge-m3"

        val config = ConfigurationImpl().apply {
            isPersistenceEnabled = false
            isSecurityEnabled = false
            addAcceptorConfiguration("in-vm", "vm://1")
        }
        embedded = EmbeddedActiveMQ().apply {
            setConfiguration(config)
            start()
        }
        brokerUrl = "vm://1"

        Files.createDirectories(tempDir.resolve("documents"))
        Files.createDirectories(tempDir.resolve("images"))
        dbPath = tempDir.resolve("aos.db").toString()
        Database(dbPath).connect().use { Migrations(it).apply() }
    }

    @AfterAll
    fun tearDown() {
        runCatching { embedded.stop() }
    }

    private fun bootConfig(): MapApplicationConfig {
        val cfg = MapApplicationConfig()
        cfg.put("ktor.deployment.port", "0")
        cfg.put("ktor.deployment.host", "127.0.0.1")
        cfg.put("app.mode", "full")
        cfg.put("app.database.path", dbPath)
        cfg.put("app.data.path", tempDir.toString())
        cfg.put("app.paths.documents", tempDir.resolve("documents").toString())
        cfg.put("app.paths.images", tempDir.resolve("images").toString())
        cfg.put("app.ollama.url", ollamaUrl)
        cfg.put("app.ollama.llmModel", llmModel)
        cfg.put("app.ollama.embedModel", embedModel)
        cfg.put("app.artemis.brokerUrl", brokerUrl)
        cfg.put("app.artemis.user", "")
        cfg.put("app.artemis.password", "")
        return cfg
    }

    private suspend fun seedOneDocumentWithEmbedding(content: String) {
        val httpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val ollamaConfig = OllamaConfig(url = ollamaUrl, llmModel = llmModel, embedModel = embedModel)
        val embeddingService = EmbeddingService(httpClient, ollamaConfig)
        try {
            val embedding = embeddingService.embed(content)
            val database = Database(dbPath)
            database.connect().use { conn ->
                val doc = DocumentRepository(conn).insert(
                    Document(
                        filename = "integration-seed.docx",
                        fileType = "docx",
                        fileSize = 1024L,
                        fileHash = "deadbeef-integration-seed"
                    )
                )
                ChunkRepository(conn).insertBatch(
                    listOf(
                        Chunk(
                            documentId = doc.id,
                            content = content,
                            contentType = "text",
                            pageNumber = 1,
                            sectionId = "1.1",
                            heading = "Integration Seed",
                            embedding = embeddingToBytes(embedding),
                            imageRefs = emptyList()
                        )
                    )
                )
            }
        } finally {
            httpClient.close()
        }
    }

    @Test
    fun `chat e2e streams queued processing token sources and done`() = testApplication {
        runBlocking {
            seedOneDocumentWithEmbedding(
                "The AOS troubleshooting code MA-42 indicates a sensor alignment fault. Recalibrate the front-left sensor to resolve it."
            )
        }

        environment { config = bootConfig() }
        application { module() }

        // Wait for backfill to reach Completed. The seeded chunk already has
        // an embedding, so the loop returns quickly — but we still allow a
        // generous timeout to cover cold-start model loading.
        waitForReady(maxMs = 120_000L)

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"What does MA-42 mean?"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        assertTrue(body.contains("event: queued"), "missing queued event:\n$body")
        assertTrue(body.contains("event: processing"), "missing processing event:\n$body")
        assertTrue(body.contains("event: token"), "missing token event:\n$body")
        assertTrue(body.contains("event: sources"), "missing sources event:\n$body")
        assertTrue(body.contains("event: done"), "missing done event:\n$body")

        val queuedIdx = body.indexOf("event: queued")
        val processingIdx = body.indexOf("event: processing")
        val firstTokenIdx = body.indexOf("event: token")
        val sourcesIdx = body.indexOf("event: sources")
        val doneIdx = body.indexOf("event: done")
        assertTrue(processingIdx > queuedIdx, "processing must follow queued")
        assertTrue(firstTokenIdx > processingIdx, "token must follow processing")
        assertTrue(sourcesIdx > firstTokenIdx, "sources must follow tokens")
        assertTrue(doneIdx > sourcesIdx, "done must follow sources")
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.waitForReady(maxMs: Long) {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val resp = client.get("/api/health/ready")
            if (resp.status == HttpStatusCode.OK) return
            delay(500)
        }
        error("/api/health/ready did not reach OK within ${maxMs}ms")
    }
}
