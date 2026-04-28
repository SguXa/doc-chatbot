# Phase 6: Chat UI

## Overview

Build the operator-facing chat surface that consumes the SSE-streamed `POST /api/chat` pipeline shipped in Phase 3. Visitors land on `/` and immediately get an empty conversation pane, ask questions about the AOS knowledge base, and watch tokens stream into a single assistant message whose status line transitions through `In queue → Embedding query → Searching documents → Generating → tokens → sources` (the backend emits three distinct `processing` strings — see `ChatService.kt:92,95,104`). The conversation is tab-scoped: it persists across `F5` via `sessionStorage` and is wiped by closing the tab or clicking `+ New chat`. There are **no backend changes** — `POST /api/chat` (§7.1), `GET /api/health/ready` (§7.5), and the `QueueEvent` contract (§10.2) are all in place from Phase 3. Export/Import knowledge base, Stop generation (with real Ollama cancellation), System Prompt Preview, parser improvements (PDF tables / OCR / Document Inspect), MODE-aware UI, dark theme, and mobile responsiveness are explicitly out of scope and ship in Phase 7+.

> **Implementation order note.** Task 1 reshuffles `ARCHITECTURE.md` §15/§16 + `CLAUDE.md` *before* any code lands. This way the architecture document is the source of truth throughout the multi-week implementation rather than diverging until the final task.

## Context

- **Files involved:** New code under `frontend/src/api/chat.ts`, `frontend/src/lib/chatErrors.ts`, `frontend/src/stores/chatStore.ts`, `frontend/src/components/chat/*`, `frontend/src/test-utils/sseMocks.ts`. Edits to `frontend/src/App.tsx` (replace placeholder `HomePage` with `ChatPage`), `frontend/src/index.css` (`--accent-magenta` CSS variable), `frontend/package.json` (`react-markdown`, `remark-gfm` deps). Doc edits in Task 1 to `ARCHITECTURE.md` §15 / §16 / §1.2, `CLAUDE.md` "Phase Discipline".
- **Already in place from Phase 3:** `POST /api/chat` returns SSE with `event: queued|processing|token|sources|done|error` per §7.1 / §10.2. `LlmService` is instructed by §9.2 prompt template to write inline citations like `[Source: Manual.docx, Section 3.2]` directly into the streamed text. `GET /api/health/ready` reports `backfill.status` (`idle | running | ready | failed`).
- **Already in place from Phase 5:** `useReadyStatus` hook (`frontend/src/hooks/useReadyStatus.ts`) polls `/api/health/ready` every 3s while running and returns `{status, isRunning}` — reused for the backfill banner with one caveat (see Design Decisions: it stops polling once status leaves `running`). `App.tsx` already mounts `react-router-dom` Routes and `@tanstack/react-query`. `lib/utils.ts` exports `cn`. shadcn components already generated: `button`, `card`, `dialog`, `alert-dialog`, `textarea`, `sonner` and others — all needed UI primitives are present, no new `shadcn add` calls.
- **Related patterns:** Functional components + TypeScript, named exports, `@/*` path alias, Vitest + RTL with files next to source as `*.test.tsx`. Phase 5 established: error-mapping module pattern (`lib/errors.ts`), table-driven tests (`lib/errors.test.ts`), TanStack Query for non-streaming server state, and **manual `hydrate()`** as the persistence pattern in Zustand (`stores/authStore.ts` reads/writes `localStorage` directly — there is no `persist` middleware anywhere in the frontend yet).
- **Source of truth:** `docs/ARCHITECTURE.md` §7.1 (chat request/response and pre-SSE error codes — note `history` is capped at **20 entries**, where each entry is one user-OR-assistant message, not a user+assistant pair), §7.5 (readiness contract), §9.2 / §9.3 (prompt template — explains the inline `[Source: ...]` text), §10.2 (`QueueEvent` shape — drives the SSE parser), §11.2 (chat is public, no `Authorization` header), §15 (implementation plan checkboxes), §16 (future enhancements — pruned in Task 1).
- **Architectural decisions:** ADR 0006 (queue-chat-dispatch with in-memory bus). Two consequences drive Phase 6 UX directly:
  1. Each chat request is dispatched through Artemis with a `correlationId`; only one in-flight chat at a time per JVM (`Semaphore(1)` consumer). This is why a queue position is meaningful and worth surfacing as a status line.
  2. Client disconnect does **not** cancel the in-flight Ollama HTTP call. This is why "Stop generation" is deferred to Phase 7 — adding a button now would either lie to the operator or sit idle.

## Design Decisions

- **No `Stop generation` button in Phase 6.** ADR 0006 documents that closing the SSE connection does not cancel the Ollama HTTP call. A button that "stops" the UI but leaves the GPU running is dishonest UX. Phase 7 will tackle real cancellation alongside an ADR update.
- **No parsing of inline `[Source: ...]` text.** The §9.2 prompt template instructs the LLM to write `[Source: Document, Section X.X]` inline. We render that text verbatim and rely on the structured `event: sources` payload for the source cards below the answer. Trying to glue them via regex is brittle — LLMs are inconsistent (`Section 3.2` vs `§3.2` vs `Sec. 3.2`) and a missed match looks worse than no match at all.
- **No links to `/admin` or `/login` from the chat page.** The chat surface is the public-facing product; the admin URL is operator knowledge (typed directly). MODE-awareness lands in Phase 7+.
- **`/api/health` is no longer surfaced in the UI.** Phase 5 placeholder `HomePage` showed a "Backend status" indicator pinging `/api/health` every 30s. That surface goes away with the chat page. `/api/health` remains for ops/Docker healthchecks; readiness — which is what actually matters for chat — is surfaced through `BackfillBanner`.
- **Conversation lives in Zustand + `sessionStorage['aos.chat.session']` via Zustand's `persist` middleware.** This **is a new pattern in this repo** — `stores/authStore.ts` uses manual `hydrate()` (single-field state, mutations go through `useAuthStore.setState`). Chat state is an array with frequent mutations (every token), so middleware-based persistence pays off (no per-mutation `localStorage.setItem` boilerplate). `zustand@^5` is already installed and ships `persist` + `createJSONStorage`. `sessionStorage` is the right scope: survives `F5`, dies with the tab.
- **Persisted state filters out in-flight messages.** Zustand's `partialize` strips messages whose status is anything but `done` or `error` — a placeholder bubble persisted across `F5` would be a zombie with no associated request. On rehydrate, the user sees only conversational history that actually completed.
- **History sent on each request: last 20 entries, with explicit filtering.** §7.1 caps `history` at 20 entries (one entry = one user-OR-assistant message). The build rule:
  1. Snapshot `messages` *before* `addUserMessage` / `addAssistantMessage` are called for the new request.
  2. Filter to messages whose `status === 'done'` (drops error-state messages and the about-to-be-added in-flight pair).
  3. Map each to `{role, content}`.
  4. `slice(-20)` to keep the most recent 20 entries (oldest dropped).
  Server-side validation is defensive — the client never relies on the server rejecting an over-long history.
