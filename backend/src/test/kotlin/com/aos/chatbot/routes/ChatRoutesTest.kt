package com.aos.chatbot.routes

import com.aos.chatbot.models.ChatMessage
import com.aos.chatbot.models.ChatRequest
import com.aos.chatbot.models.QueueEvent
import com.aos.chatbot.models.Source
import com.aos.chatbot.services.BackfillStatus
import com.aos.chatbot.services.ChatResponseBus
import com.aos.chatbot.services.EmbeddingBackfillJob
import com.aos.chatbot.services.QueueService
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatRoutesTest {

    private fun preSeededChannel(vararg events: QueueEvent): ReceiveChannel<QueueEvent> {
        val channel = Channel<QueueEvent>(Channel.UNLIMITED)
        for (event in events) channel.trySend(event)
        channel.close()
        return channel
    }

    @Test
    fun `happy path streams queued and all bus events in order`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(embedded = 0, skipped = 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(
            QueueEvent.Processing("Embedding query..."),
            QueueEvent.Token("hello"),
            QueueEvent.Token(" world"),
            QueueEvent.Sources(listOf(Source(1L, "doc.pdf", "1.1", 3, "snippet"))),
            QueueEvent.Done(totalTokens = 2)
        )
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"Hello"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.headers["Content-Type"]?.substringBefore(";"))

        val body = response.bodyAsText()
        val queuedIdx = body.indexOf("event: queued")
        val processingIdx = body.indexOf("event: processing")
        val firstTokenIdx = body.indexOf("event: token")
        val sourcesIdx = body.indexOf("event: sources")
        val doneIdx = body.indexOf("event: done")

        assertTrue(queuedIdx >= 0, "queued event missing:\n$body")
        assertTrue(processingIdx > queuedIdx, "processing must follow queued")
        assertTrue(firstTokenIdx > processingIdx, "token must follow processing")
        assertTrue(sourcesIdx > firstTokenIdx, "sources must follow tokens")
        assertTrue(doneIdx > sourcesIdx, "done must follow sources")

        assertTrue(body.contains("\"position\":0"))
        assertTrue(body.contains("\"estimatedWait\":0"))
        assertTrue(body.contains("\"text\":\"hello\""))
        assertTrue(body.contains("\"text\":\" world\""))
        assertTrue(body.contains("\"totalTokens\":2"))

        verify(exactly = 1) { responseBus.close(any()) }
    }

    @Test
    fun `empty message returns 400 invalid_request`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("empty_message", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `history size 21 returns 400 history_too_long`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val history = (1..21).joinToString(",") {
            """{"role":"user","content":"msg $it"}"""
        }
        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi","history":[$history]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("history_too_long", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `message over 4000 chars returns 400 message_too_long`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val oversize = "x".repeat(4001)
        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"$oversize"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("message_too_long", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `history entry over 4000 chars returns 400 history_entry_too_long`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val oversize = "y".repeat(4001)
        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi","history":[{"role":"user","content":"$oversize"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("history_entry_too_long", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `history role other than user or assistant returns 400 invalid_history_role`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi","history":[{"role":"system","content":"bad"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("invalid_history_role", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `malformed json body returns 400 malformed_body`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi",,,}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("invalid_request", body["error"]?.jsonPrimitive?.content)
        assertEquals("malformed_body", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `history size 20 is accepted`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(QueueEvent.Done(0))
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val history = (1..20).joinToString(",") {
            """{"role":"user","content":"msg $it"}"""
        }
        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi","history":[$history]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `backfill running returns 503 with retry-after`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Running(processed = 2, total = 10)

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("10", response.headers["Retry-After"])
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["error"]?.jsonPrimitive?.content)
        assertEquals("embedding_backfill_in_progress", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
        verify(exactly = 0) { responseBus.open(any()) }
    }

    @Test
    fun `backfill Failed returns 503 backfill_failed without retry-after`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Failed("Ollama embedding dim changed")

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        // Failed is terminal — no Retry-After, because retrying won't help.
        assertEquals(null, response.headers["Retry-After"])
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["error"]?.jsonPrimitive?.content)
        assertEquals("embedding_backfill_failed", body["reason"]?.jsonPrimitive?.content)

        coVerify(exactly = 0) { queueService.enqueue(any()) }
    }

    @Test
    fun `backfill idle also blocks request`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()
        every { backfillJob.status() } returns BackfillStatus.Idle

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("not_ready", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `enqueue failure returns 503 queue_unavailable before SSE`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        every { responseBus.open(any()) } returns Channel<QueueEvent>(Channel.UNLIMITED)
        every { responseBus.close(any()) } returns Unit
        coEvery { queueService.enqueue(any()) } throws
            IllegalStateException("Artemis queue unavailable: service not started")

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
        assertEquals("queue_unavailable", body["error"]?.jsonPrimitive?.content)

        verify(exactly = 1) { responseBus.close(any()) }
    }

    @Test
    fun `getPosition returns -1 clamps to 0 in queued event`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns -1
        every { responseBus.open(any()) } returns preSeededChannel(QueueEvent.Done(0))
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"position\":0"), "expected clamped position=0 in $body")
        assertTrue(body.contains("\"estimatedWait\":0"))
    }

    @Test
    fun `error event from bus writes event error and closes stream`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(
            QueueEvent.Processing("Embedding query..."),
            QueueEvent.Error("LLM stream interrupted")
        )
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("event: error"), "error event missing:\n$body")
        assertTrue(body.contains("LLM stream interrupted"))
    }

    @Test
    fun `close is invoked exactly once on normal completion`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(QueueEvent.Done(1))
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        verify(exactly = 1) { responseBus.close(any()) }
    }

    @Test
    fun `route not registered returns 404`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }

        application {
            install(ContentNegotiation) { json() }
            // Simulate MODE=admin: chat routes not registered.
            routing { }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `events emitted synchronously during enqueue all reach client in order`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val bus = ChatResponseBus()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.getPosition(any()) } returns 0
        coEvery { queueService.enqueue(any()) } coAnswers {
            val req = firstArg<ChatRequest>()
            bus.emit(req.correlationId, QueueEvent.Processing("Embedding query..."))
            bus.emit(req.correlationId, QueueEvent.Token("alpha"))
            bus.emit(req.correlationId, QueueEvent.Token("beta"))
            bus.emit(req.correlationId, QueueEvent.Token("gamma"))
            bus.emit(req.correlationId, QueueEvent.Done(3))
            bus.close(req.correlationId)
        }

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, bus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        val alphaIdx = body.indexOf("\"text\":\"alpha\"")
        val betaIdx = body.indexOf("\"text\":\"beta\"")
        val gammaIdx = body.indexOf("\"text\":\"gamma\"")
        val doneIdx = body.indexOf("event: done")

        assertTrue(alphaIdx > 0, "alpha missing:\n$body")
        assertTrue(betaIdx > alphaIdx, "beta must follow alpha")
        assertTrue(gammaIdx > betaIdx, "gamma must follow beta")
        assertTrue(doneIdx > gammaIdx, "done must follow last token")
    }

    @Test
    fun `queued event carries real position and estimated wait`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 3
        every { responseBus.open(any()) } returns preSeededChannel(QueueEvent.Done(0))
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        val body = response.bodyAsText()
        val firstEventLines = body.lineSequence().take(3).toList()
        assertEquals("event: queued", firstEventLines[0])
        assertNotNull(firstEventLines[1])
        assertTrue(firstEventLines[1].startsWith("data: "))
        val queuedJson = Json.decodeFromString<JsonObject>(firstEventLines[1].removePrefix("data: "))
        assertEquals(3, queuedJson["position"]?.jsonPrimitive?.int)
        assertEquals(90, queuedJson["estimatedWait"]?.jsonPrimitive?.int)
    }

    @Test
    fun `sources event contains document citations`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.enqueue(any()) } returns Unit
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(
            QueueEvent.Sources(
                listOf(
                    Source(documentId = 42L, documentName = "install-guide.pdf", section = "3.2", page = 7, snippet = "installation steps"),
                    Source(documentId = 43L, documentName = "troubleshooting.docx", section = null, page = null, snippet = "error code MA-11")
                )
            ),
            QueueEvent.Done(0)
        )
        every { responseBus.close(any()) } returns Unit

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hi"}""")
        }

        val body = response.bodyAsText()
        assertTrue(body.contains("install-guide.pdf"))
        assertTrue(body.contains("troubleshooting.docx"))
        assertTrue(body.contains("\"documentId\":42"))
        assertTrue(body.contains("\"documentId\":43"))
    }

    @Test
    fun `chat body with history is accepted and passes ChatMessage through`() = testApplication {
        environment { config = io.ktor.server.config.MapApplicationConfig() }
        val queueService = mockk<QueueService>()
        val responseBus = mockk<ChatResponseBus>()
        val backfillJob = mockk<EmbeddingBackfillJob>()

        every { backfillJob.status() } returns BackfillStatus.Completed(0, 0)
        every { backfillJob.isRunning() } returns false
        coEvery { queueService.getPosition(any()) } returns 0
        every { responseBus.open(any()) } returns preSeededChannel(QueueEvent.Done(0))
        every { responseBus.close(any()) } returns Unit

        var captured: ChatRequest? = null
        coEvery { queueService.enqueue(any()) } coAnswers {
            captured = firstArg()
        }

        application {
            install(ContentNegotiation) { json() }
            routing { chatRoutes(queueService, responseBus, backfillJob) }
        }

        val response = client.post("/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"message":"follow up","history":[{"role":"user","content":"first"},{"role":"assistant","content":"reply"}]}"""
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val req = captured
        assertNotNull(req)
        assertEquals("follow up", req.message)
        assertEquals(2, req.history.size)
        assertEquals(ChatMessage("user", "first"), req.history[0])
        assertEquals(ChatMessage("assistant", "reply"), req.history[1])
        assertTrue(req.correlationId.isNotBlank())
        assertTrue(req.enqueuedAt.isNotBlank())
    }
}
