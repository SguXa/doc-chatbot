package com.aos.chatbot.services

import com.aos.chatbot.config.OllamaConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmbeddingServiceTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var httpClient: HttpClient
    private lateinit var config: OllamaConfig

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        httpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        config = OllamaConfig(
            url = "http://localhost:${wireMock.port()}",
            llmModel = "qwen2.5:7b-instruct-q4_K_M",
            embedModel = "bge-m3"
        )
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        if (wireMock.isRunning) wireMock.stop()
    }

    private fun loadFixture(): String {
        val stream = this::class.java.classLoader.getResourceAsStream("fixtures/ollama-embedding-response.json")
            ?: error("Fixture ollama-embedding-response.json not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `embed returns 1024-length FloatArray for fixture response`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .withRequestBody(equalToJson("""{"model":"bge-m3","prompt":"hello"}"""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture))
        )
        val service = EmbeddingService(httpClient, config)

        val embedding = service.embed("hello")

        assertEquals(1024, embedding.size)
        wireMock.verify(postRequestedFor(urlEqualTo("/api/embeddings")))
    }

    @Test
    fun `embed sends model and prompt fields in request body`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture))
        )
        val service = EmbeddingService(httpClient, config)

        service.embed("some query text")

        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/embeddings"))
                .withRequestBody(equalToJson("""{"model":"bge-m3","prompt":"some query text"}"""))
        )
    }

    @Test
    fun `embed throws OllamaUnavailableException on 503`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .willReturn(aResponse().withStatus(503).withBody("server overloaded"))
        )
        val service = EmbeddingService(httpClient, config)

        val ex = assertFailsWith<OllamaUnavailableException> {
            service.embed("hello")
        }
        assertTrue(ex.message!!.contains("503"), "expected 503 in message, got: ${ex.message}")
    }

    @Test
    fun `embed throws OllamaUnavailableException on 500`() {
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/api/embeddings"))
                    .willReturn(aResponse().withStatus(500))
            )
            val service = EmbeddingService(httpClient, config)

            assertFailsWith<OllamaUnavailableException> {
                service.embed("hello")
            }
        }
    }

    @Test
    fun `embed throws OllamaUnavailableException on malformed JSON body`() {
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/api/embeddings"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{not json"))
            )
            val service = EmbeddingService(httpClient, config)

            assertFailsWith<OllamaUnavailableException> {
                service.embed("hello")
            }
        }
    }

    @Test
    fun `embed throws OllamaUnavailableException on empty embedding array`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"embedding":[]}"""))
        )
        val service = EmbeddingService(httpClient, config)

        val ex = assertFailsWith<OllamaUnavailableException> {
            service.embed("hello")
        }
        assertTrue(ex.message!!.contains("empty"), "expected 'empty' in message, got: ${ex.message}")
    }

    @Test
    fun `embed throws OllamaUnavailableException when server unreachable`() {
        runBlocking {
            wireMock.stop()
            val service = EmbeddingService(httpClient, config)

            assertFailsWith<OllamaUnavailableException> {
                service.embed("hello")
            }
        }
    }

    @Test
    fun `embed throws OllamaUnavailableException on request timeout`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(2_000)
                        .withBody("""{"embedding":[0.1,0.2,0.3]}""")
                )
        )
        val service = EmbeddingService(httpClient, config, timeoutMs = 200)

        val ex = assertFailsWith<OllamaUnavailableException> {
            service.embed("hello")
        }
        assertTrue(
            ex.message!!.contains("timed out", ignoreCase = true),
            "expected 'timed out' in message, got: ${ex.message}"
        )
    }

    @Test
    fun `embed trims trailing slash from url before appending path`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/embeddings"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture))
        )
        val trailing = config.copy(url = "http://localhost:${wireMock.port()}/")
        val service = EmbeddingService(httpClient, trailing)

        val embedding = service.embed("hello")

        assertEquals(1024, embedding.size)
    }
}
