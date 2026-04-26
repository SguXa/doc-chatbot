package com.aos.chatbot.services

import com.aos.chatbot.db.Database
import com.aos.chatbot.db.embeddingToBytes
import com.aos.chatbot.db.repositories.ChunkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Background coroutine that embeds every chunk with `embedding IS NULL` and
 * keeps the in-memory [SearchService] index in sync.
 *
 * The job runs at application startup and, until it reaches a
 * [BackfillStatus.Completed] state, `/api/health/ready` returns 503 so the
 * chat route is closed. It is safe to invoke `run()` more than once on the
 * same instance (for example, from the admin reindex flow after embeddings
 * have been nulled).
 *
 * Retry policy:
 * - Per-chunk: up to [perChunkBackoffMs].size attempts with the matching
 *   backoff delay between attempts. After the last failure, the chunk is
 *   logged at ERROR and skipped (counted as `skipped` in [BackfillStatus.Completed]).
 * - Global (readiness): before the per-chunk loop begins, a dummy embedding
 *   probe verifies Ollama is reachable. While the probe throws
 *   [OllamaUnavailableException], we wait [globalRetryWaitMs] and retry
 *   forever. This is the "wait for Ollama to come up" phase that lets
 *   slow-boot deployments converge without losing chunks.
 *
 * Both retry loops call [ensureActive] before each attempt so application
 * shutdown cancels the job cleanly.
 */
