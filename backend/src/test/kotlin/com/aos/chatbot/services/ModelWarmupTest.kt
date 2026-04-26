package com.aos.chatbot.services

import com.aos.chatbot.models.ChatMessage
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ModelWarmupTest {

    private lateinit var embeddingService: EmbeddingService
    private lateinit var llmService: LlmService

    @BeforeEach
    fun setUp() {
        embeddingService = mockk()
        llmService = mockk()
    }

    @AfterEach
    fun tearDown() {
        clearMocks(embeddingService, llmService)
    }

    @Test
    fun `warmupAsync invokes embedding then LLM exactly once`() = runBlocking {
        coEvery { embeddingService.embed(any()) } returns FloatArray(4) { 0f }
        coEvery { llmService.generateFull(any()) } returns "ok"

        val warmup = ModelWarmup(embeddingService, llmService)
        val job: Job = warmup.warmupAsync(this)
        job.join()

        coVerify(exactly = 1) { embeddingService.embed(any()) }
        coVerify(exactly = 1) { llmService.generateFull(any()) }
    }

    @Test
    fun `warmupAsync passes system and user warmup messages to LLM`() = runBlocking {
        coEvery { embeddingService.embed(any()) } returns FloatArray(4) { 0f }
        coEvery { llmService.generateFull(any()) } returns "ok"

        val warmup = ModelWarmup(embeddingService, llmService)
        warmup.warmupAsync(this).join()

        coVerify(exactly = 1) {
            llmService.generateFull(
                match<List<ChatMessage>> { messages ->
                    messages.size == 2 &&
                        messages[0].role == "system" &&
                        messages[1].role == "user"
                }
            )
        }
    }

    @Test
    fun `embedding failure is swallowed and LLM warmup is skipped`() = runBlocking {
        coEvery { embeddingService.embed(any()) } throws OllamaUnavailableException("down")

        val warmup = ModelWarmup(embeddingService, llmService)
        val job = warmup.warmupAsync(this)
        job.join()

        coVerify(exactly = 1) { embeddingService.embed(any()) }
        coVerify(exactly = 0) { llmService.generateFull(any()) }
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled, "job should complete normally (not cancelled) despite embed failure")
    }

    @Test
    fun `LLM failure after successful embedding is swallowed`() = runBlocking {
        coEvery { embeddingService.embed(any()) } returns FloatArray(4) { 0f }
        coEvery { llmService.generateFull(any()) } throws OllamaUnavailableException("down")

        val warmup = ModelWarmup(embeddingService, llmService)
        val job = warmup.warmupAsync(this)
        job.join()

        coVerify(exactly = 1) { embeddingService.embed(any()) }
        coVerify(exactly = 1) { llmService.generateFull(any()) }
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled, "job should complete normally despite LLM failure")
    }

    @Test
    fun `warmupAsync is non-blocking and returns immediately`() = runBlocking {
        coEvery { embeddingService.embed(any()) } coAnswers {
            delay(500)
            FloatArray(4) { 0f }
        }
        coEvery { llmService.generateFull(any()) } coAnswers {
            delay(500)
            "ok"
        }

        val warmup = ModelWarmup(embeddingService, llmService)

        val startNs = System.nanoTime()
        val job: Job = warmup.warmupAsync(this)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        // warmupAsync should return immediately; completing the child job
        // takes at least 1s of simulated delay.
        assertTrue(elapsedMs < 200, "warmupAsync should return fast, took ${elapsedMs}ms")
        assertTrue(!job.isCompleted, "job should still be running when warmupAsync returns")

        withTimeout(5_000) { job.join() }
    }

    @Test
    fun `non-Ollama exception from embedding is swallowed and LLM warmup is skipped`() = runBlocking {
        // LlmService and EmbeddingService can in principle leak non-OllamaUnavailableException
        // failures (e.g. a raw IOException from a mid-stream LLM disconnect, or any
        // unforeseen runtime failure). Warmup must still keep the app alive.
        coEvery { embeddingService.embed(any()) } throws RuntimeException("unexpected")

        val warmup = ModelWarmup(embeddingService, llmService)
        val job = warmup.warmupAsync(this)
        job.join()

        coVerify(exactly = 1) { embeddingService.embed(any()) }
        coVerify(exactly = 0) { llmService.generateFull(any()) }
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled, "job should complete normally despite unexpected embed failure")
    }

    @Test
    fun `non-Ollama exception from LLM warmup is swallowed`() = runBlocking {
        coEvery { embeddingService.embed(any()) } returns FloatArray(4) { 0f }
        coEvery { llmService.generateFull(any()) } throws java.io.IOException("mid-stream disconnect")

        val warmup = ModelWarmup(embeddingService, llmService)
        val job = warmup.warmupAsync(this)
        job.join()

        coVerify(exactly = 1) { llmService.generateFull(any()) }
        assertTrue(job.isCompleted)
        assertTrue(!job.isCancelled, "job should complete normally despite unexpected LLM failure")
    }

    @Test
    fun `warmup job can be cancelled via scope cancellation`() = runBlocking {
        coEvery { embeddingService.embed(any()) } coAnswers {
            delay(10_000)
            FloatArray(4) { 0f }
        }

        val scope = CoroutineScope(kotlin.coroutines.coroutineContext)
        val warmup = ModelWarmup(embeddingService, llmService)

        val job = warmup.warmupAsync(scope)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        coVerify(exactly = 0) { llmService.generateFull(any()) }
    }
}
