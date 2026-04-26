package com.aos.chatbot.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.embeddingToBytes
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.ChatMessage
import com.aos.chatbot.models.ChatRequest
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.Document
import com.aos.chatbot.models.QueueEvent
import com.aos.chatbot.models.SearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatServiceTest {

    private lateinit var tempDir: Path
    private lateinit var database: Database
    private var documentId: Long = 0

    private lateinit var queueService: QueueService
    private lateinit var embeddingService: EmbeddingService
    private lateinit var searchService: SearchService
    private lateinit var llmService: LlmService
    private lateinit var responseBus: ChatResponseBus

    private lateinit var service: ChatService

    private val fakeEmbedding: FloatArray = FloatArray(8) { it.toFloat() }

    private lateinit var logAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("chat-service-test")
        val dbPath = tempDir.resolve("test.db").toString()
        database = Database(dbPath)
        database.connect().use { conn ->
            Migrations(conn).apply()
            val docRepo = DocumentRepository(conn)
            documentId = docRepo.insert(
                Document(
                    filename = "handbook.docx",
                    fileType = "docx",
                    fileSize = 100,
                    fileHash = "h1"
                )
            ).id
        }

        queueService = mockk(relaxed = true)
        embeddingService = mockk()
        searchService = SearchService()
        llmService = mockk()
        responseBus = ChatResponseBus()

        service = ChatService(
            queueService = queueService,
            embeddingService = embeddingService,
            searchService = searchService,
            llmService = llmService,
            database = database,
            responseBus = responseBus
        )

        // Attach list appender to capture structured completion logs.
        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        val chatLogger = LoggerFactory.getLogger(ChatService::class.java) as Logger
        chatLogger.addAppender(logAppender)
        chatLogger.level = Level.INFO
    }

    @AfterEach
    fun tearDown() {
        val chatLogger = LoggerFactory.getLogger(ChatService::class.java) as Logger
        chatLogger.detachAppender(logAppender)
        if (Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun newRequest(
        message: String = "What is MA-42?",
        history: List<ChatMessage> = emptyList(),
        correlationId: String = "corr-${System.nanoTime()}"
    ): ChatRequest = ChatRequest(
        correlationId = correlationId,
        message = message,
        history = history,
        enqueuedAt = "2026-04-21T10:00:00Z"
    )

    private fun insertChunkInIndex(
        content: String,
        sectionId: String? = "3.2",
        pageNumber: Int? = 42
    ): Chunk {
        val bytes = embeddingToBytes(FloatArray(8) { 1f })
        val inserted: Chunk
        database.connect().use { conn ->
            val repo = ChunkRepository(conn)
            repo.insertBatch(
                listOf(
                    Chunk(
                        documentId = documentId,
                        content = content,
                        contentType = "text",
                        sectionId = sectionId,
                        pageNumber = pageNumber,
                        embedding = bytes
                    )
                )
            )
            inserted = repo.findByDocumentId(documentId).last()
        }
        searchService.loadInitial(listOf(inserted))
        return inserted
    }

    @Test
    fun `happy path emits processing token sources and done in order`() = runBlocking {
        insertChunkInIndex("MA-42 indicates sensor fault.")
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        coEvery { embeddingService.embed(request.message) } returns fakeEmbedding
        every { llmService.generate(any()) } returns flow {
            emit("The ")
            emit("answer ")
            emit("is 42.")
        }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }

        // 3 Processing + 3 Token + 1 Sources + 1 Done = 8 events in that order.
        assertEquals(8, events.size)
        assertTrue(events[0] is QueueEvent.Processing)
        assertEquals("Embedding query...", (events[0] as QueueEvent.Processing).status)
        assertTrue(events[1] is QueueEvent.Processing)
        assertEquals("Searching documents...", (events[1] as QueueEvent.Processing).status)
        assertTrue(events[2] is QueueEvent.Processing)
        assertEquals("Generating response...", (events[2] as QueueEvent.Processing).status)
        assertEquals("The ", (events[3] as QueueEvent.Token).text)
        assertEquals("answer ", (events[4] as QueueEvent.Token).text)
        assertEquals("is 42.", (events[5] as QueueEvent.Token).text)
        val sources = events[6] as QueueEvent.Sources
        assertEquals(1, sources.sources.size)
        val source = sources.sources[0]
        assertEquals(documentId, source.documentId)
        assertEquals("handbook.docx", source.documentName)
        assertEquals("3.2", source.section)
        assertEquals(42, source.page)
        assertTrue(source.snippet.startsWith("MA-42"))
        val done = events[7] as QueueEvent.Done
        assertEquals(3, done.totalTokens)

        assertTrue(responseBus.isOrphaned(request.correlationId))
    }

    @Test
    fun `completion log line is emitted before Done event`() = runBlocking {
        insertChunkInIndex("content")
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } returns flow { emit("hi") }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }
        val doneIndex = events.indexOfFirst { it is QueueEvent.Done }
        assertTrue(doneIndex >= 0, "Done event must be present")

        val chatCompletedLogs = logAppender.list.filter {
            it.formattedMessage.contains("\"event\":\"chat_completed\"")
        }
        assertEquals(1, chatCompletedLogs.size, "exactly one chat_completed log line")
        val line = chatCompletedLogs.single().formattedMessage

        // The log line must carry the correlationId, question, answer, sources,
        // and timing fields — this is the feedback anchor for future phases.
        assertTrue(line.contains(request.correlationId))
        assertTrue(line.contains("\"question\":\"What is MA-42?\""))
        assertTrue(line.contains("\"answer\":\"hi\""))
        assertTrue(line.contains("\"tokenCount\":1"))
        assertTrue(line.contains("\"chunksRetrieved\":1"))
    }

    @Test
    fun `orphaned request emits nothing and skips LLM call`() = runBlocking {
        val request = newRequest()
        // Never open() — bus is orphaned for this correlationId.
        assertTrue(responseBus.isOrphaned(request.correlationId))

        service.handle(request)

        coVerify(exactly = 0) { embeddingService.embed(any()) }
        coVerify(exactly = 0) { llmService.generate(any()) }
    }

    @Test
    fun `OllamaUnavailableException mid-stream emits Error and closes bus`() = runBlocking {
        insertChunkInIndex("content")
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } returns flow {
            emit("ok ")
            throw OllamaUnavailableException("connection reset mid-stream")
        }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }
        assertTrue(events.last() is QueueEvent.Error)
        assertEquals("LLM stream interrupted", (events.last() as QueueEvent.Error).message)
        assertTrue(responseBus.isOrphaned(request.correlationId), "bus closed after error")

        // No Done event in the stream.
        assertFalse(events.any { it is QueueEvent.Done })
        // Token emitted before the error remains visible to the client.
        assertTrue(events.any { it is QueueEvent.Token && it.text == "ok " })
    }

    @Test
    fun `raw IOException mid-stream emits LLM stream interrupted not Internal error`() = runBlocking {
        // LlmService.generate intentionally propagates post-stream-start
        // failures unchanged (see its KDoc). ChatService must still surface
        // those as "LLM stream interrupted" so operators see a consistent
        // Ollama-down signal instead of the generic "Internal error".
        insertChunkInIndex("content")
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } returns flow {
            emit("partial ")
            throw java.io.IOException("socket closed mid-stream")
        }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }
        val error = events.last() as QueueEvent.Error
        assertEquals("LLM stream interrupted", error.message)
        assertFalse(events.any { it is QueueEvent.Done })
    }

    @Test
    fun `consumer survives after error and handles next request`() = runBlocking {
        insertChunkInIndex("content")

        val failing = newRequest(message = "first")
        val good = newRequest(message = "second")
        responseBus.open(failing.correlationId)
        val goodReceiver = responseBus.open(good.correlationId)

        coEvery { embeddingService.embed(any()) } returns fakeEmbedding

        // First call blows up mid-stream, second call succeeds.
        var call = 0
        every { llmService.generate(any()) } answers {
            call++
            if (call == 1) {
                flow<String> { throw OllamaUnavailableException("boom") }
            } else {
                flow { emit("pong") }
            }
        }

        service.handle(failing)
        service.handle(good)

        val secondEvents = withTimeout(5_000) { goodReceiver.toList() }
        assertTrue(secondEvents.any { it is QueueEvent.Done }, "second request completed normally")
        assertTrue(secondEvents.any { it is QueueEvent.Token && it.text == "pong" })
    }

    @Test
    fun `zero search hits still invokes LLM and emits empty Sources`() = runBlocking {
        // Index is empty — SearchService returns no hits.
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        val capturedMessages = mutableListOf<List<ChatMessage>>()
        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } answers {
            capturedMessages.add(firstArg())
            flow { emit("no-data-response") }
        }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }
        val sources = events.filterIsInstance<QueueEvent.Sources>().single()
        assertTrue(sources.sources.isEmpty())
        assertTrue(events.any { it is QueueEvent.Done })
        assertEquals(1, capturedMessages.size, "LLM was still invoked")

        val systemMessage = capturedMessages.single().first { it.role == "system" }
        assertTrue(
            systemMessage.content.contains("(no relevant context retrieved)"),
            "system prompt signals empty context block"
        )
    }

    @Test
    fun `page-null chunks render without page suffix and do not crash`() = runBlocking {
        insertChunkInIndex("Pageless content.", sectionId = "5.1", pageNumber = null)
        val request = newRequest()
        val receiver = responseBus.open(request.correlationId)

        val capturedMessages = mutableListOf<List<ChatMessage>>()
        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } answers {
            capturedMessages.add(firstArg())
            flow { emit("token") }
        }

        service.handle(request)

        val events = withTimeout(5_000) { receiver.toList() }
        val sources = events.filterIsInstance<QueueEvent.Sources>().single()
        assertEquals(1, sources.sources.size)
        assertNull(sources.sources[0].page)
        assertEquals("5.1", sources.sources[0].section)

        val systemContent = capturedMessages.single().first { it.role == "system" }.content
        assertTrue(systemContent.contains("section 5.1"), "section rendered when present")
        assertFalse(systemContent.contains("page null"), "null page is omitted entirely")
        assertFalse(systemContent.contains(", page "), "no page suffix for null-page chunks")
    }

    @Test
    fun `history from request is forwarded to LLM between system and user`() = runBlocking {
        insertChunkInIndex("content")
        val history = listOf(
            ChatMessage(role = "user", content = "previous question"),
            ChatMessage(role = "assistant", content = "previous answer")
        )
        val request = newRequest(history = history)
        responseBus.open(request.correlationId)

        val captured = mutableListOf<List<ChatMessage>>()
        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } answers {
            captured.add(firstArg())
            emptyFlow()
        }

        service.handle(request)

        val messages = captured.single()
        assertEquals("system", messages.first().role)
        assertEquals("previous question", messages[1].content)
        assertEquals("previous answer", messages[2].content)
        assertEquals("user", messages.last().role)
        assertEquals(request.message, messages.last().content)
    }

    @Test
    fun `system prompt is read from the config table`() = runBlocking {
        val customPrompt = "Custom prompt from config"
        val encoded = Json.encodeToString(String.serializer(), customPrompt)
        database.connect().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO config(key, value, updated_at) VALUES('system_prompt', ?, CURRENT_TIMESTAMP)"
            ).use { stmt ->
                stmt.setString(1, encoded)
                stmt.executeUpdate()
            }
        }

        insertChunkInIndex("content")
        val request = newRequest()
        responseBus.open(request.correlationId)

        val captured = mutableListOf<List<ChatMessage>>()
        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } answers {
            captured.add(firstArg())
            emptyFlow()
        }

        service.handle(request)

        val systemContent = captured.single().first { it.role == "system" }.content
        assertTrue(systemContent.startsWith(customPrompt), "custom prompt appears at top of system message")
    }

    @Test
    fun `search invoked with topK 5 and minScore 0_3`() = runBlocking {
        val searchSpy = mockk<SearchService>()
        every { searchSpy.search(any(), any(), any()) } returns emptyList<SearchResult>()

        service = ChatService(
            queueService = queueService,
            embeddingService = embeddingService,
            searchService = searchSpy,
            llmService = llmService,
            database = database,
            responseBus = responseBus
        )

        val request = newRequest()
        responseBus.open(request.correlationId)
        coEvery { embeddingService.embed(any()) } returns fakeEmbedding
        every { llmService.generate(any()) } returns emptyFlow()

        service.handle(request)

        io.mockk.verify { searchSpy.search(fakeEmbedding, topK = 5, minScore = 0.3f) }
    }
}
