package com.aos.chatbot.services

import com.aos.chatbot.models.QueueEvent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory correlation bus that routes [QueueEvent] values from the chat
 * consumer coroutine (`ChatService`) to the matching SSE handler
 * (`ChatRoutes`) via a shared `correlationId`.
 *
 * Lifecycle contract:
 *  1. The SSE route calls [open] BEFORE `QueueService.enqueue`, so any event
 *     emitted between enqueue and the route's collect loop is buffered, not
 *     dropped.
 *  2. The consumer coroutine looks up the channel by `correlationId` and
 *     emits events via [emit].
 *  3. The producer calls [close] after the terminal event (`Done` or
 *     `Error`); the route's collect loop then completes naturally.
 *  4. On client disconnect, the SSE route calls [close] from its `finally`
 *     block; the consumer sees [isOrphaned] == true on its next check and
 *     skips further work (in-flight Ollama calls still run to completion —
 *     see ADR 0006).
 *
 * Channels (capacity = [Channel.UNLIMITED]) are used deliberately over
 * `SharedFlow(replay = 0)`: a `SharedFlow` drops emissions produced before
 * any subscriber starts collecting, which races with route startup. An
 * unbounded channel buffers every emission until consumed or closed. See
 * ADR 0006.
 */
class ChatResponseBus {

    private val channels = ConcurrentHashMap<String, Channel<QueueEvent>>()

    /**
     * Opens (or returns the existing) buffered channel for [correlationId].
     *
     * MUST be called by the SSE route BEFORE `QueueService.enqueue` so early
     * events from the worker are guaranteed to be buffered.
     */
    fun open(correlationId: String): ReceiveChannel<QueueEvent> {
        return channels.computeIfAbsent(correlationId) {
            Channel(capacity = Channel.UNLIMITED)
        }
    }

    /**
     * Emits [event] to the channel keyed by [correlationId]. Silently drops
     * when no channel exists (consumer never opened or already closed) or
     * when the channel has been closed. Never throws.
     */
    fun emit(correlationId: String, event: QueueEvent) {
        val channel = channels[correlationId] ?: return
        channel.trySend(event)
    }

    /**
     * Closes the channel for [correlationId] and removes the map entry. Any
     * active receiver sees the channel as closed and its collect loop
     * completes. Idempotent: calling twice is a no-op.
     */
    fun close(correlationId: String) {
        val channel = channels.remove(correlationId) ?: return
        channel.close()
    }

    /**
     * Returns `true` when the channel for [correlationId] has been closed or
     * its entry removed. Consumers (ChatService) can short-circuit work for
     * abandoned requests.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun isOrphaned(correlationId: String): Boolean {
        val channel = channels[correlationId] ?: return true
        return channel.isClosedForSend
    }
}
