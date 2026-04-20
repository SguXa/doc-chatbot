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
```

## Coding Conventions

### Kotlin Backend
- Use coroutines for all async operations
- Named exports, no wildcard imports
- Manual constructor dependency injection (no DI framework)
- All API routes under `/api/`
- SSE for streaming responses (chat, future phases)
- Repositories are operation-scoped — they take a `Connection` in the constructor and must not outlive it
- Errors that cross the API boundary use stable string discriminators in the response body, not enum-typed unions

### React Frontend
- Functional components with TypeScript
- TanStack Query for server state
- Zustand only for client-side state that genuinely needs sharing across components (e.g., auth state, when auth lands)
- shadcn/ui components — do not reinvent
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
1. Check shadcn/ui first: `npx shadcn-ui@latest add <component>`
2. If custom, add under `frontend/src/components/`
3. Use Tailwind for styling

## Phase Discipline

- **Auth is Phase 4 work.** Phase 2 and Phase 3 must not introduce JWT, login routes, `authenticate { ... }` blocks, or auth-related env vars (`JWT_SECRET`, `ADMIN_PASSWORD`). Admin routes during the pre-auth window are unprotected by design — see [ADR 0005](docs/adr/0005-auth-deferred-out-of-phase-2.md). The `MODE=client` deployment is the only acceptable public-facing mode until auth lands.
- **Embeddings are Phase 3 work.** Phase 2 persists chunks with `embedding = NULL`. The V002 migration relaxes the V001 NOT NULL constraint to make this possible.
- **Chat is Phase 3 work.** Phase 2 has no `ChatService`, no `LlmService`, no `SearchService`, no SSE chat route.
- Do not introduce future-phase features speculatively. If a future phase needs something you are noticing right now, document it in ARCHITECTURE.md or an ADR — do not implement it.

## Important Operating Notes

- **Offline operation** — no external API calls; everything runs on the local network
- **Language** — UI is English only; the LLM handles DE+EN queries
- **Queue** — Apache Artemis already exists on target servers; reuse it (Phase 3+)
- **No chat history persistence** — sessions only, no DB storage of conversations
- **Vector search** — in-memory cosine similarity, not a separate vector DB

---

*For architectural questions, see `docs/ARCHITECTURE.md`. For decision rationale, see `docs/adr/`. For what is currently being built, see `docs/plans/`.*