- **SSE client: hand-rolled `fetch` + `ReadableStream` reader, no library; returns `AsyncIterable<ChatStreamEvent>`.** Native `EventSource` is GET-only — incompatible with our POST. `@microsoft/fetch-event-source` adds a dependency and an opinionated retry policy we don't want. The protocol is simple — two field types (`event:` and `data:`), terminated by blank lines. The function shape is `streamChat(body, signal): AsyncIterable<ChatStreamEvent>` — committed, not "callbacks if cleaner" — because the consumer in `ChatPage` uses `for await` and tests use the same iteration.
- **`ChatHttpError` carries the `Retry-After` header value as `retryAfterSeconds: number`.** Computed at construction via `parseRetryAfterSeconds(response.headers)`. Without this, `mapHttpError` cannot surface the header to `chatErrors.ts` — they don't share the `Response` object. Defaults to 10 if absent or unparseable.
- **Single message state-machine for the assistant.** One `assistant` message in the conversation array progresses through `queued → processing → streaming → done | error`. Each event mutates the same message via store actions — `appendToken` concatenates text, `setSources` populates the cards, `setStatus` updates the pre-token status line, `setError` flips to error and stores the `ChatUxError`.
- **`react-markdown` + `remark-gfm` for the assistant text only.** User text rendered as plain `<p>` with `whitespace-pre-wrap`. Assistant gets full Markdown including GFM tables, fenced code, lists, and task lists. `react-markdown` is XSS-safe by default (does **not** render raw HTML unless explicitly allowed) — Task 12 includes an explicit test that a `<script>alert(1)</script>` payload in the LLM response renders as escaped text, not as a script element.
- **Markdown links use default `<a>` behavior (no special handling).** In an offline deployment external URLs won't resolve and internal URLs aren't expected in LLM output. If the LLM emits `[click](http://evil.com)`, the user clicks and navigates away — acceptable for an internal kiosk surface. Reconsider if Phase 8 polish surfaces this differently.
- **`useReadyStatus` is reused as-is, with a known limitation.** The hook stops polling once status leaves `'running'`. Concretely: if the operator hits `'failed'` and an admin then runs reindex, the chat page does **not** auto-recover from blocker → working — the operator must reload the tab. Acceptable trade-off; documented here so a future implementer doesn't think it's a bug.
- **Auto-retry on `503 not_ready, embedding_backfill_in_progress`** uses the `Retry-After` header (default 10s if missing). The assistant message shows a countdown ("Retrying in 8s…") — the user does nothing. Other error kinds (`queue_unavailable`, `network_failure`, `mid_stream`, `invalid_request`) produce a manual `Retry` button on the message; the user decides when to try again.
- **`AbortController` lifecycle (concrete spec):** `ChatPage` holds a `useRef<AbortController | null>(null)`. Every call to the orchestration helper `runStream(messageId, body)` (1) creates a fresh `AbortController`, (2) assigns it to the ref, (3) passes `.signal` to `streamChat`. The component aborts and clears the ref in three places: `useEffect` cleanup (unmount), the `clearAll` flow (sidebar `+ New chat`), and the `handleRetry` flow (so a click during a countdown cancels the pending retry first). The store's `clearAll` does NOT manage the controller; `ChatPage` subscribes to `messages.length === 0` transitions and triggers the abort externally. There is at most one in-flight controller at a time; `setIsStreaming(false)` always clears the ref.
- **Retry timer cleanup (concrete spec):** When auto-retry is scheduled for `backfill_running`, `ChatPage` stores `{ timeoutId, intervalId, messageId }` in a ref. `clearAll`, `handleRetry` (manual click), unmount, and the `setTimeout` firing itself all clear both timers and null the ref. The `setInterval` (1s tick) updates the message's `statusText` to `Retrying in {N}s…` and decrements `N`; when `N` hits 0, the interval clears, the timeout fires `runStream(messageId, ...)` again. Tested: `backfill_running` 503 with `Retry-After: 2` → user clicks `+ New chat` after 1s → no further requests, no further `setStatus` calls.
- **Magenta accent in exactly two places: assistant avatar circle + runway vanishing point.** A defined CSS variable `--accent-magenta` (default `#C2185B`, Material Pink 700) lets us tune later without hunting through components. The Send button, hyperlinks, and other interactive elements stay on the standard shadcn token palette so that magenta retains semantic weight ("AOS / aviation theme").
- **Layout: 2-column, sidebar 240px + max-width 768px chat column centered in the remaining viewport.** Desktop only. Sidebar contains: PlaneTakeoff icon + product name → `+ New chat` button → `flex-grow` empty space → `RunwayBackground` SVG anchored to the bottom (vanishing point upward, base at the very bottom edge). The runway is a permanent decorative element of the sidebar — not a background of the chat area.
- **No `<ChatBackground />` wrapper.** The brainstorm dialog floated this as a future-customization seam, but it would be a literal `<div>{children}</div>` with a comment — textbook YAGNI. If a future phase introduces a chat background, the change is one Tailwind class on the `<main>` element. Direct rendering in `ChatPage` until then.
- **Flat message style (no chat bubbles).** Each message is a horizontal row: avatar + name on the left (32px column), content on the right. The assistant row is visually distinguished by `bg-muted/50` (a Tailwind shadcn token, no new CSS variable required); the user row uses the default page background.
- **Per-message memoization on token streams.** Streaming a 2000-token answer triggers ~2000 store mutations, each re-rendering `MessageList`. To prevent jank, `UserMessage` and `AssistantMessage` are wrapped in `React.memo` and `MessageList` keys them by `message.id`. The store's `appendToken` mutates only the targeted message in place, so `React.memo`'s default reference comparison is sufficient (only the streaming message re-renders per token; prior `done` messages do not).
- **`+ New chat` requires confirmation only when the conversation is non-empty.** Click on an empty conversation **early-returns** (no store action, no notification cycle). With ≥ 1 message, an `AlertDialog` (shadcn) confirms before clearing.
- **`Enter` to send, `Shift+Enter` for newline; auto-resize textarea up to 6 lines with internal scroll afterwards.** Standard chat UX. Textarea remains editable while a stream is active — the user can compose their next question while waiting; only the Send button is disabled until the stream completes (`done` or terminal `error`).
- **Always-visible character counter `N / 4000`.** Subtle gray under the textarea. Server caps at 4000 chars (§7.1 `message_too_long`); we mirror the cap client-side and disable Send when exceeded.
- **Empty state is plain text (no clickable example chips).** A centered prompt with two example questions before the first message.
- **No inline-toast for `400 invalid_request` errors.** The client validates `message_too_long` and trims `history` before sending; these 400 codes are defensive. If one fires (e.g., stale code mismatch), the message renders as a generic error bubble with the server-provided reason.
- **`fetchReady` location stays in `api/admin.ts`.** Strictly accurate: `/api/health/ready` is public per §11.2, but moving the function would require touching Phase 5's `useReadyStatus` import and its tests. Out of scope for Phase 6. Adding a one-line file header comment to `api/admin.ts` clarifying that `fetchReady` is a public-endpoint helper is enough; defer the actual move to Phase 7+ if a Phase 7 module needs it from `api/health.ts`.
- **No new shadcn components are needed.** Phase 5 already pulled `button`, `card`, `dialog`, `alert-dialog`, `textarea`, `sonner`, `dropdown-menu`, `input`, `label`, `table`. Phase 6 reuses Card, AlertDialog, Button, Textarea — all present.
- **No E2E tests in Phase 6.** Vitest + RTL component tests cover every SSE event type, every error case in `chatErrors.ts`, store transitions, and the orchestration via mock streams. Playwright is Phase 8 polish work.
- **No `data-testid` proliferation.** RTL queries by `getByRole`, `getByText`, `getByLabelText`. `data-testid` only as a last resort.

## Development Approach

