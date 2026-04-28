# AOS Documentation Chatbot — Repository Instructions

This file is for repository conventions, coding rules, and workflow guidance only. It is **not** a product spec or an implementation plan.

## Sources of Truth

| Source | Purpose | When to consult |
|--------|---------|-----------------|
| `docs/ARCHITECTURE.md` | Stable behavior, API, data, and deployment contracts | When you need to know **what** the system does or **what shape** an interface has. |
| `docs/plans/*.md` | Active execution plans for the current phase | When you need to know **what is being built right now** and **in what order**. The plan in the active branch is authoritative for current implementation work. |
| `docs/plans/completed/*.md` | Historical record of finished phases | Reference only. Do not modify. |
| `docs/adr/*.md` | Architectural decision records — the **why** behind durable choices | When you need rationale for a design that looks unusual or restrictive. |
| `CLAUDE.md` (this file) | Repository conventions and coding guidance | When in doubt about conventions, structure, or workflow. |

**Rules:**
- Do not duplicate ARCHITECTURE.md content here. Link to it.
- Do not introduce features that belong to a future phase unless the active plan in `docs/plans/` explicitly requires them.
- If a plan file disagrees with ARCHITECTURE.md, fix the disagreement deliberately — do not let drift accumulate.

## Quick Reference

```bash
# Development (full mode)
docker compose -f docker-compose.dev.yml up

# Backend only
cd backend && ./gradlew run

# Frontend only
cd frontend && npm run dev

# Tests
cd backend && ./gradlew test
cd frontend && npm test

# Integration tests (require a local Ollama with bge-m3 + the configured LLM)
cd backend && OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest
```

## Coding Conventions

### Kotlin Backend
- Use coroutines for all async operations
- Named exports, no wildcard imports
- Manual constructor dependency injection (no DI framework)
- All API routes under `/api/`
- SSE for streaming responses (chat pipeline uses `call.respondBytesWriter` with `ContentType.Text.EventStream`)
- Repositories are operation-scoped — they take a `Connection` in the constructor and must not outlive it
- Errors that cross the API boundary use stable string discriminators in the response body, not enum-typed unions

### React Frontend
- Functional components with TypeScript
- TanStack Query for server state
- Zustand only for client-side state that genuinely needs sharing across components (e.g., `stores/authStore.ts`)
- shadcn/ui components — do not reinvent. Generated components land in `frontend/src/components/ui/` and are git-tracked
- Toast notifications use `sonner` (mounted via `components/ui/sonner.tsx`)
- Tailwind v4 is configured via the Vite plugin (`@tailwindcss/vite`); there is no `tailwind.config.ts` or `postcss.config.js`. Theme tokens live in `frontend/src/index.css` via `@theme`
- Imports use the `@/*` path alias (configured in `tsconfig.app.json` + `vite.config.ts`); prefer `@/lib/foo` over deep relative paths
- Named exports from all files

### Database
- SQLite, accessed via plain JDBC + `PreparedStatement` (no ORM)
- Migrations in `backend/src/main/resources/db/migration/` as `Vnnn__name.sql` files; **migrations are immutable once committed**
- Foreign keys enforced via PRAGMA, with `ON DELETE CASCADE` where appropriate
- Embeddings stored as Float32 BLOBs (little-endian)

### Testing
- Backend: JUnit 5 + MockK
- Frontend: Vitest + React Testing Library
- Test files mirror source layout under `src/test/kotlin/...` (Kotlin) or live next to source as `*.test.tsx` (frontend)
- Every functional increment ships with tests; tests must be green before moving to the next task
- Ollama HTTP contracts are tested with WireMock; JMS flows use an embedded Artemis broker (`EmbeddedActiveMQ`, `vm://` transport)
- Tests tagged `@Tag("integration")` are excluded from `./gradlew test` and run via `./gradlew integrationTest` against a real Ollama (gated on `OLLAMA_TEST_URL`)

## Repository Workflow

- Each active phase lives on a branch named `phase-N-<slug>`. The matching plan file is `docs/plans/phase-N-<slug>.md`.
- Tasks are executed top-to-bottom. Each task should produce a compilable, runnable increment with tests.
- When all tasks in a plan are complete and merged, move the plan file into `docs/plans/completed/` with a date prefix.
- Do not commit new product features without a corresponding entry in the active plan or in ARCHITECTURE.md.

## Common Tasks

### Add a new API endpoint
1. Confirm it is in scope for the current phase plan
2. Create route under `backend/src/main/kotlin/com/aos/chatbot/routes/`
3. Register it in `Application.kt`
4. Add request/response shapes to `docs/ARCHITECTURE.md` §7

### Add a new document parser
1. Implement the `DocumentParser` interface
2. Register it in `ParserFactory`
3. Add tests with representative input fixtures
4. Honor the image linkage contract and pageNumber policy from ARCHITECTURE.md §8.4 / §8.5

### Add a UI component
1. Check shadcn/ui first: `npx shadcn@latest add <component>`
2. If custom, add under `frontend/src/components/`
3. Use Tailwind for styling

## Phase Discipline

- **Auth (Phase 4):** single admin via JWT. `/api/admin/*` requires `Authorization: Bearer <token>`; `/api/chat/*` and `/api/health/*` are public. Password from `ADMIN_PASSWORD` env (required in `MODE=full`/`MODE=admin`), hashed in memory, not persisted. See [ADR 0005](docs/adr/0005-auth-deferred-out-of-phase-2.md) for the deferral context and [ADR 0007](docs/adr/0007-single-admin-no-persisted-users.md) for the single-admin design.
- **Deployment caveat.** `MODE=full` and `MODE=admin` should still be restricted to operator workstations or VPN — chat remains public on the same listener and admin tokens, while signed, do not include rate limiting in Phase 4.
- **Embeddings are generated inline on upload (Phase 3).** `DocumentService` calls `EmbeddingService` before persisting chunks, so `embedding` is never NULL for new uploads. Any chunk still carrying `embedding = NULL` (Phase 2 legacy data) is handled by `EmbeddingBackfillJob` on startup.
- **Chat pipeline is queue-dispatched with an in-memory token bus (Phase 3).** See [ADR 0006](docs/adr/0006-queue-chat-dispatch-with-in-memory-bus.md). `POST /api/chat` enqueues a `ChatRequest` onto `aos.chat.requests`; a single-JVM consumer (`ChatService`, `Semaphore(1)`) streams `QueueEvent`s back through `ChatResponseBus` to the SSE handler. Tokens never traverse JMS.
- **Admin UI (Phase 5):** React admin surface for document management and system prompt editing. Backend addition: `GET/PUT /api/config/system-prompt` (admin-protected). Login at `/login`, admin at `/admin/documents` and `/admin/system-prompt`. Token in `localStorage['aos.token']`. **Export/Import is Phase 6** (with Chat UI). Document Inspect mode is a Phase 7 candidate — see ARCHITECTURE.md §16.
- **Chat UI and export/import are Phase 6 work.** Do not introduce future-phase features speculatively. If a future phase needs something you are noticing right now, document it in ARCHITECTURE.md or an ADR — do not implement it.

## Important Operating Notes

- **Offline operation** — no external API calls; everything runs on the local network
- **Language** — UI is English only; the LLM handles DE+EN queries
- **Queue** — Apache Artemis already exists on target servers; reuse it (Phase 3+)
- **No chat history persistence** — sessions only, no DB storage of conversations
- **Vector search** — in-memory cosine similarity, not a separate vector DB

---

*For architectural questions, see `docs/ARCHITECTURE.md`. For decision rationale, see `docs/adr/`. For what is currently being built, see `docs/plans/`.*
