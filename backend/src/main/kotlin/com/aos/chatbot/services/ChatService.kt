package com.aos.chatbot.services

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.repositories.ConfigRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.ChatMessage
import com.aos.chatbot.models.ChatRequest
import com.aos.chatbot.models.QueueEvent
import com.aos.chatbot.models.SearchResult
import com.aos.chatbot.models.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process chat orchestrator. Consumes [ChatRequest] messages from Artemis,
 * runs the RAG pipeline (embed query → vector search → stream LLM), and
 * publishes [QueueEvent] values onto [ChatResponseBus] keyed by the request's
 * `correlationId`.
 *
 * Parallelism is strictly 1 — Ollama is CPU-bound and concurrent LLM calls
 * thrash. A single-permit [Semaphore] guards [handle] even though the queue
 * consumer is already sequential, so future multi-consumer deployments stay
 * safe without a protocol change.
 *
 * Error handling:
 *  - Every exception inside [handle] is caught. The bus receives one terminal
 *    [QueueEvent.Error], the entry is closed, and the consumer coroutine moves
 *    on to the next message. The JMS message is always acknowledged because
 *    the request is dead from the client's perspective — the SSE stream has
 *    already reported the failure.
 *  - Orphaned requests (the SSE route closed its receiver before we started)
 *    short-circuit: no LLM call, no emit, just a log line and return.
 *
 * Backfill gating lives in the chat route (Task 14), not here; the route
 * refuses to enqueue while backfill is running, which keeps the gating check
 * atomic with the pre-SSE 503 response.
 */
