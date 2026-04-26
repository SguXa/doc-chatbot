package com.aos.chatbot.db

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a [FloatArray] embedding as a Float32 little-endian [ByteArray]
 * suitable for the `chunks.embedding` BLOB column.
 */
fun embeddingToBytes(embedding: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (f in embedding) buf.putFloat(f)
    return buf.array()
}

/**
 * Decodes a Float32 little-endian [ByteArray] (as stored in `chunks.embedding`)
 * back to a [FloatArray].
 */
fun bytesToEmbedding(bytes: ByteArray): FloatArray {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(bytes.size / Float.SIZE_BYTES)
    for (i in out.indices) out[i] = buf.float
    return out
}
