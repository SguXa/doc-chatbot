package com.aos.chatbot.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingCodecTest {

    @Test
    fun `round trip preserves values`() {
        val original = floatArrayOf(0.0f, 1.5f, -2.75f, 3.14159f, Float.MIN_VALUE, Float.MAX_VALUE)
        val bytes = embeddingToBytes(original)
        val decoded = bytesToEmbedding(bytes)

        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            assertEquals(original[i], decoded[i])
        }
    }

    @Test
    fun `bytes layout is little-endian Float32 with expected length`() {
        val original = floatArrayOf(1.0f, -1.0f, 0.5f)
        val bytes = embeddingToBytes(original)

        assertEquals(original.size * Float.SIZE_BYTES, bytes.size)

        // 1.0f = 0x3F800000 → little-endian: 00 00 80 3F
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F.toByte(), bytes[3])
    }

    @Test
    fun `empty array round trips to empty array`() {
        val bytes = embeddingToBytes(floatArrayOf())
        assertEquals(0, bytes.size)
        val decoded = bytesToEmbedding(bytes)
        assertEquals(0, decoded.size)
    }

    @Test
    fun `large embedding round trips`() {
        val original = FloatArray(1024) { i -> i.toFloat() * 0.001f }
        val bytes = embeddingToBytes(original)
        val decoded = bytesToEmbedding(bytes)

        assertEquals(1024, decoded.size)
        assertTrue(original.contentEquals(decoded))
    }
}
