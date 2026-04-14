# Phase 2: Document Processing

## Overview

Implement the document parsing pipeline that turns uploaded Word and PDF documents into chunks and images persisted in SQLite. Adds Apache POI / PDFBox dependencies, two new migrations, data models, repositories, parsers (Word, PDF, AOS-specific post-processing), the chunking service, image extraction, the orchestrating `DocumentService`, and synchronous admin upload routes. No embeddings (Phase 3), no auth (Phase 4), no chat (Phase 3).

## Context

- Files involved: New code under `backend/src/main/kotlin/com/aos/chatbot/{parsers,services,models,db/repositories,routes}/`, two new migrations under `resources/db/migration/`, AdminRoutes wired into `Application.kt`, AppConfig extended with `documentsPath`/`imagesPath`.
- Related patterns: Manual constructor DI, coroutines, named exports, JUnit 5 + MockK, kotlinx.serialization. All conventions inherit from Phase 1.
- Source of truth: `docs/ARCHITECTURE.md` §6 (schema), §7.2 (admin endpoints), §8 (parsing strategy + image linkage contract + pageNumber policy), §12.3 (path defaults).
- Architectural decisions: see `docs/adr/` — ADR 0001 (sync upload), 0002 (UNIQUE without NOT NULL), 0003 (success-only lifecycle), 0004 (no orphan reconciliation), 0005 (auth deferred).

## Design Decisions

- **Synchronous upload only.** `POST /api/admin/documents` blocks for the full parse/persist cycle and returns the final outcome. No `jobId`, no polling. ADR 0001.
- **Embeddings deferred.** Phase 2 persists chunks with `embedding = NULL`. V002 relaxes the V001 `NOT NULL` constraint so chunks can be inserted ahead of Phase 3.
- **`documents.file_hash` is unique at the DB level but stays nullable.** V003 adds a UNIQUE index for race-defense; NOT NULL hardening is deferred to a future phase. ADR 0002.
- **Success-only document lifecycle.** Failed uploads insert no rows; the only `UploadResult` variants are `Created` and `Duplicate`. ADR 0003.
- **Startup cleanup is temp-only.** `cleanupOrphanTempFiles` deletes `*.tmp.*` files from `documentsPath` (root) and `imagesPath/{documentId}/`. It does NOT scan or reconcile orphan final artifacts. ADR 0004.
- **Admin routes are unprotected and that is intentional.** Auth is Phase 4. Application emits a startup `WARN` in `MODE=full`/`MODE=admin`. ADR 0005.
- **Repositories are operation-scoped, not singletons.** A repository instance is built per unit of work, takes a `Connection` in its constructor, and is discarded when the connection closes. The caller owns the connection lifecycle.
- **JSON serialization for `imageRefs` lives only in `ChunkRepository`.** The domain model carries `List<String>` everywhere else. Empty list maps to SQL `NULL`, not `"[]"`.

## Development Approach

- **Testing approach**: Regular (code first, then tests).
- Complete each task fully before moving to the next.
- Each task produces a compilable/runnable increment.
- **CRITICAL: each functional increment must include appropriate tests.**
- **CRITICAL: all tests must pass before starting next task.**

## Validation Commands

- `cd backend && ./gradlew test`
- `cd backend && ./gradlew build`

## Implementation Steps

### Task 1: Add Apache POI and PDFBox dependencies

**Files:**
- Modify: `backend/build.gradle.kts`

- [x] Add `org.apache.poi:poi-ooxml:5.2.5` to dependencies
- [x] Add `org.apache.pdfbox:pdfbox:3.0.1` to dependencies
- [x] Verify: `cd backend && ./gradlew build` succeeds

### Task 2: V002 migration — make chunks.embedding nullable

