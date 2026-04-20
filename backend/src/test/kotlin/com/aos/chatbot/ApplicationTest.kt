package com.aos.chatbot

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun `application starts and health endpoint responds`() = testApplication {
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
