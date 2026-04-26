package com.aos.chatbot.integration

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.models.ChatMessage
import com.aos.chatbot.services.EmbeddingService
import com.aos.chatbot.services.LlmService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration smoke tests that exercise [EmbeddingService] and [LlmService]
 * against a locally running Ollama. Excluded from the default `./gradlew test`
 * suite via `@Tag("integration")`.
 *
 * How to run:
 * ```
 * OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest
 * ```
 *
 * Expected runtime: ~30 s once models are warm.
 *
 * Models required on the target Ollama instance (pull before running):
 *  - `bge-m3` (embedding model, 1024-dim)
 *  - `qwen2.5:7b-instruct-q4_K_M` (LLM)
 *
 * The `@EnabledIfEnvironmentVariable` guard ensures the suite is skipped
 * cleanly when `OLLAMA_TEST_URL` is unset, so CI environments without Ollama
 * still report the suite as disabled rather than failing.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_URL", matches = ".*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OllamaIntegrationTest {

    private lateinit var httpClient: HttpClient
    private lateinit var config: OllamaConfig
    private lateinit var embeddingService: EmbeddingService
    private lateinit var llmService: LlmService

    @BeforeAll
    fun setUp() {
        val url = System.getenv("OLLAMA_TEST_URL")
            ?: error("OLLAMA_TEST_URL must be set (e.g., http://localhost:11434)")
        httpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        config = OllamaConfig(
            url = url,
            llmModel = System.getenv("OLLAMA_TEST_LLM_MODEL") ?: "qwen2.5:7b-instruct-q4_K_M",
            embedModel = System.getenv("OLLAMA_TEST_EMBED_MODEL") ?: "bge-m3"
        )
        embeddingService = EmbeddingService(httpClient, config)
        llmService = LlmService(httpClient, config)
    }

    @AfterAll
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun `embed returns 1024-length FloatArray from real Ollama`() = runBlocking {
        val embedding = embeddingService.embed("hello")
        assertEquals(1024, embedding.size, "bge-m3 must return 1024-dimensional embeddings")
    }

    @Test
    fun `generate emits at least one token from real Ollama`() = runBlocking {
        val tokens = llmService.generate(listOf(ChatMessage(role = "user", content = "hi"))).count()
        assertTrue(tokens >= 1, "expected at least one token, got $tokens")
    }
}