**Files:**
- Create: `backend/src/main/resources/db/migration/V002__chunks_embedding_nullable.sql`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt`

- [x] Create V002 using SQLite table-rebuild pattern: `CREATE TABLE chunks_new` mirroring V001 columns/PK/FK except `embedding BLOB` is nullable; `INSERT INTO chunks_new SELECT * FROM chunks`; `DROP TABLE chunks`; `ALTER TABLE chunks_new RENAME TO chunks`
- [x] Recreate all three indexes from V001 (`idx_chunks_document`, `idx_chunks_content_type`, `idx_chunks_section`)
- [x] Preserve `FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE`
- [x] Do NOT modify V001 — migrations are immutable
- [x] Add MigrationsTest case: insert chunk with `embedding = NULL` succeeds, insert with non-null blob also succeeds
- [x] Add MigrationsTest case: FK violation still raised when inserting chunk with non-existent `document_id`
- [x] Add MigrationsTest case: ON DELETE CASCADE removes chunks (including those with NULL embedding) when parent document deleted
- [x] Add MigrationsTest case: `schema_version` records version 2
- [x] Verify: `cd backend && ./gradlew test`

### Task 3: V003 migration — UNIQUE index on documents.file_hash

**Files:**
- Create: `backend/src/main/resources/db/migration/V003__documents_file_hash_unique.sql`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt`

See [ADR 0002](../adr/0002-file-hash-unique-without-not-null.md) for why we add UNIQUE without NOT NULL.

- [x] Create V003 with `CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_file_hash_unique ON documents(file_hash)`
- [x] Do NOT modify V001 or V002
- [x] Add MigrationsTest case: index `idx_documents_file_hash_unique` exists in `sqlite_master` after V003
- [x] Add MigrationsTest case: second insert with duplicate `file_hash` raises `SQLException` containing `UNIQUE`
- [x] Add MigrationsTest case: rows with different `file_hash` values coexist
- [x] Add MigrationsTest case: `schema_version` records version 3
- [x] Verify: `cd backend && ./gradlew test`

### Task 4: Add documentsPath and imagesPath to AppConfig

**Files:**
- Modify: `backend/src/main/resources/application.conf`
- Modify: `backend/src/main/kotlin/com/aos/chatbot/config/AppConfig.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/config/AppConfigTest.kt`
- Modify: `.env.example`
- Modify: `README.md`

Both paths derive from `app.data.path` via HOCON substitution, with optional independent overrides via `DOCUMENTS_PATH` and `IMAGES_PATH`. See ARCHITECTURE.md §12.3.

- [x] Add `app.paths.documents` and `app.paths.images` blocks to `application.conf` with HOCON defaults derived from `app.data.path` and env-var overrides
- [x] Add `documentsPath: String` and `imagesPath: String` fields to `AppConfig` data class
- [x] Read both paths in `AppConfig.from(environment)` via `config.property(...).getString()`
- [x] Add AppConfigTest case: only `DATA_PATH` set → both paths derive correctly
- [x] Add AppConfigTest case: `DOCUMENTS_PATH` overrides documents independently of images
- [x] Add AppConfigTest case: `IMAGES_PATH` overrides images independently of documents
- [x] Add AppConfigTest case: both overrides honored together
- [x] Add AppConfigTest case: defaults only → `./data/documents` and `./data/images`
- [x] Update `.env.example` comment block to explain default derivation and when overrides are needed
- [x] Add a `## Configuration` section to README.md listing the four path env vars
- [x] Verify: `cd backend && ./gradlew test`

### Task 5: Create data models for document processing

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/Document.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/Chunk.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/ExtractedImage.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/models/ParsedContent.kt`

The image linkage contract and pageNumber policy these models implement are in ARCHITECTURE.md §8.4 and §8.5 — link to them from KDoc rather than duplicating the prose here.

- [x] Create `Document` data class with id, filename, fileType, fileSize, fileHash (non-null at the model layer), chunkCount, imageCount, indexedAt, createdAt
- [x] Create `Chunk` data class with id, documentId, content, contentType, pageNumber, sectionId, heading, embedding (nullable ByteArray), imageRefs (`List<String>`), createdAt
- [x] Create `ExtractedImage` data class with id, documentId, filename, path, pageNumber, caption, description, embedding, createdAt
- [x] Create `ParsedContent` with `textBlocks: List<TextBlock>`, `images: List<ImageData>`, `metadata: Map<String,String>`
- [x] Create `TextBlock` with content, type, pageNumber, sectionId, heading, imageRefs
- [x] Create `ImageData` with filename, data, pageNumber, caption
- [x] Annotate models needed by API responses with `@Serializable` (Document, Chunk)
- [x] In KDoc on `ParsedContent`, link to ARCHITECTURE.md §8.4 (image linkage contract)
- [x] In KDoc on `TextBlock` and `ImageData`, link to ARCHITECTURE.md §8.5 (pageNumber policy)

### Task 6: Create DocumentRepository, ChunkRepository, ImageRepository

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/repositories/DocumentRepository.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/repositories/ChunkRepository.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/repositories/ImageRepository.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/db/repositories/DocumentRepositoryTest.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/db/repositories/ChunkRepositoryTest.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/db/repositories/ImageRepositoryTest.kt`

