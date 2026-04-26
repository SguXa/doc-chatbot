package com.aos.chatbot.services

import com.aos.chatbot.config.ArtemisConfig
import com.aos.chatbot.models.ChatMessage
import com.aos.chatbot.models.ChatRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueServiceTest {

    private lateinit var embedded: EmbeddedActiveMQ
    private lateinit var brokerUrl: String

    @BeforeAll
    fun startBroker() {
        val config = ConfigurationImpl().apply {
            isPersistenceEnabled = false
            isSecurityEnabled = false
            addAcceptorConfiguration("in-vm", "vm://0")
        }
        embedded = EmbeddedActiveMQ().apply {
            setConfiguration(config)
            start()
        }
        brokerUrl = "vm://0"
    }

    @AfterAll
    fun stopBroker() {
        runCatching { embedded.stop() }
    }

    @BeforeEach
    fun purgeQueue() {
        // Tests share the embedded broker but each expects a clean queue;
        // destroying it forces auto-recreation on the next producer/consumer
        // call and discards messages left behind by previous tests (e.g.,
        // getPosition enqueues that were never consumed).
        runCatching {
            embedded.activeMQServer.destroyQueue(SimpleString.toSimpleString(QueueService.QUEUE_NAME))
        }
    }

    private fun freshService(): QueueService {
        val cfg = ArtemisConfig(brokerUrl = brokerUrl, user = "", password = "")
        return QueueService(cfg).also { it.start() }
    }

    private fun newRequest(correlationId: String = UUID.randomUUID().toString(), message: String = "hello"): ChatRequest =
        ChatRequest(
            correlationId = correlationId,
            message = message,
            history = listOf(ChatMessage(role = "user", content = "prior")),
            enqueuedAt = "2026-04-21T10:00:00Z"
        )

    @Test
    fun `enqueue then consume delivers the same ChatRequest`() = runBlocking {
        val service = freshService()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val received = CompletableDeferred<ChatRequest>()
            val consumer = service.consume(scope) { req -> received.complete(req) }

            val sent = newRequest(message = "deliver-me")
            service.enqueue(sent)

            val delivered = withTimeout(5_000) { received.await() }
            assertEquals(sent.correlationId, delivered.correlationId)
            assertEquals("deliver-me", delivered.message)
            assertEquals(1, delivered.history.size)
            assertEquals("prior", delivered.history[0].content)

            consumer.cancelAndJoin()
            scope.cancel()
        } finally {
            service.stop()
        }
    }

    @Test
    fun `getPosition returns zero-based index when message is still queued`() = runBlocking {
        val service = freshService()
        try {
            val a = newRequest()
            val b = newRequest()
            val c = newRequest()
            service.enqueue(a)
            service.enqueue(b)
            service.enqueue(c)

            val pos = service.getPosition(b.correlationId)
            assertEquals(1, pos, "b is the second message in queue")

            val posA = service.getPosition(a.correlationId)
            assertEquals(0, posA)
        } finally {
            service.stop()
        }
    }

    @Test
    fun `getPosition returns -1 for unknown correlationId`() = runBlocking {
        val service = freshService()
        try {
            assertEquals(-1, service.getPosition(UUID.randomUUID().toString()))
        } finally {
            service.stop()
        }
    }

    @Test
    fun `consumer handler exception does not kill the loop`() = runBlocking {
        val service = freshService()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val failingId = UUID.randomUUID().toString()
            val goodId = UUID.randomUUID().toString()
            val failingRedelivered = CompletableDeferred<ChatRequest>()
            val goodReceived = CompletableDeferred<ChatRequest>()

            // CLIENT_ACKNOWLEDGE would silently ack the failing message when
            // the good message's acknowledge() runs; we rely on session.recover()
            // in the handler-exception path to force a real redelivery. Both
            // deferreds MUST complete — if the failing message is never
            // redelivered, failingRedelivered.await() will time out and the
            // test fails.
            val failingAttempts = AtomicInteger(0)
            val consumer = service.consume(scope) { req ->
                when (req.correlationId) {
                    failingId -> {
                        if (failingAttempts.incrementAndGet() == 1) {
                            throw RuntimeException("boom")
                        } else {
                            failingRedelivered.complete(req)
                        }
                    }
                    goodId -> {
                        goodReceived.complete(req)
                    }
                }
            }

            service.enqueue(newRequest(correlationId = failingId))
            service.enqueue(newRequest(correlationId = goodId))

            withTimeout(10_000) { goodReceived.await() }
            withTimeout(10_000) { failingRedelivered.await() }

            consumer.cancelAndJoin()
            scope.cancel()
        } finally {
            service.stop()
        }
    }

    @Test
    fun `enqueue to a stopped service throws IllegalStateException`() {
        runBlocking {
            val service = QueueService(ArtemisConfig(brokerUrl = brokerUrl, user = "", password = ""))
            // Never call start(); service is effectively stopped.
            assertFailsWith<IllegalStateException> {
                service.enqueue(newRequest())
            }
        }
    }

    @Test
    fun `enqueue after explicit stop throws IllegalStateException`() {
        runBlocking {
            val service = freshService()
            service.stop()
            assertFailsWith<IllegalStateException> {
                service.enqueue(newRequest())
            }
        }
    }

    @Test
    fun `stop is idempotent`() {
        val service = freshService()
        service.stop()
        service.stop() // second call must not throw
        assertFalse(service.isConnected())
    }

    @Test
    fun `isConnected reflects start and stop`() {
        val service = freshService()
        assertTrue(service.isConnected())
        service.stop()
        assertFalse(service.isConnected())
    }

    @Test
    fun `consumer coroutine exits cleanly on cancel`() = runBlocking {
        val service = freshService()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val job = service.consume(scope) { /* noop */ }

            // Let the receive loop run at least once.
            delay(150)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            service.stop()
        }
    }

    @Test
    fun `correlationId is preserved as a JMS string property`() = runBlocking {
        val service = freshService()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val received = CompletableDeferred<ChatRequest>()
            val id = UUID.randomUUID().toString()

            service.enqueue(newRequest(correlationId = id))

            // Position lookup relies on the `correlationId` string property being set.
            val pos = service.getPosition(id)
            assertEquals(0, pos)

            val consumer = service.consume(scope) { req -> received.complete(req) }
            val delivered = withTimeout(5_000) { received.await() }
            assertEquals(id, delivered.correlationId)

            consumer.cancelAndJoin()
            scope.cancel()
        } finally {
            service.stop()
        }
    }

    @Test
    fun `multiple concurrent enqueues all land on the queue`() = runBlocking {
        val service = freshService()
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val total = 20
            val ids = (1..total).map { UUID.randomUUID().toString() }

            val jobs = ids.map { id ->
                scope.launch { service.enqueue(newRequest(correlationId = id, message = id)) }
            }
            jobs.forEach { it.join() }

            val seen = mutableSetOf<String>()
            val completed = CompletableDeferred<Unit>()
            val consumer = service.consume(scope) { req ->
                synchronized(seen) {
                    seen.add(req.correlationId)
                    if (seen.size == total && !completed.isCompleted) completed.complete(Unit)
                }
            }

            val done = withTimeoutOrNull(10_000) { completed.await() }
            assertNotNull(done, "expected all $total messages within timeout, got ${seen.size}")
            assertEquals(total, seen.size)

            consumer.cancelAndJoin()
            scope.cancel()
        } finally {
            service.stop()
        }
    }
}
