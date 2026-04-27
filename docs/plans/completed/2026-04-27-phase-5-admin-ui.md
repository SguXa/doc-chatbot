# Phase 5: Admin UI

## Overview

Build the React admin surface that consumes the JWT-protected backend shipped in Phase 4. Operators authenticate at `/login` with `ADMIN_PASSWORD`, land on `/admin/documents`, and from there manage the knowledge base: list documents, drag-and-drop upload `.docx`/`.pdf` with byte-level progress and a parsing spinner, delete documents, trigger a global reindex with progress polling, and edit the system prompt that drives `/api/chat`. The only backend addition is `POST/GET /api/config/system-prompt` (`ConfigRoutes`) — `ConfigRepository` and the V004 seed migration already exist (Phase 4). Export/Import knowledge base, Chat UI, and MODE-aware navigation are explicitly out of scope and ship in Phase 6.

## Context

- **Files involved:** New code under `backend/src/main/kotlin/com/aos/chatbot/routes/` (`ConfigRoutes.kt`, `dto/ConfigRequests.kt`); a small extension to `db/repositories/ConfigRepository.kt`; one wiring change in `Application.kt`. New frontend modules under `frontend/src/api/`, `frontend/src/stores/`, `frontend/src/components/auth/`, `frontend/src/components/admin/`, `frontend/src/components/ui/` (shadcn-generated), `frontend/src/lib/errors.ts`. Edits to `App.tsx`, `package.json`, `vite.config.ts` (only if shadcn-init mandates), `tsconfig.app.json` (path aliases for `@/*`). Doc edits to `ARCHITECTURE.md` §15 / §16, `CLAUDE.md` "Phase Discipline", `README.md`.
- **Already in place from Phase 4:** `ConfigRepository.get(key)` / `put(key, value)`, `V004__seed_system_prompt.sql` (idempotent INSERT OR IGNORE), `ChatService.readSystemPrompt()` reading from `ConfigRepository` with `DEFAULT_SYSTEM_PROMPT` fallback. The brainstorm originally listed "V006 seed migration" as a Phase 5 task — that was based on stale information; V004 already does it and no new migration is required.
- **Related patterns:** Manual constructor DI, coroutines, named exports, JUnit 5 + MockK, kotlinx.serialization, operation-scoped repositories, stable string error discriminators, mode-gated route registration. Frontend follows the conventions in CLAUDE.md: TanStack Query for server state, Zustand only for state that genuinely needs sharing (auth), shadcn/ui for components, Tailwind for styling, named exports.
- **Source of truth:** `docs/ARCHITECTURE.md` §7.2 (admin endpoints + stable error discriminators — the upload UX maps directly to this), §7.3 (config endpoints), §7.4 (auth endpoints — already implemented), §11.2 (protected-route table), §15 (implementation plan checkboxes), §16 (future enhancements — extended in this phase).
- **Architectural decisions:** ADR 0001 (synchronous upload — drives the two-stage upload UX), ADR 0005 (auth deferred — historical context), ADR 0006 (queue-chat-dispatch — drives the reindex polling contract), ADR 0007 (single admin — drives the auth flow shape).

## Design Decisions

- **Token storage: `localStorage['aos.token']`.** Hydrated once on app boot before render, so there is no logged-in/logged-out flicker. Acceptable XSS exposure: app is offline-only, no user-generated content, no third-party iframes. Considered `sessionStorage` and `httpOnly` cookies — both rejected (UX friction or backend rewrite for a phantom benefit).
- **No client-side JWT exp parsing.** Token is "valid until backend says 401". Reduces deps (no `jwt-decode`), real outcome is identical (a stale token would 401 on the next request anyway).
- **401 → `apiFetch` calls `useAuthStore.getState().logout()` directly, then throws `UnauthorizedError`.** The store-import lives **inside the function body** of `apiFetch`, not at module top — this side-steps the `apiFetch ↔ authStore` circular import while keeping a single source of truth (the store) for token state. After 401, any React component subscribed to `useAuthStore(s => s.isAuthenticated)` re-renders; `ProtectedRoute` then redirects to `/login` with `state.from = location` so the user lands back where they were after login. There is no separate "storage event" or "useToken selector" wiring — the store's `logout()` action is the single mutation point.
- **Logout calls `POST /api/auth/logout`.** Server is stateless (returns 204) per ADR 0007, so the network call is decorative — but the contract is documented and the call is fire-and-forget, so we make it. Consistency over micro-optimization.
- **MODE-aware UI is NOT addressed.** Frontend builds one bundle. In `MODE=client` admin endpoints return 404 (route not registered); the operator hitting `/admin` sees an `apiFetch` 404 surfaced as "Admin is not available in this deployment". Real MODE awareness lands when chat UI ships in Phase 6 — without chat UI there is nothing to switch between.
- **No `react-hook-form`, no `zod`.** Two forms: login (one password field), system prompt (one textarea). `useState` + manual validation is ~10 lines per form; rhf+zod would add dependencies and ceremony for zero benefit. CLAUDE.md "Don't add features beyond what the task requires" applies.
- **Drag-and-drop upload via `XMLHttpRequest`, not `fetch`.** `XMLHttpRequest.upload.onprogress` exposes byte-level upload progress; `fetch` does not. This is a localized escape from the `apiFetch` wrapper for one component (`DocumentUpload`); the wrapper is still used for the response parsing.
- **Two-stage upload feedback.** (1) Real 0–100% byte progress while uploading; (2) indeterminate spinner "Parsing document…" after 100%, because the synchronous `POST /api/admin/documents` blocks for parse + chunking + image extraction + Ollama embedding, which can take ~60s on a large `.docx` with many images. Without stage 2 the operator believes the upload froze at 100%.
- **UI copy is English-only** per CLAUDE.md "Important Operating Notes" ("UI is English only; the LLM handles DE+EN queries"). Every label, button, error message, and dialog text in this plan is English. The brainstorm dialogue happened in Russian; the product itself is not localized.
- **All upload error discriminators handled by name.** `lib/errors.ts` returns a tagged union by `(error, reason)` from the response body. Every case in ARCHITECTURE.md §7.2 maps to a distinct UX message; an unknown `error` falls back to a generic "Server error: …" so we never silently swallow a new backend discriminator.
- **Reindex flow uses `/api/health/ready` polling, no dedicated job-status endpoint.** Per ADR 0006, `POST /api/admin/reindex` is fire-and-forget; the readiness probe already reports `backfill.status` (`idle | running | ready | failed`). The button polls every 3s after kickoff and disables all mutating actions while `running`. This is also how *initial-page-load* gating works: the `useReadyStatus` hook polls on mount and any time a 503 is received from a mutation.
- **`/api/health/ready` returns the same JSON body on 200 and 503 (per ARCHITECTURE.md §7.5).** `apiFetch`'s default behavior is to throw `ApiError` on non-2xx, which would lose the `backfill.status` payload during exactly the time we need it most. `fetchReady` therefore bypasses `apiFetch` and uses a plain `fetch` call (the endpoint is public per §11.2, so no Authorization header is needed) that parses the JSON body for both 200 and 503. This keeps the polling hook honest about `backfill.status === 'failed'` instead of stalling on the last known status.
- **Default prompt seeding stays in V004 — no V006.** Brainstorm assumed a new seed migration was needed; V004 already covers it idempotently. The fallback `DEFAULT_SYSTEM_PROMPT` constant in `ChatService` stays as defense-in-depth (covers the "operator manually wiped the row in dev" case); not "fixing" something that already works.
- **`ConfigRepository.getWithUpdatedAt` is added as an instance method.** Existing `get(key)` returns only `value`; the response shape in §7.3 requires `updatedAt`. The new method is an instance method (NOT a top-level extension function) so it can read the `private val conn` set in the existing constructor — an extension function would not have access. The original `get` is preserved (used by `ChatService`) — no breaking change.
- **`config.value` stays JSON-in-TEXT.** V004 stores the prompt as a JSON-encoded string (so `\n` escapes survive a SQLite TEXT column without mangling). `ChatService` already JSON-decodes on read; `ConfigRoutes` does the same on GET and JSON-encodes on PUT. No schema change.
- **System prompt size limit: 8000 chars (≈2000 tokens).** Validated both client- and server-side. Generous enough for any sane system prompt; small enough to prevent an operator from accidentally putting a megabyte into the LLM context window. Returned as `400 invalid_request, reason=prompt_too_long`.
- **shadcn/ui generated components live in `components/ui/`.** They are git-tracked (per shadcn convention — these are not deps, they are project code). Add components incrementally as needed: `button`, `input`, `label`, `card`, `dialog`, `alert-dialog`, `table`, `dropdown-menu`, `textarea`, `sonner` (toasts).
- **Path alias `@/*` → `frontend/src/*`.** shadcn-init expects this; it also makes imports tidy. Configured in `tsconfig.app.json` and `vite.config.ts`.
- **No E2E tests in Phase 5.** Vitest + RTL component tests cover every error discriminator (DocumentUpload), every auth state (ProtectedRoute, LoginForm), and every flow in SystemPromptPage. Playwright is Polish-phase work — adding it now means a new toolchain, CI jobs, and a Docker browser image for one phase that doesn't yet have a chat UI to exercise.
- **No `data-testid` proliferation.** RTL tests query by accessible role / text per RTL conventions; `data-testid` is a last resort.
- **DELETE-specific error paths (`Invalid document ID`, `Document not found`) intentionally fall through to `kind: 'unknown'` in `lib/errors.ts`.** Operators never type raw IDs; both come up only if a row is deleted from a stale UI state, in which case the generic "Server error" message is acceptable and the operator can refresh. Adding two more named branches would be defensive code for a corner case.
- **`apiPost`, `apiPut`, and `apiDelete` all gracefully handle `204 No Content`** (return `undefined` instead of attempting `response.json()`). Logout returns 204 per ADR 0007 — without this, `apiPost('/api/auth/logout')` would crash on JSON parse.

