package com.aos.chatbot.services

import com.aos.chatbot.db.bytesToEmbedding
import com.aos.chatbot.models.Chunk
import com.aos.chatbot.models.SearchResult
import kotlin.math.sqrt

/**
 * In-memory vector index backing chat retrieval.
 *
 * Concurrency model: reads are lock-free snapshots of a volatile reference;
 * writes construct a new list and compare-and-swap the reference; concurrent
 * reads during a write see either the pre-write or post-write list, never a
 * partially-mutated list.
 *
 * The index is loaded once from SQLite at startup via [loadInitial] and
 * mutated in-process by upload (append), delete (remove by documentId), and
 * reindex (replace).
 */
class SearchService {

    @Volatile
    private var chunks: List<Chunk> = emptyList()

    private val writeLock = Any()

    /**
     * Atomically replaces the index with the provided chunks.
     *
     * Used at startup (after backfill) and at reindex completion.
     */
    fun loadInitial(chunks: List<Chunk>) {
        synchronized(writeLock) {
            this.chunks = chunks.toList()
        }
    }

    /**
     * Atomically appends the given chunks to the index.
     *
     * Every [Chunk] carries its own `documentId`; the caller is not
     * responsible for homogeneity.
     */
    fun appendChunks(chunks: List<Chunk>) {
        if (chunks.isEmpty()) return
        synchronized(writeLock) {
            val next = ArrayList<Chunk>(this.chunks.size + chunks.size)
            next.addAll(this.chunks)
            next.addAll(chunks)
            this.chunks = next
        }
    }

    /**
     * Atomically removes all chunks with the given `documentId` from the index.
     */
    fun removeDocument(documentId: Long) {
        synchronized(writeLock) {
            val current = this.chunks
            val next = current.filter { it.documentId != documentId }
            if (next.size != current.size) {
                this.chunks = next
            }
        }
    }

    /**
     * Returns the current indexed chunk count. Used by `/api/stats` and
     * `/api/health/ready` readiness surfaces.
     */
    fun size(): Int = chunks.size

    /**
     * Searches the in-memory index for the top-[topK] chunks above [minScore],
     * sorted by cosine similarity DESC.
     *
     * Skips chunks whose `embedding` is null (defensive — backfill may still
     * be running).
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minScore: Float = 0.3f
    ): List<SearchResult> {
        val snapshot = chunks
        if (snapshot.isEmpty()) return emptyList()
        val results = ArrayList<SearchResult>()
        for (chunk in snapshot) {
            val bytes = chunk.embedding ?: continue
            val vec = bytesToEmbedding(bytes)
            val score = cosineSimilarity(queryEmbedding, vec)
            if (score >= minScore) {
                results.add(SearchResult(chunk, score))
            }
        }
        results.sortByDescending { it.score }
        return if (results.size > topK) results.subList(0, topK).toList() else results
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