All repositories take `java.sql.Connection` in the constructor, are operation-scoped, and use `PreparedStatement` for every query.

- [x] DocumentRepository: `insert`, `findById`, `findAll`, `findByHash`, `updateChunkCount`, `updateIndexedAt`, `delete`
- [x] DocumentRepository.findAll emits `ORDER BY created_at DESC, id DESC` (newest first, deterministic tie-break)
- [x] ChunkRepository: `insertBatch`, `findByDocumentId`, `findAll`, `deleteByDocumentId`, `count`
- [x] ChunkRepository.findByDocumentId and findAll emit `ORDER BY id ASC` (preserves parser traversal order)
- [x] ChunkRepository serializes `imageRefs: List<String>` to a JSON array string on insert; empty list → SQL `NULL`
- [x] ChunkRepository deserializes `image_refs` column on read; SQL `NULL` → `emptyList()`
- [x] ImageRepository: `insert`, `findByDocumentId` (`ORDER BY id ASC`), `deleteByDocumentId`
- [x] KDoc on each repository class: instances are operation-scoped, must not outlive the injected Connection
- [x] KDoc on each list-returning method: document its `ORDER BY` clause at the call site
- [x] Tests use in-memory SQLite (`:memory:`) with a single connection per test (same pattern as `MigrationsTest`)
- [x] Tests cover insert/find/delete round-trips, ordering (insert in non-sorted order, assert sorted result), `imageRefs` JSON round-trip including empty-list-as-NULL, and FK cascade behavior
- [x] Verify: `cd backend && ./gradlew test`

### Task 7: Create DocumentParser interface and ParserFactory

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/DocumentParser.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/ParserFactory.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/ParserFactoryTest.kt`

- [x] Define `DocumentParser` interface: `parse(file: File): ParsedContent`, `supportedExtensions(): List<String>`
- [x] Implement `ParserFactory` mapping `.docx` → WordParser, `.pdf` → PdfParser
- [x] Throw `IllegalArgumentException` for unsupported extensions
- [x] Test: correct parser selected for each supported extension; unknown extension throws
- [x] Verify: `cd backend && ./gradlew test`

### Task 8: Implement WordParser (Apache POI)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/WordParser.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/UnreadableDocumentException.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/WordParserTest.kt`

Implements the image linkage contract (ARCHITECTURE.md §8.4) and pageNumber policy (§8.5).

- [x] Extract paragraphs, headings (style-based), tables (rendered as text with `|` separators), and images via `XWPFPictureData`
- [x] Detect section numbers (e.g., `3.2.1 Component Setup` → `sectionId = "3.2.1"`)
- [x] Concatenate consecutive body paragraphs into a single TextBlock; flush on new heading
- [x] Generate image filenames as `img_{NNN}.{ext}`, sequential, 3-digit padding, in document traversal order
- [x] Maintain `currentTextBlock` and `pendingImageRefs` state during traversal so images attach to the next text TextBlock
- [x] Trailing images create a synthetic empty TextBlock to preserve the linkage contract
- [x] Set `pageNumber = null` on every emitted TextBlock and ImageData (Word does not expose reliable page numbers)
- [x] Define `UnreadableDocumentException(reason: UnreadableReason, fileType: String, cause: Throwable?)` with reason variants for: corrupted, password-protected, unsupported-version, ole2-instead-of-ooxml, etc.
- [x] Wrap parse body in try/catch translating POI/IO exceptions into `UnreadableDocumentException`; never let raw POI exceptions escape
- [x] Log unreadable cases at INFO with sanitized filename and reason code (not ERROR)
- [x] Tests use programmatically created `.docx` fixtures; cover heading detection, table rendering, image attachment scenarios (text→image→text, leading image, trailing image, table-with-inline-image, multiple images per block)
- [x] Tests assert referential integrity invariant: every image filename appears on exactly one TextBlock; every TextBlock ref points to a real ImageData
- [x] Tests for corrupted input: plain-ASCII file, truncated docx, 0-byte file, password-protected docx — every path raises `UnreadableDocumentException`, never raw POI types
- [x] `@AfterEach` invariant check: every TextBlock and ImageData in test fixtures has `pageNumber == null`
- [x] Verify: `cd backend && ./gradlew test`

