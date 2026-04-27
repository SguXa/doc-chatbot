package com.aos.chatbot

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aos.chatbot.config.AppMode
import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.routes.healthRoutes
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.QueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: String
    private lateinit var database: Database
    private lateinit var healthHttpClient: HttpClient
    private lateinit var unreachableOllama: OllamaConfig

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("apptest")
        Files.createDirectories(tempDir.resolve("documents"))
        Files.createDirectories(tempDir.resolve("images"))
        dbPath = tempDir.resolve("aos.db").toString()
        database = Database(dbPath)
        database.connect().use { Migrations(it).apply() }
        healthHttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        unreachableOllama = OllamaConfig(
            url = "http://127.0.0.1:65535",
            llmModel = "qwen2.5",
            embedModel = "bge-m3"
        )
    }

    @AfterEach
    fun tearDown() {
        healthHttpClient.close()
        runCatching {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Build a config that points Ollama and Artemis at a port that nothing
     * listens on. Both clients fail fast on connection refused, which lets
     * [Application.module] come up cleanly under test without waiting on
     * real external dependencies.
     *
     * In FULL/ADMIN modes the auth fail-fast requires a non-empty
     * `JWT_SECRET` (>= 32 chars) and `ADMIN_PASSWORD`; defaults provided
     * here satisfy that. In CLIENT mode the values are ignored and may be
     * overridden to empty by passing the relevant args.
     */
    private fun bootConfig(
        mode: String = "full",
        jwtSecret: String = "test-jwt-secret-padding-padding-padding",
        adminPassword: String = "test-admin-password"
    ): MapApplicationConfig {
        val cfg = MapApplicationConfig()
        cfg.put("ktor.deployment.port", "8080")
        cfg.put("ktor.deployment.host", "0.0.0.0")
        cfg.put("app.mode", mode)
        cfg.put("app.database.path", dbPath)
        cfg.put("app.data.path", tempDir.toString())
        cfg.put("app.paths.documents", tempDir.resolve("documents").toString())
        cfg.put("app.paths.images", tempDir.resolve("images").toString())
        cfg.put("app.ollama.url", "http://127.0.0.1:65535")
        cfg.put("app.ollama.llmModel", "qwen2.5")
        cfg.put("app.ollama.embedModel", "bge-m3")
        cfg.put("app.artemis.brokerUrl", "tcp://127.0.0.1:65535")
        cfg.put("app.artemis.user", "")
        cfg.put("app.artemis.password", "")
        cfg.put("app.auth.jwtSecret", jwtSecret)
        cfg.put("app.auth.adminPassword", adminPassword)
        return cfg
    }

    @Test
    fun `application boots through module and health endpoint responds 200`() = testApplication {
        environment { config = bootConfig(mode = "full") }
        application { module() }

        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `api health ready returns 503 when backfill is running`() = testApplication {
        environment { config = MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { queueService.isConnected() } returns true
        every { backfillJob.isRunning() } returns true
        every { backfillJob.status() } returns BackfillStatus.Running(processed = 5, total = 10)

        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(
                    database = database,
                    databasePath = dbPath,
                    ollamaClient = healthHttpClient,
                    ollamaConfig = unreachableOllama,
                    queueService = queueService,
                    backfillJob = backfillJob
                )
            }
        }

        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["status"]?.jsonPrimitive?.content)
        assertEquals(
            "running",
            body["backfill"]?.jsonObject?.get("status")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `api health ready returns 200 when deps are up and backfill completed`() = testApplication {
        environment { config = MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { queueService.isConnected() } returns true
        every { backfillJob.isRunning() } returns false
        every { backfillJob.status() } returns BackfillStatus.Completed(embedded = 3, skipped = 0)

        // Stub Ollama /api/tags with WireMock (same pattern as HealthRoutesTest).
        val wireMock = com.github.tomakehurst.wiremock.WireMockServer(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig().dynamicPort()
        )
        wireMock.start()
        wireMock.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/tags")
            ).willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"models":[{"name":"qwen2.5"},{"name":"bge-m3"}]}""")
            )
        )
        val reachableOllama = OllamaConfig(
            url = "http://localhost:${wireMock.port()}",
            llmModel = "qwen2.5",
            embedModel = "bge-m3"
        )

        try {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    healthRoutes(
                        database = database,
                        databasePath = dbPath,
                        ollamaClient = healthHttpClient,
                        ollamaConfig = reachableOllama,
                        queueService = queueService,
                        backfillJob = backfillJob
                    )
                }
            }

            val response = client.get("/api/health/ready")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
            assertEquals("ready", body["status"]?.jsonPrimitive?.content)
            assertEquals(
                "ready",
                body["backfill"]?.jsonObject?.get("status")?.jsonPrimitive?.content
            )
        } finally {
            wireMock.stop()
        }
    }

    @Test
    fun `registerModeGatedRoutes wires chat route in FULL mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.FULL,
                    chatRegistrar = { get("/api/chat") { call.respond(HttpStatusCode.OK, "chat") } }
                )
            }
        }
        val response = client.get("/api/chat")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `registerModeGatedRoutes wires chat route in CLIENT mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.CLIENT,
                    chatRegistrar = { get("/api/chat") { call.respond(HttpStatusCode.OK, "chat") } }
                )
            }
        }
        val response = client.get("/api/chat")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `registerModeGatedRoutes returns 404 for chat route in ADMIN mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.ADMIN,
                    chatRegistrar = {
                        error("chat registrar must not run under ADMIN mode")
                    }
                )
            }
        }
        val response = client.get("/api/chat")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `registerModeGatedRoutes wires admin routes in FULL mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.FULL,
                    adminRegistrar = {
                        get("/api/admin/ping") { call.respond(HttpStatusCode.OK, "admin") }
                    }
                )
            }
        }
        val response = client.get("/api/admin/ping")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `registerModeGatedRoutes wires admin routes in ADMIN mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.ADMIN,
                    adminRegistrar = {
                        get("/api/admin/ping") { call.respond(HttpStatusCode.OK, "admin") }
                    }
                )
            }
        }
        val response = client.get("/api/admin/ping")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `registerModeGatedRoutes returns 404 for admin routes in CLIENT mode`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(ContentNegotiation) { json() }
            routing {
                registerModeGatedRoutes(
                    mode = AppMode.CLIENT,
                    adminRegistrar = {
                        error("admin registrar must not run under CLIENT mode")
                    }
                )
            }
        }
        val response = client.get("/api/admin/ping")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `application fails to start in FULL mode when JWT_SECRET is missing`() = testApplication {
        environment { config = bootConfig(mode = "full", jwtSecret = "") }
        application { module() }
        val ex = assertFailsWith<IllegalArgumentException> { startApplication() }
        assertTrue(
            ex.message?.contains("JWT_SECRET") == true,
            "expected message to mention JWT_SECRET, got: ${ex.message}"
        )
    }

    @Test
    fun `application fails to start in FULL mode when JWT_SECRET is shorter than 32 chars`() = testApplication {
        environment { config = bootConfig(mode = "full", jwtSecret = "short-secret") }
        application { module() }
        val ex = assertFailsWith<IllegalArgumentException> { startApplication() }
        assertTrue(
            ex.message?.contains("JWT_SECRET") == true,
            "expected message to mention JWT_SECRET, got: ${ex.message}"
        )
    }

    @Test
    fun `application fails to start in ADMIN mode when JWT_SECRET is missing`() = testApplication {
        environment { config = bootConfig(mode = "admin", jwtSecret = "") }
        application { module() }
        val ex = assertFailsWith<IllegalArgumentException> { startApplication() }
        assertTrue(
            ex.message?.contains("JWT_SECRET") == true,
            "expected message to mention JWT_SECRET, got: ${ex.message}"
        )
    }

    @Test
    fun `application fails to start in FULL mode when ADMIN_PASSWORD is missing`() = testApplication {
        environment { config = bootConfig(mode = "full", adminPassword = "") }
        application { module() }
        val ex = assertFailsWith<IllegalArgumentException> { startApplication() }
        assertTrue(
            ex.message?.contains("ADMIN_PASSWORD") == true,
            "expected message to mention ADMIN_PASSWORD, got: ${ex.message}"
        )
    }

    @Test
    fun `application fails to start in ADMIN mode when ADMIN_PASSWORD is missing`() = testApplication {
        environment { config = bootConfig(mode = "admin", adminPassword = "") }
        application { module() }
        val ex = assertFailsWith<IllegalArgumentException> { startApplication() }
        assertTrue(
            ex.message?.contains("ADMIN_PASSWORD") == true,
            "expected message to mention ADMIN_PASSWORD, got: ${ex.message}"
        )
    }

    @Test
    fun `application starts cleanly in CLIENT mode without auth env vars`() = testApplication {
        environment { config = bootConfig(mode = "client", jwtSecret = "", adminPassword = "") }
        application { module() }

        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `pre-auth WARN line is no longer emitted in any mode`() {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = rootLogger.loggerContext
            start()
        }
        rootLogger.addAppender(appender)
        try {
            for (mode in listOf("full", "admin", "client")) {
                appender.list.clear()
                testApplication {
                    environment { config = bootConfig(mode = mode) }
                    application { module() }
                    client.get("/api/health")
                }
                val warnHit = appender.list.any { event ->
                    event.formattedMessage.contains("Admin routes are unprotected")
                }
                assertFalse(warnHit, "WARN line should not be emitted in MODE=$mode")
            }
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `admin route returns 401 without token in FULL mode`() = testApplication {
        environment { config = bootConfig(mode = "full") }
        application { module() }

        val response = client.get("/api/admin/documents")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin route returns 404 in CLIENT mode regardless of token`() = testApplication {
        environment { config = bootConfig(mode = "client", jwtSecret = "", adminPassword = "") }
        application { module() }

        val response = client.get("/api/admin/documents")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
