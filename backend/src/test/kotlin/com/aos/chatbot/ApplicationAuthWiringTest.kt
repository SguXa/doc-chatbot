package com.aos.chatbot

import com.aos.chatbot.config.JwtConfig
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.routes.dto.InvalidLoginResponse
import com.aos.chatbot.routes.dto.LoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-cutting auth wiring coverage for Phase 4 (ADR 0005 step 4):
 * proves every protected admin route returns 401 without a valid token,
 * and every public route works without one.
 *
 * Boots [Application.module] end-to-end with unreachable Ollama/Artemis
 * (the same pattern as [ApplicationTest]) so admin handlers can return
 * business responses without touching real external services. Not named
 * `*IntegrationTest` and not tagged `@Tag("integration")` — the project
 * reserves those for real-Ollama tests; this exercises only auth wiring
 * and Ktor.
 */
class ApplicationAuthWiringTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: String

    private val jwtSecret = "test-jwt-secret-padding-padding-padding"
    private val differentSecret = "different-jwt-secret-pad-pad-pad-pad-pad"
    private val adminPassword = "test-admin-password"

    /**
     * Admin route inventory. **If you add a new admin route, add it to this
     * list — otherwise this test will not protect it.** Each negative-token
     * case below iterates this list, so the suite automatically covers any
     * new admin endpoint.
     */
    private val adminRoutes: List<Pair<HttpMethod, String>> = listOf(
        HttpMethod.Post to "/api/admin/documents",
        HttpMethod.Get to "/api/admin/documents",
        HttpMethod.Delete to "/api/admin/documents/1",
        HttpMethod.Post to "/api/admin/reindex"
    )

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("apptest-auth")
        Files.createDirectories(tempDir.resolve("documents"))
        Files.createDirectories(tempDir.resolve("images"))
        dbPath = tempDir.resolve("aos.db").toString()
        val database = Database(dbPath)
        database.connect().use { Migrations(it).apply() }
    }

    @AfterEach
    fun tearDown() {
        runCatching {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun bootConfig(
        mode: String = "full",
        jwtSec: String = jwtSecret,
        adminPw: String = adminPassword
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
        cfg.put("app.auth.jwtSecret", jwtSec)
        cfg.put("app.auth.adminPassword", adminPw)
        return cfg
    }

    private suspend fun login(client: HttpClient): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"$adminPassword"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status, "login must succeed: ${resp.bodyAsText()}")
        return Json.decodeFromString<LoginResponse>(resp.bodyAsText()).token
    }

    @Test
    fun `POST auth login with correct password returns 200 with usable token`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val token = login(client)
        assertTrue(token.isNotEmpty(), "token must be non-empty")
    }

    @Test
    fun `every admin endpoint without Authorization header returns 401`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) { this.method = method }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "expected 401 for $method $path, got ${resp.status}: ${resp.bodyAsText()}"
            )
        }
    }

    @Test
    fun `every admin endpoint with expired token returns 401`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        // Same secret as the running app, but issued in the distant past with a 1-day
        // TTL, so signature is valid but `exp` has elapsed.
        val pastClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val expiredToken = JwtConfig(secret = jwtSecret, clock = pastClock).sign()

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $expiredToken")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "expected 401 for $method $path with expired token, got ${resp.status}: ${resp.bodyAsText()}"
            )
        }
    }

    @Test
    fun `every admin endpoint with garbage token returns 401`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer not.a.jwt")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "expected 401 for $method $path with garbage token, got ${resp.status}: ${resp.bodyAsText()}"
            )
        }
    }

    @Test
    fun `every admin endpoint with token signed by different secret returns 401`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val foreignToken = JwtConfig(secret = differentSecret).sign()

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $foreignToken")
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "expected 401 for $method $path with foreign-secret token, got ${resp.status}: ${resp.bodyAsText()}"
            )
        }
    }

    @Test
    fun `every admin endpoint with valid token returns NOT 401`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val token = login(client)
        for ((method, path) in adminRoutes) {
            val resp = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertNotEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "$method $path with valid token must not be 401 (business response is fine), got: ${resp.bodyAsText()}"
            )
        }
    }

    @Test
    fun `GET api health returns 200 without any token`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val resp = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `GET api health ready never returns 401 without token`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val resp = client.get("/api/health/ready")
        assertNotEquals(HttpStatusCode.Unauthorized, resp.status)
        // Ollama is unreachable in this test, so the realistic outcome is 503;
        // accept 200 as well to stay forward-compatible with future readiness tweaks.
        assertTrue(
            resp.status == HttpStatusCode.OK || resp.status == HttpStatusCode.ServiceUnavailable,
            "expected 200 or 503, got ${resp.status}"
        )
    }

    @Test
    fun `POST api chat never returns 401 without token`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val resp = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello"}""")
        }
        assertNotEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "chat must never return 401: ${resp.bodyAsText()}"
        )
    }

    @Test
    fun `POST auth login with wrong password returns auth-domain 401 with invalid_credentials body`() = testApplication {
        // The JWT challenge handler returns 401 with no body; the auth-domain
        // login failure returns 401 with InvalidLoginResponse. Same status,
        // different body — assert the body discriminator so a future change
        // that swaps the two surfaces is caught.
        environment { config = bootConfig() }
        application { module() }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"wrong-password"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        val body = Json.decodeFromString<InvalidLoginResponse>(resp.bodyAsText())
        assertEquals("invalid_credentials", body.error)
    }

    @Test
    fun `POST auth logout returns 204 without token`() = testApplication {
        environment { config = bootConfig() }
        application { module() }

        val resp = client.post("/api/auth/logout")
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    @Test
    fun `POST api chat with bogus Authorization header is still processed (regression guard)`() = testApplication {
        // Catches a future change that accidentally wires chat into
        // authenticate("jwt-admin"): with that mistake, this test would
        // fail with 401 instead of the expected business response.
        environment { config = bootConfig() }
        application { module() }

        val resp = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer not.a.jwt")
            setBody("""{"message":"hello"}""")
        }
        assertNotEquals(
            HttpStatusCode.Unauthorized,
            resp.status,
            "chat must ignore Authorization header entirely: ${resp.bodyAsText()}"
        )
    }

    @Test
    fun `in CLIENT mode auth login returns 404 and admin routes return 404`() = testApplication {
        environment { config = bootConfig(mode = "client", jwtSec = "", adminPw = "") }
        application { module() }

        val loginResp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, loginResp.status)

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) { this.method = method }
            assertEquals(
                HttpStatusCode.NotFound,
                resp.status,
                "$method $path must be 404 in CLIENT mode, got ${resp.status}"
            )
        }
    }

    @Test
    fun `in ADMIN mode chat returns 404 and admin routes are auth-gated`() = testApplication {
        environment { config = bootConfig(mode = "admin") }
        application { module() }

        val chatResp = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello"}""")
        }
        assertEquals(HttpStatusCode.NotFound, chatResp.status)

        for ((method, path) in adminRoutes) {
            val resp = client.request(path) { this.method = method }
            assertEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "no-token $method $path in ADMIN mode must be 401, got ${resp.status}"
            )
        }

        val token = login(client)
        for ((method, path) in adminRoutes) {
            val resp = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertNotEquals(
                HttpStatusCode.Unauthorized,
                resp.status,
                "with-token $method $path in ADMIN mode must not be 401: ${resp.bodyAsText()}"
            )
        }
    }
}
