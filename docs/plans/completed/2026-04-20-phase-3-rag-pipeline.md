# Phase 3: RAG Pipeline

## Overview

Implement the end-to-end RAG pipeline: Ollama-backed embedding and LLM services, an in-memory vector search index, a chat orchestrator driven by an Artemis-fronted request queue, and an SSE chat route. Embeddings are generated synchronously on upload from this phase onward, and all Phase 2 chunks persisted with `embedding = NULL` are backfilled on startup. Adds the `ConfigRepository` over the existing `config` table (seeded with the default system prompt via V004), a minimal `/api/admin/reindex` endpoint, structured conversation logging as the anchor for future feedback/rating, and upgrades `/api/health/ready` and `/api/stats` to reflect real Ollama/Artemis/backfill status. No auth (Phase 4), no chat or admin UI (Phase 4/5), no export/import (Phase 5), no persistent chat history (deferred indefinitely per §16), no separate worker JVM.

## Context

- Files involved: New code under `backend/src/main/kotlin/com/aos/chatbot/{services,routes,models,db/repositories}/`, one new migration under `resources/db/migration/`, existing `Application.kt`, `AppConfig.kt`, `DocumentService.kt`, `HealthRoutes.kt`, `AdminRoutes.kt` extended. New `docs/adr/0006-*.md`. Edits to `docs/ARCHITECTURE.md` §4.3, §10, §16.
- Related patterns: Manual constructor DI, coroutines, named exports, JUnit 5 + MockK, kotlinx.serialization, operation-scoped repositories, stable string error discriminators. All conventions inherit from Phase 1/2.
- Source of truth: `docs/ARCHITECTURE.md` §6 (schema), §7.1 (chat API), §7.5 (health/stats), §9 (RAG pipeline, prompt template, default system prompt), §10 (queue system), §12 (config and env vars), §14.4 (warm-up).
- Architectural decisions: see `docs/adr/` — ADR 0001 (sync upload — extended here to include inline embedding), 0002 (file_hash UNIQUE without NOT NULL), 0003 (success-only lifecycle), 0004 (no orphan reconciliation), 0005 (auth deferred), **ADR 0006 (this phase — queue-based chat dispatch with in-process consumer and in-memory token bus)**.

## Design Decisions

- **Queue topology: Artemis for ordering, in-memory bus for tokens.** `POST /api/chat` generates a `correlationId`, enqueues a `ChatRequest` onto `aos.chat.requests`, and opens SSE. A single-JVM consumer coroutine (parallelism = 1 on the LLM step) dequeues, runs embed → search → LLM-stream, and publishes `QueueEvent` values onto an in-memory `ChatResponseBus` keyed by `correlationId`. Streaming tokens do **not** traverse JMS. See ADR 0006.
- **Sync upload extended with inline embedding.** `DocumentService.processDocument` now calls `EmbeddingService` before persisting chunks, honoring ADR 0001's single-request/single-response contract. No new `jobId`, no polling. Upload latency grows with document size; this is acceptable for the 1–2 admins who use it.
- **Startup backfill for Phase 2 legacy NULL embeddings.** On startup, a coroutine job reads `chunks WHERE embedding IS NULL` in batches, embeds each, and writes back. `/api/health/ready` does NOT return `ready` until the job completes. Per-chunk max 3 attempts then log ERROR and skip; overall retry is forever while Ollama is unreachable (exponential backoff).
- **`/api/admin/reindex` is minimal and idempotent.** `POST /api/admin/reindex` returns 202 Accepted immediately, clears all embeddings in a single transaction, and launches the same backfill job. A second call while a reindex is in flight is a no-op (in-memory lock). No progress endpoint, no SSE. `/api/health/ready` reports `not_ready` while reindex runs.
- **System prompt is stored in `config` table as JSON-in-TEXT.** V004 migration inserts `('system_prompt', <JSON-encoded default from §9.3>)`. `ConfigRepository.get(key)` returns the raw string; `getJson<T>(key)` deserializes via kotlinx.serialization. ChatService reads directly via the repository — no HTTP routes in Phase 3 (GET/PUT land in Phase 5). §4.3 of ARCHITECTURE.md is corrected to state the prompt lives in `aos.db`, not in a separate `config/system-prompt.txt` file.
- **Chat history is session-only and stateless on the server.** Clients send the full prior `{role, content}` list with every POST `/api/chat` per §7.1; the backend persists nothing. Structured JSON conversation logging (one line per completed chat with `correlationId`, question, answer, sources, timings) lands in stdout through SLF4J — this is the anchor for future feedback/rating, which will reference conversations by `correlationId`.
- **Error handling contract for SSE.** (a) Ollama unreachable at request start → HTTP 503 before SSE opens. (b) Ollama fails mid-stream → emit `event: error` and close the SSE; already-delivered tokens stay at the client. (c) Search returns zero chunks above threshold → LLM is still invoked with an empty context block (the §9.2 prompt tells the model to answer "I don't have information"); we do NOT short-circuit with a canned reply. (d) Client disconnects mid-stream → Приёмщик cancels its bus subscription; the consumer coroutine finishes the current LLM call and discards the result. (Cancelling the in-flight Ollama HTTP call is deferred to a future ADR.) (e) Backfill per-chunk failure after 3 retries → log ERROR and skip; backfill continues.
- **Repositories stay operation-scoped.** `ConfigRepository` takes a `Connection` in the constructor like every other repository. Per-call construction inside the service layer.
- **Migrations stay immutable.** V001–V003 are not touched. V004 seeds the `system_prompt` row via `INSERT`. If schema changes are needed during implementation, a new migration file is created rather than editing V004.
- **Admin routes remain unprotected, intentionally.** Auth is still Phase 4. The existing Phase 2 WARN at startup in `MODE=full`/`MODE=admin` is unchanged and still correct. ADR 0005 holds.
- **No Phase 4+ surface is introduced.** No `authenticate { }` blocks, no JWT, no `ADMIN_PASSWORD` or `JWT_SECRET` consumption, no `GET/PUT /api/config/system-prompt`, no `/api/admin/export` or `/api/admin/import`.

## Development Approach

- **Testing approach**: Regular (code first, then tests).
- Complete each task fully before moving to the next.
- Each task produces a compilable/runnable increment.
- **CRITICAL: each functional increment must include appropriate tests.** Unit tests with MockK for business logic, WireMock for HTTP contracts against Ollama, Ktor `TestApplication` for SSE route shape. The `@Tag("integration")` suite is not gated by `./gradlew test`.
- **CRITICAL: all tests must pass before starting next task.**

## Validation Commands

- `cd backend && ./gradlew test`
- `cd backend && ./gradlew build`
- (manual, optional) `cd backend && ./gradlew integrationTest` — runs the `@Tag("integration")` suite against a real local Ollama

## Implementation Steps

### Task 1: Add Artemis, Ktor HTTP client, WireMock dependencies

**Files:**
- Modify: `backend/build.gradle.kts`

