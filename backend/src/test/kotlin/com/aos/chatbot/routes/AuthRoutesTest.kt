package com.aos.chatbot.routes

import com.aos.chatbot.config.AuthConfig
import com.aos.chatbot.config.JwtConfig
import com.aos.chatbot.routes.dto.InvalidLoginResponse
import com.aos.chatbot.routes.dto.InvalidRequestResponse
import com.aos.chatbot.routes.dto.LoginResponse
import com.aos.chatbot.services.AuthService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutesTest {

    private val secret32 = "0123456789abcdef0123456789abcdef"
    private val adminPassword = "S3cr3t!-correct horse battery staple"
    private val ttlSeconds = 86_400L

    private fun newAuthService(): AuthService {
        val jwt = JwtConfig(secret = secret32)
        return AuthService(
            authConfig = AuthConfig(jwtSecret = secret32, adminPassword = adminPassword),
            jwtConfig = jwt
        )
    }

    @Test
    fun `POST login with correct password returns 200 LoginResponse with token`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"$adminPassword"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<LoginResponse>(response.bodyAsText())
        assertTrue(body.token.isNotEmpty(), "token must be non-empty")
        assertEquals(86_400L, body.expiresIn)
        assertEquals("admin", body.user.username)
        assertEquals("admin", body.user.role)
    }

    @Test
    fun `POST login with wrong password returns 401 InvalidLoginResponse`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"wrong-password"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = Json.decodeFromString<InvalidLoginResponse>(response.bodyAsText())
        assertEquals("invalid_credentials", body.error)
    }

    @Test
    fun `POST login with empty password returns 400 InvalidRequestResponse with empty_password reason`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":""}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<InvalidRequestResponse>(response.bodyAsText())
        assertEquals("invalid_request", body.error)
        assertEquals("empty_password", body.reason)
    }

    @Test
    fun `POST login with malformed JSON body returns 400 InvalidRequestResponse with malformed_body reason`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{not valid json""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<InvalidRequestResponse>(response.bodyAsText())
        assertEquals("invalid_request", body.error)
        assertEquals("malformed_body", body.reason)
    }

    @Test
    fun `POST login with non-admin username but correct password returns 200 username is ignored`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"someone-else","password":"$adminPassword"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<LoginResponse>(response.bodyAsText())
        assertTrue(body.token.isNotEmpty())
        assertEquals("admin", body.user.username)
        assertEquals("admin", body.user.role)
    }

    @Test
    fun `POST logout returns 204 with empty body`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val authService = newAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { authRoutes(authService, ttlSeconds) }
        }

        val response = client.post("/api/auth/logout")

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("", response.bodyAsText())
    }
}