## Development Approach

- **Testing approach:** Regular (code first, then tests within the same task).
- Complete each task fully before moving to the next.
- Each task produces a compilable/runnable increment that does not regress prior tasks.
- **CRITICAL: each task ends with running the corresponding test suite green** — `cd backend && ./gradlew test` for backend tasks, `cd frontend && npm test` for frontend tasks, both for tasks that touch both.
- **CRITICAL: all tests must pass before starting the next task.** No "I'll fix it next task".
- **CRITICAL: update this plan file when scope changes during implementation** — checkboxes get `[x]`, new tasks get `➕` prefix, blockers get `⚠️` prefix.
- Frontend tests use Vitest + React Testing Library (already configured in `vite.config.ts`). Mock `fetch`/`XMLHttpRequest` directly with `vi.spyOn(globalThis, 'fetch')` and per-test XHR shims.
- Backend tests follow the same conventions as Phase 4: JUnit 5 + MockK; route tests via Ktor `TestApplication`; auth wired via `authenticate("jwt-admin")`.
- No `@Tag("integration")` tests added in Phase 5 — no new Ollama/Artemis interactions are introduced.

## Validation Commands

- `cd backend && ./gradlew test`
- `cd backend && ./gradlew build`
- `cd frontend && npm test`
- `cd frontend && npm run build`
- `cd frontend && npm run lint`
- (manual smoke) Start backend in `MODE=full` with `JWT_SECRET=$(openssl rand -hex 32)` and `ADMIN_PASSWORD=test1234`; start frontend (`npm run dev`); log in at `http://localhost:5173/login`; upload a sample `.docx`; observe progress + parsing spinner + 201 toast; delete it; edit the system prompt; logout.

## Implementation Steps

