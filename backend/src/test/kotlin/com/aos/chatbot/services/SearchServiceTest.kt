package com.aos.chatbot.services

import com.aos.chatbot.models.Chunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchServiceTest {

    private fun floatsToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buf.putFloat(f)
        return buf.array()
    }

    private fun chunk(
        id: Long,
        documentId: Long,
        embedding: FloatArray?,
        content: String = "chunk-$id"
    ): Chunk = Chunk(
        id = id,
        documentId = documentId,
        content = content,
        contentType = "text",
        embedding = embedding?.let { floatsToBytes(it) }
    )

    @Test
    fun `identical vectors score 1 and orthogonal score 0`() {
        val service = SearchService()
        val v = floatArrayOf(1f, 0f, 0f)
        val orth = floatArrayOf(0f, 1f, 0f)
        service.loadInitial(
            listOf(
                chunk(1, 1, v),
                chunk(2, 1, orth)
            )
        )

        val results = service.search(v, topK = 5, minScore = -1f)

        assertEquals(2, results.size)
        val matchIdentical = results.first { it.chunk.id == 1L }
        val matchOrth = results.first { it.chunk.id == 2L }
        assertTrue(abs(matchIdentical.score - 1f) < 1e-5f)
        assertTrue(abs(matchOrth.score - 0f) < 1e-5f)
    }

    @Test
    fun `negated vector scores minus one`() {
        val service = SearchService()
        val v = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val neg = FloatArray(v.size) { -v[it] }
        service.loadInitial(listOf(chunk(1, 1, neg)))

        val results = service.search(v, topK = 5, minScore = -1f)

        assertEquals(1, results.size)
        assertTrue(abs(results[0].score - (-1f)) < 1e-5f)
    }

    @Test
    fun `search respects topK truncation`() {
        val service = SearchService()
        val q = floatArrayOf(1f, 0f, 0f)
        service.loadInitial(
            (1..10L).map { id ->
                chunk(id, 1, floatArrayOf(1f, 0f, 0f))
            }
        )

        val results = service.search(q, topK = 3, minScore = -1f)

        assertEquals(3, results.size)
    }

    @Test
    fun `search filters by minScore`() {
        val service = SearchService()
        val q = floatArrayOf(1f, 0f, 0f)
        service.loadInitial(
            listOf(
                chunk(1, 1, floatArrayOf(1f, 0f, 0f)),
                chunk(2, 1, floatArrayOf(0f, 1f, 0f)),
                chunk(3, 1, floatArrayOf(0.9f, 0.1f, 0f))
            )
        )

        val results = service.search(q, topK = 5, minScore = 0.5f)

        assertEquals(2, results.size)
        assertTrue(results.all { it.score >= 0.5f })
        assertTrue(results.none { it.chunk.id == 2L })
    }

    @Test
    fun `search on empty index returns empty list`() {
        val service = SearchService()
        val results = service.search(floatArrayOf(1f, 0f, 0f))
        assertEquals(emptyList(), results)
    }

    @Test
    fun `search returns empty when no chunks cross threshold`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(0f, 1f, 0f))))

        val results = service.search(floatArrayOf(1f, 0f, 0f), topK = 5, minScore = 0.5f)

        assertEquals(emptyList(), results)
    }

    @Test
    fun `removeDocument drops matching chunks`() {
        val service = SearchService()
        service.loadInitial(
            listOf(
                chunk(1, 1, floatArrayOf(1f, 0f)),
                chunk(2, 2, floatArrayOf(1f, 0f)),
                chunk(3, 1, floatArrayOf(1f, 0f))
            )
        )

        service.removeDocument(1)

        assertEquals(1, service.size())
        val results = service.search(floatArrayOf(1f, 0f), topK = 5, minScore = -1f)
        assertEquals(listOf(2L), results.map { it.chunk.id })
    }

    @Test
    fun `removeDocument on missing id is a no-op`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f))))

        service.removeDocument(99)

        assertEquals(1, service.size())
    }

    @Test
    fun `appendChunks grows index after loadInitial`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f))))
        service.appendChunks(
            listOf(
                chunk(2, 2, floatArrayOf(0f, 1f)),
                chunk(3, 2, floatArrayOf(1f, 1f))
            )
        )

        assertEquals(3, service.size())
        val results = service.search(floatArrayOf(1f, 0f), topK = 5, minScore = -1f)
        assertEquals(3, results.size)
    }

    @Test
    fun `appendChunks with empty list is a no-op`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f))))
        service.appendChunks(emptyList())
        assertEquals(1, service.size())
    }

    @Test
    fun `search skips chunks with null embedding`() {
        val service = SearchService()
        service.loadInitial(
            listOf(
                chunk(1, 1, null),
                chunk(2, 1, floatArrayOf(1f, 0f))
            )
        )

        val results = service.search(floatArrayOf(1f, 0f), topK = 5, minScore = -1f)

        assertEquals(1, results.size)
        assertEquals(2L, results[0].chunk.id)
    }

    @Test
    fun `size reflects current index state`() {
        val service = SearchService()
        assertEquals(0, service.size())
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f))))
        assertEquals(1, service.size())
        service.appendChunks(listOf(chunk(2, 1, floatArrayOf(0f, 1f))))
        assertEquals(2, service.size())
        service.removeDocument(1)
        assertEquals(0, service.size())
    }

    @Test
    fun `results are sorted by score DESC`() {
        val service = SearchService()
        val q = floatArrayOf(1f, 0f, 0f)
        service.loadInitial(
            listOf(
                chunk(1, 1, floatArrayOf(0.5f, 0.5f, 0f)),
                chunk(2, 1, floatArrayOf(1f, 0f, 0f)),
                chunk(3, 1, floatArrayOf(0.9f, 0.1f, 0f))
            )
        )

        val results = service.search(q, topK = 5, minScore = -1f)

        assertEquals(listOf(2L, 3L, 1L), results.map { it.chunk.id })
        for (i in 1 until results.size) {
            assertTrue(results[i - 1].score >= results[i].score)
        }
    }

    @Test
    fun `concurrent searches during append do not throw`() = runBlocking {
        val service = SearchService()
        val q = floatArrayOf(1f, 0f, 0f)
        service.loadInitial(
            (1..50L).map { chunk(it, 1, floatArrayOf(1f, 0f, 0f)) }
        )

        coroutineScope {
            val searchers = (1..100).map {
                async(Dispatchers.Default) {
                    repeat(20) {
                        val results = service.search(q, topK = 5, minScore = -1f)
                        assertTrue(results.isNotEmpty())
                    }
                }
            }
            launch(Dispatchers.Default) {
                repeat(50) { i ->
                    service.appendChunks(
                        listOf(chunk(1000L + i, 2, floatArrayOf(1f, 0f, 0f)))
                    )
                }
            }
            searchers.awaitAll()
        }

        assertTrue(service.size() >= 50)
    }

    @Test
    fun `loadInitial replaces the index atomically`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f))))
        service.loadInitial(
            listOf(
                chunk(10, 5, floatArrayOf(0f, 1f)),
                chunk(11, 5, floatArrayOf(1f, 1f))
            )
        )

        assertEquals(2, service.size())
        val results = service.search(floatArrayOf(0f, 1f), topK = 5, minScore = -1f)
        assertEquals(setOf(10L, 11L), results.map { it.chunk.id }.toSet())
    }

    @Test
    fun `mismatched dimensions produce zero score`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(1f, 0f, 0f, 0f))))
        val q = floatArrayOf(1f, 0f, 0f)

        val results = service.search(q, topK = 5, minScore = 0.01f)

        assertEquals(emptyList(), results)
    }

    @Test
    fun `zero vector produces zero score without division by zero`() {
        val service = SearchService()
        service.loadInitial(listOf(chunk(1, 1, floatArrayOf(0f, 0f, 0f))))

        val results = service.search(floatArrayOf(1f, 0f, 0f), topK = 5, minScore = -1f)

        assertEquals(1, results.size)
        assertEquals(0f, results[0].score)
    }

}
