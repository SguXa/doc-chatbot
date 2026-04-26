package com.aos.chatbot.services

import com.aos.chatbot.models.QueueEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatResponseBusTest {

    @Test
    fun `buffered events arrive in order when consumer subscribes late`() = runBlocking {
        val bus = ChatResponseBus()
        val correlationId = "req-1"

        val receiver = bus.open(correlationId)

        bus.emit(correlationId, QueueEvent.Processing("Embedding query..."))
        bus.emit(correlationId, QueueEvent.Token("hello"))
        bus.emit(correlationId, QueueEvent.Token(" "))
        bus.emit(correlationId, QueueEvent.Token("world"))
        bus.emit(correlationId, QueueEvent.Done(totalTokens = 3))
        bus.close(correlationId)

        val events = withTimeout(2_000) { receiver.toList() }

        assertEquals(5, events.size)
        assertTrue(events[0] is QueueEvent.Processing)
        assertEquals("hello", (events[1] as QueueEvent.Token).text)
        assertEquals(" ", (events[2] as QueueEvent.Token).text)
        assertEquals("world", (events[3] as QueueEvent.Token).text)
        assertEquals(3, (events[4] as QueueEvent.Done).totalTokens)
    }

    @Test
    fun `emit before open is silently dropped`() {
        val bus = ChatResponseBus()

        bus.emit("unknown", QueueEvent.Token("lost"))

        assertTrue(bus.isOrphaned("unknown"))
    }

    @Test
    fun `emit after close is silently dropped`() = runBlocking {
        val bus = ChatResponseBus()
        val correlationId = "req-close"

        val receiver = bus.open(correlationId)
        bus.emit(correlationId, QueueEvent.Token("first"))
        bus.close(correlationId)

        bus.emit(correlationId, QueueEvent.Token("after-close"))

        val events = withTimeout(2_000) { receiver.toList() }
        assertEquals(1, events.size)
        assertEquals("first", (events[0] as QueueEvent.Token).text)
    }

    @Test
    fun `isOrphaned is false while receiver is active and true after close`() {
        val bus = ChatResponseBus()
        val correlationId = "req-orphan"

        assertTrue(bus.isOrphaned(correlationId), "unopened id is orphaned")

        bus.open(correlationId)
        assertFalse(bus.isOrphaned(correlationId), "open channel is not orphaned")

        bus.close(correlationId)
        assertTrue(bus.isOrphaned(correlationId), "closed channel is orphaned")
    }

    @Test
    fun `open is idempotent and returns the same channel`() = runBlocking {
        val bus = ChatResponseBus()
        val correlationId = "req-idempotent"

        val first = bus.open(correlationId)
        val second = bus.open(correlationId)

        bus.emit(correlationId, QueueEvent.Token("shared"))
        bus.close(correlationId)

        val firstEvents = withTimeout(2_000) { first.toList() }
        val secondEvents = withTimeout(2_000) { second.toList() }

        // Both references point at the same channel; the event can only be
        // consumed once. We assert one of the receivers got it and neither
        // returns events that the other's collect already drained.
        val combined = firstEvents + secondEvents
        assertEquals(1, combined.size, "same channel, single delivery")
        assertEquals("shared", (combined[0] as QueueEvent.Token).text)
    }

    @Test
    fun `100 buffered events before collection all arrive in order`() = runBlocking {
        val bus = ChatResponseBus()
        val correlationId = "req-burst"
        val receiver = bus.open(correlationId)

        repeat(100) { i -> bus.emit(correlationId, QueueEvent.Token("t$i")) }
        bus.close(correlationId)

        val events = withTimeout(5_000) { receiver.toList() }
        assertEquals(100, events.size)
        events.forEachIndexed { i, e ->
            assertEquals("t$i", (e as QueueEvent.Token).text)
        }
    }

    @Test
    fun `10 concurrent producer-consumer pairs do not cross-talk`() = runBlocking {
        val bus = ChatResponseBus()
        val ids = (1..10).map { "req-concurrent-$it" }
        val perIdEvents = 50

        val receivers = ids.associateWith { bus.open(it) }

        val producers = ids.map { id ->
            launch {
                repeat(perIdEvents) { i ->
                    bus.emit(id, QueueEvent.Token("$id#$i"))
                }
                bus.close(id)
            }
        }

        val consumers = ids.map { id ->
            async {
                val channel = receivers.getValue(id)
                val events = withTimeout(5_000) { channel.toList() }
                id to events.map { (it as QueueEvent.Token).text }
            }
        }

        producers.forEach { it.join() }
        val results = consumers.awaitAll().toMap()

        for (id in ids) {
            val tokens = results.getValue(id)
            assertEquals(perIdEvents, tokens.size, "channel $id should receive exactly $perIdEvents events")
            tokens.forEach { text ->
                assertTrue(text.startsWith("$id#"), "no cross-talk: $text must belong to $id")
            }
            tokens.forEachIndexed { i, text ->
                assertEquals("$id#$i", text, "order preserved per correlationId")
            }
        }

        for (id in ids) {
            assertTrue(bus.isOrphaned(id), "channel $id entry removed after close")
        }
    }

    @Test
    fun `close is idempotent`() {
        val bus = ChatResponseBus()
        val correlationId = "req-double-close"

        bus.open(correlationId)
        bus.close(correlationId)
        bus.close(correlationId)

        assertTrue(bus.isOrphaned(correlationId))
    }
}
