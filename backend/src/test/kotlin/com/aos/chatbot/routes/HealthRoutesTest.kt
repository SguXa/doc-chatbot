package com.aos.chatbot.routes

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {

    @Test
    fun `GET api health returns 200 with healthy status`() = testApplication {
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("healthy", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET api health ready returns 200 when database is available`() = testApplication {
        val response = client.get("/api/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("ready", body["status"]?.jsonPrimitive?.content)
    }
}
