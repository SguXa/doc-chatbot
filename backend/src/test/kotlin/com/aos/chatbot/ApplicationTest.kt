package com.aos.chatbot

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun `application starts and responds`() = testApplication {
        // testApplication auto-loads module from application.conf
        val response = client.get("/")
        // No route defined yet, so 404 is expected — the point is that the app starts
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
