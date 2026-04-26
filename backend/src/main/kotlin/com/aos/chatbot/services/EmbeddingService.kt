package com.aos.chatbot.services

import com.aos.chatbot.config.OllamaConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.IOException

private const val EMBEDDING_TIMEOUT_MS = 30_000L

class EmbeddingService(
    private val httpClient: HttpClient,
    private val config: OllamaConfig,
    private val timeoutMs: Long = EMBEDDING_TIMEOUT_MS
) {

    /**
     * Embeds a single text via Ollama's `/api/embeddings` endpoint.
     *
     * Connection failures (including non-IOException setup errors like
     * [java.nio.channels.UnresolvedAddressException] for a bad `OLLAMA_URL`
     * host), timeouts, 5xx responses, malformed JSON, and zero-length
     * embedding arrays are all translated to [OllamaUnavailableException].
     * Coroutine cancellation propagates unchanged.
     */
    suspend fun embed(text: String): FloatArray {
        val response: HttpResponse = try {
            withTimeout(timeoutMs) {
                httpClient.post("${config.url.trimEnd('/')}/api/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody(EmbeddingRequest(model = config.embedModel, prompt = text))
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw OllamaUnavailableException("Ollama embedding request timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw OllamaUnavailableException("Ollama unreachable for embeddings", e)
        } catch (e: Exception) {
            // Ktor CIO surfaces some request-setup failures (e.g. unresolved
            // DNS as UnresolvedAddressException) as non-IOException types.
            // Treat any such pre-response failure as an Ollama outage so
            // callers see a consistent dependency-down signal.
            throw OllamaUnavailableException("Ollama embedding request failed to start", e)
        }

        if (response.status.value !in 200..299) {
            throw OllamaUnavailableException("Ollama returned ${response.status.value} for embeddings")
        }

        val body: EmbeddingResponse = try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OllamaUnavailableException("Failed to decode Ollama embedding response", e)
        }

        if (body.embedding.isEmpty()) {
            throw OllamaUnavailableException("Ollama returned an empty embedding array")
        }

        val result = FloatArray(body.embedding.size)
        for (i in body.embedding.indices) {
            result[i] = body.embedding[i]
        }
        return result
    }
}

@Serializable
internal data class EmbeddingRequest(
    val model: String,
    val prompt: String
)

@Serializable
internal data class EmbeddingResponse(
    val embedding: List<Float>
)
