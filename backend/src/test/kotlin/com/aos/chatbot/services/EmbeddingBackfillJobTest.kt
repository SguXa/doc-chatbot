package com.aos.chatbot.services

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.Migrations
import com.aos.chatbot.db.repositories.ChunkRepository
import com.aos.chatbot.db.repositories.DocumentRepository
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.Document
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingBackfillJobTest {

    private lateinit var tempDir: Path
    private lateinit var database: Database
    private var documentId: Long = 0
    private lateinit var embeddingService: EmbeddingService
    private lateinit var searchService: SearchService

    // Keep backoffs short so tests run fast but still exercise the retry paths.
    private val fastBackoff: List<Long> = listOf(5L, 10L, 20L)
    private val fastGlobalWait: Long = 10L

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("backfill-test")
        val dbPath = tempDir.resolve("test.db").toString()
        database = Database(dbPath)
        database.connect().use { conn ->
            Migrations(conn).apply()
            val docRepo = DocumentRepository(conn)
            documentId = docRepo.insert(
                Document(filename = "x.docx", fileType = "docx", fileSize = 10, fileHash = "h")
            ).id
        }
        embeddingService = mockk()
        searchService = SearchService()
    }

    @AfterEach
    fun tearDown() {
        clearMocks(embeddingService)
        if (Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun insertChunk(content: String, embedding: ByteArray? = null): Long {
        database.connect().use { conn ->
            val repo = ChunkRepository(conn)
            repo.insertBatch(
                listOf(
                    Chunk(
                        documentId = documentId,
                        content = content,
                        contentType = "text",
                        embedding = embedding
                    )
                )
            )
            return repo.findByDocumentId(documentId).last().id
        }
    }

    private fun newJob(): EmbeddingBackfillJob = EmbeddingBackfillJob(
        database = database,
        embeddingService = embeddingService,
        searchService = searchService,
        perChunkBackoffMs = fastBackoff,
        globalRetryWaitMs = fastGlobalWait
    )

    @Test
    fun `no null chunks returns Completed(0, 0) without Ollama calls`() = runBlocking {
        // All chunks already embedded.
        val bytes = ByteArray(4) { 0 }
        insertChunk("already embedded", bytes)

        val job = newJob()
        val result = job.run()

        assertEquals(0, result.embedded)
        assertEquals(0, result.skipped)
        // No embed calls at all — not even the readiness probe, because we short-circuit.
        coVerify(exactly = 0) { embeddingService.embed(any()) }
        // Search index is loaded with the already-embedded chunk
        assertEquals(1, searchService.size())
        assertTrue(job.status() is BackfillStatus.Completed)
    }

    @Test
    fun `all null chunks succeed`() = runBlocking {
        repeat(5) { insertChunk("chunk-$it") }
        val fake = FloatArray(8) { it.toFloat() }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        val result = job.run()

        assertEquals(5, result.embedded)
        assertEquals(0, result.skipped)
        // 1 probe + 5 chunk embeds
        coVerify(exactly = 6) { embeddingService.embed(any()) }
        assertEquals(5, searchService.size())

        // All chunks now have non-null embedding
        database.connect().use { conn ->
            val chunks = ChunkRepository(conn).findAll()
            for (c in chunks) assertNotNull(c.embedding, "chunk ${c.id} should have non-null embedding")
        }
    }

    @Test
    fun `chunk that fails twice then succeeds on third attempt completes`() = runBlocking {
        (1..5).forEach { insertChunk("chunk-$it") }
        val targetContent = "chunk-3"
        val failsBeforeSuccess = AtomicInteger(0)
        val fake = FloatArray(4) { it.toFloat() }

        coEvery { embeddingService.embed(any()) } answers {
            val text = firstArg<String>()
            if (text == targetContent) {
                val n = failsBeforeSuccess.incrementAndGet()
                if (n <= 2) throw OllamaUnavailableException("transient")
            }
            fake
        }

        val job = newJob()
        val result = job.run()

        assertEquals(5, result.embedded)
        assertEquals(0, result.skipped)
        // chunk-3 was embedded 3 times (2 failures + 1 success); other 4 chunks once each; + 1 probe.
        coVerify(exactly = 3) { embeddingService.embed(targetContent) }
        assertEquals(5, searchService.size())
    }

    @Test
    fun `chunk that fails three times is skipped and logged`() = runBlocking {
        (1..5).forEach { insertChunk("chunk-$it") }
        val targetContent = "chunk-3"
        val fake = FloatArray(4) { 1f }

        coEvery { embeddingService.embed(any()) } answers {
            val text = firstArg<String>()
            if (text == targetContent) throw OllamaUnavailableException("permanently unavailable")
            fake
        }

        val job = newJob()
        val result = job.run()

        assertEquals(4, result.embedded)
        assertEquals(1, result.skipped)
        // Exactly the number of per-chunk attempts was used for chunk-3.
        coVerify(exactly = fastBackoff.size) { embeddingService.embed(targetContent) }

        database.connect().use { conn ->
            val chunks = ChunkRepository(conn).findAll()
            val targetChunk = chunks.single { it.content == targetContent }
            assertNull(targetChunk.embedding)
            assertEquals(4, chunks.count { it.embedding != null })
        }
        // Skipped chunk is still loaded into the index (with null embedding SearchService ignores it on query).
        assertEquals(5, searchService.size())
    }

    @Test
    fun `Ollama down during readiness recovers and completes`() = runBlocking {
        (1..5).forEach { insertChunk("chunk-$it") }
        val probeCalls = AtomicInteger(0)
        val recoverAfter = 3 // first 3 probe attempts fail, 4th succeeds
        val fake = FloatArray(4) { 2f }

        coEvery { embeddingService.embed(any()) } answers {
            val text = firstArg<String>()
            if (text == "backfill-readiness-probe") {
                val n = probeCalls.incrementAndGet()
                if (n <= recoverAfter) throw OllamaUnavailableException("Ollama warming up")
            }
            fake
        }

        val job = newJob()
        val result = job.run()

        assertEquals(5, result.embedded)
        assertEquals(0, result.skipped)
        assertEquals(recoverAfter + 1, probeCalls.get()) // global retry triggered 3 times then probe succeeds on 4th
        assertEquals(5, searchService.size())
    }

    @Test
    fun `search index is rebuilt with both previously-embedded and newly-embedded chunks`() = runBlocking {
        // 2 chunks already embedded, 3 with null embedding
        val preEmbeddedBytes = ByteArray(4) { 7 }
        insertChunk("pre-1", preEmbeddedBytes)
        insertChunk("pre-2", preEmbeddedBytes)
        insertChunk("new-1")
        insertChunk("new-2")
        insertChunk("new-3")

        val fake = FloatArray(4) { 3f }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        val result = job.run()

        assertEquals(3, result.embedded)
        assertEquals(0, result.skipped)
        assertEquals(5, searchService.size())
    }

    @Test
    fun `clearAndReindex nulls all embeddings and re-embeds them`() = runBlocking {
        // Seed with two already-embedded chunks so we can prove the clear happens.
        val existingBytes = ByteArray(4) { 9 }
        insertChunk("alpha", existingBytes)
        insertChunk("beta", existingBytes)

        val fake = FloatArray(4) { 1f }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        job.clearAndReindex()

        assertTrue(job.status() is BackfillStatus.Completed)
        assertFalse(job.isRunning())
        assertEquals(2, searchService.size())

        database.connect().use { conn ->
            val chunks = ChunkRepository(conn).findAll()
            // Both chunks now have a NEW embedding blob (matches fake size = 4 floats)
            for (c in chunks) {
                assertNotNull(c.embedding)
                assertEquals(4 * Float.SIZE_BYTES, c.embedding!!.size)
            }
        }
    }

    @Test
    fun `isRunning toggles across clearAndReindex lifecycle`() = runBlocking {
        insertChunk("only")
        val fake = FloatArray(4) { 0f }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        assertFalse(job.isRunning())
        job.clearAndReindex()
        assertFalse(job.isRunning())
    }

    @Test
    fun `clearAndReindex waits for withReindexLock holder then runs`() = runBlocking {
        insertChunk("held")
        val fake = FloatArray(4) { 0f }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val released = kotlinx.coroutines.CompletableDeferred<Unit>()

        // Hold the reindex lock from a simulated upload on a background dispatcher.
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
        val holder = scope.launch {
            job.withReindexLock {
                gate.complete(Unit)
                released.await()
            }
        }
        try {
            gate.await()
            // clearAndReindex should block waiting for the holder, not silently no-op.
            val reindex = scope.launch { job.clearAndReindex() }
            // Yield long enough to prove no embed happens while the lock is held.
            kotlinx.coroutines.delay(50)
            coVerify(exactly = 0) { embeddingService.embed(any()) }
            database.connect().use { conn ->
                val chunks = ChunkRepository(conn).findAll()
                assertNull(chunks.single().embedding)
            }

            // Release the holder; clearAndReindex should now proceed and complete.
            released.complete(Unit)
            holder.join()
            reindex.join()

            // After release, the chunk is re-embedded.
            database.connect().use { conn ->
                val chunks = ChunkRepository(conn).findAll()
                assertNotNull(chunks.single().embedding)
            }
        } finally {
            if (!released.isCompleted) released.complete(Unit)
            scope.cancel()
        }
    }


    @Test
    fun `initial DB read failure transitions state to Failed instead of stranding Idle`() = runBlocking {
        // The initial findIdsWithNullEmbedding() call used to sit OUTSIDE the
        // try/catch, so a JDBC failure here would propagate up to the startup
        // launcher (which logs-and-swallows) while leaving state at Idle —
        // permanently closing /api/chat because readiness maps Idle to
        // backfill=idle. Reindex could not recover it either because the admin
        // route gates on status ∈ {Completed, Failed}.
        val brokenDb = mockk<Database>()
        every { brokenDb.connect() } throws java.sql.SQLException("initial read failed")
        val job = EmbeddingBackfillJob(
            database = brokenDb,
            embeddingService = embeddingService,
            searchService = searchService,
            perChunkBackoffMs = fastBackoff,
            globalRetryWaitMs = fastGlobalWait
        )
        try {
            job.run()
            error("expected SQLException to propagate")
        } catch (e: java.sql.SQLException) {
            // expected — state must have flipped to Failed so reindex can recover
        }
        assertTrue(job.status() is BackfillStatus.Failed, "status was ${job.status()}")
    }

    @Test
    fun `status transitions Idle to Running to Completed`() = runBlocking {
        insertChunk("one")
        val fake = FloatArray(4) { 1f }
        coEvery { embeddingService.embed(any()) } returns fake

        val job = newJob()
        assertTrue(job.status() is BackfillStatus.Idle)

        val result = job.run()
        assertTrue(job.status() is BackfillStatus.Completed)
        assertEquals(BackfillStatus.Completed(embedded = 1, skipped = 0), result)
        assertEquals(result, job.status())
    }
}
