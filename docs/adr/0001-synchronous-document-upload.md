# ADR 0001: Synchronous document upload instead of async jobs

**Status:** Accepted (Phase 2)
**Date:** 2026-04-14

## Context

`POST /api/admin/documents` parses, chunks, extracts images, and writes everything to SQLite. Earlier drafts of the architecture proposed an asynchronous job model: the endpoint returns `202 Accepted` with a `jobId`, the client polls a status endpoint, the work runs on a background worker. Phase 2 ships the pipeline synchronously instead — the endpoint blocks until the full parse/persist cycle finishes and returns `201 Created` (or an error) in a single response.

## Decision

Phase 2 implements synchronous upload only. The HTTP request thread is the worker. There is no `jobId`, no polling endpoint, no background queue for upload work.

## Rationale

- Uploads happen in **admin/full mode only**, by a small number of trusted operators preparing knowledge bases. There is no concurrency pressure.
- The dominant cost is parsing + image extraction. For typical AOS documents this completes in seconds, well within HTTP timeout budgets.
- Async jobs add real complexity: a job table, a worker loop, status transitions, polling endpoints, lifecycle bugs around partially-written rows. None of that complexity buys anything for the actual workload.
- The error contract is much cleaner synchronously: the client gets `400 invalid_upload`, `400 unreadable_document`, `400 empty_content`, `409 duplicate_document`, or `201 Created` — all in one response, all branchable client-side.
- The chat path is independent of upload and is **not** affected by this decision. Chat will use Artemis (Phase 3) for its own queueing needs.

## Consequences

- A pathologically large document holds an HTTP connection for its full parse time. Acceptable for the admin workflow.
- If a future requirement forces async (for example, multi-hundred-MB documents or background re-indexing), the migration path is **a new endpoint**, not a shape mutation of `POST /api/admin/documents`. The existing endpoint stays a stable contract.
- The synchronous response shape is documented as a durable contract in `docs/ARCHITECTURE.md` §7.2.