### Task 9: Implement PdfParser (Apache PDFBox)

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/PdfParser.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/PdfParserTest.kt`

Implements the image linkage contract (ARCHITECTURE.md §8.4) and pageNumber policy (§8.5).

- [x] Page-by-page text extraction via `PDFTextStripper`
- [x] Heuristic heading detection (ALL CAPS short lines followed by body text)
- [x] Image extraction from `PDPage` resources (`PDImageXObject`)
- [x] Filename convention `img_p{PAGE}_{NNN}.{ext}`, sequence resets per page
- [x] Set `pageNumber = N` on every TextBlock and ImageData emitted from page N (1-indexed)
- [x] Image-only pages create a synthetic empty TextBlock; text-only pages have `imageRefs = emptyList()`
- [x] Explicit `doc.isEncrypted` check at load time → raise `UnreadableDocumentException(EncryptedDocument, "pdf")` before extraction
- [x] Wrap parse body in try/catch translating PDFBox/IO exceptions into `UnreadableDocumentException`
- [x] Log unreadable cases at INFO with sanitized filename
- [x] Tests use programmatically created PDF fixtures; cover single-page text+image, two-page (sequence resets), image-only page, multi-image page, text-only page
- [x] Tests assert referential integrity invariant and filename convention
- [x] Tests for corrupted input: plain-text-as-PDF, truncated PDF, corrupted xref, 0-byte file, password-protected via both `InvalidPasswordException` and `doc.isEncrypted` paths
- [x] `@AfterEach` invariant check: every TextBlock and ImageData has `pageNumber != null`
- [x] Verify: `cd backend && ./gradlew test`

### Task 10: Implement ChunkingService

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/ChunkingService.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/ChunkingServiceTest.kt`

- [x] Split TextBlocks into chunks with `maxChunkTokens=500`, `overlapTokens=50`, `minChunkTokens=100`
- [x] Preserve sentence boundaries on `.`, `!`, `?` followed by whitespace; never split mid-sentence
- [x] Do NOT split special types `table` and `troubleshoot` even if they exceed max size
- [x] Apply overlap between consecutive chunks
- [x] Merge trailing chunks below `minChunkTokens` into the previous chunk
- [x] Replicate parent's full `imageRefs` onto every output chunk (per ARCHITECTURE.md §8.4)
- [x] On min-chunk merge, union imageRefs from both chunks (preserve order, dedupe)
- [x] Synthetic empty blocks with non-empty imageRefs MUST survive (do not drop)
- [x] Preserve `pageNumber` unchanged from parent to every output chunk
- [x] Tests: sentence-boundary splitting, short-text passthrough, overlap correctness, special-type non-splitting, min-chunk merging
- [x] Tests for imageRefs: long block splits into 3 chunks all carrying same refs; short block passthrough; merge with overlapping refs; synthetic empty block survives
- [x] Test: end-to-end imageRefs union equality (input set == output set)
- [x] Test: pageNumber preserved on every output chunk for both `pageNumber=5` and `pageNumber=null` inputs
- [x] Verify: `cd backend && ./gradlew test`

