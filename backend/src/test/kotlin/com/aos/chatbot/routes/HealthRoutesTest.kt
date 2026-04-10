package com.aos.chatbot.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import io.mockk.every
import io.mockk.mockk

class HealthRoutesTest {

    @Test
    fun `GET api health returns 200 with healthy status`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        val mockConnection = mockk<Connection>()

        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(mockConnection)
            }
        }

        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("healthy", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET api health ready returns 200 when database is available`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        val mockConnection = mockk<Connection>()
        every { mockConnection.isValid(any()) } returns true

        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(mockConnection)
            }
        }

        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("ready", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET api health ready returns 503 when database is unavailable`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        val mockConnection = mockk<Connection>()
        every { mockConnection.isValid(any()) } returns false

        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(mockConnection)
            }
        }

        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("unavailable", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET api health ready returns 503 when database throws exception`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig()
        }
        val mockConnection = mockk<Connection>()
        every { mockConnection.isValid(any()) } throws RuntimeException("DB error")

        application {
            install(ContentNegotiation) { json() }
            routing {
                healthRoutes(mockConnection)
            }
        }

        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("unavailable", body["status"]?.jsonPrimitive?.content)
    }
}