class EmbeddingBackfillJob(
    private val database: Database,
    private val embeddingService: EmbeddingService,
    private val searchService: SearchService,
    private val perChunkBackoffMs: List<Long> = DEFAULT_PER_CHUNK_BACKOFF_MS,
    private val globalRetryWaitMs: Long = DEFAULT_GLOBAL_RETRY_WAIT_MS
) {
    private val logger = LoggerFactory.getLogger(EmbeddingBackfillJob::class.java)

    private val state = AtomicReference<BackfillStatus>(BackfillStatus.Idle)

    // Serializes reindex against concurrent uploads: [DocumentService]'s
    // embedding step acquires this mutex via [withReindexLock] so a reindex
    // cannot clear embeddings in the window between embed and DB commit.
    // Uploads hold the lock briefly; a reindex holds it for the full clear +
    // re-embed cycle.
    private val reindexLock = Mutex()

    // Distinguishes "reindex in flight" from "upload briefly holds the mutex".
    // `/api/health/ready` and upload gating both key off this flag, not off
    // mutex ownership (since mutex ownership would falsely trip during a
    // regular upload's embedding window).
    private val reindexRunning = AtomicBoolean(false)

    /** Thread-safe read of the current state. */
    fun status(): BackfillStatus = state.get()

    /**
     * True while a reindex is actively running. Used by `/api/health/ready`
     * and by the admin upload route to short-circuit with 503 when the index
     * is being rebuilt.
     */
    fun isRunning(): Boolean = reindexRunning.get()

    /**
     * Runs [block] under the shared reindex/upload mutex. Callers in the
     * upload path use this to guarantee that a reindex cannot wipe newly
     * embedded chunks between embed and DB commit.
     */
    suspend fun <T> withReindexLock(block: suspend () -> T): T =
        reindexLock.withLock { block() }

    /**
     * Clears every chunk's `embedding` column in a single transaction and
     * re-runs [run] to re-embed them. Waits for the reindex/upload mutex so a
     * brief upload embedding window does not cause the reindex to silently
     * no-op (the admin route has already responded 202, so the client expects
     * the work to actually happen). If a reindex is already running,
     * [reindexRunning] short-circuits the second invocation.
     */
    suspend fun clearAndReindex() {
        // Claim the reindex flag BEFORE waiting for the lock. This closes the
        // TOCTOU window between the admin-route's isRunning() gate and the
        // coroutine that actually starts the reindex: a second concurrent call
        // sees `reindexRunning = true` and fails fast instead of queueing
        // behind the lock for a duplicate pass.
        if (!reindexRunning.compareAndSet(false, true)) {
            logger.info("Reindex skipped: another reindex is already running")
            return
        }
        try {
            reindexLock.withLock {
                val cleared = database.connect().use { conn ->
                    conn.autoCommit = false
                    try {
                        val stmt = conn.prepareStatement("UPDATE chunks SET embedding = NULL")
                        val n = stmt.use { it.executeUpdate() }
                        conn.commit()
                        n
                    } catch (e: Exception) {
                        conn.rollback()
                        throw e
                    }
                }
                logger.info("Reindex cleared {} chunk embeddings; starting re-embed pass", cleared)
                // Lock is already held by clearAndReindex, so refresh inline.
                runCore(lockForRefresh = false)
            }
        } finally {
            reindexRunning.set(false)
        }
    }

    /**
     * Embeds all NULL-embedding chunks, writes them back, and refreshes the
     * in-memory index. Always returns [BackfillStatus.Completed]; non-recoverable
     * errors abort via exception.
     */
    suspend fun run(): BackfillStatus.Completed = runCore(lockForRefresh = true)

    /**
     * Core backfill loop. [lockForRefresh] controls whether the final index
     * swap acquires [reindexLock]: startup callers pass true so concurrent
     * uploads cannot slip an `appendChunks` between our `findAll` and
     * `loadInitial`; [clearAndReindex] already holds the lock and passes false
     * to avoid deadlock.
     */
    private suspend fun runCore(lockForRefresh: Boolean): BackfillStatus.Completed {
        try {
            val nullIds = database.connect().use { conn ->
                ChunkRepository(conn).findIdsWithNullEmbedding()
            }

            logger.info("Embedding backfill starting: {} chunks to process", nullIds.size)
            state.set(BackfillStatus.Running(processed = 0, total = nullIds.size))

            if (nullIds.isEmpty()) {
                refreshSearchIndexMaybeLocked(lockForRefresh)
                val result = BackfillStatus.Completed(embedded = 0, skipped = 0)
                state.set(result)
                logger.info("Embedding backfill finished: 0 embedded, 0 skipped")
                return result
            }

            // Global readiness probe — waits forever while Ollama is unreachable.
            probeUntilReady()

            var embedded = 0
            var skipped = 0
            var processed = 0

            for (chunkId in nullIds) {
                coroutineContext.ensureActive()
                val ok = embedSingleChunk(chunkId)
                if (ok) embedded++ else skipped++
                processed++
                state.set(BackfillStatus.Running(processed = processed, total = nullIds.size))
            }

            refreshSearchIndexMaybeLocked(lockForRefresh)

            val result = BackfillStatus.Completed(embedded = embedded, skipped = skipped)
            state.set(result)
            logger.info("Embedding backfill finished: {} embedded, {} skipped", embedded, skipped)
            return result
        } catch (e: CancellationException) {
            // Cancellation: leave the state where it was — a fresh run() call
            // (e.g., the admin reindex path) resets it, and `Running` while
            // shutting down is strictly better than `Failed`.
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e::class.qualifiedName ?: "unknown error"
            state.set(BackfillStatus.Failed(msg))
            logger.error("Embedding backfill failed: {}", msg, e)
            throw e
        }
    }

    private suspend fun probeUntilReady() {
        while (true) {
            coroutineContext.ensureActive()
            try {
                embeddingService.embed(READINESS_PROBE_TEXT)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any non-cancellation error during readiness (Ollama down,
                // transient JSON parse, I/O hiccup) is treated as "not ready
                // yet" — retry indefinitely so slow-boot deployments converge.
                // Transitioning to Failed here would permanently close `/api/chat`.
                logger.warn(
                    "Embedding backfill readiness probe failed ({}); retrying in {} ms",
                    e.message ?: e::class.qualifiedName, globalRetryWaitMs
                )
                delay(globalRetryWaitMs)
            }
        }
    }

    private suspend fun embedSingleChunk(chunkId: Long): Boolean {
        val chunk = try {
            database.connect().use { conn ->
                ChunkRepository(conn).findById(chunkId)
            }
        } catch (e: SQLException) {
            // Transient read failures (e.g. SQLITE_BUSY under write contention)
            // must not brick the whole backfill — skip this chunk and let the
            // next run pick it up.
            logger.error("Backfill skipping chunkId={} — could not read: {}", chunkId, e.message)
            return false
        }
        if (chunk == null) {
            logger.warn("Backfill skipping chunkId={} — row no longer exists", chunkId)
            return false
        }

        for (attempt in perChunkBackoffMs.indices) {
            coroutineContext.ensureActive()
            try {
                val embedding = embeddingService.embed(chunk.content)
                val bytes = embeddingToBytes(embedding)
                database.connect().use { conn ->
                    ChunkRepository(conn).updateEmbedding(chunkId, bytes)
                }
                return true
            } catch (e: OllamaUnavailableException) {
                val isLast = attempt == perChunkBackoffMs.lastIndex
                if (isLast) {
                    logger.error("Embedding backfill giving up on chunkId={} after {} attempts: {}",
                        chunkId, perChunkBackoffMs.size, e.message)
                    return false
                }
                delay(perChunkBackoffMs[attempt])
            } catch (e: SQLException) {
                // DB write blips (e.g. SQLITE_BUSY) are transient — retry with
                // the same backoff policy. Letting these escape to runCore
                // would flip backfill to Failed and permanently 503 /api/chat.
                val isLast = attempt == perChunkBackoffMs.lastIndex
                if (isLast) {
                    logger.error("Embedding backfill giving up on chunkId={} after {} DB attempts: {}",
                        chunkId, perChunkBackoffMs.size, e.message)
                    return false
                }
                delay(perChunkBackoffMs[attempt])
            }
        }
        return false
    }

    private suspend fun refreshSearchIndexMaybeLocked(acquireLock: Boolean) {
        if (acquireLock) {
            reindexLock.withLock { refreshSearchIndex() }
        } else {
            refreshSearchIndex()
        }
    }

    private fun refreshSearchIndex() {
        val all = database.connect().use { conn ->
            ChunkRepository(conn).findAll()
        }
        searchService.loadInitial(all)
    }

    companion object {
        val DEFAULT_PER_CHUNK_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)
        const val DEFAULT_GLOBAL_RETRY_WAIT_MS: Long = 5_000L
        private const val READINESS_PROBE_TEXT = "backfill-readiness-probe"
    }
}
