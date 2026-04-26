package com.aos.chatbot.services

import com.aos.chatbot.config.ArtemisConfig
import com.aos.chatbot.models.ChatRequest
import jakarta.jms.Connection
import jakarta.jms.JMSException
import jakarta.jms.Message
import jakarta.jms.MessageConsumer
import jakarta.jms.QueueBrowser
import jakarta.jms.Session
import jakarta.jms.TextMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Thin wrapper around Apache Artemis's JMS client.
 *
 * Publishes [ChatRequest] payloads onto the `aos.chat.requests` queue for fair
 * ordering across concurrent chat sessions, and exposes a coroutine-driven
 * consumer loop that hands each dequeued request to a suspend handler.
 *
 * Tokens never traverse JMS — see ADR 0006. Only the initial request with its
 * `correlationId` goes through Artemis; the response stream rides an in-memory
 * [ChatResponseBus].
 *
 * Thread model:
 *  - A single shared [Connection] is opened in [start] and reused for the
 *    lifetime of the service.
 *  - [enqueue] creates and closes a per-call producer session on
 *    `Dispatchers.IO`; JMS `Session` objects are NOT thread-safe, so a fresh
 *    session per send keeps concurrent enqueues safe.
 *  - [consume] opens ONE dedicated `CLIENT_ACKNOWLEDGE` session for its receive
 *    loop, isolated from the producer sessions.
 *  - [getPosition] uses a short-lived session + `QueueBrowser` that is closed
 *    synchronously before returning.
 *
 * A [MessageListener] callback was rejected because it is not a suspend
 * function. Combined with the `Semaphore(1)` the ChatService uses for LLM
 * serialization, a listener would force `runBlocking` on the JMS dispatcher
 * thread and block the consumer while a request is being handled. The receive-
 * loop coroutine pattern lets suspension happen on a coroutine-managed
 * dispatcher instead.
 */
class QueueService(private val config: ArtemisConfig) {