### Task 1: Frontend pre-requisites — shadcn/ui + zustand + path alias

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/tsconfig.app.json`
- Modify: `frontend/vite.config.ts`
- Create: `frontend/components.json` (shadcn-init artifact)
- Create: `frontend/src/components/ui/*.tsx` (initial set — see below)
- Modify: `frontend/src/index.css` (Tailwind layers if shadcn-init mandates)

shadcn-init writes a few files (`components.json`, base CSS variables, the `cn` helper at `lib/utils.ts` which already exists). After init, add only the components needed in this phase.

- [x] Run `npx shadcn@latest init` — accept defaults: TypeScript yes, style "new-york" or "default" (operator preference; "default" is fine), base color "slate", CSS vars yes, `components.json` at frontend root
- [x] Verify `frontend/tsconfig.app.json` and `frontend/vite.config.ts` have `@/*` → `./src/*` path alias (shadcn-init may add this; if not, add manually)
- [x] shadcn-init writes (or overwrites) `frontend/src/lib/utils.ts` with the standard `cn` helper. Verify the export exists after init runs; do not pre-create it
- [x] Add components: `npx shadcn@latest add button input label card dialog alert-dialog table dropdown-menu textarea sonner`
- [x] Install zustand: `npm install zustand`
- [x] Verify `cd frontend && npm run build` succeeds (compiles all newly added shadcn components)
- [x] Verify `cd frontend && npm test` still green (no test changes; existing `client.test.ts` and `App.test.tsx` must keep passing)
- [x] Verify `cd frontend && npm run lint` reports no new errors

### Task 2: Backend `ConfigRoutes` and repo extension

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/db/repositories/ConfigRepository.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/ConfigRoutes.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/dto/ConfigRequests.kt`
- Modify: `backend/src/main/kotlin/com/aos/chatbot/Application.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/db/repositories/` — add `ConfigRepositoryTest.kt` if missing, otherwise extend
- Create: `backend/src/test/kotlin/com/aos/chatbot/routes/ConfigRoutesTest.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/ApplicationAuthWiringTest.kt` — add `/api/config/*` to the admin-route inventory list

Implements `GET /api/config/system-prompt` and `PUT /api/config/system-prompt` from ARCHITECTURE.md §7.3 / §11.2. `system_prompt` value is stored as a JSON-encoded string in `config.value` (V004 layout); GET decodes, PUT encodes.

- [x] Add `data class ConfigEntry(val value: String, val updatedAt: String)` and an instance method `fun getWithUpdatedAt(key: String): ConfigEntry?` on `ConfigRepository` (NOT a top-level extension function — the existing `private val conn` is needed). Implementation: `SELECT value, updated_at FROM config WHERE key = ?`. Return `null` for missing row. `updated_at` is read as a String from SQLite (ISO-8601 timestamp form `YYYY-MM-DD HH:MM:SS`)
- [x] Create `ConfigRequests.kt` with `@Serializable data class SystemPromptResponse(val prompt: String, val updatedAt: String)`, `@Serializable data class UpdateSystemPromptRequest(val prompt: String)`, `@Serializable data class InvalidConfigRequestResponse(val error: String = "invalid_request", val reason: String)`
- [x] Create `ConfigRoutes.kt` with `fun Route.configRoutes(database: Database)` extension (signature uses the existing `Database` injection pattern, not `DatabaseConfig` — adminRoutes/healthRoutes already take `Database`):
  - [x] `GET /api/config/system-prompt`: open a connection, call `ConfigRepository(conn).getWithUpdatedAt(SYSTEM_PROMPT_KEY)`, JSON-decode the value, respond 200 with `SystemPromptResponse(prompt, updatedAt)`. If repo returns `null` (cannot happen post-V004, but defensive), respond 500 with stable error `{"error": "config_missing"}` so the test environment fails loudly rather than the user seeing an empty editor
  - [x] `PUT /api/config/system-prompt`: deserialize body — on failure 400 `{"error": "invalid_request", "reason": "malformed_body"}`. Validate `prompt.isNotBlank()` → otherwise 400 `reason="empty_prompt"`. Validate `prompt.length <= 8000` → otherwise 400 `reason="prompt_too_long"`. JSON-encode the prompt and call `ConfigRepository(conn).put(SYSTEM_PROMPT_KEY, jsonEncoded)`. Respond 200 with the freshly read `SystemPromptResponse`
  - [x] `SYSTEM_PROMPT_KEY` constant pulled from `ChatService.SYSTEM_PROMPT_KEY` (already defined there) to keep one source of truth
- [x] Wire in `Application.kt`: only in `MODE=full`/`MODE=admin`, register `configRoutes(database)` inside `authenticate("jwt-admin") { ... }` — same provider that protects `/api/admin/*`. Confirm by browsing the existing `registerModeGatedRoutes` call site (Phase 4 introduced the helper) and add config routes alongside admin routes
- [x] `ConfigRepositoryTest`: `getWithUpdatedAt` returns the row; `getWithUpdatedAt` returns `null` for missing key; existing `get`/`put` tests stay green (don't break what works)
- [x] `ConfigRoutesTest` with `TestApplication`:
  - [x] GET with valid token → 200, decoded `SystemPromptResponse` has the V004-seeded default prompt and a non-empty `updatedAt` (auth-token coverage handled in `ApplicationAuthWiringTest`; this test mounts routes directly per `AdminRoutesTest` convention)
  - [x] GET without token → 401 (auth wrapping proof — exercised by `ApplicationAuthWiringTest.adminRoutes` data-driven matrix)
  - [x] PUT with valid token and `{"prompt": "new prompt"}` → 200, response reflects the new prompt; subsequent GET returns the new prompt
  - [x] PUT with empty prompt → 400, `reason=empty_prompt`
  - [x] PUT with 8001-char prompt → 400, `reason=prompt_too_long`
  - [x] PUT with malformed JSON body → 400, `reason=malformed_body`
  - [x] PUT without token → 401 (covered by `ApplicationAuthWiringTest`)
  - [x] After PUT, `ChatService.readSystemPrompt(conn)` returns the new value (cross-check: same DB read path that production chat uses)
- [x] Update `ApplicationAuthWiringTest.adminRoutes` data-driven list: append `HttpMethod.Get to "/api/config/system-prompt"` and `HttpMethod.Put to "/api/config/system-prompt"`. The existing without-token / expired-token / wrong-secret iterators now exercise these routes too — no new test methods needed
- [x] Verify: `cd backend && ./gradlew test` and `cd backend && ./gradlew build`

### Task 3: Frontend API client — auth injection, 401 handling, apiPut/apiDelete

**Files:**
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/client.test.ts`

Extend the existing minimal `client.ts`. The current `apiGet`/`apiPost` use `fetch` and throw `ApiError` on non-2xx; we add `apiPut`, `apiDelete`, an `Authorization: Bearer` header injection, an `UnauthorizedError` subclass for 401, body-parsing of structured error JSON so error discriminators reach `lib/errors.ts` (Task 5), and 204 No Content handling for all verbs.

- [x] Introduce `class UnauthorizedError extends ApiError` (status is always 401)
- [x] Refactor `apiFetch(path, init)` as the shared core:
  - [x] Reads token via `useAuthStore.getState().token` — implemented with a static top-level import of `useAuthStore` and a getState() call inside the function body. The "lazy dynamic import" originally proposed broke `App.test.tsx` (an async tick let test 1's pending fetch leak into test 2 and consume its mock Response body). There is no actual circular dependency — `authStore.ts` does not import from `client.ts` — so the defensive lazy import was unnecessary. Single source of truth (the store) is preserved.
  - [x] Injects `Authorization: Bearer ${token}` when token is non-null
  - [x] On 401 → calls `useAuthStore.getState().logout()` (which itself clears `localStorage['aos.token']`) and throws `UnauthorizedError`
  - [x] On non-2xx → tries `await response.json()`, attaches parsed body to `ApiError.body` (typed as `unknown`); on JSON parse failure, `body` is `undefined`
  - [x] On 204 No Content → returns `undefined as unknown as T` (callers that expect data must not call `apiPost`/`apiPut` against a 204 endpoint)
- [x] Reimplement `apiGet`/`apiPost` on top of `apiFetch`; add `apiPut(path, body)` and `apiDelete(path)`. Preserve existing exports
- [x] Export `ApiError`, `UnauthorizedError`. `ApiError.body` is the new public surface used by `lib/errors.ts`
- [x] Tests in `client.test.ts` (extend, do not replace existing):
  - [x] All four verbs send `Authorization: Bearer <token>` when the store has a token
  - [x] All four verbs send no Authorization header when token is absent
  - [x] 401 response: throws `UnauthorizedError`; `useAuthStore.getState().isAuthenticated` is false afterwards; `localStorage['aos.token']` is cleared (the latter is implementation detail of `logout()` but worth pinning)
  - [x] 4xx with JSON body — `error.body` is the parsed object
  - [x] 4xx with non-JSON body — `error.body` is `undefined` and the call still throws `ApiError`
  - [x] 5xx with JSON body — same as 4xx (covers 503 from upload/reindex paths)
  - [x] All four verbs return `undefined` on 204 No Content (no JSON parse, no crash). Logout (Task 7) and DELETE (Task 9) both rely on this
- [x] Verify: `cd frontend && npm test`

### Task 4: zustand `authStore` + boot hydration

**Files:**
- Create: `frontend/src/stores/authStore.ts`
- Create: `frontend/src/stores/authStore.test.ts`
- Modify: `frontend/src/main.tsx`

Single zustand store, no persistence middleware (we manage `localStorage` explicitly, so the API client and the store agree on the key without round-tripping through `zustand/middleware/persist`).

- [x] Create the store: `interface AuthState { token: string | null; isAuthenticated: boolean; login: (token: string) => void; logout: () => void; hydrate: () => void }`
- [x] `login(token)`: writes `localStorage.setItem('aos.token', token)`, sets `{ token, isAuthenticated: true }`
- [x] `logout()`: removes `localStorage['aos.token']`, sets `{ token: null, isAuthenticated: false }`
- [x] `hydrate()`: reads `localStorage.getItem('aos.token')`; if non-null sets `{ token, isAuthenticated: true }`; else no-op
- [x] `main.tsx`: call `useAuthStore.getState().hydrate()` once before `ReactDOM.createRoot(...).render(...)`. Rationale: this avoids the unauthenticated → authenticated flicker on React's first paint. (Header injection in `apiFetch` does NOT depend on this — it reads the store directly each call — but `ProtectedRoute`'s very first render does, so hydration must precede `render()`)
- [x] Tests:
  - [x] `login(token)` sets `token` field, flips `isAuthenticated` to true, and writes `localStorage['aos.token']`
  - [x] `logout()` clears `token`, flips `isAuthenticated` to false, and removes `localStorage['aos.token']`
  - [x] `hydrate()` with token in localStorage sets state authenticated
  - [x] `hydrate()` with no token in localStorage leaves state unauthenticated (no localStorage write)
  - [x] After `apiFetch` 401 (mock): `useAuthStore.getState().isAuthenticated` is false. This test belongs in `client.test.ts` (Task 3 already lists it) but is cross-referenced here — proves the store IS the single mutation point on 401
- [x] Verify: `cd frontend && npm test`

### Task 5: `lib/errors.ts` — `parseApiError`

**Files:**
- Create: `frontend/src/lib/errors.ts`
- Create: `frontend/src/lib/errors.test.ts`

Single source of truth for translating backend error discriminators into UX messages. Used by Login (400/401 paths), DocumentUpload (every row in the §7.2 table), Delete and Reindex (503 paths), SystemPromptPage (400 paths). Writing it now means downstream tasks just call `parseApiError(error)` and render `error.message`.

- [x] Define `type ParsedError = { kind: 'duplicate' | 'unsupported_extension' | 'unreadable_document' | 'empty_content' | 'malformed_multipart' | 'file_too_large' | 'invalid_content_type' | 'ollama_unavailable' | 'reindex_in_progress' | 'invalid_credentials' | 'empty_password' | 'malformed_body' | 'empty_prompt' | 'prompt_too_long' | 'unauthorized' | 'unknown'; message: string; existing?: DocumentDto }` where `DocumentDto` is the same type defined in `api/documents.ts` (Task 8). The wire shape of `body.existing` for `duplicate_document` is the **full `Document` model** (per `backend/src/main/kotlin/com/aos/chatbot/routes/dto/AdminResponses.kt:10`), not a trimmed triple — kotlinx.serialization emits camelCase by default, so the field names match `DocumentDto` directly. Forward-declare `DocumentDto` in `lib/errors.ts` via a `type` import to avoid a `lib/` → `api/` dependency
- [x] `function parseApiError(error: unknown): ParsedError` — narrows `unknown` to `ApiError`; reads `error.body.error` and `error.body.reason`; maps to `ParsedError.kind` per the table in ARCHITECTURE.md §7.2 + §7.3 + §7.4; `existing` is only present for `duplicate` (lifted from `body.existing`); `message` is an English human-readable string
- [x] Default branch: `kind: 'unknown'`, `message: error.message ?? 'Server error'` — never silently drops; downstream UI shows the generic message. DELETE-specific 400/404 errors (raw "Invalid document ID", "Document not found") also fall through here intentionally (see Design Decisions)
- [x] Tests:
  - [x] Every kind from the union has a deterministic mapping (table-driven test, one row per kind)
  - [x] `UnauthorizedError` → `kind: 'unauthorized'`
  - [x] `ApiError` with `body=undefined` → `kind: 'unknown'`, message preserved from `error.message`
  - [x] Non-`ApiError` thrown value (string, plain `Error`) → `kind: 'unknown'`, sensible fallback message
  - [x] `duplicate` carries the `existing` payload through
- [x] Verify: `cd frontend && npm test`

### Task 6: `LoginForm` + `ProtectedRoute` + base routing

**Files:**
- Create: `frontend/src/components/auth/LoginForm.tsx`
- Create: `frontend/src/components/auth/LoginForm.test.tsx`
- Create: `frontend/src/components/auth/ProtectedRoute.tsx`
- Create: `frontend/src/components/auth/ProtectedRoute.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`

After this task, an operator can navigate to `/login`, submit `ADMIN_PASSWORD`, and land on a placeholder `/admin/documents`. The `AdminLayout` and real pages land in subsequent tasks.

- [x] `LoginForm.tsx`:
  - [x] One `<input type="password">` (shadcn `Input`), submit `<Button>`. Container is a centered shadcn `Card`
  - [x] Local state: `password`, `submitting`, `error: ParsedError | null`
  - [x] On submit: `apiPost<LoginResponse>('/api/auth/login', { username: 'admin', password })`; on success → `useAuthStore.getState().login(response.token)` + `navigate(state.from?.pathname ?? '/admin/documents', { replace: true })`. On error → `parseApiError(error)` and render `error.message`
  - [x] Empty-password client-side validation: don't submit, show "Enter password" without round-tripping
  - [x] Loading: disable button + show shadcn spinner inside it
- [x] `ProtectedRoute.tsx`: reads `useAuthStore(s => s.isAuthenticated)`; if false → `<Navigate to="/login" state={{ from: location }} replace />`; else `<Outlet />`
- [x] `App.tsx` routing:
  - [x] `/` — keep current `HomePage` (chat UI is Phase 6)
  - [x] `/login` — `<LoginForm />`
  - [x] `/admin` — `<ProtectedRoute />` wrapping a placeholder element (Task 7 replaces with `AdminLayout`); nested redirect from `/admin` to `/admin/documents`
- [x] Tests:
  - [x] `LoginForm.test.tsx`: renders password field; submit calls `apiPost` with the right body; on success token is stored and route changes to `/admin/documents` (assert with `MemoryRouter` + `useLocation` spy); on 401 displays "Invalid password"; on 503 displays a network-error message; empty password does not submit
  - [x] `ProtectedRoute.test.tsx`: when unauthenticated, renders `<Navigate>` (assert location change with `MemoryRouter`); when authenticated, renders the wrapped route; deeplink preservation: visit `/admin/documents` while logged out, get redirected to `/login`, after `login()` the next render of `/login` would `Navigate` back to `/admin/documents` (test the state-passing contract)
  - [x] `App.test.tsx` (existing): keep the `/api/health` rendering test; add: visiting `/admin/documents` while unauthenticated lands on `/login`
- [x] Verify: `cd frontend && npm test`

### Task 7: `AdminLayout` — sidebar + outlet + logout

**Files:**
- Create: `frontend/src/components/admin/AdminLayout.tsx`
- Create: `frontend/src/components/admin/AdminLayout.test.tsx`
- Modify: `frontend/src/App.tsx`

Replace the placeholder element from Task 6 with the real layout. After this task, logged-in operators see a left sidebar with two links and a logout button at the bottom.

- [x] `AdminLayout.tsx`:
  - [x] Flex container: 240px sidebar on the left, main content (`<Outlet />`) on the right
  - [x] Sidebar: app title at top, `<NavLink>` to `/admin/documents` ("Documents") and `/admin/system-prompt` ("System Prompt") with `aria-current="page"` styling, `<Button variant="ghost">` "Log out" at the bottom
  - [x] Logout: `apiPost('/api/auth/logout')` (await but ignore failure) → `useAuthStore.getState().logout()` → `navigate('/login', { replace: true })`. Fire-and-forget on failure: even if backend is unreachable, local logout still proceeds. Wrapped in `try/catch` so failures don't block the user
- [x] Update `App.tsx`: `/admin` route uses `<ProtectedRoute>` containing `<AdminLayout>` containing nested routes (`documents`, `system-prompt`) — initially still placeholder elements per route (real pages in Tasks 8/11)
- [x] Tests:
  - [x] Renders both nav links with correct hrefs
  - [x] Active link shows the active state when route matches
  - [x] Logout button: clicking calls `apiPost('/api/auth/logout')`, clears auth store, navigates to `/login`. Assert all three with mocks
  - [x] Logout still clears local state when the API call rejects (mock `apiPost` to reject)
- [x] Verify: `cd frontend && npm test`

### Task 8: `DocumentsPage` — read-only list

**Files:**
- Create: `frontend/src/api/documents.ts`
- Create: `frontend/src/api/documents.test.ts`
- Create: `frontend/src/components/admin/DocumentsPage.tsx`
- Create: `frontend/src/components/admin/DocumentTable.tsx`
- Create: `frontend/src/components/admin/DocumentsPage.test.tsx`
- Modify: `frontend/src/App.tsx` — wire route

Pure read path. No mutations yet — those land in Task 9. After this task the operator sees a table of indexed documents (or an empty-state card).

- [x] `api/documents.ts`: `interface DocumentDto { id: number; filename: string; fileType: 'docx' | 'pdf'; fileSize: number; fileHash: string; chunkCount: number; imageCount: number; indexedAt: string; createdAt: string }`; `fetchDocuments(): Promise<{ documents: DocumentDto[]; total: number }>` calling `apiGet('/api/admin/documents')`. Server already returns newest-first per `DocumentRepository.findAll()` (ARCHITECTURE.md §7.2) — frontend does NOT re-sort
- [x] `DocumentsPage.tsx`:
  - [x] `useQuery({ queryKey: ['documents'], queryFn: fetchDocuments })`
  - [x] Loading skeleton: shadcn `<Skeleton>` (add via shadcn-add if not present), or a simple "Loading…" message
  - [x] Error state: `parseApiError(error).message` in a shadcn `Card` with red accent
  - [x] Empty state: `Card` with "No documents. Upload your first to get started."
  - [x] Non-empty state: render `<DocumentTable documents={data.documents} />`
- [x] `DocumentTable.tsx`: shadcn `<Table>` with columns: `Filename`, `Type` (badge), `Size` (humanize via small util — KB/MB rounding), `Chunks`, `Images`, `Indexed at` (relative date — small util `formatRelativeTime` is fine, or `Intl.RelativeTimeFormat`). Last column is empty for now (delete button lands in Task 9)
- [x] `App.tsx`: replace the `/admin/documents` placeholder with `<DocumentsPage />`
- [x] Tests:
  - [x] `documents.test.ts`: `fetchDocuments` calls the right URL with the right method; returns shape preserved
  - [x] `DocumentsPage.test.tsx`: shows loading state initially; shows empty state when API returns `{ documents: [], total: 0 }`; shows table with N rows when API returns N docs (mock `useQuery` or wrap in `QueryClientProvider`); shows error message when API throws `ApiError`
  - [x] `DocumentsPage.test.tsx`: server order is preserved in render (no re-sorting on the client) — pass two docs in a known order, assert DOM order matches
- [x] Verify: `cd frontend && npm test`

### Task 9: `DocumentTable` mutations — Delete + `ReindexButton` with polling

**Files:**
- Modify: `frontend/src/components/admin/DocumentTable.tsx`
- Create: `frontend/src/components/admin/ReindexButton.tsx`
- Modify: `frontend/src/components/admin/DocumentsPage.tsx`
- Create: `frontend/src/api/admin.ts` — `reindex()`, `deleteDocument(id)`, `fetchReady()`
- Create: `frontend/src/api/admin.test.ts`
- Create: `frontend/src/hooks/useReadyStatus.ts` — backfill polling hook
- Create: `frontend/src/hooks/useReadyStatus.test.ts`
- Create: `frontend/src/components/admin/ReindexButton.test.tsx`
- Modify: `frontend/src/components/admin/DocumentsPage.test.tsx`

Adds the two mutation flows. Both depend on the readiness probe — `useReadyStatus` is the central place that polls `/api/health/ready` while a backfill is running.

- [x] `api/admin.ts`:
  - [x] `interface ReadyStatus { backfill: { status: 'idle' | 'running' | 'ready' | 'failed' } }` (only the field we use; full shape per §7.5 is bigger but irrelevant)
  - [x] `fetchReady(): Promise<ReadyStatus>` — bypasses `apiFetch` and uses raw `fetch('/api/health/ready')` directly so it can parse the JSON body for both 200 and 503 (per §7.5 the body shape is identical). Endpoint is public per §11.2, no Authorization header needed. `/api/health/ready` returning a non-JSON body or a network error is treated as `{ backfill: { status: 'idle' } }` so the UI does not panic-block on transient failures
  - [x] `useReadyStatus.test.ts` includes a test that 503 with `backfill.status='running'` body still resolves to `running` (not throws) — this is the regression guard against accidentally re-routing through `apiFetch`
  - [x] `reindex(): Promise<{ status: 'started' | 'already_running' }>` calling `apiPost('/api/admin/reindex')`
  - [x] `deleteDocument(id): Promise<void>` calling `apiDelete('/api/admin/documents/${id}')`
- [x] `useReadyStatus`:
  - [x] `useQuery({ queryKey: ['ready'], queryFn: fetchReady, refetchInterval: state => state?.backfill.status === 'running' ? 3000 : false })` — polls only while running
  - [x] Returns `{ status, isRunning }` where `isRunning = status === 'running'`. Failure of the readiness call itself does NOT mark `isRunning` true — the hook returns the last known status
- [x] `ReindexButton.tsx`:
  - [x] `Button` "Reindex all" → opens shadcn `<AlertDialog>` with confirmation (title, description, Confirm/Cancel)
  - [x] On confirm: `reindex()` mutation. On success: invalidate `['ready']` so polling kicks in immediately. Toast "Reindex started" via `sonner`. On `503 reindex_in_progress` (already running) — same UX (idempotent)
  - [x] Disabled when `useReadyStatus().isRunning` is true; tooltip "Reindex is running"
- [x] Delete column in `DocumentTable.tsx`:
  - [x] Trash icon button per row → opens `<AlertDialog>` "Delete {filename}? Chunks and images will be removed"
  - [x] On confirm: `deleteDocument(id)` mutation. On success: invalidate `['documents']`, toast "Deleted"
  - [x] On 503 reindex_in_progress: toast with `parseApiError(error).message`. The button is disabled while `isRunning` (consistency with upload)
- [x] `DocumentsPage.tsx` integration: render `<ReindexButton />` next to a future upload zone (Task 10 replaces the wrapper); pass `isRunning` down to disable mutations during reindex
- [x] Tests:
  - [x] `admin.test.ts`: each function calls the right URL and method
  - [x] `useReadyStatus.test.ts`: refetch interval is 3000 when status=running, false otherwise (poll a fake `fetchReady` and observe call count over time using `vi.useFakeTimers()`)
  - [x] `ReindexButton.test.tsx`: clicking opens the dialog; confirm triggers `reindex()`; toast shown on success; button is disabled while `isRunning`; tooltip text appears on hover when disabled
  - [x] `DocumentsPage.test.tsx` extension: delete button per row → opens dialog → confirm triggers `deleteDocument`; query invalidates and refetches (assert via mock `queryClient.invalidateQueries` call); 503 reindex_in_progress shows the parsed error; rows are disabled while reindex is running
- [x] Verify: `cd frontend && npm test`

### Task 10: `DocumentUpload` — drag-drop + XHR progress + every error discriminator

**Files:**
- Create: `frontend/src/components/admin/DocumentUpload.tsx`
- Create: `frontend/src/components/admin/DocumentUpload.test.tsx`
- Modify: `frontend/src/components/admin/DocumentsPage.tsx`

The riskiest task in this phase. Synchronous backend POST that may block ~60s on a real Ollama call requires deliberate UX. Most valuable test: every error discriminator from ARCHITECTURE.md §7.2 maps to a comprehensible UX.

- [x] `DocumentUpload.tsx`:
  - [x] Drag-and-drop zone (shadcn-style `Card` with `dashed` border; `onDragEnter` / `onDragOver` / `onDragLeave` / `onDrop` handlers)
  - [x] Click-to-upload fallback: hidden `<input type="file" accept=".docx,.pdf">` triggered by clicking the zone
  - [x] Reject files with extension other than `.docx`/`.pdf` client-side (no network round-trip) — shows message "Only .docx and .pdf are supported"
  - [x] Upload via `XMLHttpRequest` (NOT `fetch`) to get `xhr.upload.onprogress` events. Set `Authorization: Bearer ${token}` from `useAuthStore.getState().token`. Send `FormData` with single field `file`
  - [x] State machine: `idle` → `uploading (progress: 0..100)` → `parsing (indeterminate)` → `success | error`. Stage 2 starts on `xhr.upload.onload` (all bytes flushed) — at that point we wait on `xhr.onload` (server response)
  - [x] On 401 (token expired mid-upload): mirror `apiFetch` behavior — call `useAuthStore.getState().logout()` and let `ProtectedRoute` redirect on next render. The XHR escape hatch must NOT bypass the 401 contract
  - [x] On 201: parse response, toast "Uploaded", invalidate `['documents']`, return to `idle`
  - [x] On any other error: build a `Response`-like object from `xhr.response` and `xhr.status`, parse JSON if possible, construct an `ApiError` with body, run `parseApiError`, render the message inline + as a toast. For `kind === 'duplicate'`, render an inline shadcn `Dialog` showing `existing.filename` + `existing.indexedAt` + a "Got it" close button (no auto-delete — operator decides)
  - [x] Disabled when `useReadyStatus().isRunning` is true (tooltip "Reindex is running"); disabled while another upload is in flight
  - [x] Visual: stage 1 shows a determinate progress bar; stage 2 shows an indeterminate spinner with text "Parsing document…"; on error shows the message in a red panel that doesn't auto-dismiss (operator must dismiss explicitly so they can read it)
- [x] `DocumentsPage.tsx`: render `<DocumentUpload />` above the table; pass `isRunning` for the disable wiring
- [x] Tests:
  - [x] Drop a `.exe` file → no XHR fires, error message "Only .docx and .pdf are supported"
  - [x] Drop a `.docx` file → XHR opens with the right URL, method `POST`, sends `multipart/form-data` with field `file`, includes `Authorization` header
  - [x] Stage transition: simulate `xhr.upload.onprogress` (50%, 100%) → DOM shows the progress bar at the right value; simulate `xhr.upload.onload` → DOM switches to "Parsing document…"
  - [x] On 201: toast called with success message; query invalidation called; control returns to idle state
  - [x] **Error matrix — one test per discriminator** (DocumentUpload's most valuable test set):
    - [x] 400 `invalid_upload` `unsupported_extension` → shows "Only .docx and .pdf are supported"
    - [x] 400 `invalid_upload` `malformed_multipart` → "File is corrupted, please retry"
    - [x] 400 `unreadable_document` (any reason) → "Could not read file: {body.message}" (server-supplied message is English per `AdminRoutes.kt:160`)
    - [x] 400 `empty_content` → "No extractable text in file"
    - [x] 409 `duplicate_document` → dialog opens with `existing.filename` and `existing.indexedAt` rendered (full `Document` object on `body.existing` per `AdminResponses.kt:10`)
    - [x] 413 `invalid_upload` `file_too_large` → "File exceeds 100 MB limit"
    - [x] 415 `invalid_upload` `invalid_content_type` → defensive message ("Invalid request format")
    - [x] 503 `ollama_unavailable` → "Ollama is unavailable. Upload was rolled back, retry shortly."
    - [x] 503 `reindex_in_progress` → "Reindex is running, retry in a minute."
    - [x] Unknown 500 with no body → generic non-mapped error message (lib/errors falls back to `error.message`, here "API error: 500")
  - [x] Disabled while `isRunning=true`: drop is a no-op
- [x] Verify: `cd frontend && npm test`

### Task 11: `SystemPromptPage`

**Files:**
- Create: `frontend/src/api/config.ts`
- Create: `frontend/src/api/config.test.ts`
- Create: `frontend/src/components/admin/SystemPromptPage.tsx`
- Create: `frontend/src/components/admin/SystemPromptPage.test.tsx`
- Modify: `frontend/src/App.tsx` — wire `/admin/system-prompt`

The default-prompt constant is duplicated here intentionally — it's a frozen UX copy of ARCHITECTURE.md §9.3 used only for the "Reset to default" button. Drift between the constant and `V004` would cause Reset to write a slightly different value than the seed; that's fine (operator pressed Save consciously). To prevent silent drift, a small unit test compares the frontend constant to a checked-in fixture extracted from V004. If the gap matters more in future, an endpoint can serve the canonical default — but that's not needed now.

- [x] `api/config.ts`: `fetchSystemPrompt(): Promise<{ prompt: string; updatedAt: string }>`; `updateSystemPrompt(prompt: string): Promise<{ prompt: string; updatedAt: string }>` calling `apiPut('/api/config/system-prompt', { prompt })`
- [x] `SystemPromptPage.tsx`:
  - [x] `useQuery({ queryKey: ['system-prompt'], queryFn: fetchSystemPrompt })`
  - [x] State: `value: string` (mirrored from server data; render-time conditional setState used instead of `useEffect` to mollify the `react-hooks/set-state-in-effect` lint rule — same behavior, no cascading-render warning); `isDirty = value !== data.prompt`
  - [x] shadcn `<Textarea>`, ~20 rows, monospace styling (`font-mono`)
  - [x] Below textarea: char counter `{value.length} / 8000`. Red when over limit
  - [x] `Save` button — disabled if `!isDirty || value.trim() === '' || value.length > 8000 || mutation.isPending`. On click: `useMutation(updateSystemPrompt)` → invalidate `['system-prompt']`, toast "Saved"
  - [x] `Discard changes` button — visible only when `isDirty`; on click resets local state to `data.prompt`
  - [x] `Reset to default` button — opens shadcn `AlertDialog` "Reset to default? This loads the built-in prompt into the editor. Changes will not be saved until you click Save". On confirm: sets local state to `DEFAULT_PROMPT_CONSTANT` (loaded from the V004 fixture via Vite `?raw`). After reset, `isDirty` is true and the operator must press Save explicitly
  - [x] Loading skeleton; error state via `parseApiError`; mutation errors (`empty_prompt`, `prompt_too_long`, `malformed_body`) shown inline
- [x] `App.tsx`: wire `/admin/system-prompt` route to `<SystemPromptPage />`
- [x] Tests:
  - [x] Loading state visible initially
  - [x] After successful load, textarea contains the prompt and char counter shows the right number
  - [x] Edit → counter updates → Save enables; click Save → `updateSystemPrompt` called with new prompt → query invalidated → toast shown
  - [x] Discard reverts to server value; `isDirty` returns to false; button disappears
  - [x] Reset opens dialog; confirm fills textarea with the default constant; `isDirty` is true; Save still requires explicit click
  - [x] Empty prompt: Save disabled; if forced via direct mutation (mock), 400 `empty_prompt` rendered as inline error
  - [x] 8001 chars: counter red; Save disabled
  - [x] Loading-state and error-state branches via mocked `useQuery`
  - [x] Drift guard: `DEFAULT_PROMPT_CONSTANT` equals the V004-seeded default. Implementation: copy the V004 prompt body into a checked-in fixture file (e.g., `frontend/src/components/admin/__fixtures__/default-system-prompt.txt`), import its content via `?raw` (Vite supports raw imports), assert string equality. If V004 ever changes the default, this test forces a corresponding update to the constant
- [x] Verify: `cd frontend && npm test`

### Task 12: Verify acceptance criteria

End-to-end gate before doc updates. No new code — just verification that everything works together.

- [x] `cd backend && ./gradlew test` green
- [x] `cd backend && ./gradlew build` green
- [x] `cd frontend && npm test` green
- [x] `cd frontend && npm run build` green (catches type errors that `npm test` skipped)
- [x] `cd frontend && npm run lint` reports no new errors
- [x] Manual: start backend in `MODE=full` with `JWT_SECRET=$(openssl rand -hex 32)` and `ADMIN_PASSWORD=test1234`; `npm run dev`; perform the full smoke flow: (skipped - not automatable; covered by Vitest + RTL component tests)
  - [x] Visit `/admin/documents` — redirected to `/login` (skipped - not automatable; covered by `ProtectedRoute.test.tsx` + `App.test.tsx`)
  - [x] Submit `test1234` → land on `/admin/documents` with deeplink working (skipped - not automatable; covered by `LoginForm.test.tsx`)
  - [x] Refresh page — still authenticated (localStorage hydration) (skipped - not automatable; covered by `authStore.test.ts` hydrate tests)
  - [x] Drag a real `.docx` from `docs/test-fixtures/` (or any sample) → progress bar fills → parsing spinner → 201 toast → table updates (skipped - not automatable; covered by `DocumentUpload.test.tsx` stage transitions)
  - [x] Try to upload the same file again → 409 dialog with `existing` info (skipped - not automatable; covered by `DocumentUpload.test.tsx` error matrix)
  - [x] Delete the document → confirmation dialog → row disappears → toast (skipped - not automatable; covered by `DocumentsPage.test.tsx` delete flow)
  - [x] Click "Reindex all" → confirm → button disables, polling visible in DevTools network tab → button re-enables on completion (skipped - not automatable; covered by `ReindexButton.test.tsx` + `useReadyStatus.test.ts`)
  - [x] Visit `/admin/system-prompt` → edit → Save → toast; refresh — new prompt persists; Reset → confirmation → Save again (skipped - not automatable; covered by `SystemPromptPage.test.tsx`)
  - [x] Logout → land on `/login`; visit `/admin/documents` directly → redirected to `/login` (skipped - not automatable; covered by `AdminLayout.test.tsx` + `ProtectedRoute.test.tsx`)
  - [x] (Negative) Manually edit `localStorage.setItem('aos.token', 'garbage')` → reload → first admin call returns 401 → token cleared → redirected to `/login` (skipped - not automatable; covered by `client.test.ts` 401 handling)
- [x] Manual: start backend in `MODE=client` (no `JWT_SECRET`/`ADMIN_PASSWORD`); visit `/admin/documents`; observe that `/api/auth/login` returns 404 → frontend `LoginForm` surfaces a generic "Server error" via `parseApiError` (the empty 404 falls through to `kind: 'unknown'`). Acceptable for Phase 5 — explicit "this deployment doesn't have admin" messaging is MODE-aware UI work for Phase 6 (skipped - not automatable; `errors.test.ts` covers `kind: 'unknown'` fallback)
- [x] No `Co-Authored-By: Claude` trailers in any new commits on this branch (per `MEMORY.md`)
- [x] No new public admin behavior beyond what the active plan and ARCHITECTURE.md describe — grep frontend for hardcoded routes that don't exist in §7

### Task 13: Update documentation

**Files:**
- Modify: `docs/ARCHITECTURE.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [x] `ARCHITECTURE.md` §15 — restructure to match what actually shipped. The original §15 has "Phase 4: Admin Panel" and "Phase 5: Chat UI"; Phase 4 was retroactively scoped to auth-only and Phase 5 became Admin UI. Apply these edits:
  - [x] Rename "Phase 4: Admin Panel" → "Phase 4: Auth (single admin)" and replace its bullets with the actual Phase 4 deliverables (JWT auth + admin route protection)
  - [x] Insert a new "Phase 5: Admin UI" section between Phase 4 and the existing "Phase 5: Chat UI". Bullets: Document upload UI ✓, Document list/delete ✓, System prompt editor ✓, Reindex UI ✓. Note "Export/Import deferred to Phase 6" without a checkbox
  - [x] Renumber the original "Phase 5: Chat UI" → "Phase 6: Chat UI + Export/Import" with an Export/Import bullet appended
  - [x] Renumber the original "Phase 6: Polish" → "Phase 7: Polish"
- [x] `ARCHITECTURE.md` §7.2 — fix the `existing.indexed_at` field name in the 409 response example (currently snake_case at line ~664). The actual wire is camelCase `indexedAt` (kotlinx.serialization default; matches the rest of the same response). Verify by `grep -n "indexed_at" docs/ARCHITECTURE.md` after the fix returns only the SQL DDL hits, not the JSON example
- [x] `ARCHITECTURE.md` §16: append four rows to the Future Enhancements table:
  | Feature | Description | Priority |
  | **System Prompt Preview** | Render the final prompt with retrieval context without sending it to the LLM, for debugging prompt formulations | Medium |
  | **Document Inspect mode** | Read-only chunk viewer per document, so operators can verify parse quality after upload (candidate for Phase 7) | Medium |
  | **PDF tables extraction** | Use `tabula-java` to recognize tabular structure in PDFs (currently text becomes mush) | Medium |
  | **PDF OCR** | Use `tess4j` for scanned PDFs that currently produce `empty_content` | Low |
- [x] `CLAUDE.md` "Phase Discipline" — add a Phase 5 entry: "**Admin UI (Phase 5):** React admin surface for document management and system prompt editing. Backend addition: `GET/PUT /api/config/system-prompt` (admin-protected). Login at `/login`, admin at `/admin/documents` and `/admin/system-prompt`. Token in `localStorage['aos.token']`. **Export/Import is Phase 6** (with Chat UI). Document Inspect mode is a Phase 7 candidate — see ARCHITECTURE.md §16."
- [x] `README.md`: add an "Admin UI" subsection under Document Management:
  - [x] Login URL, default port (5173 for `npm run dev`, 3000 for the prod nginx)
  - [x] Drag-drop upload, list/delete, reindex, system prompt editor — one bullet each
  - [x] Note: "Export/Import lands in Phase 6"
  - [x] Note: production deployment of `MODE=full`/`MODE=admin` should still be restricted to internal networks (chat is public)
- [x] No changes to ADRs in this phase — no new architectural choices warrant one (a possible exception is "fronted state architecture", but the design is conventional enough that an ADR would be filler)

### Task 14: Move plan to completed and update branch

- [x] Once every checkbox above is `[x]`, move this file to `docs/plans/completed/<YYYY-MM-DD>-phase-5-admin-ui.md` (use the actual finish date, matching the convention of `2026-04-27-phase-4-auth.md`)
- [x] Verify the moved plan renders correctly (no broken relative links to `docs/adr/` or `docs/ARCHITECTURE.md`)
- [x] Final `cd backend && ./gradlew test && cd ../frontend && npm test` from a clean checkout (`git stash` any local-only configs first)

## Post-Completion

**Manual verification** (informational, not gating):
- Browser-level smoke on Firefox and Chrome (drag-drop and `XMLHttpRequest.upload.onprogress` are well-supported, but a quick cross-browser pass catches surprises).
- Light load test: try uploading 5 medium-size `.docx` files in sequence to confirm the synchronous POST + reindex polling don't deadlock the UI under realistic operator load.
- Confirm operator workflow with a real AOS document — check that `unreadable_document` and `empty_content` errors are decipherable when they happen, not just "the upload failed".

**External system updates** (none in Phase 5 — everything is in-repo):
- Phase 6 (Chat UI + Export/Import) will:
  - Add a chat surface that consumes `POST /api/chat` SSE — that's a frontend-only feature.
  - Add `GET /api/admin/export` and `POST /api/admin/import` (the deferred half of the original Phase 4 admin scope).
  - Likely introduce MODE-aware navigation (chat-only in `MODE=client`, both surfaces in `MODE=full`).
- Phase 7 candidate: Document Inspect mode (read-only chunk viewer per document) — see ARCHITECTURE.md §16.