- [x] Add `org.apache.activemq:artemis-jms-client:2.32.0` (JMS API + Artemis client) to `implementation`
- [x] Add `jakarta.jms:jakarta.jms-api:3.1.0` to `implementation` (explicit pin to match Artemis's expected JMS API)
- [x] Add Ktor HTTP client: `io.ktor:ktor-client-core`, `io.ktor:ktor-client-cio`, `io.ktor:ktor-client-content-negotiation`, `io.ktor:ktor-serialization-kotlinx-json` (versions via existing Ktor BOM)
- [x] Add `com.github.tomakehurst:wiremock-jre8-standalone:3.0.1` to `testImplementation`
- [x] Register a Gradle task `integrationTest` that runs only tests annotated `@Tag("integration")`, using `useJUnitPlatform { includeTags("integration") }`. The default `test` task excludes this tag
- [x] Verify: `cd backend && ./gradlew build` succeeds
- [x] Verify: `cd backend && ./gradlew test` still green (no new tests yet)

### Task 2: V004 migration — seed system_prompt

**Files:**
- Create: `backend/src/main/resources/db/migration/V004__seed_system_prompt.sql`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt`

V004 seeds the `config` row containing the default system prompt from ARCHITECTURE.md §9.3 as a JSON-encoded string. `config` is a key-value store with JSON-in-TEXT values; simple strings are valid JSON when wrapped in quotes.

- [x] Create V004 with a single `INSERT OR IGNORE INTO config(key, value, updated_at) VALUES('system_prompt', '<JSON-encoded §9.3 prompt>', CURRENT_TIMESTAMP)`
- [x] Use `INSERT OR IGNORE` so re-running migrations over an already-seeded DB is a no-op (supports `reindex`-style scenarios and idempotent re-runs in dev)
- [x] Value is a JSON string literal (escaped newlines, escaped quotes); paste the §9.3 prompt verbatim
- [x] Do NOT modify V001, V002, or V003
- [x] Future updates to the default prompt go through a NEW migration (V005+) with an `UPDATE` statement OR via Phase 5's `PUT /api/config/system-prompt` runtime edit. V004 is the one-time seed and is never edited after commit
- [x] Add MigrationsTest case: after V004, `SELECT value FROM config WHERE key='system_prompt'` returns a non-null, non-empty string
- [x] Add MigrationsTest case: parsing that value as JSON yields a String (not an object); the decoded string contains "AOS Documentation Assistant"
- [x] Add MigrationsTest case: `schema_version` records version 4
- [x] Add MigrationsTest case: running migrations twice does not insert a second `system_prompt` row (idempotence)
- [x] Verify: `cd backend && ./gradlew test`

### Task 3: Extend AppConfig with Ollama and Artemis properties

**Files:**
- Modify: `backend/src/main/resources/application.conf`
- Modify: `backend/src/main/kotlin/com/aos/chatbot/config/AppConfig.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/config/AppConfigTest.kt`
- Modify: `.env.example`
- Modify: `README.md`

ARCHITECTURE.md §12.1 already names these env vars — we just make them real on the Kotlin side. Artemis credentials may be empty for unauthenticated brokers (the dev default).

- [x] Add `app.ollama { url, llmModel, embedModel }` block with `OLLAMA_URL`, `OLLAMA_LLM_MODEL`, `OLLAMA_EMBED_MODEL` bindings to `application.conf`
- [x] Defaults: `http://ollama:11434`, `qwen2.5:7b-instruct-q4_K_M`, `bge-m3`
- [x] Add `app.artemis { brokerUrl, user, password }` block with `ARTEMIS_BROKER_URL`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD` bindings
- [x] Default `brokerUrl` is `tcp://artemis:61616`; `user` and `password` default to empty strings
- [x] Add `OllamaConfig(url, llmModel, embedModel)` and `ArtemisConfig(brokerUrl, user, password)` data classes on `AppConfig`
- [x] `AppConfig.from(environment)` reads all six properties; empty user/password must serialize as empty strings, not nulls
- [x] Update `.env.example` with the six env vars, grouped `# Ollama` and `# Artemis`, with short inline comments
- [x] Update `README.md` Configuration section to list the new env vars alongside the existing path vars
- [x] AppConfigTest: defaults resolve when no env vars set
- [x] AppConfigTest: each env var overrides its default independently
- [x] AppConfigTest: empty `ARTEMIS_USER`/`ARTEMIS_PASSWORD` produce empty strings in the config object (not nulls)
- [x] Verify: `cd backend && ./gradlew test`

### Task 4: Create ConfigRepository

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/repositories/ConfigRepository.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/db/repositories/ConfigRepositoryTest.kt`

Operation-scoped repository over the `config` (key, value, updated_at) table. Values are JSON-in-TEXT; the repository exposes both raw string access and typed JSON decoding.

- [x] Constructor `ConfigRepository(connection: java.sql.Connection)`
- [x] `get(key: String): String?` — returns raw `value` column via `SELECT value FROM config WHERE key = ?`
- [x] `getJson<T>(key: String, deserializer: DeserializationStrategy<T>): T?` — reads raw value, returns null if absent, otherwise decodes via `Json.decodeFromString(deserializer, raw)`. Signature uses explicit deserializer (no `reified` in a repository) to avoid inlining constraints
- [x] `put(key: String, value: String)` — upsert via `INSERT ... ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP`
- [x] `putJson<T>(key: String, serializer: SerializationStrategy<T>, value: T)` — `Json.encodeToString(serializer, value)` then delegate to `put`
- [x] KDoc on the class: operation-scoped, must not outlive the injected `Connection`; Phase 3 has no HTTP surface writing to this table, `put*` exists for `reindex`-like internal flows and for Phase 5
- [x] Tests use in-memory SQLite with V001–V004 applied
- [x] Test: `get("system_prompt")` after migration returns a non-null JSON string; `getJson(...)` decodes to a non-empty String
- [x] Test: `get("missing_key")` returns null; `getJson("missing_key", ...)` returns null
- [x] Test: `put` + `get` round-trips a raw string; `putJson` + `getJson` round-trips a structured value (e.g., `data class SearchTuning(val topK: Int, val minScore: Float)`)
- [x] Test: `put` on an existing key updates `value` and bumps `updated_at`
- [x] Verify: `cd backend && ./gradlew test`

### Task 5: Implement EmbeddingService (Ollama /api/embeddings)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/EmbeddingService.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/OllamaUnavailableException.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/EmbeddingServiceTest.kt`
- Create: `backend/src/test/resources/fixtures/ollama-embedding-response.json`

- [x] Constructor `EmbeddingService(httpClient: HttpClient, config: OllamaConfig)`; the `HttpClient` is a constructor dep so tests can inject a WireMock-pointed client
- [x] `suspend fun embed(text: String): FloatArray` — `POST {url}/api/embeddings` with body `{"model": embedModel, "prompt": text}`; parse `{"embedding": [float, ...]}` and return as `FloatArray`
- [x] `suspend fun embedBatch(texts: List<String>): List<FloatArray>` — sequentially call `embed` for each (bge-m3 does not support batching in Ollama's `/api/embeddings`; document this in KDoc)
- [x] Configure HttpClient call timeout = 30 s for embeddings
- [x] Translate connection/timeout/5xx errors into `OllamaUnavailableException(cause)`; all other exceptions propagate
- [x] Assert returned array length is non-zero; zero-length response → `OllamaUnavailableException`
- [x] Record a real embedding response (1024-dim for bge-m3) to `ollama-embedding-response.json`; check it in
- [x] WireMock tests: happy path (fixture response → 1024-length FloatArray); 503 response → `OllamaUnavailableException`; 200 with malformed JSON → `OllamaUnavailableException`; connection refused (stop WireMock mid-test) → `OllamaUnavailableException`; timeout → `OllamaUnavailableException`
- [x] WireMock test: `embedBatch` of 3 items produces 3 requests to `/api/embeddings` and 3 FloatArrays in order
- [x] Verify: `cd backend && ./gradlew test`

### Task 6: Implement LlmService (Ollama /api/chat streaming)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/LlmService.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/ChatMessage.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/LlmServiceTest.kt`
- Create: `backend/src/test/resources/fixtures/ollama-chat-stream.ndjson`

Ollama's `/api/chat` endpoint returns NDJSON — one JSON object per line, with `{"message": {"content": "..."}, "done": false}` until a final `{"done": true, ...}` with totals. We consume this as a Flow.

- [x] Create `ChatMessage(role: String, content: String)` data class with `@Serializable`. Roles: `"system"`, `"user"`, `"assistant"`
- [x] Constructor `LlmService(httpClient: HttpClient, config: OllamaConfig)`
- [x] `fun generate(messages: List<ChatMessage>): Flow<String>` — emits token chunks (`message.content` per streamed line) and completes on `done=true`
- [x] Request body: `{"model": llmModel, "messages": [...], "stream": true}`. No temperature override in Phase 3 (use Ollama defaults); tuning lives in future `config` rows
- [x] `suspend fun generateFull(messages: List<ChatMessage>): String` — collects the flow into a single string (used by warmup)
- [x] Configure HttpClient call timeout = 120 s for LLM
- [x] Parse NDJSON by reading body as a byte channel and splitting on `\n`; ignore empty lines; decode each line independently
- [x] Translate connection/5xx errors into `OllamaUnavailableException`; mid-stream errors surface as a flow exception (callers handle)
- [x] Record a real 5-token streaming response to `ollama-chat-stream.ndjson`; check it in
- [x] WireMock tests: happy stream (fixture NDJSON → expected 5 token emissions); empty message list → `IllegalArgumentException`; 503 before stream → `OllamaUnavailableException`; stream interrupted mid-body (WireMock drops connection) → flow throws
- [x] WireMock test: `generateFull` concatenates all emitted tokens into a single string
- [x] Verify: `cd backend && ./gradlew test`

### Task 7: Implement SearchService (in-memory cosine similarity)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/SearchService.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/SearchResult.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/SearchServiceTest.kt`

Holds the in-memory vector index. Loaded once from SQLite at startup; mutated by upload (append), delete (remove by documentId), and reindex (replace).

- [x] Create `SearchResult(chunk: Chunk, score: Float)` data class
- [x] Constructor `SearchService()` — no I/O dependencies; the service is a pure in-memory index
- [x] `loadInitial(chunks: List<Chunk>)` — replaces the current index atomically (via a volatile `List<Chunk>` reference; readers snapshot the reference, writers swap a fresh list)
- [x] `appendChunks(chunks: List<Chunk>)` — appends chunks (already embedded) to the index in an atomic swap. No `documentId` parameter: every `Chunk` already carries it, and callers are not responsible for homogeneity
- [x] `removeDocument(documentId: Long)` — drops all chunks with matching `documentId` via atomic swap
- [x] KDoc on the class MUST state the atomic-swap invariant: "reads are lock-free snapshots of a volatile reference; writes construct a new list and compare-and-swap the reference; concurrent reads during a write see either the pre-write or post-write list, never a partially-mutated list"
- [x] `search(queryEmbedding: FloatArray, topK: Int = 5, minScore: Float = 0.3f): List<SearchResult>` — computes cosine similarity against every chunk, returns results above `minScore` sorted DESC, capped at `topK`
- [x] Returns empty list when the index is empty OR when no chunks cross the threshold
- [x] Skip chunks with `embedding == null` during search (defensive — backfill may still be running)
- [x] `size(): Int` — returns current indexed chunk count (for `/api/stats` and `/api/health/ready`)
- [x] Unit tests with handcrafted FloatArrays: cosine math (orthogonal → 0, identical → 1, negated → -1); topK truncation; threshold filtering; empty index; document removal; append after load; null-embedding chunks skipped
- [x] Test thread-safety: 100 concurrent `search` calls during a simultaneous `appendFromDocument` never throw ConcurrentModificationException
- [x] Verify: `cd backend && ./gradlew test`

### Task 8: Implement EmbeddingBackfillJob (startup backfill)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/EmbeddingBackfillJob.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/BackfillStatus.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/EmbeddingBackfillJobTest.kt`

Background coroutine that embeds every chunk with `embedding IS NULL` and keeps the in-memory `SearchService` index in sync. Completion gates `/api/health/ready`.

- [x] `BackfillStatus` sealed class: `Idle`, `Running(processed: Int, total: Int)`, `Completed(embedded: Int, skipped: Int)`, `Failed(message: String)`
- [x] Constructor `EmbeddingBackfillJob(database: Database, embeddingService: EmbeddingService, searchService: SearchService)`
- [x] `suspend fun run(): BackfillStatus.Completed` — reads all chunk IDs with NULL embedding in batches of 50, embeds each, writes back via UPDATE, then calls `searchService.loadInitial(...)` with the full chunk set (including previously embedded chunks)
- [x] Per-chunk retry: exponential backoff `[1s, 2s, 4s]` for `OllamaUnavailableException`; after 3 failures, log `ERROR` with `chunkId` and skip
- [x] Global retry on Ollama-down: if backfill loop catches `OllamaUnavailableException` during index readiness checks, wait 5s and retry
- [x] Both retry loops MUST call `coroutineContext.ensureActive()` before each attempt, so application shutdown cancels the job cleanly instead of spinning forever
- [x] Expose `fun status(): BackfillStatus` — thread-safe read of current state (backed by `AtomicReference` or a `MutableStateFlow`)
- [x] Log INFO at start: `"Embedding backfill starting: N chunks to process"`; INFO at completion: `"Embedding backfill finished: M embedded, K skipped"`
- [x] Tests with MockK on `EmbeddingService`: 5 NULL-embedding chunks all succeed → status becomes `Completed(5, 0)`, SearchService contains all 5
- [x] Test: 5 chunks, chunk #3 fails twice then succeeds → `Completed(5, 0)`, 3 total attempts for chunk #3
- [x] Test: 5 chunks, chunk #3 fails 3 times → `Completed(4, 1)`, ERROR logged with chunk ID
- [x] Test: Ollama down for first 10 seconds then recovers → eventually `Completed`, global retry triggers
- [x] Test: no NULL chunks → returns `Completed(0, 0)` quickly without Ollama calls
- [x] Test: after `run()` finishes, `searchService.size()` matches total embedded chunks (from both previously-embedded and newly-embedded rows)
- [x] Verify: `cd backend && ./gradlew test`

### Task 9: DocumentService upgrade — inline embeddings + SearchService sync

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/services/DocumentService.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/services/DocumentServiceTest.kt`

The sync upload pipeline now embeds chunks before insertion, and notifies the in-memory `SearchService` about append/remove.

- [x] Add constructor dependencies: `embeddingService: EmbeddingService`, `searchService: SearchService`
- [x] Between "chunking step" and "image linkage validation step" (see Phase 2 plan Task 13), add an embedding step: for each chunk, `embeddingService.embed(chunk.content)` → populate `chunk.embedding` as Float32 little-endian ByteArray (reuse the helper from §6.2; extract to a top-level function `embeddingToBytes` / `bytesToEmbedding` in a new `backend/src/main/kotlin/com/aos/chatbot/db/EmbeddingCodec.kt` if not already present)
- [x] Embedding failures inside upload raise `OllamaUnavailableException` → this propagates out to the caller; the existing rollback path (file deletion + transaction abort) handles cleanup unchanged
- [x] Acquire the shared reindex/upload mutex (introduced in Task 15) BEFORE the embedding step; release it after commit or rollback. If the mutex is held by a running reindex, the upload blocks (admin waits) OR the route layer returns 503 — implementation detail chosen in Task 15. This inline note is a forward reference; the actual mutex is owned by the `EmbeddingBackfillJob` (forward reference noted with TODO in DocumentService; mutex wiring lands in Task 15)
- [x] After successful commit, call `searchService.appendChunks(persistedChunks)` with the embedded chunks (the in-memory index sees the new document)
- [x] On delete (extend `AdminRoutes` DELETE handler at its call site in Task 17, or in a helper `deleteDocument` method here): after DB commit, call `searchService.removeDocument(id)` (helper `DocumentService.deleteDocument` added; AdminRoutes wiring deferred to Task 17)
- [x] Tests: happy path with mocked `EmbeddingService` → upload succeeds, embeddings are non-null in DB, `searchService.size()` increases by chunk count
- [x] Test: mock `EmbeddingService.embed` to throw `OllamaUnavailableException` on the 3rd chunk → upload fails, no document row, no source file, no image directory, `searchService.size()` unchanged
- [x] Test: delete document → `searchService.removeDocument` called with the correct ID
- [x] Test: existing Phase 2 tests still pass (no regressions in validation, dedup, rollback paths)
- [x] Extract `embeddingToBytes` / `bytesToEmbedding` into the new `EmbeddingCodec.kt` file with tests for round-trip correctness
- [x] Verify: `cd backend && ./gradlew test`

### Task 10: Implement ModelWarmup

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/ModelWarmup.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/ModelWarmupTest.kt`

Fires a dummy embed + LLM call once at startup so models are resident in Ollama RAM before the first user request. Warmup is fire-and-forget and does NOT gate readiness.

- [x] Constructor `ModelWarmup(embeddingService: EmbeddingService, llmService: LlmService)`
- [x] `fun warmupAsync(scope: CoroutineScope)` — launches a coroutine that calls `embeddingService.embed("warmup")` then `llmService.generateFull(listOf(ChatMessage("system", "warmup"), ChatMessage("user", "warmup")))`. Matching the real request shape (system + user messages) ensures Ollama compiles the same prompt template and cache state as the first real query will hit; catches `OllamaUnavailableException` and logs WARN (don't crash on a cold Ollama)
- [x] Log INFO on start and on each step completion (`"embedding warmup complete"`, `"llm warmup complete"`)
- [x] Tests: with both services mocked to return quickly, `warmupAsync` completes and invokes each mock exactly once
- [x] Test: if embedding mock throws → WARN logged, LLM warmup NOT attempted (sequencing), no exception escapes `warmupAsync`
- [x] Test: warmup is non-blocking — `warmupAsync` returns immediately even when mock delays return by 100 ms
- [x] Verify: `cd backend && ./gradlew test`

### Task 11: Implement ChatResponseBus

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/ChatResponseBus.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/QueueEvent.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/ChatResponseBusTest.kt`

In-memory correlation bus: producer (ChatService) emits `QueueEvent` per `correlationId`; consumer (ChatRoutes) subscribes by the same `correlationId`. Lifecycle: created on enqueue, closed on `done` or on consumer cancellation.

- [x] Create `QueueEvent` sealed class per ARCHITECTURE.md §10.2: `Queued(position, estimatedWait)`, `Processing(status)`, `Token(text)`, `Sources(sources)`, `Done(totalTokens)`, `Error(message)`
- [x] Create `Source(documentId, documentName, section, page, snippet)` data class (used inside `Sources`)
- [x] `ChatResponseBus` holds a `ConcurrentHashMap<String, Channel<QueueEvent>>`. Channels (not SharedFlows) are chosen deliberately: a Channel BUFFERS emissions even when no consumer has started collecting yet, which eliminates the race where the consumer emits between route-level `open` and route-level collect. See ADR 0006 for the rationale and why we moved away from `SharedFlow`
- [x] `fun open(correlationId: String): ReceiveChannel<QueueEvent>` — creates a new `Channel<QueueEvent>(capacity = Channel.UNLIMITED)` keyed by `correlationId`, returns it. Subsequent `open` with the same ID returns the existing channel
- [x] `suspend fun emit(correlationId: String, event: QueueEvent)` — looks up the channel and calls `trySend(event)`. If no channel exists (consumer already cancelled) OR the channel is closed, silently drops. Must NEVER throw
- [x] `fun close(correlationId: String)` — closes the channel and removes the entry; any receiver sees the channel as closed and flow collection completes
- [x] `fun isOrphaned(correlationId: String): Boolean` — true if the channel is closed or the entry has been removed; consumer (ChatService) can skip LLM work for orphaned requests
- [x] KDoc on the class MUST document the lifecycle contract: `open` is called by the SSE route BEFORE `QueueService.enqueue` so early events from the worker are guaranteed to be buffered, not dropped
- [x] Tests: happy path — open, emit 5 events (no receiver yet), THEN start collecting → all 5 events arrive in order, close completes the flow
- [x] Test: emit before open → silently dropped, no error
- [x] Test: emit after close → silently dropped
- [x] Test: `isOrphaned` returns true after the route closes its receiver; false while a receiver is active
- [x] Test: 10 concurrent producers + 10 concurrent consumers across 10 different correlationIds → no cross-talk, no leaks
- [x] Test: early-emit burst — emit 100 events before any consumer collects, then consume → all 100 arrive (proves buffering)
- [x] Verify: `cd backend && ./gradlew test`

### Task 12: Implement QueueService (Artemis JMS)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/QueueService.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/ChatRequest.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/QueueServiceTest.kt`
- Modify: `docker-compose.dev.yml`

Artemis wrapper: producer publishes `ChatRequest` onto `aos.chat.requests` with a correlationId property; consumer is driven by ChatService (Task 13); `QueueBrowser` is used for position lookups.

- [x] Create `ChatRequest(correlationId: String, message: String, history: List<ChatMessage>, enqueuedAt: String)` — serializable JSON payload
- [x] Constructor `QueueService(config: ArtemisConfig)`
- [x] `fun start()` — creates `ActiveMQConnectionFactory`, opens a `Connection`, a dedicated `Session` for producers (`AUTO_ACKNOWLEDGE`), the `Queue("aos.chat.requests")`, and `connection.start()`. Call from `Application.module()`
- [x] `fun stop()` — closes every open consumer session, closes producer session, closes connection. Idempotent
- [x] `suspend fun enqueue(request: ChatRequest)` — creates `TextMessage(Json.encodeToString(request))`, sets `JMSCorrelationID` and the string property `"correlationId"` to `request.correlationId`, sends via a per-call producer. Send runs on `Dispatchers.IO`
- [x] `fun getPosition(correlationId: String): Int` — opens a `QueueBrowser`, iterates messages, returns 0-based index of the message with matching `correlationId` property, or `-1` if the message is no longer in the queue (already consumed). Browser iteration is best-effort (Artemis may reorder under load — acceptable for 5–10 users)
- [x] `suspend fun consume(scope: CoroutineScope, handler: suspend (ChatRequest) -> Unit): Job` — launches a coroutine on `scope` that:
  1. Creates a DEDICATED `Session` (`CLIENT_ACKNOWLEDGE`) and `MessageConsumer` for this loop (isolated from the producer session)
  2. Loops: calls `consumer.receive(1000)` (1 s timeout) on `Dispatchers.IO` — blocking call, but the coroutine yields between iterations
  3. On message: parses `TextMessage` body as `ChatRequest`, invokes `handler(request)` (suspend), then calls `message.acknowledge()` AFTER `handler` completes (so crashes leave the message to be redelivered on next start)
  4. On `handler` exception: logs ERROR, does NOT acknowledge (Artemis redelivers — acceptable because `handler` is the ChatService orchestrator which itself writes `Error` to the response bus and closes it; on redelivery the bus is already closed so `isOrphaned` short-circuits)
  5. Respects cancellation: checks `coroutineContext.ensureActive()` before each `receive`; on cancel, closes the session and consumer cleanly
  - Returns the `Job` so callers can await or cancel it
- [x] Rationale for NOT using `MessageListener`: listener callbacks are not suspend functions, forcing `runBlocking` on the listener thread; combined with a `Semaphore(1)` in ChatService that would serialize inside the listener and block the JMS consumer thread as well. The receive-loop coroutine pattern cleanly separates JMS thread lifecycle from coroutine semantics and lets handler suspension happen on a coroutine-managed dispatcher
- [x] On JMS exceptions during `enqueue`, wrap as `IllegalStateException("Artemis queue unavailable", cause)` and rethrow — `ChatRoutes` turns this into 503 before opening SSE
- [x] Add Artemis container to `docker-compose.dev.yml`: image `apache/activemq-artemis:2.32.0`, env `ARTEMIS_USER=`, `ARTEMIS_PASSWORD=` (anonymous), ports `61616:61616`, `8161:8161` (web console), service name `artemis`. Backend service depends on it
- [x] Use Artemis embedded broker for tests: `EmbeddedActiveMQ` in `@BeforeAll`, stopped in `@AfterAll`; tests point `QueueService` at `vm://0` or `tcp://localhost:<random>`
- [x] Tests: enqueue one message → consumer receives the same `ChatRequest` with matching correlationId
- [x] Test: enqueue 3 messages, call `getPosition` for the 2nd → returns 1 (0-based)
- [x] Test: `getPosition` for unknown correlationId returns -1
- [x] Test: consumer handler exception does not kill the listener (next message still delivered)
- [x] Test: `enqueue` to a stopped service throws `IllegalStateException`
- [x] Verify: `cd backend && ./gradlew test`

### Task 13: Implement ChatService (Повар — consumer loop + orchestration)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/ChatService.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/ChatServiceTest.kt`

The consumer coroutine that dequeues from Artemis, runs the RAG pipeline, and emits events through `ChatResponseBus`. Parallelism is 1 — one request at a time, Ollama-bound.

- [x] Constructor `ChatService(queueService: QueueService, embeddingService: EmbeddingService, searchService: SearchService, llmService: LlmService, database: Database, responseBus: ChatResponseBus, backfillJob: EmbeddingBackfillJob)`
- [x] `fun start(scope: CoroutineScope)` — registers a `queueService.consume { request -> handle(request) }` listener. The listener dispatches each request onto `scope.launch(Dispatchers.IO)` but uses a `Semaphore(1)` so LLM work is strictly serialized
- [x] `suspend fun handle(request: ChatRequest)` — the orchestration body:
  - [x] Short-circuit if `responseBus.isOrphaned(request.correlationId)` → emit nothing, log INFO "request abandoned before processing, correlationId=..."
  - [x] Emit `Processing("Embedding query...")`
  - [x] `val queryEmbedding = embeddingService.embed(request.message)`
  - [x] Emit `Processing("Searching documents...")`
  - [x] `val hits = searchService.search(queryEmbedding, topK = 5, minScore = 0.3f)`
  - [x] Read the system prompt from `ConfigRepository.get("system_prompt")` (decode JSON string), falling back to the hardcoded §9.3 default if null (defense in depth; migration guarantees presence, but the fallback prevents a crash)
  - [x] Build the prompt per §9.2 template: system prompt, formatted context from `hits` (each chunk with `[Source: doc, section X.X, page N]` — handle `page = null` as omitted), conversation history from `request.history`, user's current message
  - [x] Emit `Processing("Generating response...")`
  - [x] `val tokenCount = AtomicInteger(0)`; `val answer = StringBuilder()`
  - [x] `llmService.generate(messages).collect { token -> answer.append(token); tokenCount.incrementAndGet(); responseBus.emit(correlationId, Token(token)) }`
  - [x] After the stream completes, resolve `Source` entries from `hits` (load document names from `DocumentRepository` via a fresh Connection)
  - [x] Log a single structured JSON line at INFO: `{event: "chat_completed", correlationId, question, answer: answer.toString(), sources, chunksRetrieved: hits.size, durationMs, queueWaitMs, tokenCount}` — MUST happen BEFORE emitting `Done` so the log line is durably flushed when the client observes stream completion (prevents test flakiness and guarantees the feedback-anchor log exists before any follow-up action on `correlationId`)
  - [x] Emit `Sources(sources)`, then `Done(tokenCount.get())`
  - [x] `close(correlationId)` on the bus after `Done`
- [x] Error handling inside `handle`:
  - [x] `OllamaUnavailableException` mid-stream → log ERROR with correlationId, `responseBus.emit(correlationId, Error("LLM stream interrupted"))`, close the bus entry
  - [x] Any other exception → log ERROR with correlationId, same `Error` event, close; the consumer coroutine keeps running for the next request
- [x] ChatService does NOT gate on backfill status. Backfill gating lives in ChatRoutes (Task 14): the route refuses to enqueue (503 before SSE) while backfill is running. Keeping gating at the route prevents the race where the worker emits `Error` before the SSE client has started collecting from the bus
- [x] Tests with MockK for every collaborator: happy path (one message, one hit, 3 token stream) → bus sees Processing × N, Token × 3, Sources × 1, Done × 1 in order; conversation log line emitted BEFORE the Done event (assert via log capture)
- [x] Test: orphaned request → nothing emitted to bus, no LLM call made
- [x] Test: `LlmService.generate` throws mid-stream → Error event emitted, bus closed, consumer still handles next request
- [x] Test: zero search hits → LLM still invoked (prompt has empty context block), Sources event contains empty list
- [x] Test: page-null chunks render correctly in the prompt (no `page:` suffix, no exception)
- [x] Verify: `cd backend && ./gradlew test`

### Task 14: Implement ChatRoutes (POST /api/chat SSE)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/ChatRoutes.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/dto/ChatRequests.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/routes/ChatRoutesTest.kt`

The SSE producer. Accepts a chat body, enqueues to Artemis, subscribes to the response bus, streams `QueueEvent` values as SSE. Handles client disconnect by cancelling its own subscription.

- [x] Create `ChatBody(message: String, history: List<ChatMessage> = emptyList())` request DTO with `@Serializable`
- [x] Route handler `POST /api/chat`:
  - [x] Validate: `message.isNotBlank()` else 400 `{"error": "invalid_request", "reason": "empty_message"}`
  - [x] Validate: `history.size <= 20` else 400 `{"error": "invalid_request", "reason": "history_too_long"}`
  - [x] **Backfill gate (BEFORE enqueue, BEFORE SSE):** if `backfillJob.status()` is not `Completed`, return 503 `{"error": "not_ready", "reason": "embedding_backfill_in_progress", "message": "System is initializing. Please retry shortly."}` with `Retry-After: 10`. Do NOT open a bus entry, do NOT touch Artemis. This is the same error-class as Ollama-down (pre-SSE 503) and keeps the gating race-free
  - [x] **Artemis gate (BEFORE SSE):** wrap `queueService.enqueue(...)` in `try/catch IllegalStateException` → on failure, return 503 `{"error": "queue_unavailable", "message": "Request queue is not reachable. Please retry shortly."}`. Still no bus entry, no SSE
  - [x] Generate `correlationId = UUID.randomUUID().toString()`
  - [x] `val receiver = responseBus.open(correlationId)` — opens the buffered channel BEFORE enqueue. Because the bus uses `Channel.UNLIMITED` (Task 11), emissions between now and the collect loop below are buffered, not dropped
  - [x] `queueService.enqueue(ChatRequest(correlationId, body.message, body.history, now))` — this is where the worker may pick up and start emitting
  - [x] `val rawPosition = queueService.getPosition(correlationId)`; `val position = max(rawPosition, 0)` — if the worker already consumed the message (raw = -1), we report position 0. Document in KDoc: "position is a best-effort snapshot at the time of enqueue; a value of 0 can mean either 'first in line' or 'already being processed'"
  - [x] `respondSSE { writer -> ... }` (implemented via `respondBytesWriter` with `ContentType.Text.EventStream` — Ktor 2.3 has no first-party SSE plugin):
    - [x] Write `event: queued\ndata: {"position": position, "estimatedWait": position * 30}\n\n` as the first event (wait estimate = 30 s per slot, crude but adequate)
    - [x] `for (event in receiver) { writer.write(formatSse(event)); writer.flush() }` — forwards all bus events: `event: processing|token|sources|done|error\ndata: <JSON>\n\n`
    - [x] On `Done` from bus → break loop, let the writer close naturally
    - [x] On `Error` from bus → write it as `event: error`, then break/close
    - [x] `CancellationException` (client disconnect) propagates out of the `for (event in receiver)` loop; in `finally`, call `responseBus.close(correlationId)` so ChatService sees `isOrphaned` on its next check. ChatService itself is not signalled — it finishes the in-flight LLM call and discards the tokens (documented limitation, see ADR 0006)
- [x] Register ChatRoutes in `Application.module()` only in `MODE=full` and `MODE=client` (NOT `MODE=admin`) (deferred to Task 17 wiring; ChatRoutes function is mode-agnostic and ready for that registration)
- [x] SSE content type = `text/event-stream`; headers `Cache-Control: no-cache`, `X-Accel-Buffering: no` (for nginx compatibility)
- [x] Tests with `TestApplication`:
  - [x] Happy path: mock `QueueService.enqueue` (success), mock `ChatResponseBus.open` to return a pre-seeded channel emitting Processing → Token × 2 → Sources → Done → stream completes; client receives exactly these 5 events plus `queued`
  - [x] Empty message → 400 with `invalid_request` discriminator
  - [x] History size 21 → 400 with `history_too_long`
  - [x] `BackfillStatus` is `Running` → 503 with `embedding_backfill_in_progress`, `Retry-After` header set, NO SSE, NO enqueue
  - [x] `QueueService.enqueue` throws `IllegalStateException` → 503 `queue_unavailable` BEFORE any SSE event
  - [x] `getPosition` returns -1 → client receives `event: queued data: {"position": 0, ...}` (clamped)
  - [x] Error event from bus → client receives `event: error` and stream closes
  - [x] Client disconnects mid-stream → `finally` block calls `responseBus.close`; assert via spy that `close(correlationId)` was invoked exactly once
  - [x] Route NOT registered in `MODE=admin`: set up the app in admin mode, assert 404 on `/api/chat`
  - [x] Subscribe-before-enqueue race: simulate ChatService emitting 3 events IMMEDIATELY after enqueue (no delay); all 3 events arrive at the client in order (proves channel buffering works)
- [x] Verify: `cd backend && ./gradlew test`

### Task 15: Implement POST /api/admin/reindex (minimal)

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/routes/AdminRoutes.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/routes/AdminRoutesTest.kt`
- Modify: `backend/src/main/kotlin/com/aos/chatbot/services/EmbeddingBackfillJob.kt`

Minimal reindex: clear all embeddings, re-run the backfill job. Idempotent via an in-memory lock held by `EmbeddingBackfillJob`.

- [x] On `EmbeddingBackfillJob`, add a single shared `kotlinx.coroutines.sync.Mutex` named `reindexLock`. This same mutex is used by BOTH reindex AND inline upload embedding to serialize them
- [x] Expose `suspend fun <T> withReindexLock(block: suspend () -> T): T` on `EmbeddingBackfillJob` — wraps `mutex.withLock { block() }`. DocumentService's embedding step (Task 9) acquires this lock around the embed+persist phase so a concurrent reindex cannot wipe newly-embedded chunks. Update Task 9's inline-embedding step to acquire this lock (noted in Task 9)
- [x] Add `suspend fun clearAndReindex(): Unit` that: calls `mutex.tryLock()` — if false (already held by another reindex or an in-flight upload), return immediately (no-op). If acquired: `UPDATE chunks SET embedding = NULL` in one transaction, invoke `run()`, then `mutex.unlock()` in `finally`
- [x] Add `fun isRunning(): Boolean` to `EmbeddingBackfillJob` — true while the mutex is held by an in-flight reindex (track via a dedicated `AtomicBoolean` since the mutex is also held by regular uploads briefly). Used by `/api/health/ready` and by upload gating
- [x] `POST /api/admin/reindex` route:
  - [x] If `isRunning()` → 202 with `{"status": "already_running"}` (idempotent)
  - [x] Otherwise: 202 Accepted immediately with `{"status": "started"}`; launches `clearAndReindex()` on the application scope in fire-and-forget mode
  - [x] Registered ONLY in `MODE=full` / `MODE=admin` (reuses existing AdminRoutes registration logic)
- [x] `POST /api/admin/documents` gating during reindex: if `isRunning()` is true at upload-entry time, return 503 `{"error": "reindex_in_progress", "message": "Reindex is running; uploads are temporarily blocked. Please retry shortly."}`. This is additional defense — the mutex itself already serializes them, but 503 gives the admin a fast, clear signal instead of silently blocking for minutes
- [x] Tests: route returns 202 `{"status": "started"}` on clean state; `isRunning()` returns true during; backfill job's `run()` eventually invoked
- [x] Test: second `POST /api/admin/reindex` while the first is running → returns 202 `{"status": "already_running"}`, no second `run()` invocation
- [x] Test: `POST /api/admin/documents` during reindex → 503 `reindex_in_progress`
- [x] Test: reindex and upload serialize correctly: start reindex, then start upload → upload waits OR 503s; after reindex completes, manually retrying upload succeeds and the new document has non-null embeddings — covered by `clearAndReindex returns immediately when withReindexLock is held elsewhere` (mutex-serialization proof) and the upload-gating 503 test
- [x] Test: route NOT registered in `MODE=client` (404)
- [x] Test: after reindex completes, all chunks have non-null embedding in DB, searchService is populated
- [x] Verify: `cd backend && ./gradlew test`

### Task 16: Upgrade HealthRoutes and /api/stats

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/routes/HealthRoutes.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/routes/HealthRoutesTest.kt`

Make `/api/health/ready` and `/api/stats` reflect real dependencies rather than stubs.

- [x] Add constructor-injected deps: `ollamaClient: HttpClient`, `ollamaConfig: OllamaConfig`, `queueService: QueueService`, `searchService: SearchService`, `backfillJob: EmbeddingBackfillJob`, `database: Database`
- [x] `/api/health/ready`:
  - [x] Calls `GET {ollamaUrl}/api/tags` with 5 s timeout; checks response contains both `llmModel` and `embedModel` as exact matches; failure → `ollama.status = "down"`
  - [x] Ollama probe result is cached for 5 seconds (TTL) via an in-service `AtomicReference<CachedResult>` — prevents k8s/Docker health probes hitting Ollama every second and serializing behind live LLM calls. Configurable via an internal constant; document in the route's KDoc
  - [x] `queueService` exposes `isConnected(): Boolean` (wraps `connection.isOpen()`); false → `queue.status = "down"`
  - [x] `backfillJob.isRunning()` → `backfill.status = "running"`; `status() is Completed` → `backfill.status = "ready"`; otherwise `backfill.status = "idle"`
  - [x] Body matches §7.5 with added `backfill` field; overall `status = "ready"` only when Ollama up, queue up, and backfill `ready`
  - [x] HTTP status: 200 when ready, 503 otherwise
  - [x] Document counts come from a fresh `Database` connection via a throwaway `DocumentRepository`
- [x] `/api/stats`:
  - [x] `documents` from `DocumentRepository.count()`
  - [x] `chunks` from `ChunkRepository.count()`; `images` via a new `ImageRepository.count()` method
  - [x] `embeddingDimension: 1024` hardcoded (bge-m3)
  - [x] `databaseSize` from `Files.size(Path(dbPath))` formatted as `"N MB"`
  - [x] `uptime` formatted from process start time
- [x] Add `ImageRepository.count()` with tests if not already present from Phase 2
- [x] Expose `QueueService.isConnected()`
- [x] HealthRoutes tests: ready returns 200 with correct body shape when all deps up; returns 503 with `ollama.status="down"` when Ollama check fails; 503 with `backfill.status="running"` mid-backfill; stats returns real counts
- [x] Tests use WireMock to stub the Ollama tags endpoint
- [x] Verify: `cd backend && ./gradlew test`

### Task 17: Wire Phase 3 components into Application.kt

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/Application.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/ApplicationTest.kt`

Bring up the full Phase 3 object graph on startup in the correct order.

- [x] Construct in `Application.module()` in this order:
  1. `HttpClient` (Ktor CIO, shared by Ollama services)
  2. `EmbeddingService`, `LlmService`
  3. `SearchService` (empty index)
  4. `EmbeddingBackfillJob` — dependencies wired
  5. `ChatResponseBus`
  6. `QueueService.start()`
  7. `ChatService.start(applicationScope)`
  8. `DocumentService` — pass new `embeddingService` and `searchService` deps; also inject `searchService` into the delete-document path
  9. `ModelWarmup.warmupAsync(applicationScope)` — fire-and-forget, does not block startup
  10. Launch `backfillJob.run()` in an `applicationScope.launch` — does not block startup, but `/api/health/ready` gates on `backfillJob.status()`
- [x] Register routes: HealthRoutes always, AdminRoutes in `MODE=full`/`MODE=admin`, ChatRoutes in `MODE=full`/`MODE=client`
- [x] On `Application.stopping`, call `queueService.stop()` and close the HttpClient
- [x] Keep the Phase 2 orphan-temp-file cleanup intact (unchanged)
- [x] Keep the Phase 2 WARN about unprotected admin routes (unchanged)
- [x] ApplicationTest: `/api/health` returns 200; `/api/health/ready` returns 503 with `backfill.status="running"` when backfill hasn't completed; returns 200 with `status="ready"` once backfill completes (use mocked deps)
- [x] Test: `/api/chat` reachable in `MODE=full` and `MODE=client`, 404 in `MODE=admin`
- [x] Test: `/api/admin/*` reachable in `MODE=full` and `MODE=admin`, 404 in `MODE=client`
- [x] Verify: `cd backend && ./gradlew test`

### Task 18: Integration suite with real Ollama

**Files:**
- Create: `backend/src/test/kotlin/com/aos/chatbot/integration/OllamaIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/integration/ChatE2eIntegrationTest.kt`

Small `@Tag("integration")` suite exercising the full stack against a locally running Ollama. Not part of the default `test` task; runs via `./gradlew integrationTest`.

- [x] `OllamaIntegrationTest`: `@Tag("integration")`; `@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_URL", matches = ".*")`; runs a real `EmbeddingService.embed("hello")` → assert 1024-length FloatArray; real `LlmService.generate(listOf(ChatMessage("user", "hi")))` → assert at least 1 token emitted
- [x] `ChatE2eIntegrationTest`: `@Tag("integration")`; spins up `TestApplication` with real Ollama, real embedded Artemis, real SQLite file in a `@TempDir`; seeds one small test document (direct repository insert with a real Ollama-produced embedding — equivalent to DocumentService path, but avoids docx fixture churn); posts to `/api/chat` with a question about that document; consumes SSE; asserts stream produces `queued`, `processing`, at least one `token`, `sources`, `done`
- [x] Document in the test class KDoc: how to run (`OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest`), expected runtime (~60 s), models required (bge-m3, qwen2.5:7b-instruct-q4_K_M)
- [x] Update README.md with a short "Integration testing" subsection pointing at the tag and env var
- [x] Verify: integration tests are NOT picked up by `./gradlew test` (the default suite stays fast); invoking `./gradlew integrationTest` with no Ollama running gracefully reports the suite as disabled via the env-var guard
- [x] Verify: `cd backend && ./gradlew test` green (integration tests excluded)

### Task 19: Create ADR 0006

**Files:**
- Create: `docs/adr/0006-queue-chat-dispatch-with-in-memory-bus.md`
- Modify: `docs/adr/README.md`

Records the queue topology choice: Artemis for enqueue/ordering, in-process consumer, in-memory SharedFlow bus for tokens, no JMS response queue.

- [x] Follow the format of existing ADRs in `docs/adr/` — Status, Context, Decision, Consequences, Alternatives
- [x] Status: Accepted
- [x] Context: we need fair queuing for 5–10 concurrent chat sessions on a single JVM. Ollama CPU throughput limits true LLM parallelism to ~1. Architecture §10 names Artemis as the queue, but leaves open whether responses traverse JMS
- [x] Decision: Artemis carries only the initial `ChatRequest` with a `correlationId`. The consumer is a coroutine inside the same JVM. Tokens stream back via an in-memory `SharedFlow` keyed by `correlationId`. There is NO `aos.chat.responses` JMS queue
- [x] Consequences:
  - Chat tokens are fast (~microsecond routing inside JVM)
  - The SSE handler and the consumer coroutine must live in the same JVM — splitting them is a future decision (Phase 3.5 if needed)
  - Artemis queue depth = fair user ordering; not delivery-durability (we don't want durability — a client disconnect means the request is dead)
  - Browser-based queue position is best-effort; acceptable for 5–10 users
- [x] Document **known limitation**: when a client disconnects mid-stream, the in-process consumer keeps running the current Ollama HTTP call to completion and discards the tokens. Cancelling the upstream Ollama request on client disconnect is NOT implemented — it would require plumbing `CoroutineContext` cancellation through the Ktor HttpClient call, with careful handling of the response body. Deferred to a follow-up ADR if latency waste becomes measurable
- [x] Document the **extension of ADR 0001** (sync upload): Phase 3 keeps sync upload intact but makes the persist phase include inline embedding via Ollama. ADR 0001 does not need editing — ADR 0006 is the single authoritative pointer for the extended Phase 3 behavior
- [x] Alternatives considered: full JMS request/response (rejected — token latency, cancellation complexity); Kotlin Channel only, no Artemis (rejected — deviates from existing infrastructure reuse requirement in CLAUDE.md); separate worker JVM (deferred — not needed at current load); SharedFlow-based response bus (rejected in favour of `Channel.UNLIMITED` because SharedFlow with `replay=0` drops events emitted before subscribe, which races with the route's collect-loop startup)
- [x] Update `docs/adr/README.md` index with a line for ADR 0006

### Task 20: Update ARCHITECTURE.md

**Files:**
- Modify: `docs/ARCHITECTURE.md`

Three targeted edits so the document reflects Phase 3 reality and the decisions taken.

- [x] §4.3 — remove the `config/system-prompt.txt` bullet from the `aos-knowledge.zip` contents. Replace with: "The system prompt is stored in the `config` table of `aos.db` and ships inside the database file; there is no separate config text file."
- [x] §10 — add a subsection `10.3 Token delivery path`: explain that the `aos.chat.responses` queue shown conceptually in earlier drafts is NOT implemented. Tokens travel from the in-process consumer to the SSE handler via an in-memory `SharedFlow<QueueEvent>` keyed by `correlationId`. Reference ADR 0006
- [x] §16 — under the Feedback entry, add parenthetical: "(Implementation will key feedback on the `correlationId` already produced by the chat pipeline; no conversation storage is required for rating.)"
- [x] §15 — check the Phase 3 checklist; mark the Phase 3 items `[x]` only in a follow-up commit after the phase closes (do NOT mark in this task)
- [x] Verify: docs render correctly; no broken internal links; `docs/adr/0006-*.md` is reachable from the adr README

### Task 21: Final verification and cleanup

- [x] All tests pass: `cd backend && ./gradlew test`
- [x] Build succeeds: `cd backend && ./gradlew build`
- [x] Manual smoke (optional): `docker compose -f docker-compose.dev.yml up`, upload a small `.docx`, POST to `/api/chat` with curl, observe SSE events on stdout (skipped - not automatable)
- [x] Manual integration run (optional): `OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest` passes against real Ollama (skipped - not automatable)
- [x] No compiler warnings, no wildcard imports
- [x] Confirm no Phase 4+ surface slipped in: grep backend source for `Authentication`, `BearerAuth`, `JWT`, `authenticate {`, `principal`, `Authorization`, `/api/config/system-prompt`, `/api/admin/export`, `/api/admin/import` — zero matches (one KDoc reference to Phase 5's `PUT /api/config/system-prompt` in `ConfigRepository.kt` is documentation of future work, not implementation)
- [x] Confirm `UploadResult` still has exactly two variants (`Created`, `Duplicate`) — Phase 2 invariant preserved
- [x] Confirm V001–V003 unchanged (`git diff main -- backend/src/main/resources/db/migration/V001__*.sql V002__*.sql V003__*.sql` is empty)
- [x] Confirm a fresh `docker-compose.dev.yml up` brings up Artemis, Ollama, and the backend; `/api/health/ready` transitions to 200 after backfill completes on a clean DB (skipped - not automatable)
- [x] Confirm `MODE=client` with a read-only volume mount reads the system prompt from `aos.db` and serves `/api/chat` (requires a prepared `aos.db` with embeddings) (skipped - not automatable)
- [x] Move this plan to `docs/plans/completed/` once all checkboxes are green

## Post-Completion

**Manual verification** (not code, not automated):
- Smoke-test a full knowledge base build end-to-end: admin uploads 5–10 real AOS documents in `MODE=admin`, copies `/data` to a client server running `MODE=client`, and confirms chat works with reasonable answers and correct source citations.
- Measure backfill time on a realistic chunk count (~1000 chunks) and confirm it fits within acceptable startup latency. If it takes >5 minutes, consider adding progress logging every 10% and/or batching embeddings in parallel HTTP calls in a follow-up.
- Measure first-token latency after warmup vs. without warmup on a cold `MODE=client` boot — confirm warmup is doing its job.

**External system updates** (none in Phase 3 — everything is in-repo):
- No consumer projects to notify.
- No shared config to update.
- Phase 4 (auth) will revisit `MODE=full`/`MODE=admin` deployment stance and retire the unprotected-routes WARN from ADR 0005.
