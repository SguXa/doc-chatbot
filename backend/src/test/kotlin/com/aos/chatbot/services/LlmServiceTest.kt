package com.aos.chatbot.services

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.models.ChatMessage
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LlmServiceTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var httpClient: HttpClient
    private lateinit var config: OllamaConfig

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        httpClient = HttpClient(CIO) {
            expectSuccess = false
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
        val stream = this::class.java.classLoader.getResourceAsStream("fixtures/ollama-chat-stream.ndjson")
            ?: error("Fixture ollama-chat-stream.ndjson not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun sampleMessages(): List<ChatMessage> = listOf(
        ChatMessage(role = "system", content = "be helpful"),
        ChatMessage(role = "user", content = "say hi")
    )

    @Test
    fun `generate emits 5 token chunks for fixture stream`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(fixture)
                )
        )
        val service = LlmService(httpClient, config)

        val tokens = service.generate(sampleMessages()).toList()

        assertEquals(listOf("Hello", ", ", "how", " are", " you?"), tokens)
        wireMock.verify(postRequestedFor(urlEqualTo("/api/chat")))
    }

    @Test
    fun `generate sends model, messages, and stream=true in request body`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(fixture)
                )
        )
        val service = LlmService(httpClient, config)

        service.generate(sampleMessages()).toList()

        val expected = """
            {
              "model": "qwen2.5:7b-instruct-q4_K_M",
              "messages": [
                {"role":"system","content":"be helpful"},
                {"role":"user","content":"say hi"}
              ],
              "stream": true
            }
        """.trimIndent()
        wireMock.verify(
            postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(equalToJson(expected))
        )
    }

    @Test
    fun `generate throws IllegalArgumentException on empty message list`() {
        val service = LlmService(httpClient, config)

        assertFailsWith<IllegalArgumentException> {
            service.generate(emptyList())
        }
    }

    @Test
    fun `generate throws OllamaUnavailableException on 503`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withStatus(503).withBody("overloaded"))
        )
        val service = LlmService(httpClient, config)

        val ex = assertFailsWith<OllamaUnavailableException> {
            service.generate(sampleMessages()).toList()
        }
        assertTrue(ex.message!!.contains("503"), "expected 503 in message, got: ${ex.message}")
    }

    @Test
    fun `generate throws OllamaUnavailableException on 500`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withStatus(500))
        )
        val service = LlmService(httpClient, config)

        assertFailsWith<OllamaUnavailableException> {
            service.generate(sampleMessages()).toList()
        }
        Unit
    }

    @Test
    fun `generate throws OllamaUnavailableException when server unreachable`() = runBlocking {
        wireMock.stop()
        val service = LlmService(httpClient, config)

        assertFailsWith<OllamaUnavailableException> {
            service.generate(sampleMessages()).toList()
        }
        Unit
    }

    @Test
    fun `generate throws OllamaUnavailableException on request timeout`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withFixedDelay(2_000)
                        .withBody("""{"message":{"content":"x"},"done":true}""" + "\n")
                )
        )
        val service = LlmService(httpClient, config, timeoutMs = 200)

        val ex = assertFailsWith<OllamaUnavailableException> {
            service.generate(sampleMessages()).toList()
        }
        assertTrue(
            ex.message!!.contains("timed out", ignoreCase = true),
            "expected 'timed out' in message, got: ${ex.message}"
        )
    }

    @Test
    fun `generate surfaces mid-stream connection failure as flow exception`() = runBlocking {
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE))
        )
        val service = LlmService(httpClient, config)

        assertFailsWith<Exception> {
            service.generate(sampleMessages()).toList()
        }
        Unit
    }

    @Test
    fun `generateFull concatenates all streamed tokens into a single string`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(fixture)
                )
        )
        val service = LlmService(httpClient, config)

        val full = service.generateFull(sampleMessages())

        assertEquals("Hello, how are you?", full)
    }

    @Test
    fun `generate ignores blank lines in NDJSON stream`() = runBlocking {
        val body = buildString {
            append("""{"message":{"content":"a"},"done":false}""").append('\n')
            append('\n')
            append("""{"message":{"content":"b"},"done":false}""").append('\n')
            append("""{"done":true}""").append('\n')
        }
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(body)
                )
        )
        val service = LlmService(httpClient, config)

        val tokens = service.generate(sampleMessages()).toList()

        assertEquals(listOf("a", "b"), tokens)
    }

    @Test
    fun `generate trims trailing slash from url before appending path`() = runBlocking {
        val fixture = loadFixture()
        wireMock.stubFor(
            post(urlEqualTo("/api/chat"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(fixture)
                )
        )
        val trailing = config.copy(url = "http://localhost:${wireMock.port()}/")
        val service = LlmService(httpClient, trailing)

        val tokens = service.generate(sampleMessages()).toList()

        assertEquals(5, tokens.size)
    }
}
