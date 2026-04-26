# ADR 0006: Queue-based chat dispatch with in-memory token bus

**Status:** Accepted (Phase 3)
**Date:** 2026-04-21

## Context

`docs/ARCHITECTURE.md` §10 names Apache Artemis as the queue for the chat pipeline but is deliberately vague about which messages traverse JMS and which do not. The concrete shape has to be decided here.

The workload has sharp, opinionated properties:

- Target load is 5–10 concurrent chat sessions on a single JVM.
- The dominant cost is the LLM call. Ollama on the target hardware runs one request at a time; true LLM parallelism is effectively 1.
- Users expect token-by-token streaming via SSE. First-token latency is the UX-critical metric; later tokens are largely CPU-bound on Ollama and arrive at whatever rate Ollama produces.
- There is no persistence requirement for in-flight chats. A client disconnect means the request is dead — the conversation is session-only (§16).
- Artemis already runs on the target servers for other purposes; reusing it is an explicit constraint in `CLAUDE.md`.

What has to be decided is therefore narrow: how does a streamed LLM response get from the consumer coroutine back to the HTTP handler that opened the SSE?

## Decision

Artemis carries only the **initial request**. Streaming tokens travel back through an **in-memory channel bus** inside the same JVM. There is no `aos.chat.responses` JMS queue.

Concretely:

1. `POST /api/chat` validates the body, generates a `correlationId`, opens a buffered `Channel<QueueEvent>` on the in-memory `ChatResponseBus` keyed by that id, and enqueues a `ChatRequest` onto `aos.chat.requests` with `JMSCorrelationID` set.
2. A single consumer coroutine (`ChatService`, parallelism = 1 via a `Semaphore(1)`) receives the request, runs embed → search → LLM-stream, and publishes each `QueueEvent` (`Processing`, `Token`, `Sources`, `Done`, `Error`) onto the bus under the same `correlationId`.
3. The SSE handler reads from the channel and writes each event as an SSE frame until `Done` or `Error`.
4. Queue **position** is read on demand via an Artemis `QueueBrowser` — best-effort, not ordered guarantees.

The bus uses `Channel<QueueEvent>(capacity = Channel.UNLIMITED)`, not `SharedFlow`. The route opens the channel **before** enqueueing so that events emitted by the consumer between enqueue and the route's collect loop are buffered, not dropped.

## Rationale

- **Token latency.** An in-JVM channel send is microseconds. Routing tokens through JMS would add per-token serialization, broker hops, and an ack protocol for data whose lifetime is already tied to the JVM.
- **Fair ordering.** Artemis gives us exactly what we need from a queue at this size: FIFO ordering under load. A client who enqueues second starts serving second. `QueueBrowser` gives us a crude "you are Nth in line" signal for the `queued` SSE event.
- **No durability requirement.** If the JVM dies, in-flight chats are lost anyway — the SSE connection is gone. We explicitly don't want message-level durability for responses; a redelivered half-stream would confuse the client.
- **Infrastructure reuse.** Artemis is already deployed. Adopting a second queueing system (e.g. Redis streams, Kafka) would violate the reuse constraint.
- **Serialization fits the hardware.** With true LLM parallelism capped at 1 by Ollama, a `Semaphore(1)` in `ChatService` is the correct concurrency model. Multiple JMS consumer threads would fight the same bottleneck and add nothing.

### Why a Channel and not a SharedFlow

The natural reach for "broadcast events by key" in Kotlin is `MutableSharedFlow(replay = 0)`. That is wrong here. A `SharedFlow` with zero replay drops any emission that happens before a subscriber calls `collect`. Our sequence has a natural race:

```
route: open() → enqueue() → respondSSE { for (e in receiver) ... }
                      ↑
                      consumer may pick up, emit Processing, even emit a Token
                      before the route enters the collect loop
```

With `SharedFlow`, those early emissions vanish. We'd have to introduce synchronization (a latch, a replayed cache) to close the window. With `Channel(UNLIMITED)`, the channel **is** the buffer: everything emitted from `open()` onward is retained until the receiver consumes it or the channel is closed.

## Consequences

- **Same-JVM constraint.** The SSE handler and the consumer coroutine must live in the same JVM. Splitting them into separate processes is a future decision — not needed at the current load, but if it becomes necessary, the response path has to be redesigned (at which point the `aos.chat.responses` queue idea comes back on the table).
- **Client disconnect behavior — known limitation.** When a client disconnects mid-stream, the SSE handler's `finally` block closes the bus entry. The consumer coroutine notices via `isOrphaned()` on its next check, but if it is already inside `llmService.generate(...)` it does **not** cancel the upstream Ollama HTTP call. It finishes reading the stream, drops the tokens, and moves on. This is deliberate for Phase 3 — cancelling the upstream call means threading `CoroutineContext` cancellation through the Ktor HttpClient call with careful body-closing semantics. Deferred to a follow-up ADR if the wasted Ollama cycles become measurable under real load.
- **Browser-based queue position is best-effort.** Artemis does not guarantee that `QueueBrowser` iteration order matches consumer delivery order under contention. At 5–10 users the divergence is invisible; at 500 it would matter. We accept the imprecision.
- **Backpressure is the LLM, not the bus.** The bus is unbounded; nothing backs up through the channel. If Ollama stalls, tokens stop being produced and the semaphore holds the next request — that is the correct place for backpressure to accumulate.
- **Gates stay in front of SSE.** Backfill-in-progress and Artemis-down both become pre-SSE 503s. The bus is only opened once those gates pass, so we never open a stream we cannot feed.

## Extension of ADR 0001

ADR 0001 committed to synchronous document upload. Phase 3 keeps that contract intact and extends the persist phase to include inline embedding via Ollama: chunks are embedded synchronously inside `DocumentService.processDocument` before they hit SQLite. The request/response shape of `POST /api/admin/documents` does not change. Upload latency now scales with document size plus Ollama embedding throughput, which is acceptable for the admin workflow.

No amendment to ADR 0001 is required. This ADR is the single authoritative pointer for the extended behavior.

## Alternatives considered

- **Full JMS request/response (`aos.chat.requests` + `aos.chat.responses`).** Rejected. Token-level latency, broker-side buffering, and cancellation semantics all get worse. Durability is not desired.
- **Kotlin `Channel` only, no Artemis.** Rejected. Works functionally but violates the infrastructure-reuse constraint in `CLAUDE.md` and discards the fair-ordering story Artemis gives us.
- **Separate worker JVM.** Deferred. Useful if chat concurrency grows to the point where the API JVM cannot also host the consumer, or if embedding work contends meaningfully with request handling. Not needed at current load.
- **`SharedFlow<QueueEvent>` keyed by `correlationId`.** Rejected. `replay = 0` races with route startup and drops early events; `replay = N` turns into an ad-hoc buffer with a guessed size. A channel is the right primitive.
- **`MessageListener` instead of a `receive` coroutine.** Rejected. Listener callbacks are not suspend functions; combined with the `Semaphore(1)` serialization requirement, they would force `runBlocking` on a JMS thread. The receive-loop coroutine pattern keeps JMS threads and coroutine dispatchers cleanly separated.