### Task 11: Implement AOS-specific parsers

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/aos/AosParser.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/aos/TroubleshootParser.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/aos/ComponentParser.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/aos/AosParserTest.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/aos/TroubleshootParserTest.kt`

- [x] `AosParser` orchestrates post-processing of `ParsedContent` from WordParser/PdfParser
- [x] `TroubleshootParser` detects MA-XX codes (`MA-\d{2,3}` at line/heading start)
- [x] Extract structured troubleshoot blocks: code, symptom (after `Symptom:`), cause (after `Cause:` or `Ursache:`), solution (after `Solution:` or `Lösung:`)
- [x] Output TextBlock with `type="troubleshoot"`, `sectionId="MA-XX"`; handle German labels
- [x] `ComponentParser` enriches table blocks with component metadata, preserves structure
- [x] Preserve `imageRefs` through every transformation (no drop, no rewrite, no truncate); allowed transforms = type change, sectionId change, content rewrite, merge (union refs), split (replicate refs)
- [x] Pass `ParsedContent.images` through unchanged
- [x] Preserve `pageNumber` unchanged; on merge the first input's value wins
- [x] Tests: typical AOS document fixtures, troubleshoot detection, component table enrichment
- [x] Tests for imageRefs passthrough: type conversion, enrichment, merge (union), passthrough
- [x] Test: end-to-end `ParsedContent.images` identity
- [x] Verify: `cd backend && ./gradlew test`

### Task 12: Implement ImageExtractor

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/parsers/ImageExtractor.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/parsers/ImageExtractorTest.kt`

Operation-scoped (NOT singleton). Saves images to disk and writes DB rows. The atomic temp+move pattern is the contract — see ARCHITECTURE.md §8.4 for filename stability and ADR 0004 for the cleanup boundary.

- [x] Constructor takes `imagesBasePath: String`, `imageRepository: ImageRepository`
- [x] Create per-document directory `{imagesBasePath}/{documentId}/` if missing
- [x] Per image: write to `{final}.tmp.{UUID}` then `Files.move(temp, final, StandardCopyOption.ATOMIC_MOVE)`
- [x] Use `ImageData.filename` verbatim for final path and DB row (no transforms)
- [x] Never use `REPLACE_EXISTING`; treat `FileAlreadyExistsException` as contract violation (log ERROR, delete temp, rethrow)
- [x] On `AtomicMoveNotSupportedException`, delete temp, throw `IllegalStateException` with filesystem-config message
- [x] On IOException during temp write, delete partial temp via `runCatching { Files.deleteIfExists }`, log ERROR, rethrow
- [x] Per-image order: file write (temp → atomic move) FIRST, DB row insert SECOND
- [x] IO errors must throw, NOT swallow, so DocumentService rollback can clean up
- [x] Test happy path: 3 images → 3 final files, zero `*.tmp.*` files remain, 3 DB rows
- [x] Test temp-write failure on 2nd of 3 images: first persisted, second has neither final nor temp, third not attempted
- [x] Test `FileAlreadyExistsException` at atomic move: pre-existing file unchanged, no DB row, exception propagates
- [x] Assert verbatim filenames on disk and in DB row (no `tmp` suffix leaks)
- [x] Assert temp path matches regex `.*\.tmp\.[0-9a-f-]{36}$`
- [x] Verify: `cd backend && ./gradlew test`

