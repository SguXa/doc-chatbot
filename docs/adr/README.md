# Architecture Decision Records

Short, durable records of decisions that shape the codebase. ADRs capture **why** — the rationale and tradeoffs behind a choice — so future readers can revisit a decision with full context instead of guessing from the code.

ADRs are not implementation plans. They do not list tasks or files. For Phase-level execution detail see `docs/plans/`. For stable behavioral and API contracts see `docs/ARCHITECTURE.md`.

## Index

- [0001 — Synchronous document upload instead of async jobs](0001-synchronous-document-upload.md)
- [0002 — UNIQUE index on documents.file_hash without NOT NULL hardening](0002-file-hash-unique-without-not-null.md)
- [0003 — Success-only document lifecycle](0003-success-only-document-lifecycle.md)
- [0004 — No orphan final-artifact reconciliation on the hot path](0004-no-orphan-final-artifact-reconciliation.md)
- [0005 — Authentication deferred out of Phase 2](0005-auth-deferred-out-of-phase-2.md)