- **Testing approach:** Regular (code first, then tests within the same task).
- Complete each task fully before moving to the next.
- Each task produces a compilable/runnable increment that does not regress prior tasks.
- **CRITICAL: each task ends with `cd frontend && npm test` green.** No "I'll fix it next task".
- **CRITICAL: all tests must pass before starting the next task.** No exceptions.
- **CRITICAL: update this plan file when scope changes during implementation** — checkboxes get `[x]`, new tasks get `➕` prefix, blockers get `⚠️` prefix.
- Frontend tests use Vitest + RTL (already configured). Mock SSE via the shared helper in `frontend/src/test-utils/sseMocks.ts` (created in Task 2): builds a `Response` whose `body` is a `ReadableStream` enqueueing each chunk.
- No backend changes — no `cd backend && ./gradlew test` runs needed.

## Validation Commands

- `cd frontend && npm test`
- `cd frontend && npm run build`
- `cd frontend && npm run lint`
- (manual smoke) Start backend in `MODE=full` with `JWT_SECRET=$(openssl rand -hex 32)` and `ADMIN_PASSWORD=test1234`; ensure at least one document is uploaded via Phase 5 admin; start frontend (`npm run dev`); open `http://localhost:5173/`.

## Implementation Steps

### Task 1: Documentation reshuffle (do this BEFORE any code)

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `CLAUDE.md`
- Modify: `frontend/src/api/admin.ts` (one-line clarifying comment)

The brainstorm decided Phase 6 = Chat UI only; Phase 7 absorbs Export/Import + selected `§16` items; old "Polish" becomes Phase 8. Doing this first keeps `ARCHITECTURE.md` as the source of truth throughout Phase 6 implementation rather than diverging until the final task.

- [x] In `ARCHITECTURE.md` §15:
  - Update Phase 6 heading and checklist:
    - Title: "Phase 6: Chat UI"
    - Items (left as `[ ]` — closure marks land in Task 18): Chat interface, Message streaming, Source cards (was "Source badges"), Queue status display, History (session only)
    - Add a forward-pointer line: "Export/Import deferred to Phase 7."
  - Insert a new Phase 7:
    - Title: "Phase 7: Export/Import + Selected Future Enhancements"
    - Items: `[ ]` Export/Import knowledge base, `[ ]` Stop generation with real Ollama cancellation (requires ADR 0006 follow-up — note that Stop generation is a new entry, not previously listed in §16), `[ ]` System Prompt Preview (debug rendering of final prompt + retrieval context), `[ ]` PDF tables extraction (tabula-java), `[ ]` PDF OCR (tess4j), `[ ]` Document Inspect mode (read-only chunk viewer)
  - Renumber the previous Phase 7 (Polish) to Phase 8: same checklist (Error handling, Loading states, MODE switching, Documentation, Integration tests, Performance testing).
- [x] In `ARCHITECTURE.md` §16 "Future Enhancements":
  - Remove the rows now scheduled into Phase 7: `System Prompt Preview`, `PDF tables extraction`, `PDF OCR`, `Document Inspect mode`.
  - Keep: Vision LLM, Feedback, Chat History (persistence), Keycloak, Multi-language UI.
  - In the prose paragraph above the table, note that §15 Phase 7 absorbed several items that used to live here.
- [x] In `ARCHITECTURE.md` §1.2 "Key Features":
  - Verify the existing bullet "Admin panel — document management, system prompt editor, export/import" is still accurate. Export/Import remains a planned admin capability (ships in Phase 7) — leave the bullet as-is.
- [x] In `CLAUDE.md` "Phase Discipline" section:
  - Edit the existing "Chat UI and export/import are Phase 6 work" bullet to: "**Export/Import is Phase 7 work** (alongside Stop generation, System Prompt Preview, parser improvements). Phase 8 is Polish (error handling, loading states, MODE switching, integration/performance tests)."
  - Add a new bullet: "**Chat UI (Phase 6):** React chat surface at `/`. Conversation in-memory + `sessionStorage`; no persistence beyond the tab. SSE consumed via hand-rolled `fetch` + `ReadableStream` in `frontend/src/api/chat.ts` (no library). `react-markdown` + `remark-gfm` for assistant text only. No `Stop generation` button — see ADR 0006 known limitation; Phase 7 will pair real cancellation with an ADR update."
- [x] In `frontend/src/api/admin.ts`, add a one-line comment above `fetchReady`: `// fetchReady targets a public endpoint (§11.2); used by both admin and chat surfaces.`
- [x] `cd frontend && npm test` — still green (this is doc-only + comment; no logic change).

### Task 2: SSE client and event types in `api/chat.ts`

**Files:**
- Create: `frontend/src/api/chat.ts`
- Create: `frontend/src/api/chat.test.ts`
- Create: `frontend/src/test-utils/sseMocks.ts`

Implements the streaming POST + SSE parser. No UI yet. The exported function takes a request body and an `AbortSignal`, returns `AsyncIterable<ChatStreamEvent>`. Pre-SSE 503 / 400 responses are JSON, not SSE — handled by throwing `ChatHttpError` before iteration begins.

- [x] Define types in `chat.ts`:
  - `Source` — `{ documentId: number; documentName: string; section: string | null; page: number | null; snippet: string }` (matches §7.1 SSE `sources` payload)
  - `ChatHistoryEntry` — `{ role: 'user' | 'assistant'; content: string }`
  - `ChatRequestBody` — `{ message: string; history: ChatHistoryEntry[] }`
  - `ChatStreamEvent` — discriminated union: `{ type: 'queued'; position: number; estimatedWait: number }`, `{ type: 'processing'; status: string }`, `{ type: 'token'; text: string }`, `{ type: 'sources'; sources: Source[] }`, `{ type: 'done'; totalTokens: number }`, `{ type: 'error'; message: string }`
- [x] Define `ChatHttpError` class with `status: number`, `body: unknown`, **`retryAfterSeconds: number`** (computed from `Retry-After` header at construction; defaults to 10 if absent or unparseable). Static factory `fromResponse(response, body)` reads the header and assigns.
- [x] Define helper `parseRetryAfterSeconds(headers: Headers): number` — reads `Retry-After` as integer seconds; returns `10` if absent / unparseable / `'0'`. Exported for reuse and tests.
- [x] Implement `streamChat(body: ChatRequestBody, signal: AbortSignal): AsyncIterable<ChatStreamEvent>`:
  - POST to `/api/chat` with `Content-Type: application/json`, `Accept: text/event-stream`, `signal`
  - On non-2xx, read JSON body and throw `ChatHttpError.fromResponse(response, body)` (the response is plain JSON in this branch per §7.1 pre-SSE error contract)
  - On 2xx, take `response.body` (`ReadableStream<Uint8Array>`), pipe through `TextDecoderStream`, read line-buffered. Maintain per-frame state: `event` (string), `dataLines` (string[]). On blank line, emit `{ event, data: dataLines.join('\n') }` then reset. On stream end, ignore any partial frame.
  - Map raw SSE frames to `ChatStreamEvent` by event name; `data` is JSON-parsed. Unknown event names are silently ignored (forward-compat).
  - If `signal.aborted` triggers mid-stream, exit the SSE-parsing loop quietly (no throw to consumer).
  - If `signal.aborted` triggers BEFORE the `fetch` resolves (or during response-header read), `fetch` itself rejects with `DOMException: AbortError`. Catch this and return — do NOT propagate to the consumer's `for await`. Concretely: the `streamChat` async generator should treat any `AbortError` (whether from `fetch` or from the stream reader) as a clean termination. The consumer must not see `setError` called as a side-effect of an abort it triggered.
