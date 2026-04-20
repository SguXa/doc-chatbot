# ADR 0002: UNIQUE index on documents.file_hash without NOT NULL hardening

**Status:** Accepted (Phase 2)
**Date:** 2026-04-14

## Context

Phase 2 deduplicates document uploads by SHA-256 of the file bytes. `DocumentService` always populates `file_hash` before any DB write, but the V001 schema declared `documents.file_hash TEXT` (nullable). Phase 2 needs DB-level race protection so two concurrent identical uploads cannot both pass `findByHash` and both insert.

The minimum change is a `UNIQUE INDEX` on `documents.file_hash`. The fuller change would also add `NOT NULL` to the column.

## Decision

V003 adds a UNIQUE index only:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_file_hash_unique
    ON documents(file_hash);
```

`file_hash` stays nullable at the schema level. NOT NULL hardening is **deferred**.

## Rationale

- The functional invariant — "every document row has a non-null file_hash" — is enforced at the **service layer**. `DocumentService` is the only write path in Phase 2 and computes the hash unconditionally. The DB-level `NOT NULL` would be defense in depth, not a primary guarantee.
- SQLite does not support `ALTER COLUMN`. Adding `NOT NULL` requires a full table rebuild. `documents` is the target of `chunks.document_id` and `images.document_id` FKs, so the rebuild requires disabling FK enforcement, running `foreign_key_check` after, extending the migration runner to handle multi-statement migrations with PRAGMA outside transactions, and adding a much larger FK/CASCADE test matrix.
- That is a large amount of migration machinery for a guarantee that already holds at the service layer under Phase 2's single write path.
- SQLite's standard semantics permit multiple NULLs in a UNIQUE index. Since the service never produces NULL `file_hash` rows, this permissive-NULL behavior is invisible in normal operation.

## Consequences

- The race-condition path works: two concurrent `processDocument` calls with identical bytes, both passing `findByHash` returning null, will see exactly one of them hit the UNIQUE violation at insert and take the race-dedup branch.
- A future phase **may** harden this to schema-level `NOT NULL` when either:
  1. A second write path emerges that bypasses `DocumentService`, or
  2. A reconciliation tool needs a schema-level guarantee.
- Such a change requires explicit design review — not a quiet migration in the middle of unrelated work.
