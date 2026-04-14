# ADR 0003: Success-only document lifecycle

**Status:** Accepted (Phase 2)
**Date:** 2026-04-14

## Context

`DocumentService.processDocument` may fail at any step: validation, parse, chunk, image extraction, persist. A natural design is to record failed attempts in the `documents` table with a status column (`indexed` / `failed` / etc.) so operators can see what went wrong. Phase 2 deliberately does **not** do this.

## Decision

The `documents` table contains rows only for successfully indexed documents. Failed uploads:
- Do **not** insert a `documents` row.
- Do **not** insert any `chunks` or `images` rows.
- Return an HTTP error (400 / 409) with a stable `error` discriminator.
- Leave no trace in the database.

The `Document` data class has no `status`, `failedAt`, or `errorCode` field. The `UploadResult` sealed class has exactly two variants: `Created(Document)` and `Duplicate(Document)`. There is no `Failed` variant.

## Rationale

- The DB stays a clean projection of the indexed knowledge base. Every row is a real, queryable, retrievable document.
- Phase 3 search loads chunks via `chunkRepository.findAll()`; introducing a `status` column would force every read path to filter on status forever. The cleanest abstraction is "if it's in the table, it's indexed."
- Failed uploads are observable in two places that **already** carry the information: the synchronous HTTP response, and structured logs at INFO/WARN. Operators retry by re-uploading, not by inspecting a half-failed row.
- Phase 2 has no UI for "documents that failed to upload." Adding a failed-state column to satisfy a UI that does not exist is speculative.

## Consequences

- Operators have no DB-side history of which uploads failed. This is intentional — logs are the source of truth for failures.
- If a future phase adds a "recent activity" view or a retry queue, that work needs its own table (`upload_attempts` or similar) — not a new column on `documents`. This keeps the indexed-document table single-purpose.
- See ADR 0004 for the related decision about orphan artifact cleanup.