- [x] Create `frontend/src/test-utils/sseMocks.ts`: helper `mockSseStream(chunks: string[], { status = 200, headers = {} } = {})` returning a fully-formed `Response` whose `body` is a `ReadableStream<Uint8Array>` enqueueing each chunk. Also export `buildErrorResponse(status, body, headers)` for the pre-SSE error tests.
- [x] `chat.test.ts` — table-driven tests using `mockSseStream`:
  - emits `queued` event correctly parsed
  - emits `processing` event correctly parsed (covers all three string variants: "Embedding query...", "Searching documents...", "Generating response...")
  - emits multiple `token` events in order, joined by the consumer
  - emits a `sources` event with the full `Source[]` shape (including `section: null` and `page: null` for chunks without that metadata)
  - emits a `done` event terminating the stream
  - emits an `error` event terminating the stream
  - handles a chunk that splits mid-frame (`event: tok` + rest in next chunk)
  - handles `\r\n` line endings (defensive)
  - drops unknown event names
  - throws `ChatHttpError` with parsed body on 400 (`{"error": "invalid_request", "reason": "message_too_long"}`)
  - throws `ChatHttpError` on 503 (`embedding_backfill_in_progress`) with `Retry-After: 7` header → `error.retryAfterSeconds === 7`
  - throws `ChatHttpError` on 503 without `Retry-After` → `error.retryAfterSeconds === 10`
  - aborts cleanly when the `AbortSignal` fires mid-stream (consumer's `for await` exits silently, no thrown error)
  - aborts cleanly when the `AbortSignal` fires BEFORE `fetch` resolves (consumer's `for await` exits silently, no `AbortError` propagated)
  - **Sequenced responses helper**: tests that need two responses from `fetch` (e.g. first call 503, second call 200 stream) use `vi.mocked(fetch).mockImplementationOnce(...)` chained per call. Document this pattern in `sseMocks.ts` as a top-of-file comment with an inline example so Task 16's tests share the convention.
  - `parseRetryAfterSeconds` table-driven: `'10' → 10`, `'abc' → 10`, missing → `10`, `'0' → 10`
- [x] Run `cd frontend && npm test` — green before Task 3

### Task 3: Error mapper in `lib/chatErrors.ts`

**Files:**
- Create: `frontend/src/lib/chatErrors.ts`
- Create: `frontend/src/lib/chatErrors.test.ts`

Maps thrown errors and mid-stream `error` payloads into a tagged UX object. Component layer renders by `kind` only.

- [x] Define `ChatUxError` discriminated union:
  - `{ kind: 'backfill_running'; retryAfterSeconds: number }` — 503 + `embedding_backfill_in_progress`. Caller schedules auto-retry.
  - `{ kind: 'backfill_failed'; message: string }` — 503 + `embedding_backfill_failed`. Caller relies on `useReadyStatus` for the page-level blocker (this kind is rare — the readiness probe usually catches `failed` before a chat request).
  - `{ kind: 'queue_unavailable' }` — 503 + `queue_unavailable`. Manual retry.
  - `{ kind: 'network_failure' }` — `TypeError` from `fetch`. Manual retry.
  - `{ kind: 'mid_stream'; message: string }` — terminal `event: error` from SSE. Manual retry.
  - `{ kind: 'invalid_request'; reason: string }` — defensive 400. Manual retry.
  - `{ kind: 'unknown'; status?: number; message: string }` — fallback.
- [x] Implement `mapHttpError(error: unknown): ChatUxError` — reads `error.retryAfterSeconds` from `ChatHttpError` for `backfill_running`.
- [x] Implement `mapMidStreamError(message: string): ChatUxError` — `{ kind: 'mid_stream', message }`.
- [x] Implement `formatChatUxError(uxError: ChatUxError): string` — English copy: `network_failure` → "Unable to reach server. Please check your connection.", `queue_unavailable` → "Server is temporarily unavailable. Please try again.", `mid_stream` → "An error occurred: ${message}", `backfill_failed` → "Knowledge base unavailable. Please contact your administrator.", `invalid_request` → "Invalid request: ${reason}", `unknown` → "Unexpected error: ${message}".
- [x] `chatErrors.test.ts` — table-driven, exhaustive:
  - `ChatHttpError(503, body, retryAfterSeconds=5)` for `embedding_backfill_in_progress` → `{ kind: 'backfill_running', retryAfterSeconds: 5 }`
  - same with `retryAfterSeconds=10` (default) → `retryAfterSeconds: 10`
  - `ChatHttpError(503, body)` for `embedding_backfill_failed` with `message: 'corrupted db'` → `{ kind: 'backfill_failed', message: 'corrupted db' }`
  - `ChatHttpError(503, body)` for `queue_unavailable` → `{ kind: 'queue_unavailable' }`
  - `new TypeError('Failed to fetch')` → `{ kind: 'network_failure' }`
  - `ChatHttpError(400, body)` for `invalid_request, reason: 'message_too_long'` → `{ kind: 'invalid_request', reason: 'message_too_long' }`
  - `new Error('boom')` → `{ kind: 'unknown', message: 'boom' }`
  - `mapMidStreamError('LLM timed out')` → `{ kind: 'mid_stream', message: 'LLM timed out' }`
  - `formatChatUxError` produces the documented English copy for each kind
- [x] Run `cd frontend && npm test` — green before Task 4

### Task 4: `chatStore` Zustand store with `persist` middleware

**Files:**
- Create: `frontend/src/stores/chatStore.ts`
- Create: `frontend/src/stores/chatStore.test.ts`

**Note on pattern divergence from Phase 5:** Phase 5's `authStore` uses manual `hydrate()` against `localStorage`. Phase 6 introduces `persist` middleware (against `sessionStorage`) because chat state is an array with frequent mutations and middleware avoids per-mutation boilerplate. `zustand@^5` already ships `persist` and `createJSONStorage` — no version bump required.

- [x] Define types:
  - `Source` — re-imported from `@/api/chat`
  - `MessageStatus` — `'queued' | 'processing' | 'streaming' | 'done' | 'error'`
  - `Message` — `{ id: string; role: 'user' | 'assistant'; content: string; status: MessageStatus; statusText?: string; sources?: Source[]; uxError?: ChatUxError }`
- [x] Store shape: `{ messages: Message[]; isStreaming: boolean }`
- [x] Actions:
  - `addUserMessage(content: string): string` — pushes a user message in `done` status, returns its id
  - `addAssistantMessage(): string` — pushes an empty assistant message in `queued` status, returns its id
  - `setStatus(messageId, status: MessageStatus, statusText?: string)` — updates by id; silently no-op if id no longer present (covers race with `clearAll`)
  - `appendToken(messageId, text: string)` — concatenates text; flips status to `streaming` if currently `queued`/`processing`; clears `statusText`. **Mutation contract for memoization (Task 10):** non-streaming messages MUST keep object identity across this update; the streaming message gets a new object. Implement via `messages.map(m => m.id === messageId ? { ...m, content: m.content + text, status, statusText: undefined } : m)` (returning `m` unchanged for non-matches preserves identity). Equivalent immer middleware would also work, but the `.map` pattern is enough — pin it as the contract so Task 10's memoization test isn't a guess. The same identity rule applies to `setStatus`, `setSources`, `setError`, `resetAssistantMessage`
  - `setSources(messageId, sources: Source[])`
  - `setError(messageId, uxError: ChatUxError)` — sets `status='error'`, stores `uxError`. **Silently no-op if id no longer present** (same guard as `setStatus`; covers race where `clearAll` fires before a stream's catch path resolves)
  - `resetAssistantMessage(messageId)` — flips back to `queued` and clears `content`, `sources`, `uxError`, `statusText`; used by manual retry. **Specced as a store action** (not composed at call site) because the test surface is cleaner — one assertion that resetting clears all fields atomically
  - `setIsStreaming(value: boolean)`
  - `clearAll()` — empties `messages`, sets `isStreaming = false`
- [x] Persist with Zustand's `persist` middleware:
  - `name: 'aos.chat.session'`
  - `storage: createJSONStorage(() => sessionStorage)`
  - `partialize: (state) => ({ messages: state.messages.filter(m => m.status === 'done' || m.status === 'error') })`
- [x] `chatStore.test.ts`:
  - `addUserMessage` / `addAssistantMessage` push correctly with unique ids
  - `setStatus` updates only the named message; no-op for unknown id
  - `appendToken` concatenates and flips `queued`/`processing` → `streaming`; clears `statusText`
  - `setSources` populates sources
  - `setError` flips to `error` with `uxError`
  - `setError` is a no-op for unknown id (mirror the `setStatus` guard)
  - `resetAssistantMessage` clears `content`, `sources`, `uxError`, `statusText`, status back to `queued`
  - `clearAll` empties messages and resets `isStreaming`
  - `partialize` strips in-flight messages — fixture has one of EACH status (`queued`, `processing`, `streaming`, `done`, `error`); force `useChatStore.persist.rehydrate()` on a fresh `sessionStorage` snapshot; assert the surviving set is `[done, error]` (verifies BOTH terminal statuses are kept and BOTH in-flight statuses are stripped)
  - persisted state survives `useChatStore.persist.rehydrate()` cycle
- [x] Run `cd frontend && npm test` — green before Task 5

### Task 5: `ChatPage` shell + routing on `/`

**Files:**
- Create: `frontend/src/components/chat/ChatPage.tsx`
- Create: `frontend/src/components/chat/ChatPage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`

Replaces placeholder `HomePage` with `ChatPage`. The page renders a 2-column skeleton; later tasks fill the slots.

- [x] Delete the inline `HomePage` function in `App.tsx` and remove its `useQuery` health-check (per Design Decisions: `/api/health` no longer surfaced in UI)
- [x] Wire the new route: `<Route path="/" element={<ChatPage />} />`
- [x] `ChatPage.tsx`:
  - Top: `<div className="h-screen flex">`
  - Left: `<aside className="w-60 shrink-0 ...">` with placeholder text
  - Right: `<main className="flex-1 flex flex-col">` with placeholders for banner, message list, input
  - The chat content wrapper inside `<main>` is `<div className="max-w-3xl mx-auto w-full ...">` (768px cap, centered)
- [x] `ChatPage.test.tsx`:
  - renders without crashing
  - has a `<main>` and an `<aside>` landmark
  - the chat content wrapper has `max-w-3xl`
- [x] Update `App.test.tsx` — replace assertions on the old `HomePage` text with assertions that the chat page renders at `/`. The "Backend status" assertion goes away.
- [x] Run `cd frontend && npm test` — green before Task 6

### Task 6: `ChatSidebar` with logo + `+ New chat` + AlertDialog

**Files:**
- Create: `frontend/src/components/chat/ChatSidebar.tsx`
- Create: `frontend/src/components/chat/ChatSidebar.test.tsx`
- Modify: `frontend/src/components/chat/ChatPage.tsx`

- [x] `ChatSidebar.tsx`:
  - Outer: `<aside className="w-60 shrink-0 flex flex-col h-full ...">` — explicit `flex flex-col h-full` is required so the `flex-1` runway slot has a determined height to fill (Task 7's `<svg className="w-full h-full">` depends on it)
  - Brand row: Lucide `PlaneTakeoff` (24×24) + product name "AOS Documentation Chatbot" (two lines if needed via `whitespace-pre-line`)
  - `<Button variant="outline" size="sm" className="w-full">` with Lucide `Plus` icon + "New chat"
  - **Click handler logic:** if `useChatStore.getState().messages.length === 0`, **early-return** (no store action). Otherwise opens an `AlertDialog` (shadcn): title "Start a new chat?", description "This will clear the current conversation. You can't undo this.", actions Cancel / Continue. Continue calls `useChatStore.getState().clearAll()`.
  - Below the button: `<div className="flex-1">` placeholder (Task 7 mounts `<RunwayBackground />`)
- [x] Mount `<ChatSidebar />` in `ChatPage.tsx` replacing the placeholder
- [x] `ChatSidebar.test.tsx`:
  - renders brand text and PlaneTakeoff icon
  - clicking "New chat" with empty messages does NOT open dialog and does NOT call `clearAll` (assert via spy on `useChatStore.setState` or explicit no-op check)
  - clicking "New chat" with ≥ 1 message opens the AlertDialog
  - clicking "Cancel" does not clear messages
  - clicking "Continue" clears messages
  - reset store via `useChatStore.setState({ messages: [], isStreaming: false }, true)` in `beforeEach`
- [x] Run `cd frontend && npm test` — green before Task 7

### Task 7: `RunwayBackground` SVG + `--accent-magenta`

**Files:**
- Create: `frontend/src/components/chat/RunwayBackground.tsx`
- Create: `frontend/src/components/chat/RunwayBackground.test.tsx`
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/components/chat/ChatSidebar.tsx`

- [ ] Add `--accent-magenta: #C2185B;` to `frontend/src/index.css` inside the `@theme` block
- [ ] `RunwayBackground.tsx`:
  - Inline `<svg viewBox="0 0 240 480" preserveAspectRatio="xMidYMax meet" className="w-full h-full">`
  - Trapezoid: wide base spanning ~80% of width at `y=480`, narrow top spanning ~10% at `y=80`. Fill very light gray; subtle stroke
  - Centerline: short series of dashes along the central axis, shorter / dimmer near the vanishing point
  - Vanishing point: `<circle cx="120" cy="80" r="3" fill="var(--accent-magenta)" />` plus a soft glow (`r=10`, `fill="var(--accent-magenta)"`, `opacity="0.2"` behind it)
- [ ] Mount `<RunwayBackground />` in the sidebar's `flex-1` slot
- [ ] `RunwayBackground.test.tsx`:
  - renders an `<svg>` element
  - the magenta vanishing point uses `var(--accent-magenta)` (`expect(circle.getAttribute('fill')).toBe('var(--accent-magenta)')`)
- [ ] Run `cd frontend && npm test` — green before Task 8

### Task 8: `EmptyState` with text hints

**Files:**
- Create: `frontend/src/components/chat/EmptyState.tsx`
- Create: `frontend/src/components/chat/EmptyState.test.tsx`

- [ ] `EmptyState.tsx`:
  - Centered: `<div className="h-full flex items-center justify-center text-center">`
  - Heading: "Ask a question about AOS documentation."
  - Body: small `<ul>` with examples — "What is the MA-03 error code?" and "How do I install component X?". Plain text, no buttons.
- [ ] `EmptyState.test.tsx`:
  - renders heading
  - renders both example questions
- [ ] Run `cd frontend && npm test` — green before Task 9

### Task 9: `ChatInput` (textarea + counter + Enter / Shift+Enter + Send)

**Files:**
- Create: `frontend/src/components/chat/ChatInput.tsx`
- Create: `frontend/src/components/chat/ChatInput.test.tsx`
- Modify: `frontend/src/components/chat/ChatPage.tsx`

- [ ] `ChatInput.tsx`:
  - Props: `onSend(text: string)`, `disabled: boolean`
  - State: `text: string`
  - `<form onSubmit>` with `<Textarea>` (shadcn) + `<Button type="submit">` (Lucide `Send` icon, `aria-label="Send"`)
  - Textarea: `placeholder="Ask a question…"`, `value={text}`, `onChange`, `onKeyDown` (Enter without Shift → submit, prevent default; Shift+Enter → newline by default)
  - Auto-resize: `useLayoutEffect` to grow `rows` up to 6, then `overflow-y: auto`
  - Counter `<span aria-live="polite">{text.length} / 4000</span>` — small gray text below textarea
  - Send disabled when: `disabled` is true OR `text.trim().length === 0` OR `text.length > 4000`
  - On submit: call `onSend(text)`, clear textarea
- [ ] Mount in `ChatPage` with placeholder `onSend={() => {}}` and `disabled={false}` for now
- [ ] `ChatInput.test.tsx`:
  - typing updates the counter
  - Enter calls `onSend` with trimmed text and clears input
  - Shift+Enter inserts a newline and does NOT call `onSend`
  - clicking Send calls `onSend`
  - whitespace-only input does not trigger `onSend`; Send is disabled
  - text > 4000 chars disables Send and is reflected in counter
  - `disabled={true}` disables Send regardless of input
- [ ] Run `cd frontend && npm test` — green before Task 10

### Task 10: `MessageList` with pin-to-bottom autoscroll + memoization

**Files:**
- Create: `frontend/src/components/chat/MessageList.tsx`
- Create: `frontend/src/components/chat/MessageList.test.tsx`
- Modify: `frontend/src/components/chat/ChatPage.tsx`

- [ ] `MessageList.tsx`:
  - Subscribes to `useChatStore(s => s.messages)`
  - Empty state: when `messages.length === 0`, render `<EmptyState />`
  - Otherwise, map messages to a row component. Use a tiny inline `MessageRow` switch on `message.role` rendering `<div>{message.content}</div>` for now (Tasks 11–12 swap in real components). The switch must be wrapped in `React.memo` keyed on `message.id` and the whole `message` reference — when only one message in the array changes (the streaming one), only that row re-renders
  - Pin-to-bottom logic:
    - `useLayoutEffect` after every render: if user was at bottom (within 20px), scroll to new bottom
    - Track "is at bottom" via state, updated `onScroll`
    - When not at bottom AND messages exist, render a floating `<Button>` (centered just above input area) labeled "Jump to latest" (Lucide `ChevronDown`). Click scrolls and re-engages pin
- [ ] Mount `<MessageList />` in `ChatPage`. Wire `useChatStore(s => s.isStreaming)` → `<ChatInput disabled={isStreaming} ... />`
- [ ] `MessageList.test.tsx`:
  - empty state renders when no messages
  - messages render their content
  - on scroll up by > 20px from bottom, "Jump to latest" appears
  - clicking "Jump to latest" scrolls to bottom (mock `Element.prototype.scrollTo`); the button disappears
  - new message appended while pinned: `scrollTop` updates to `scrollHeight - clientHeight`
  - new message appended while user scrolled up: `scrollTop` does NOT update
  - **memoization**: appending a token to the streaming message does NOT re-render a prior `done` message (assert via render counter / `React.Profiler` or by spying on the row component's render function)
- [ ] Run `cd frontend && npm test` — green before Task 11

### Task 11: `UserMessage` component

**Files:**
- Create: `frontend/src/components/chat/UserMessage.tsx`
- Create: `frontend/src/components/chat/UserMessage.test.tsx`
- Modify: `frontend/src/components/chat/MessageList.tsx`

- [ ] `UserMessage.tsx`, wrapped in `React.memo`:
  - `<div className="flex gap-3 px-4 py-4">`
  - Left: 32×32 avatar slot (neutral background, Lucide `User` icon)
  - Right: "You" label small/dim, then `<div className="whitespace-pre-wrap">{content}</div>`
- [ ] In `MessageList`, replace the temporary placeholder for `role === 'user'` with `<UserMessage message={...} />`
- [ ] `UserMessage.test.tsx`:
  - renders content text
  - renders "You" label
  - renders the User icon avatar
- [ ] Run `cd frontend && npm test` — green before Task 12

### Task 12: `AssistantMessage` state-machine + markdown + Retry

**Files:**
- Create: `frontend/src/components/chat/AssistantMessage.tsx`
- Create: `frontend/src/components/chat/AssistantMessage.test.tsx`
- Modify: `frontend/src/components/chat/MessageList.tsx`
- Modify: `frontend/package.json`

- [ ] Add deps: `npm install react-markdown remark-gfm` (commit `package.json` + `package-lock.json`)
- [ ] `AssistantMessage.tsx`, wrapped in `React.memo`:
  - Outer: `<div className="flex gap-3 px-4 py-4 bg-muted/50">`
  - Avatar: 32×32 circle with `style={{ backgroundColor: 'var(--accent-magenta)' }}`, white Lucide `Bot` icon
  - Right column:
    - "AOS Assistant" label small/dim
    - Body switches on `message.status`:
      - `queued | processing`: `<div className="flex items-center gap-2 text-muted-foreground"><Loader2 className="animate-spin" /> {message.statusText ?? 'Working…'}</div>`
      - `streaming | done`: `<ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>`. When `done` and `message.sources` non-empty, render placeholder Source list (real `<SourceCard />` lands in Task 14)
      - `error`: red bubble (`<div className="border border-destructive bg-destructive/10 rounded p-3">`) with `formatChatUxError(message.uxError!)`. Below: `<Button onClick={onRetry} variant="outline" size="sm">Retry</Button>`
  - Accept `onRetry?: (messageId: string) => void` prop. Unset → no Retry button
- [ ] In `MessageList`, render `<AssistantMessage message={msg} onRetry={onRetry} />` for `role === 'assistant'`. Add `onRetry?: (messageId: string) => void` prop on `MessageList`. Wired in Task 16; pass `undefined` for now
- [ ] `AssistantMessage.test.tsx`:
  - `queued` renders `Loader2` + `statusText`
  - `processing` updated `statusText` renders
  - `streaming` renders markdown content (assert that `**bold**` becomes `<strong>`; a fenced code block becomes `<pre><code>`; a GFM table renders as `<table>`)
  - `done` with sources renders markdown + a "Sources" heading
  - `error` renders the formatted UX message + Retry button; clicking Retry calls `onRetry(message.id)`
  - **XSS escape**: content `<script>alert(1)</script>` renders as escaped text — the rendered DOM contains NO `<script>` element (assert `container.querySelector('script')` is null and the escaped text appears in `textContent`)
  - assistant avatar uses `var(--accent-magenta)` (`expect(avatar).toHaveStyle('background-color: var(--accent-magenta)')`)
- [ ] Run `cd frontend && npm test` — green before Task 13

### Task 13: `SourceCard` with Show more

**Files:**
- Create: `frontend/src/components/chat/SourceCard.tsx`
- Create: `frontend/src/components/chat/SourceCard.test.tsx`

- [ ] `SourceCard.tsx`:
  - Wraps a shadcn `<Card>`
  - Header text: `documentName` always; `§{section}` if non-null/non-empty; `p.{page}` if non-null. Joined with `· ` separator
  - Body: snippet display with `expanded: boolean` state
    - If `snippet.length <= 150`: render full text, no button
    - Else: render `expanded ? snippet : snippet.slice(0, 150).trimEnd() + '…'` plus a `<button>` toggling. Label: `expanded ? 'Show less' : 'Show more'`
- [ ] `SourceCard.test.tsx`:
  - header includes documentName, section, page when all present
  - section omitted when `null`
  - page omitted when `null`
  - snippet ≤ 150 chars: full text, no button
  - snippet > 150 chars: truncated with "Show more"
  - clicking "Show more" expands; clicking "Show less" collapses
- [ ] Run `cd frontend && npm test` — green before Task 14

### Task 14: Wire `SourceCard` into `AssistantMessage`

**Files:**
- Modify: `frontend/src/components/chat/AssistantMessage.tsx`
- Modify: `frontend/src/components/chat/AssistantMessage.test.tsx`

- [ ] Replace the placeholder source list (when `status === 'done'` and `sources` non-empty) with `<div className="mt-4 space-y-2"><h4 className="text-sm font-medium">Sources</h4>{sources.map((s, idx) => <SourceCard key={`${s.documentId}-${idx}`} source={s} />)}</div>`. Key by `documentId-idx` (not array index alone) — `SourceCard` holds local `expanded` state, and `idx` alone could let two cards' state bleed across re-renders if document order shifts. The parent `AssistantMessage` is keyed by `message.id` already, so this scope is sufficient.
- [ ] Update tests:
  - source cards render `documentName`
  - sources block absent when `streaming`
  - sources block absent when `done` and `sources` is empty/undefined
- [ ] Run `cd frontend && npm test` — green before Task 15

### Task 15: `BackfillBanner` reading `useReadyStatus` + page-level blocker

**Files:**
- Create: `frontend/src/components/chat/BackfillBanner.tsx`
- Create: `frontend/src/components/chat/BackfillBanner.test.tsx`
- Modify: `frontend/src/components/chat/ChatPage.tsx`
- Modify: `frontend/src/components/chat/ChatPage.test.tsx`

- [ ] `BackfillBanner.tsx`:
  - Calls `useReadyStatus()`. If `status === 'running'`, render a yellow banner: "Knowledge base is being prepared. You can ask questions, but expect a short wait." Otherwise return `null`
- [ ] In `ChatPage.tsx`:
  - Read `useReadyStatus()` once
  - If `status === 'failed'`: render a centered red-bordered card replacing the message list + input area: "Knowledge base unavailable. Please contact your administrator." (Sidebar continues to render normally)
  - Otherwise: mount `<BackfillBanner />` above `<MessageList />`; mount input as usual
- [ ] `BackfillBanner.test.tsx`:
  - mock `useReadyStatus` returning `running` → banner renders
  - mock returning `failed` / `idle` / `ready` → null
- [ ] Update `ChatPage.test.tsx`:
  - `status: 'failed'` → blocker renders, `MessageList` and `ChatInput` do NOT render
  - `status: 'running'` → banner renders, message list and input render
- [ ] Run `cd frontend && npm test` — green before Task 16

### Task 16: Full chat orchestration in `ChatPage`

**Files:**
- Modify: `frontend/src/components/chat/ChatPage.tsx`
- Modify: `frontend/src/components/chat/ChatPage.test.tsx`

The orchestration capstone. Wire `onSend`, abort lifecycle, retry, auto-retry with countdown.

- [ ] Refs in `ChatPage`:
  - `controllerRef = useRef<AbortController | null>(null)` — at most one in-flight controller
  - `retryRef = useRef<{ timeoutId: number; intervalId: number; messageId: string } | null>(null)` — at most one pending auto-retry
- [ ] Subscribe to `messages.length` transitions via `useChatStore.subscribe` set up in a `useEffect(..., [])` (mount-once). Use a closure-tracked previous-length variable: act ONLY when length transitions from `> 0` to `0` (the `clearAll` flow). Ignore the initial `0` (mount before rehydrate) and ignore the post-rehydrate `[] → [N]` transition. On the actual `> 0 → 0` transition: abort `controllerRef.current`, `clearTimeout`/`clearInterval` on `retryRef.current`, null both refs.
- [ ] `useEffect` cleanup on unmount: abort `controllerRef.current` if non-null; clear both timers in `retryRef.current` if non-null; null both refs.
- [ ] Define `runStream(messageId: string, body: ChatRequestBody)`:
  1. Clear prior state (paranoid; also handles the Retry-during-countdown click): if `retryRef.current` is non-null → `clearTimeout(retryRef.current.timeoutId)`, `clearInterval(retryRef.current.intervalId)`, `retryRef.current = null`. If `controllerRef.current` is non-null → `controllerRef.current.abort()`, `controllerRef.current = null`.
  2. Create new `AbortController`; assign to `controllerRef`
  3. `setIsStreaming(true)`
  4. `try { for await (const event of streamChat(body, controller.signal)) { switch ... } } catch (error) { const ux = mapHttpError(error); if (ux.kind === 'backfill_running') { scheduleAutoRetry(messageId, body, ux.retryAfterSeconds); return; } setError(messageId, ux); } finally { if (!retryRef.current) setIsStreaming(false); controllerRef.current = null; }`
  5. Event switch:
     - `queued` → `setStatus(id, 'queued', \`In queue (#${event.position}, ~${event.estimatedWait}s)…\`)`
     - `processing` → `setStatus(id, 'processing', event.status)`
     - `token` → `appendToken(id, event.text)`
     - `sources` → `setSources(id, event.sources)`
     - `done` → `setStatus(id, 'done')`
     - `error` (mid-stream) → `setError(id, mapMidStreamError(event.message))`
- [ ] Define `scheduleAutoRetry(messageId, body, seconds)`:
  - Set status to `queued` with `statusText = \`Retrying in ${seconds}s…\``
  - Use a local `let remaining = seconds`. Start a `setInterval(1000)` whose callback decrements `remaining` and calls `setStatus(messageId, 'queued', \`Retrying in ${remaining}s…\`)`
  - Set a `setTimeout(seconds * 1000)` whose callback **MUST execute in this exact order**: (1) `clearInterval(intervalId)`, (2) `retryRef.current = null` (BEFORE the next step — the next `runStream`'s `finally` evaluates `!retryRef.current` and would misbehave if we don't null first), (3) `runStream(messageId, body)`
  - Store `{ timeoutId, intervalId, messageId }` in `retryRef.current`
- [ ] Define `handleSend(text: string)`:
  1. Snapshot `messages` from `useChatStore.getState()` BEFORE adding the new message
  2. Build `history`: filter snapshot to `status === 'done'`, map `{role, content}`, `slice(-20)`
  3. `addUserMessage(text)` and `addAssistantMessage()` — capture assistant id
  4. Build `body = { message: text, history }`
  5. Call `runStream(assistantId, body)`
- [ ] Define `handleRetry(messageId: string)`:
  1. Cancel any pending auto-retry / in-flight stream (abort controller, clear timers)
  2. Find the user message immediately preceding `messageId`; capture its `id` as `precedingUserId` and its `content` as `text`
  3. `resetAssistantMessage(messageId)`
  4. Build `history`: snapshot `messages` (taken AFTER `resetAssistantMessage` from step 3 ran — so the assistant is back to `queued` and naturally drops via the `done` filter), filter to `status === 'done'` AND `id !== precedingUserId`, map `{role, content}`, `slice(-20)`. The `id !== precedingUserId` exclusion is required because the user message is the question we're re-asking — putting it in `history` would duplicate it alongside `body.message`. (The `id !== messageId` clause from earlier drafts is redundant after `resetAssistantMessage` — the status filter already drops it.)
  5. Build `body = { message: text, history }`
  6. Call `runStream(messageId, body)`
- [ ] Pass `handleSend` to `<ChatInput onSend={handleSend} disabled={isStreaming} />`. The `readyStatus === 'failed'` branch swaps the entire input area for the blocker card (Task 15), so passing `readyStatus` here would be dead-code defensive — skip it.
- [ ] Pass `handleRetry` to `<MessageList onRetry={handleRetry} />`
- [ ] `ChatPage.test.tsx` — using `mockSseStream` from `frontend/src/test-utils/sseMocks.ts`. **Test setup (top of file, in `beforeEach`):**
  - reset the chat store: `useChatStore.setState({ messages: [], isStreaming: false }, true)`
  - mock `useReadyStatus` to return `{status: 'ready', isRunning: false}` so the chat area renders normally and the real `fetch('/api/health/ready')` doesn't compete with mocked chat fetches
  - render `<ChatPage />` inside a `QueryClientProvider` wrapper (mirror Phase 5's `DocumentsPage.test.tsx` helper) — `useReadyStatus` wraps `useQuery` and the `BackfillBanner` mounts under the same provider context even when the hook itself is mocked
  - tests that need sequential responses use `vi.mocked(fetch).mockImplementationOnce(...)` chained per call (per the convention noted in `sseMocks.ts`)
  - use `vi.useFakeTimers()` for tests involving the auto-retry countdown, then `vi.advanceTimersByTime(...)` to step through; restore real timers in `afterEach`
- [ ] Test cases:
  - happy path: send → fetch called with right body (filtered + sliced history) → events fold into store correctly → final state has done message with sources
  - history filter: build state with 1 done user msg, 1 done assistant msg, 1 error assistant msg → send → request body's `history` length 2 (drops error)
  - history slice: build state with 21 done messages → send → request body's `history` length 20 (drops oldest)
  - mid-stream error → message ends in `error` status, Retry button → click rerun succeeds
  - pre-SSE 503 `embedding_backfill_in_progress` (Retry-After: 1 for fast test) → countdown shown → second attempt succeeds
  - pre-SSE 503 `queue_unavailable` → error state, Retry works
  - pre-SSE 503 `backfill_failed` → error state with the `formatChatUxError` text
  - pre-SSE 400 `invalid_request` → error state with reason in message
  - network failure (`fetch` throws TypeError) → error state, Retry works
  - **abort-on-clear**: send → mid-stream `clearAll()` via store → no further `setStatus`/`appendToken`/**`setError`** calls → no further `fetch` calls; after, send a fresh message → it streams normally. The `setError` assertion is critical — without it, an `AbortError` from the underlying `fetch` could erroneously surface as a phantom error message.
  - **abort-on-unmount**: send → unmount `ChatPage` → no further `setStatus`/`appendToken`/`setError` calls
  - **clear-during-countdown**: backfill 503 with `Retry-After: 2` → `vi.advanceTimersByTime(1000)` → assert statusText shows "Retrying in 1s…" → call `clearAll()` → `vi.advanceTimersByTime(5000)` → no follow-up `runStream` call, no `setStatus`/`setError` after clear
  - **retry-during-countdown**: backfill 503 with `Retry-After: 2` → user clicks Retry on the assistant bubble → assert countdown timer is cleared (no further statusText decrement on tick) → new request fires immediately
  - **countdown observable values**: backfill 503 with `Retry-After: 2` → assert initial statusText is "Retrying in 2s…" → `advanceTimersByTime(1000)` → "Retrying in 1s…" → `advanceTimersByTime(1000)` → second `fetch` is called (subsequent stream is mocked to succeed)
  - `disabled` on input is true while streaming; back to false after `done`
- [ ] Run `cd frontend && npm test` — green before Task 17

### Task 17: Verify acceptance criteria (manual smoke)

This task does not produce code — it is a manual gate before declaring Phase 6 done.

- [ ] `cd frontend && npm test` — full green
- [ ] `cd frontend && npm run build` — clean build
- [ ] `cd frontend && npm run lint` — no new warnings/errors
- [ ] Start backend (`cd backend && JWT_SECRET=$(openssl rand -hex 32) ADMIN_PASSWORD=test1234 ./gradlew run`). Upload at least one `.docx` via `/admin/documents` (Phase 5 admin)
- [ ] Start frontend (`cd frontend && npm run dev`). Open `http://localhost:5173/`
- [ ] Verify each acceptance scenario:
  - empty state: hint and runway visible, ChatInput focusable, no admin/login link anywhere
  - first question → status transitions through at least two distinct `processing` strings (e.g., "Embedding query..." → "Searching documents..." → "Generating response...") → tokens stream → sources appear
  - inline `[Source: …]` text appears verbatim in the assistant message body (NOT a clickable link)
  - "Show more" only appears on snippets > 150 chars; expands on click; "Show less" collapses
  - asking a second question keeps the prior conversation; history sent to the backend includes prior turns
  - `+ New chat` with empty conversation does nothing; with ≥ 1 message shows confirmation dialog; Cancel keeps state, Continue clears
  - F5 → conversation persists; close the tab + reopen → conversation cleared
  - long answer + scroll up → "Jump to latest" appears; click jumps and re-engages pin
  - Enter sends; Shift+Enter inserts newline; whitespace-only does not send; counter updates per keystroke
  - while streaming: Send is disabled; the textarea remains editable
  - markdown tables in the assistant response render and don't horizontally overflow at 1080p
  - markdown fenced code blocks render with monospace font
  - simulate `embedding_backfill_in_progress` (use ReactDevTools to override `useReadyStatus` mock; or manually run with a fresh DB triggering the backfill) → yellow banner appears; chat still works (with a brief 503 → retry countdown on the first send)
  - simulate `'failed'` similarly → blocker replaces chat area; sidebar still renders
  - simulate `queue_unavailable` (stop Artemis temporarily) → manual Retry on the message body recovers when Artemis comes back
  - the runway has a magenta vanishing point; the assistant avatar is on a magenta circle; nothing else in the page is magenta
- [ ] Mark any deviations as ➕ tasks; mark this task `[x]` when all scenarios pass

### Task 18: Mark Phase 6 closure in `ARCHITECTURE.md` §15

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] In §15 Phase 6 ("Chat UI"), flip every checklist item from `[ ]` to `[x]` (Chat interface, Message streaming, Source cards, Queue status display, History (session only))
- [ ] Confirm Phase 7 / Phase 8 lists are unchanged from Task 1 (`[ ]` items only — those phases are not yet started)
- [ ] `cd frontend && npm test` — still green
- [ ] Commit message: `feat: verify acceptance criteria for Phase 6` (matching the Phase 5 closure pattern)

## Post-Completion

*Items requiring manual intervention or external systems — no checkboxes, informational only.*

**Plan archive (do NOT do this until all reviews pass):**
- The plan file must stay at `docs/plans/phase-6-chat-ui.md` during all of the following: task execution (ralphex or interactive), code review, and any review iterations on the branch. Reviewers reference task IDs against the live file — moving it early disconnects the review thread from the source.
- **Only after all reviews are passed**, manually run: `git mv docs/plans/phase-6-chat-ui.md docs/plans/completed/2026-04-28-phase-6-chat-ui.md` (today's date — adjust at archive time) on the same branch, commit, push.
- This step is **not** part of the plan checklist on purpose: (a) including it inside ralphex breaks completion detection (see auto-memory `feedback_ralphex_plan_archive`); (b) "tasks all done" is a weaker gate than "reviews accepted" — only the latter justifies graduating the plan.

**Manual verification scenarios** (in addition to Task 17 — re-run before merge if implementation took multiple sessions):
- Real Ollama latency: a `.docx` with many images can take ~30s on the first query; verify the queued/processing status keeps the operator engaged.
- Long answer with markdown tables and a fenced code block: verify rendering and horizontal layout at 1080p.
- Inline `[Source: …]` text in a real LLM response: confirm it renders as plain text.
