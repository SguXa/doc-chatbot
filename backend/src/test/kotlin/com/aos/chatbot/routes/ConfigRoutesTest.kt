package com.aos.chatbot.routes

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.ConfigRepository
import com.aos.chatbot.routes.dto.InvalidConfigRequestResponse
import com.aos.chatbot.routes.dto.SystemPromptResponse
import com.aos.chatbot.services.ChatService
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigRoutesTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: String
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("config-routes-test")
        dbPath = tempDir.resolve("aos.db").toString()
        database = Database(dbPath)
        // Apply migrations on a separate connection so V004 seeds system_prompt.
        database.connect().use { Migrations(it).apply() }
    }

    @AfterEach
    fun tearDown() {
        runCatching {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `GET system-prompt returns seeded default and non-empty updatedAt`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val response = client.get("/api/config/system-prompt")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<SystemPromptResponse>(response.bodyAsText())
        assertTrue(body.prompt.isNotBlank(), "seeded prompt must not be blank")
        assertTrue(
            body.prompt.contains("AOS Documentation Assistant"),
            "expected V004-seeded default prompt, got: ${body.prompt}"
        )
        assertTrue(body.updatedAt.isNotEmpty(), "updatedAt must be non-empty")
    }

    @Test
    fun `PUT system-prompt with valid prompt persists value and GET returns the new prompt`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val newPrompt = "You are a fancy new assistant.\nLine two with \"quotes\"."
        val putResp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":${Json.encodeToString(String.serializer(), newPrompt)}}""")
        }
        assertEquals(HttpStatusCode.OK, putResp.status, putResp.bodyAsText())
        val putBody = Json.decodeFromString<SystemPromptResponse>(putResp.bodyAsText())
        assertEquals(newPrompt, putBody.prompt)
        assertTrue(putBody.updatedAt.isNotEmpty())

        val getResp = client.get("/api/config/system-prompt")
        assertEquals(HttpStatusCode.OK, getResp.status)
        val getBody = Json.decodeFromString<SystemPromptResponse>(getResp.bodyAsText())
        assertEquals(newPrompt, getBody.prompt)
    }

    @Test
    fun `PUT system-prompt with empty prompt returns 400 empty_prompt`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.decodeFromString<InvalidConfigRequestResponse>(resp.bodyAsText())
        assertEquals("invalid_request", body.error)
        assertEquals("empty_prompt", body.reason)
    }

    @Test
    fun `PUT system-prompt with whitespace-only prompt returns 400 empty_prompt`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"   \n  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.decodeFromString<InvalidConfigRequestResponse>(resp.bodyAsText())
        assertEquals("empty_prompt", body.reason)
    }

    @Test
    fun `PUT system-prompt with 8001-char prompt returns 400 prompt_too_long`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val tooLong = "a".repeat(8001)
        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"$tooLong"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.decodeFromString<InvalidConfigRequestResponse>(resp.bodyAsText())
        assertEquals("prompt_too_long", body.reason)
    }

    @Test
    fun `PUT system-prompt with exactly 8000-char prompt is accepted`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val maxPrompt = "a".repeat(8000)
        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"$maxPrompt"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    @Test
    fun `PUT system-prompt with malformed JSON returns 400 malformed_body`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{not valid json""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.decodeFromString<InvalidConfigRequestResponse>(resp.bodyAsText())
        assertEquals("invalid_request", body.error)
        assertEquals("malformed_body", body.reason)
    }

    @Test
    fun `after PUT, ChatService readSystemPrompt path returns the new value`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing { configRoutes(database) }
        }

        val newPrompt = "Cross-checked production read path"
        val resp = client.put("/api/config/system-prompt") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"$newPrompt"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())

        // Production read path: ChatService.readSystemPrompt() opens a DB
        // connection and calls ConfigRepository(conn).get(SYSTEM_PROMPT_KEY),
        // then JSON-decodes. Mirror that here without booting ChatService.
        val raw = database.connect().use { conn ->
            ConfigRepository(conn).get(ChatService.SYSTEM_PROMPT_KEY)
        }
        assertNotNull(raw, "system_prompt row must exist after PUT")
        val decoded = Json.decodeFromString(String.serializer(), raw)
        assertEquals(newPrompt, decoded)
    }
}
