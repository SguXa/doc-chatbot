package com.aos.chatbot.services

import com.aos.chatbot.config.OllamaConfig
import com.aos.chatbot.models.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

private const val LLM_TIMEOUT_MS = 120_000L

class LlmService(
    private val httpClient: HttpClient,
    private val config: OllamaConfig,
    private val timeoutMs: Long = LLM_TIMEOUT_MS
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams token chunks from Ollama's `/api/chat` NDJSON response.
     *
     * Each streamed line is `{"message":{"content":"..."}, "done":false}` until a
     * final `{"done":true, ...}` object carrying totals. Emitted values are the
     * successive `message.content` strings; the final `done` object contributes
     * nothing to the flow.
     *
     * Connection failures (including non-IOException request-setup errors like
     * [java.nio.channels.UnresolvedAddressException] from Ktor CIO for a bad
     * `OLLAMA_URL` host), timeouts, and 5xx responses that surface BEFORE any
     * streamed body bytes are wrapped as [OllamaUnavailableException]. Errors
     * encountered mid-stream (e.g., WireMock dropping the socket) propagate as
     * flow exceptions unchanged — callers are expected to close their bus entry
     * and log accordingly.
     */
    fun generate(messages: List<ChatMessage>): Flow<String> {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val body = ChatStreamRequest(
            model = config.llmModel,
            messages = messages,
            stream = true
        )
        val requestBody = json.encodeToString(ChatStreamRequest.serializer(), body)
        val url = "${config.url.trimEnd('/')}/api/chat"

        return flow {
            var streamStarted = false
            try {
                withTimeout(timeoutMs) {
                    httpClient.preparePost(url) {
                        contentType(ContentType.Application.Json)
                        setBody(requestBody)
                    }.execute { response ->
                        val status = response.status.value
                        if (status !in 200..299) {
                            throw OllamaUnavailableException("Ollama returned $status for chat")
                        }
                        streamStarted = true
                        val channel = response.bodyAsChannel()
                        var sawDone = false
                        while (true) {
                            val line = channel.readUTF8Line() ?: break
                            if (line.isBlank()) continue
                            val chunk = json.decodeFromString(ChatStreamChunk.serializer(), line)
                            val content = chunk.message?.content
                            if (!content.isNullOrEmpty()) emit(content)
                            if (chunk.done) {
                                sawDone = true
                                break
                            }
                        }
                        if (!sawDone) {
                            throw OllamaUnavailableException("Ollama chat stream ended without a done marker")
                        }
                    }
                }
            } catch (e: OllamaUnavailableException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                throw OllamaUnavailableException("Ollama chat request timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (streamStarted) throw e
                throw OllamaUnavailableException("Ollama unreachable for chat", e)
            } catch (e: SerializationException) {
                throw OllamaUnavailableException("Malformed NDJSON line in Ollama chat stream", e)
            } catch (e: Exception) {
                // Ktor CIO can surface pre-response setup failures (e.g.
                // unresolved DNS as UnresolvedAddressException) as non-IO
                // exception types. Wrap those so chat callers see a
                // consistent Ollama-down signal; mid-stream failures still
                // propagate unchanged per the contract above.
                if (streamStarted) throw e
                throw OllamaUnavailableException("Ollama chat request failed to start", e)
            }
        }
    }

    /**
     * Convenience variant of [generate] that collects the full answer into a
     * single string. Used primarily by warmup.
     */
    suspend fun generateFull(messages: List<ChatMessage>): String =
        generate(messages).toList().joinToString(separator = "")
}

@Serializable
internal data class ChatStreamRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean
)

@Serializable
internal data class ChatStreamChunk(
    val message: ChatStreamMessage? = null,
    val done: Boolean = false
)

@Serializable
internal data class ChatStreamMessage(
    val role: String? = null,
    val content: String? = null
)