    private val log = LoggerFactory.getLogger(QueueService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val started = AtomicBoolean(false)

    @Volatile private var connection: Connection? = null

    private val consumerSessionsLock = Any()
    private val consumerSessions = mutableListOf<Session>()

    companion object {
        const val QUEUE_NAME: String = "aos.chat.requests"
        private const val RECEIVE_TIMEOUT_MS: Long = 1_000L
    }

    /**
     * Opens and starts the JMS connection. Idempotent — subsequent calls are
     * no-ops while the service is already running.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        var conn: Connection? = null
        try {
            val factory = ActiveMQConnectionFactory(config.brokerUrl)
            conn = if (config.user.isNotEmpty()) {
                factory.createConnection(config.user, config.password)
            } else {
                factory.createConnection()
            }
            conn.start()
            this.connection = conn
        } catch (e: Exception) {
            runCatching { conn?.close() }
            started.set(false)
            throw IllegalStateException("Failed to start Artemis queue service", e)
        }
    }

    /**
     * Closes consumer sessions and the shared connection. Idempotent; errors
     * during close are logged and swallowed so shutdown is best-effort.
     */
    fun stop() {
        if (!started.getAndSet(false)) return
        synchronized(consumerSessionsLock) {
            consumerSessions.forEach { runCatching { it.close() } }
            consumerSessions.clear()
        }
        runCatching { connection?.close() }
        connection = null
    }

    /**
     * Returns `true` once [start] has succeeded and [stop] has not been called.
     * Backing the Phase 3 `/api/health/ready` queue probe.
     */
    fun isConnected(): Boolean = started.get() && connection != null

    /**
     * Serializes [request] as JSON and enqueues it onto `aos.chat.requests`.
     *
     * Uses a fresh per-call session on `Dispatchers.IO` because JMS sessions
     * are not thread-safe. `JMSCorrelationID` and the string property
     * `correlationId` are both set so browsers, routing and redelivery paths
     * can match the request by ID.
     *
     * Any underlying JMS failure is rewrapped as [IllegalStateException] with
     * message `"Artemis queue unavailable"` — `ChatRoutes` maps this to a
     * pre-SSE 503.
     */
    suspend fun enqueue(request: ChatRequest) {
        if (!started.get()) {
            throw IllegalStateException("Artemis queue unavailable: service not started")
        }
        val conn = connection
            ?: throw IllegalStateException("Artemis queue unavailable: no connection")
        val payload = json.encodeToString(ChatRequest.serializer(), request)
        withContext(Dispatchers.IO) {
            val session = try {
                conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
            } catch (e: JMSException) {
                throw IllegalStateException("Artemis queue unavailable", e)
            }
            try {
                val producer = try {
                    val q = session.createQueue(QUEUE_NAME)
                    session.createProducer(q)
                } catch (e: JMSException) {
                    throw IllegalStateException("Artemis queue unavailable", e)
                }
                try {
                    val message: TextMessage = session.createTextMessage(payload).apply {
                        jmsCorrelationID = request.correlationId
                        setStringProperty("correlationId", request.correlationId)
                    }
                    producer.send(message)
                } catch (e: JMSException) {
                    throw IllegalStateException("Artemis queue unavailable", e)
                } finally {
                    runCatching { producer.close() }
                }
            } finally {
                runCatching { session.close() }
            }
        }
    }

    /**
     * Returns the 0-based position of the request with matching
     * [correlationId] in the queue, or `-1` if it has already been consumed
     * or the broker is unreachable.
     *
     * Browser iteration is a best-effort snapshot — Artemis may reorder
     * messages under load, which is acceptable at the 5–10 concurrent user
     * target.
     *
     * The JMS browse is blocking; it runs on `Dispatchers.IO` so Ktor's HTTP
     * worker threads are not held up when the broker stalls.
     */
    suspend fun getPosition(correlationId: String): Int {
        if (!started.get()) return -1
        val conn = connection ?: return -1
        return withContext(Dispatchers.IO) {
            try {
                val session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
                try {
                    val q = session.createQueue(QUEUE_NAME)
                    val browser: QueueBrowser = session.createBrowser(q)
                    try {
                        val iter = browser.enumeration
                        var index = 0
                        while (iter.hasMoreElements()) {
                            val msg = iter.nextElement() as Message
                            val id = runCatching { msg.getStringProperty("correlationId") }.getOrNull()
                            if (id == correlationId) return@withContext index
                            index++
                        }
                        -1
                    } finally {
                        runCatching { browser.close() }
                    }
                } finally {
                    runCatching { session.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to browse queue for position: ${e.message}")
                -1
            }
        }
    }

    /**
     * Launches a receive-loop coroutine on [scope] that dequeues each
     * [ChatRequest] and passes it to [handler]. Acknowledgement is deferred
     * until the handler returns successfully; a thrown handler leaves the
     * message unacknowledged so Artemis can redeliver on the next consumer
     * cycle.
     *
     * The returned [Job] is the loop itself — cancel it to stop consuming.
     * Cancellation closes the consumer and its dedicated session.
     */
    suspend fun consume(scope: CoroutineScope, handler: suspend (ChatRequest) -> Unit): Job {
        if (!started.get()) {
            throw IllegalStateException("Artemis queue unavailable: service not started")
        }
        val conn = connection
            ?: throw IllegalStateException("Artemis queue unavailable: no connection")

        val session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE)
        synchronized(consumerSessionsLock) { consumerSessions.add(session) }
        val consumer: MessageConsumer = try {
            val q = session.createQueue(QUEUE_NAME)
            session.createConsumer(q)
        } catch (e: JMSException) {
            runCatching { session.close() }
            synchronized(consumerSessionsLock) { consumerSessions.remove(session) }
            throw IllegalStateException("Artemis queue unavailable", e)
        }

        return scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    coroutineContext.ensureActive()
                    val msg: Message? = try {
                        consumer.receive(RECEIVE_TIMEOUT_MS)
                    } catch (e: jakarta.jms.IllegalStateException) {
                        // Consumer or session was closed out from under the
                        // loop, typically by a concurrent stop(). Exit cleanly
                        // rather than spinning on a permanently-broken receive.
                        break
                    } catch (e: JMSException) {
                        if (!isActive) break
                        log.error("Consumer receive failed: ${e.message}", e)
                        null
                    }
                    if (msg == null) continue
                    if (msg !is TextMessage) {
                        log.warn("Ignoring non-text JMS message on $QUEUE_NAME")
                        runCatching { msg.acknowledge() }
                        continue
                    }
                    val text = runCatching { msg.text }.getOrNull()
                    if (text.isNullOrBlank()) {
                        log.warn("Ignoring empty TextMessage on $QUEUE_NAME")
                        runCatching { msg.acknowledge() }
                        continue
                    }
                    val request = try {
                        json.decodeFromString(ChatRequest.serializer(), text)
                    } catch (e: Exception) {
                        log.error("Failed to parse ChatRequest JSON: ${e.message}")
                        runCatching { msg.acknowledge() }
                        continue
                    }
                    try {
                        handler(request)
                        runCatching { msg.acknowledge() }
                    } catch (e: CancellationException) {
                        // Cancellation must unwind the receive loop; don't treat
                        // shutdown as a handler failure that Artemis should retry.
                        throw e
                    } catch (e: Exception) {
                        log.error(
                            "Handler failed for correlationId=${request.correlationId}: ${e.message}",
                            e
                        )
                        // CLIENT_ACKNOWLEDGE semantics: calling acknowledge() on
                        // any later message also acks every earlier message
                        // consumed on this session. Simply skipping acknowledge()
                        // here would let the next successful message silently
                        // ack this failed one, so Artemis would never redeliver.
                        // session.recover() resets the session's delivery
                        // position to the first unacked message, forcing the
                        // broker to redeliver it. The ChatService orchestrator
                        // is itself responsible for emitting Error events to
                        // the bus and closing it, so a redelivery for an
                        // already-orphaned correlationId is a cheap no-op
                        // inside the handler.
                        runCatching { session.recover() }
                    }
                }
            } finally {
                runCatching { consumer.close() }
                runCatching { session.close() }
                synchronized(consumerSessionsLock) { consumerSessions.remove(session) }
            }
        }
    }
}
