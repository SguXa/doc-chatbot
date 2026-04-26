package com.aos.chatbot.routes

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.Document
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.EmbeddingService
import com.aos.chatbot.services.QueueService
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthRoutesTest {

    private lateinit var wireMock: WireMockServer
    private lateinit var httpClient: HttpClient
    private lateinit var ollamaConfig: OllamaConfig
    private lateinit var tempDir: Path
    private lateinit var dbPath: String
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()
        httpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ClientContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        ollamaConfig = OllamaConfig(
            url = "http://localhost:${wireMock.port()}",
            llmModel = "qwen2.5:7b-instruct-q4_K_M",
            embedModel = "bge-m3"
        )
        tempDir = Files.createTempDirectory("healthroutes-test")
        dbPath = tempDir.resolve("aos.db").toString()
        database = Database(dbPath)
        database.connect().use { conn ->
            Migrations(conn).apply()
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        if (wireMock.isRunning) wireMock.stop()
        runCatching { Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun stubOllamaTagsOk() {
        val body = """{"models":[{"name":"qwen2.5:7b-instruct-q4_K_M"},{"name":"bge-m3"}]}"""
        wireMock.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body))
        )
    }

    private fun stubOllamaTagsMissingModel() {
        val body = """{"models":[{"name":"bge-m3"}]}"""
        wireMock.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body))
        )
    }

    private fun stubOllamaTagsDown() {
        wireMock.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(503))
        )
    }

    private fun readyDeps(
        queueConnected: Boolean = true,
        backfillStatus: BackfillStatus = BackfillStatus.Completed(0, 0),
        backfillIsRunning: Boolean = false
    ): Deps {
        val queueService = mockk<QueueService>()
        every { queueService.isConnected() } returns queueConnected

        val embeddingService = mockk<EmbeddingService>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns backfillStatus
        every { backfillJob.isRunning() } returns backfillIsRunning
        return Deps(queueService, embeddingService, backfillJob)
    }

    private data class Deps(
        val queueService: QueueService,
        val embeddingService: EmbeddingService,
        val backfillJob: EmbeddingBackfillJob
    )

    @Test
    fun `GET api health returns 200 with healthy status`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(
                    database = database,
                    databasePath = dbPath,
                    ollamaClient = httpClient,
                    ollamaConfig = ollamaConfig,
                    queueService = deps.queueService,
                    backfillJob = deps.backfillJob
                )
            }
        }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("healthy", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 200 when ollama queue and backfill are all up`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("ready", body["status"]?.jsonPrimitive?.content)
        assertEquals("up", body["ollama"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        val models = body["ollama"]!!.jsonObject["models"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(models.contains("qwen2.5:7b-instruct-q4_K_M"))
        assertTrue(models.contains("bge-m3"))
        assertEquals("up", body["queue"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("ready", body["backfill"]!!.jsonObject["status"]?.jsonPrimitive?.content)
        assertEquals("up", body["database"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 503 with ollama down when tags endpoint fails`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsDown()
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["status"]?.jsonPrimitive?.content)
        assertEquals("down", body["ollama"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 503 with ollama down when a required model is missing`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsMissingModel()
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("down", body["ollama"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready treats an unqualified config model as latest when matching ollama tags`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        // Real Ollama normalizes `ollama pull bge-m3` to `bge-m3:latest` in /api/tags,
        // and likewise returns `qwen2.5:7b-instruct-q4_K_M:latest`-style entries.
        val body = """{"models":[{"name":"qwen2.5:7b-instruct-q4_K_M"},{"name":"bge-m3:latest"}]}"""
        wireMock.stubFor(
            get(urlEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body))
        )
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val respBody = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("up", respBody["ollama"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 503 when backfill is running`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps(
            backfillStatus = BackfillStatus.Running(processed = 10, total = 50),
            backfillIsRunning = false
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("running", body["backfill"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 503 when a reindex is running`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps(
            backfillStatus = BackfillStatus.Completed(5, 0),
            backfillIsRunning = true
        )
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("running", body["backfill"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready returns 503 when queue is not connected`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps(queueConnected = false)
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("down", body["queue"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready caches ollama probe result for 5 seconds`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        // Hit /ready 3 times in rapid succession; only one underlying Ollama
        // probe should have been issued because results are cached for 5 s.
        repeat(3) {
            val r = client.get("/api/health/ready")
            assertEquals(HttpStatusCode.OK, r.status)
        }
        val probeCount = wireMock.countRequestsMatching(
            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/api/tags")).build()
        ).count
        assertEquals(1, probeCount)
    }

    @Test
    fun `ready failure probe is not cached for the full success TTL so recovery is fast`() = testApplication {
        // A failed Ollama probe was previously cached for the same 5 s as a
        // success, so recovery after a brief outage took seconds to show up
        // in /api/health/ready. Failures now use a much shorter cache window.
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsDown()
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        // Seed the cache with a failure.
        val first = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, first.status)

        // Flip the stub to success and wait past the failure cache window.
        wireMock.resetAll()
        stubOllamaTagsOk()
        kotlinx.coroutines.delay(750)

        val second = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.OK, second.status, "readiness recovers within ~1 s of Ollama coming back")
    }

    @Test
    fun `ready returns 503 with database down when the count query throws`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        val deps = readyDeps()
        // Stand up a real Database whose connect() throws, simulating a
        // corrupt-file or locked-WAL scenario. The old response lied with
        // `database.status = "up"` and still flipped `ready = true` when the
        // counts silently fell back to zero, masking the outage.
        val brokenDb = mockk<Database>()
        every { brokenDb.connect() } throws java.sql.SQLException("database is locked")
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(brokenDb, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["status"]?.jsonPrimitive?.content)
        assertEquals("down", body["database"]!!.jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ready reports document and chunk counts from database`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        stubOllamaTagsOk()
        database.connect().use { conn ->
            val docRepo = DocumentRepository(conn)
            docRepo.insert(Document(filename = "a.docx", fileType = "docx", fileSize = 10, fileHash = "h1"))
            docRepo.insert(Document(filename = "b.docx", fileType = "docx", fileSize = 20, fileHash = "h2"))
        }
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/health/ready")
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals(2, body["database"]!!.jsonObject["documents"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, body["database"]!!.jsonObject["chunks"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `stats returns real counts and formatted sizes`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        database.connect().use { conn ->
            val docRepo = DocumentRepository(conn)
            docRepo.insert(Document(filename = "a.docx", fileType = "docx", fileSize = 10, fileHash = "h1"))
            docRepo.insert(Document(filename = "b.docx", fileType = "docx", fileSize = 20, fileHash = "h2"))
            docRepo.insert(Document(filename = "c.docx", fileType = "docx", fileSize = 30, fileHash = "h3"))
        }
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/stats")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals(3, body["documents"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, body["chunks"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, body["images"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1024, body["embeddingDimension"]?.jsonPrimitive?.content?.toInt())
        val dbSize = body["databaseSize"]?.jsonPrimitive?.content ?: ""
        assertTrue(dbSize.endsWith(" MB"), "databaseSize should end in MB: $dbSize")
        val uptime = body["uptime"]?.jsonPrimitive?.content ?: ""
        assertTrue(Regex("""\d+d \d+h \d+m""").matches(uptime), "uptime should match Nd Nh Nm: $uptime")
    }

    @Test
    fun `stats reports zero counts for empty database`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val deps = readyDeps()
        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(database, dbPath, httpClient, ollamaConfig, deps.queueService, deps.backfillJob)
            }
        }
        val response = client.get("/api/stats")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals(0, body["documents"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, body["chunks"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, body["images"]?.jsonPrimitive?.content?.toInt())
    }
}