class ChatService(
    private val queueService: QueueService,
    private val embeddingService: EmbeddingService,
    private val searchService: SearchService,
    private val llmService: LlmService,
    private val database: Database,
    private val responseBus: ChatResponseBus
) {
    private val logger = LoggerFactory.getLogger(ChatService::class.java)
    private val semaphore = Semaphore(1)

    /**
     * Launches the Artemis consumer loop on [scope]. Each dequeued request
     * runs through [handle] under [semaphore], so LLM work is strictly
     * serialized even if the caller wires multiple consumers in a future
     * phase.
     */
    suspend fun start(scope: CoroutineScope) {
        queueService.consume(scope) { request ->
            semaphore.withPermit { handle(request) }
        }
    }

    /** Orchestrates a single chat request end-to-end. Never throws. */
    suspend fun handle(request: ChatRequest) {
        val correlationId = request.correlationId

        if (responseBus.isOrphaned(correlationId)) {
            logger.info("request abandoned before processing, correlationId={}", correlationId)
            return
        }

        val startMs = System.currentTimeMillis()
        val queueWaitMs = computeQueueWaitMs(request, startMs)
        val tokenCount = AtomicInteger(0)
        val answer = StringBuilder()
        // Only embed and LLM calls throw OllamaUnavailableException; track which
        // one was in flight so the user-visible error isn't misleading.
        var inLlmPhase = false

        try {
            responseBus.emit(correlationId, QueueEvent.Processing("Embedding query..."))
            val queryEmbedding = embeddingService.embed(request.message)

            responseBus.emit(correlationId, QueueEvent.Processing("Searching documents..."))
            val hits: List<SearchResult> =
                searchService.search(queryEmbedding, topK = SEARCH_TOP_K, minScore = SEARCH_MIN_SCORE)

            val systemPrompt = readSystemPrompt()
            val sources = resolveSources(hits)
            val messages = buildMessages(systemPrompt, hits, sources, request.history, request.message)

            inLlmPhase = true
            responseBus.emit(correlationId, QueueEvent.Processing("Generating response..."))
            llmService.generate(messages).collect { token ->
                answer.append(token)
                tokenCount.incrementAndGet()
                responseBus.emit(correlationId, QueueEvent.Token(token))
            }

            val durationMs = System.currentTimeMillis() - startMs

            // The completion log is the feedback anchor — future rating flows
            // will reference the conversation by correlationId. Logging MUST
            // precede the Done event so observers of `Done` can be sure the
            // log line is already flushed.
            logCompleted(
                correlationId = correlationId,
                question = request.message,
                answer = answer.toString(),
                sources = sources,
                chunksRetrieved = hits.size,
                durationMs = durationMs,
                queueWaitMs = queueWaitMs,
                tokenCount = tokenCount.get()
            )

            responseBus.emit(correlationId, QueueEvent.Sources(sources))
            responseBus.emit(correlationId, QueueEvent.Done(tokenCount.get()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OllamaUnavailableException) {
            val userMessage = if (inLlmPhase) "LLM stream interrupted" else "Embedding service unavailable"
            logger.error(
                "Ollama unavailable (inLlmPhase={}) for correlationId={}: {}",
                inLlmPhase, correlationId, e.message
            )
            responseBus.emit(correlationId, QueueEvent.Error(userMessage))
        } catch (e: Exception) {
            // A mid-stream IOException from the LLM flow surfaces here because
            // LlmService deliberately propagates post-stream-start failures
            // unchanged. Keep the user-visible message aligned with the phase
            // so operators don't see a generic "Internal error" for an Ollama
            // disconnect.
            val userMessage = if (inLlmPhase) "LLM stream interrupted" else "Internal error"
            logger.error("Unexpected error (inLlmPhase={}) for correlationId={}: {}", inLlmPhase, correlationId, e.message, e)
            responseBus.emit(correlationId, QueueEvent.Error(userMessage))
        } finally {
            responseBus.close(correlationId)
        }
    }

    private fun readSystemPrompt(): String {
        return try {
            database.connect().use { conn ->
                val raw = ConfigRepository(conn).get(SYSTEM_PROMPT_KEY)
                if (raw == null) {
                    DEFAULT_SYSTEM_PROMPT
                } else {
                    Json.decodeFromString(String.serializer(), raw)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to read system_prompt from config, using default: {}", e.message)
            DEFAULT_SYSTEM_PROMPT
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        hits: List<SearchResult>,
        sources: List<Source>,
        history: List<ChatMessage>,
        userMessage: String
    ): List<ChatMessage> {
        val namesById: Map<Long, String> = sources.associate { it.documentId to it.documentName }
        val contextBlock = if (hits.isEmpty()) {
            "(no relevant context retrieved)"
        } else {
            hits.joinToString(separator = "\n\n") { hit ->
                val chunk = hit.chunk
                val docName = namesById[chunk.documentId] ?: "document ${chunk.documentId}"
                val sourceTag = buildString {
                    append("[Source: ")
                    append(docName)
                    if (!chunk.sectionId.isNullOrBlank()) append(", section ${chunk.sectionId}")
                    if (chunk.pageNumber != null) append(", page ${chunk.pageNumber}")
                    append("]")
                }
                "${chunk.content}\n$sourceTag"
            }
        }

        val systemContent = buildString {
            append(systemPrompt.trim())
            append("\n\nContext from AOS documentation:\n---\n")
            append(contextBlock)
            append("\n---\n\nInstructions:\n")
            append("- Answer based ONLY on the provided context\n")
            append("- If the answer is not in the context, say \"I don't have information about this\"\n")
            append("- Cite sources using [Source: Document, Section X.X]\n")
            append("- Respond in the same language as the question")
        }

        val out = ArrayList<ChatMessage>(2 + history.size)
        out.add(ChatMessage(role = "system", content = systemContent))
        out.addAll(history)
        out.add(ChatMessage(role = "user", content = userMessage))
        return out
    }

    private fun resolveSources(hits: List<SearchResult>): List<Source> {
        if (hits.isEmpty()) return emptyList()
        val documentIds = hits.map { it.chunk.documentId }.distinct()
        val namesById: Map<Long, String> = try {
            database.connect().use { conn ->
                val repo = DocumentRepository(conn)
                documentIds.associateWith { id -> repo.findById(id)?.filename ?: "document $id" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to resolve document names for sources: {}", e.message)
            documentIds.associateWith { id -> "document $id" }
        }
        return hits.map { hit ->
            val chunk = hit.chunk
            Source(
                documentId = chunk.documentId,
                documentName = namesById[chunk.documentId] ?: "document ${chunk.documentId}",
                section = chunk.sectionId,
                page = chunk.pageNumber,
                snippet = chunk.content.take(SNIPPET_MAX_CHARS)
            )
        }
    }

    private fun computeQueueWaitMs(request: ChatRequest, startMs: Long): Long {
        return try {
            val enqueuedAt = OffsetDateTime.parse(request.enqueuedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
            (startMs - enqueuedAt).coerceAtLeast(0L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
    }

    private fun logCompleted(
        correlationId: String,
        question: String,
        answer: String,
        sources: List<Source>,
        chunksRetrieved: Int,
        durationMs: Long,
        queueWaitMs: Long,
        tokenCount: Int
    ) {
        val line = buildJsonObject {
            put("event", "chat_completed")
            put("correlationId", correlationId)
            put("question", question)
            put("answer", answer)
            put("sources", buildJsonArray {
                for (source in sources) add(sourceToJson(source))
            })
            put("chunksRetrieved", chunksRetrieved)
            put("durationMs", durationMs)
            put("queueWaitMs", queueWaitMs)
            put("tokenCount", tokenCount)
        }
        logger.info(Json.encodeToString(JsonObject.serializer(), line))
    }

    private fun sourceToJson(source: Source): JsonObject = buildJsonObject {
        put("documentId", source.documentId)
        put("documentName", source.documentName)
        put("section", source.section)
        put("page", source.page)
        put("snippet", source.snippet)
    }

    companion object {
        const val SYSTEM_PROMPT_KEY = "system_prompt"
        const val SEARCH_TOP_K = 5
        const val SEARCH_MIN_SCORE = 0.3f
        const val SNIPPET_MAX_CHARS = 240

        /** Fallback used only when the config row is missing. V004 seeds it. */
        val DEFAULT_SYSTEM_PROMPT: String = """
            You are an AOS Documentation Assistant. Your role is to help users find information
            in the AOS technical documentation.

            Guidelines:
            - Provide accurate, concise answers based on the documentation
            - Always cite your sources with document name and section
            - For troubleshooting codes (MA-XX), provide the full symptom, cause, and solution
            - If information is not available, clearly state that
            - Respond in German if the question is in German, otherwise in English
            - Format code and technical terms appropriately
        """.trimIndent()

    }
}
