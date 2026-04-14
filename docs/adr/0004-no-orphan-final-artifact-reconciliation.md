# ADR 0004: No orphan final-artifact reconciliation on the hot path

**Status:** Accepted (Phase 2)
**Date:** 2026-04-14

## Context

`DocumentService` writes two kinds of files to disk: source documents under `documentsPath` and extracted images under `imagesPath/{documentId}/`. Disk writes happen before the final DB commit. If the JVM hard-crashes (kill -9, OOM, power loss) between "files written" and "DB committed", final-named files exist on disk with no corresponding `documents` row pointing at them. These are *orphan final artifacts*.

A reconciliation strategy could scan disk on startup, compare against the `documents` table, and delete orphans. Phase 2 deliberately does **not** do this.

## Decision

Phase 2's startup cleanup (`cleanupOrphanTempFiles`) scans for and deletes **temp files only** (`*.tmp.*` pattern), not orphan final artifacts. The scan:
- Looks at the root of `documentsPath` for source temps.
- Looks one level deep in `imagesPath` (per-document subdirectories) for image temps.
- Does **not** recurse further.
- Does **not** consult the `documents` table.
- Does **not** touch any file without a `.tmp.` infix.

Hard-crash-induced orphan final artifacts are an accepted limitation.

## Rationale

- The atomic temp+move pattern (write to `name.tmp.{UUID}` then `Files.move(... ATOMIC_MOVE)`) means temps are unambiguous: they are always failed writes, never in-progress legitimate state. Cleaning them is safe and local.
- Detecting orphan **final** artifacts requires reading the entire `documents` table, walking the entire disk tree, and reasoning about which side wins. That is a stateful reconciliation tool, not a startup hook.
- The window for orphans is narrow. The normal failure path (parser exception, DB constraint violation, IOException) goes through `DocumentService`'s rollback+compensation path, which deletes its own files. Only **hard crashes between disk write and DB commit** leave orphans, and those are rare in admin/full mode operation.
- Operators can recover orphan disk space manually; the alternative (a reconciliation tool that might delete the wrong file under a logic bug) is more dangerous than leaving stale bytes on disk.

## Consequences

- Disk usage on a server that has experienced repeated hard crashes during upload may slowly drift upward. This is acceptable and operators can clean it up out-of-band.
- If a future phase adds a reconciliation tool, it should be a **separate command** (e.g., `./gradlew reconcileArtifacts`) that operators run intentionally — not a startup hook that runs on every boot.
- Phase 2 task verification (see Task 15) explicitly asserts that no code path attempts to detect or heal orphan final artifacts. This is checked, not assumed.
