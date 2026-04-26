package com.aos.chatbot.services

import com.aos.chatbot.models.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Fires one dummy embedding and one dummy LLM call once at startup so the
 * embed and chat models are resident in Ollama's RAM before the first real
 * user request arrives.
 *
 * Warmup is fire-and-forget and does NOT gate `/api/health/ready`. Failures
 * are logged at WARN and swallowed — a cold Ollama must not crash the app
 * on startup. Steps are strictly sequential: if the embedding call fails,
 * the LLM call is skipped.
 */
class ModelWarmup(
    private val embeddingService: EmbeddingService,
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(ModelWarmup::class.java)

    fun warmupAsync(scope: CoroutineScope): Job = scope.launch {
        logger.info("Model warmup starting")
        val embedOk = try {
            embeddingService.embed(WARMUP_TEXT)
            logger.info("embedding warmup complete")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("embedding warmup failed; skipping LLM warmup: {}", e.message)
            false
        }

        if (!embedOk) return@launch

        try {
            llmService.generateFull(
                listOf(
                    ChatMessage(role = "system", content = WARMUP_TEXT),
                    ChatMessage(role = "user", content = WARMUP_TEXT)
                )
            )
            logger.info("llm warmup complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // LlmService propagates mid-stream failures (raw IOException, etc)
            // unchanged. Swallow them here so warmup honors its contract of
            // never crashing the app on startup.
            logger.warn("llm warmup failed: {}", e.message)
        }
    }

    companion object {
        private const val WARMUP_TEXT = "warmup"
    }
}