### Task 13: Create DocumentService to orchestrate the parsing pipeline

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/DocumentService.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/UploadResult.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/InvalidUploadException.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/EmptyDocumentException.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/DocumentServiceTest.kt`

The orchestration follows ADR 0001 (sync), ADR 0002 (race-aware dedup), ADR 0003 (success-only lifecycle), ADR 0004 (rollback deletes its own files; orphan finals are accepted).

- [x] Constructor takes `database: Database`, `parserFactory: ParserFactory`, `aosParser: AosParser`, `chunkingService: ChunkingService`, `documentsPath: String`, `imagesPath: String`
- [x] DocumentService is a singleton; per-call repositories and connections are constructed inside `processDocument`
- [x] `UploadResult` sealed class: exactly two variants — `Created(Document)` and `Duplicate(Document)`. No `Failed` variant
- [x] `processDocument(originalFilename: String, bytes: ByteArray): UploadResult` — entry point; runs on `Dispatchers.IO`
- [x] **Validation step**: extension is `docx` or `pdf`; bytes non-empty; filename non-empty after normalization; raise `InvalidUploadException(reason)` with stable reason discriminator (`unsupported_extension`, `empty_file`, `missing_filename`, etc.)
- [x] **Hash step**: compute SHA-256 of bytes before any DB write; populate `Document.fileHash` from this
- [x] **Dedup pre-check**: open connection, query `documentRepository.findByHash(hash)`; if hit → return `UploadResult.Duplicate(existing)`
- [x] **Source file write**: write to `{documentsPath}/{hash}.{ext}` via temp+atomic-move (`{final}.tmp.{UUID}` → `Files.move ATOMIC_MOVE`)
- [x] **Parse step**: `parserFactory.getParser(filename).parse(file)`; let `UnreadableDocumentException` propagate
- [x] **AOS post-processing**: `aosParser.process(parsed)`
- [x] **Chunking step**: `chunkingService.chunk(processed.textBlocks)`
- [x] **Empty-content check**: if zero chunks AND zero images → raise `EmptyDocumentException` (HTTP 400 `empty_content`, no row inserted)
- [x] **Image linkage validation**: assert every chunk's `imageRefs` references a real `ImageData`; assert every `ImageData` referenced by at least one chunk; failure → throw to trigger rollback
- [x] **Persist phase** (narrow transaction):
  - [x] Begin transaction on a fresh connection
  - [x] Insert document row → captures `documentId`
  - [x] Build `ImageExtractor(imagesPath, ImageRepository(conn))` and call `saveImages(documentId, parsed.images)` BEFORE chunk insert
  - [x] Build `ChunkRepository(conn)` and `insertBatch(chunks.map { it.copy(documentId = documentId) })`
  - [x] `updateChunkCount(documentId, chunkCount, imageCount)` and `updateIndexedAt(documentId)`
  - [x] Commit transaction; close connection
- [x] **Race-condition handling**: if document insert raises `SQLException` containing `UNIQUE constraint failed: idx_documents_file_hash_unique`, rollback, look up the now-existing row by hash, and return `UploadResult.Duplicate(existing)`
- [x] **Rollback / compensation on any other failure**: rollback DB transaction; delete the source file written above; delete any image files already persisted under `{imagesPath}/{documentId}/`; rethrow original exception
- [x] **No `Document` row left behind on failure** (success-only invariant — ADR 0003)
- [x] Tests use a file-backed SQLite DB (NOT in-memory), so per-call connection lifecycle is exercised realistically
- [x] Test happy paths: docx upload → Created; pdf upload → Created; assert chunks/images/document row counts and indexedAt populated
- [x] Test pre-check duplicate: upload same bytes twice → second call returns `Duplicate`, no second insert
- [x] Test race duplicate: simulate concurrent uniqueness violation by pre-inserting a row with the same hash between hash compute and insert; assert second call returns `Duplicate`
- [x] Test InvalidUploadException variants for each reason discriminator (unsupported extension, empty file, missing filename)
- [x] Test UnreadableDocumentException propagation: pass corrupted docx → exception escapes; assert no document row, no chunks, no images, no source file, no image directory
- [x] Test EmptyDocumentException: pass valid-but-empty document → exception escapes, no row inserted
- [x] Test rollback: induce failure during chunk insert (e.g., mock ChunkRepository to throw); assert document row absent, source file absent, image directory absent
- [x] Test image linkage validation failure: feed handcrafted ParsedContent with broken refs → throws, no row inserted
- [x] Verify: `cd backend && ./gradlew test`

### Task 14: Wire DocumentService into Application.kt and add admin routes

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/Application.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/AdminRoutes.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/dto/AdminResponses.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/routes/AdminRoutesTest.kt`

Routes are unprotected — see [ADR 0005](../adr/0005-auth-deferred-out-of-phase-2.md). Do NOT add any auth code, placeholder or otherwise.

- [ ] Wire stateless dependencies in `Application.module()`: `ParserFactory`, `AosParser`, `ChunkingService`, `DocumentService`
- [ ] DocumentService receives the `Database` factory, NOT pre-built repositories or a long-lived `Connection`
- [ ] Implement `cleanupOrphanTempFiles(documentsPath: String, imagesPath: String): Int`: scan `documentsPath` root for `*.tmp.*`, scan `imagesPath` one level deep (per-document subdirs), delete matches, return combined count. Do NOT recurse deeper. Do NOT touch any file without a `.tmp.` infix
- [ ] Call `cleanupOrphanTempFiles` AFTER migrations, BEFORE route registration, conditional on `mode in (FULL, ADMIN)`
- [ ] Log at INFO: `"Startup temp-file cleanup: N orphaned temp files removed (sources: A, images: B)"`
- [ ] Register `AdminRoutes` only in `MODE=full` and `MODE=admin`; do NOT register in `MODE=client`
- [ ] `POST /api/admin/documents`: accept multipart file upload, call `documentService.processDocument(...)`
  - [ ] On `UploadResult.Created` → 201 with full Document JSON
  - [ ] On `UploadResult.Duplicate` → 409 with `DuplicateDocumentResponse { error="duplicate_document", message, existing }`
  - [ ] Catch `InvalidUploadException` → 400 `InvalidUploadResponse { error="invalid_upload", reason, message }`
  - [ ] Catch `UnreadableDocumentException` → 400 `UnreadableDocumentResponse { error="unreadable_document", reason, message }` — do NOT leak POI/PDFBox internals
  - [ ] Catch `EmptyDocumentException` → 400 `EmptyDocumentResponse { error="empty_content", reason, message }`
- [ ] `GET /api/admin/documents`: pass-through `documentRepository.findAll()` order, return 200 with `{ documents: [...], total: N }`. The route MUST NOT re-sort
- [ ] `DELETE /api/admin/documents/{id}`: 204 on success, 404 if not found
- [ ] DTOs are distinct classes (not unions): `DuplicateDocumentResponse`, `InvalidUploadResponse`, `UnreadableDocumentResponse`, `EmptyDocumentResponse`
- [ ] `error` field is a stable discriminator (`"invalid_upload"`, `"unreadable_document"`, `"empty_content"`, `"duplicate_document"`) — do not refactor it into an enum yet
- [ ] Pass raw client filename through to the service (sanitization is the service's job, not the route's)
- [ ] Emit a startup `WARN` log line in `MODE=full`/`MODE=admin` once at startup: `"Phase 2 admin routes are unprotected — auth is deferred to Phase 4. Restrict this deployment to internal networks."` Do NOT emit in `MODE=client`
- [ ] Tests use mocked `DocumentService`
- [ ] Test 201 Created path: assert response body shape and headers
- [ ] Test 409 Duplicate path: assert `error="duplicate_document"` and `existing` block populated
- [ ] Test 400 paths: one test per exception type, asserting the correct discriminator and response body
- [ ] Test that response bodies do NOT contain POI/PDFBox class names or stack traces
- [ ] Test 404 on DELETE missing
- [ ] Test GET preserves DocumentService order (do not re-sort in the route)
- [ ] Test routes are NOT registered in `MODE=client`
- [ ] Verify: `cd backend && ./gradlew test`

### Task 15: Final verification and cleanup

- [ ] All tests pass: `cd backend && ./gradlew test`
- [ ] Build succeeds: `cd backend && ./gradlew build`
- [ ] No compiler warnings, no wildcard imports
- [ ] Confirm `documents` schema has NO `status`, `failed_at`, `error_code` columns (PRAGMA `table_info(documents)`)
- [ ] Confirm `Document` data class has NO `status`, `failedAt`, `errorCode` fields
- [ ] Confirm `UploadResult` has exactly two variants: `Created` and `Duplicate` (no `Failed`)
- [ ] Confirm every failure-mode test asserts "no document row inserted" (ADR 0003 invariant)
- [ ] Confirm startup orphan scan deletes only `*.tmp.*` files; no code path attempts to detect or heal orphan **final** artifacts (ADR 0004)
- [ ] Grep backend source tree: no occurrences of `Authentication`, `BearerAuth`, `JWT`, `authenticate {`, `principal`, `Authorization`, `AUTH_DISABLED`, `DEV_BYPASS_AUTH`, `SKIP_AUTH` (ADR 0005 — auth is Phase 4 only)
- [ ] Confirm startup WARN about unprotected admin routes fires in `MODE=full`/`MODE=admin`, does NOT fire in `MODE=client`
- [ ] Move this plan to `docs/plans/completed/` once all checkboxes are green
