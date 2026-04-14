# Plan: Phase 2 — Document Processing

Phase 2 implements the document parsing pipeline: WordParser (Apache POI), PdfParser (PDFBox), AOS-specific parsers, ChunkingService, image extraction, and repositories for persisting results to SQLite.

All code follows the existing patterns from Phase 1:
- Kotlin package: `com.aos.chatbot`
- Manual constructor DI (no framework)
- Coroutines for async operations
- Named exports, no wildcards
- Tests next to source: `*.test.kt` pattern in `src/test/kotlin/com/aos/chatbot/`
- JUnit 5 + MockK for testing
- kotlinx.serialization for JSON

Reference `docs/ARCHITECTURE.md` sections 5, 6, 8 for file structure, DB schema, and parsing strategy.

## Validation Commands

- `cd backend && ./gradlew test`
- `cd backend && ./gradlew build`

---

### Task 1: Add Apache POI and PDFBox dependencies

Add document processing dependencies to `backend/build.gradle.kts`:
- Apache POI 5.2.5 (`poi-ooxml`) for Word (.docx) parsing
- Apache PDFBox 3.0.1 for PDF text extraction
- Apache Commons IO (if not already transitive) for file utilities

Do NOT change any existing dependencies. Only add new lines in the `dependencies` block.
Verify the project compiles with `./gradlew build`.

- [ ] Add Apache POI dependency (org.apache.poi:poi-ooxml:5.2.5)
- [ ] Add Apache PDFBox dependency (org.apache.pdfbox:pdfbox:3.0.1)
- [ ] Verify project compiles successfully

---

### Task 2: Add V002 migration to make chunks.embedding nullable

The Phase 1 schema (`V001__initial_schema.sql`) defines `chunks.embedding BLOB NOT NULL`, but Phase 2 persists chunks before embeddings are generated (embeddings are deferred to Phase 3). This migration relaxes that constraint.

SQLite does not support `ALTER COLUMN`, so use the standard table-rebuild pattern. Create `backend/src/main/resources/db/migration/V002__chunks_embedding_nullable.sql`:

1. `CREATE TABLE chunks_new` with the exact same columns, PRIMARY KEY, and FOREIGN KEY as `chunks` in V001 — except `embedding BLOB` is nullable (drop the `NOT NULL`). The `FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE` clause MUST be preserved.
2. `INSERT INTO chunks_new SELECT * FROM chunks` — copies any existing rows (empty in practice, but the statement must be present so the migration works on databases that already have data).
3. `DROP TABLE chunks`
4. `ALTER TABLE chunks_new RENAME TO chunks`
5. Recreate all three indexes from V001: `idx_chunks_document`, `idx_chunks_content_type`, `idx_chunks_section`.

Do NOT modify `V001__initial_schema.sql` — migrations are immutable.

Extend `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt` with new tests that run `Migrations(conn).apply()` (which applies V001 + V002) and assert:

- **Embedding is now nullable:** insert a document, then insert a chunk with `embedding = NULL` — must succeed without error. Also insert a chunk with a non-null `embedding` blob — must also succeed.
- **All three chunks indexes still exist** after V002 runs (`idx_chunks_document`, `idx_chunks_content_type`, `idx_chunks_section`). The existing `all expected indexes exist after migration` test already covers part of this — verify it still passes, and explicitly query `sqlite_master WHERE type='index' AND tbl_name='chunks'` to confirm the indexes point to the rebuilt table.
- **FK enforcement still works on the rebuilt table:** inserting a chunk with a non-existent `document_id` (e.g., 999) must fail with a `FOREIGN KEY` constraint error. The existing `foreign keys are enforced - chunks reference documents` test at `MigrationsTest.kt:89` must still pass after V002; update its `embedding` literal to `NULL` (or add a new test variant using `NULL`) so it also covers the nullable column.
- **ON DELETE CASCADE still works on the rebuilt table:** insert a document, insert two chunks referencing it (one with `embedding = NULL`, one with a non-null blob), `DELETE FROM documents WHERE id = ?`, assert both chunks are removed. The existing `cascade delete removes chunks when document is deleted` test at `MigrationsTest.kt:132` must still pass — extend it or add a new test that explicitly exercises the post-V002 table.
- **schema_version table records version 2** with the expected name (`chunks_embedding_nullable` or equivalent).
- **Migration is idempotent:** applying `Migrations` twice on the same connection does not error (the existing `migration is idempotent` test must still pass).

- [ ] Create V002__chunks_embedding_nullable.sql with table-rebuild pattern
- [ ] Preserve FOREIGN KEY ON DELETE CASCADE on document_id in chunks_new
- [ ] Recreate all three chunks indexes from V001 (idx_chunks_document, idx_chunks_content_type, idx_chunks_section)
- [ ] Add MigrationsTest case asserting chunks.embedding accepts NULL after V002
- [ ] Add MigrationsTest case asserting FK violation still raised when inserting chunk with non-existent document_id (using NULL embedding)
- [ ] Add MigrationsTest case asserting ON DELETE CASCADE removes chunks (including chunks with NULL embedding) when parent document is deleted
- [ ] Add MigrationsTest case asserting schema_version records version 2 after V002
- [ ] Verify existing MigrationsTest cases (indexes, idempotency, table existence) still pass unchanged
- [ ] Run `./gradlew test` and confirm the full migrations suite is green

---

### Task 3: Add V003 migration for UNIQUE index on documents.file_hash

Phase 2 uses `file_hash` as the sole deduplication key in DocumentService (Task 13) and as the basis of server-side storage naming. Enforcement is layered:

1. **Service layer (primary — functional invariant).** DocumentService computes the SHA-256 hash at pipeline step 2 before any DB write, and always populates `file_hash` on the `Document` row inserted at step 13. The service never produces a row with `NULL` file_hash. This is the contract the rest of Phase 2 relies on.
2. **DB layer (defense in depth — scope of V003).** A UNIQUE index on `documents.file_hash` catches race conditions between `findByHash` and `insert` — two concurrent `processDocument` calls with identical bytes can both pass `findByHash` returning null, then one hits the UNIQUE violation at insert and takes the race-dedup path (see Task 13 "Race condition handling"). Without this defense, both would insert.

**What V003 does NOT do, and why:**
- V003 does **not** add `NOT NULL` at the schema level. Doing so would require a table rebuild (SQLite does not support `ALTER COLUMN`), which in turn requires disabling FK enforcement (`documents` is the target of `chunks.document_id` and `images.document_id` FKs), running `foreign_key_check` after the rebuild, adjusting the Phase 1 Migrations runner to support multi-statement migrations with PRAGMA outside transactions, and a much larger test matrix for FK integrity and CASCADE behavior after the rebuild. That is significant migration machinery for a guarantee that is already enforced at the service layer.
- `file_hash` stays nullable at the DB level (as in V001). The service never produces NULL rows, so the nullability is observably harmless under Phase 2's single write path. A future phase MAY harden this to schema-level `NOT NULL` when either (a) a second write path emerges that bypasses DocumentService, or (b) a reconciliation tool needs a schema-level guarantee — such a change requires explicit design review, not a quiet Phase 2 edit.

Create `backend/src/main/resources/db/migration/V003__documents_file_hash_unique.sql`. The file contains only the SQL statement below (with a comment header for self-documentation):

```sql
-- V003: UNIQUE index on documents.file_hash.
-- Provides DB-level race-condition defense for dedup.
-- NOT NULL hardening is intentionally deferred — see the Phase 2 plan
-- (Task 3) for the deferred-hardening rationale.
CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_file_hash_unique ON documents(file_hash);
```

The text below is plan commentary (in `docs/plans/phase-2-document-processing.md`), NOT content of the `.sql` file.

**Notes:**
- `IF NOT EXISTS` is defensive; the Migrations runner's `schema_version` skip mechanism already handles idempotency.
- No data migration. Phase 1 / V002 do not insert any rows.
- Do NOT modify V001 or V002 — migrations are immutable.
- SQLite UNIQUE indexes permit multiple NULL values (standard SQLite semantics). The service never produces NULL `file_hash` rows, so this permissive-NULL behavior is invisible under normal operation. A Phase 4+ reviewer should not be surprised by it.

Extend `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt` with new cases that run `Migrations(conn).apply()` (applies V001 + V002 + V003) and assert:

- **Unique index exists after V003.** Query `sqlite_master WHERE type='index' AND name='idx_documents_file_hash_unique'` and assert the row is present.
- **UNIQUE enforcement on duplicate hash.** Insert a document with `file_hash = 'abc123'`. Inserting a second row with the same `file_hash = 'abc123'` must fail with `SQLException` whose message contains `UNIQUE` (or extended result code `SQLITE_CONSTRAINT_UNIQUE`).
- **Different hashes coexist.** Inserting rows with `file_hash = 'abc'` and `file_hash = 'def'` both succeed.
- **schema_version records version 3** after V003 with name `documents_file_hash_unique`.
- **Existing V001/V002 tests still pass unchanged** — table existence, FK enforcement on chunks/images, CASCADE delete, `chunks.embedding` nullability, and idempotency. No modification needed; just re-run and confirm green.

- [ ] Create V003__documents_file_hash_unique.sql with `CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_file_hash_unique ON documents(file_hash)`
- [ ] Do not modify V001 or V002
- [ ] Add MigrationsTest case asserting `idx_documents_file_hash_unique` exists in `sqlite_master` after V003
- [ ] Add MigrationsTest case asserting a second insert with duplicate `file_hash` raises `SQLException` with `UNIQUE` in the message
- [ ] Add MigrationsTest case asserting rows with different `file_hash` values coexist
- [ ] Add MigrationsTest case asserting `schema_version` records version 3 after V003 with name `documents_file_hash_unique`
- [ ] Verify all existing V001/V002 MigrationsTest cases still pass unchanged
- [ ] Run `./gradlew test` and confirm the migrations suite is green

---

### Task 4: Add DOCUMENTS_PATH and IMAGES_PATH to configuration

Phase 2 persists uploaded files to a documents directory and extracted images to an images directory, but these paths are currently not declared anywhere in the backend config — Phase 1 only introduced `dataPath` (`AppConfig.kt:20-26`, `application.conf:20-23`). The project's `.env.example` already lists `DOCUMENTS_PATH` and `IMAGES_PATH` at lines 26-28, but the backend never reads them — this is fantom configuration. Close this gap before Phase 2 services start composing those paths ad-hoc in different places.

**Design:** derive both paths from `dataPath` by default via HOCON substitution (single source of truth), but allow each to be overridden independently via its own env var so ops can mount documents and images on separate volumes.

#### File changes

1. **`backend/src/main/resources/application.conf`** — add a `paths` block under `app`:
    ```
    app {
        ...
        data {
            path = "./data"
            path = ${?DATA_PATH}
        }
        paths {
            documents = ${app.data.path}/documents
            documents = ${?DOCUMENTS_PATH}
            images = ${app.data.path}/images
            images = ${?IMAGES_PATH}
        }
    }
    ```
    HOCON resolves `${app.data.path}` to whatever `DATA_PATH` was set to (or its default `./data`), so setting only `DATA_PATH` works for normal dev. `${?DOCUMENTS_PATH}` / `${?IMAGES_PATH}` override the default when set. Do NOT compose these paths in Kotlin — let HOCON do the substitution so the config file remains the single source of truth.

2. **`backend/src/main/kotlin/com/aos/chatbot/config/AppConfig.kt`** — add two fields to the `AppConfig` data class:
    ```kotlin
    data class AppConfig(
        val mode: AppMode,
        val port: Int,
        val host: String,
        val databasePath: String,
        val dataPath: String,
        val documentsPath: String,
        val imagesPath: String
    )
    ```
    Extend `from(environment)` to read `app.paths.documents` and `app.paths.images` via `config.property(...).getString()`, in the same style as the existing fields.

3. **`backend/src/test/kotlin/com/aos/chatbot/config/AppConfigTest.kt`** — if this test file does not exist yet, create it; otherwise extend it. Use Ktor's `MapApplicationConfig` or similar to drive AppConfig parsing in isolation. Cover these cases:
    - Only `DATA_PATH=/custom` is set → `config.documentsPath == "/custom/documents"` and `config.imagesPath == "/custom/images"` (default derivation).
    - `DATA_PATH=/custom` plus `DOCUMENTS_PATH=/var/docs` → `documentsPath == "/var/docs"`, `imagesPath` still `"/custom/images"` (independent override).
    - `DATA_PATH=/custom` plus `IMAGES_PATH=/var/imgs` → symmetric: `imagesPath == "/var/imgs"`, `documentsPath` still `"/custom/documents"`.
    - Both `DOCUMENTS_PATH` and `IMAGES_PATH` set → both honored, `dataPath` still reflects `DATA_PATH`.
    - Nothing set beyond defaults → `documentsPath == "./data/documents"`, `imagesPath == "./data/images"`.
    If driving env vars from tests is awkward on Windows, test the resolution by loading HOCON strings directly via `ConfigFactory.parseString(...)` and passing through `HoconApplicationConfig`. The important assertion is that HOCON substitution and override precedence are correct.

4. **`.env.example`** — the `DOCUMENTS_PATH` and `IMAGES_PATH` entries already exist at lines 26-28 (commented out). Keep them commented (same style as other optional overrides) but update the surrounding comment block to explicitly state that they default to `${DATA_PATH}/documents` and `${DATA_PATH}/images` respectively and only need to be set when ops wants to mount them on a separate volume from `DATA_PATH`.

5. **`README.md`** — add a new `## Configuration` section (place it after `## Prerequisites` and before `## Quick Start`). List the path-related environment variables with defaults and relationship:
    - `DATA_PATH` — base data directory (default `./data`)
    - `DATABASE_PATH` — SQLite file (default `./data/aos.db`)
    - `DOCUMENTS_PATH` — uploaded source documents (default `${DATA_PATH}/documents`)
    - `IMAGES_PATH` — extracted images (default `${DATA_PATH}/images`)
    Briefly note that setting only `DATA_PATH` is sufficient for normal use; the other three auto-derive.

#### Downstream coupling (informational — do not edit those tasks here)

- Task 12 (`ImageExtractor`) will take its base path from `config.imagesPath`, not an ad-hoc literal.
- Task 13 (`DocumentService`) will take its documents storage path from `config.documentsPath`.
- Task 14 (Application.kt wiring) passes both new config fields into the respective service constructors.

Those tasks below already reflect this usage; this task only introduces the config surface.

- [ ] Add `app.paths.documents` and `app.paths.images` to application.conf with HOCON defaults derived from `app.data.path`
- [ ] Honor `DOCUMENTS_PATH` and `IMAGES_PATH` env var overrides in application.conf
- [ ] Add `documentsPath` and `imagesPath` fields to AppConfig data class
- [ ] Read `app.paths.documents` and `app.paths.images` in AppConfig.from(environment)
- [ ] Add AppConfigTest case for default derivation from DATA_PATH only
- [ ] Add AppConfigTest case for DOCUMENTS_PATH override (independent of imagesPath)
- [ ] Add AppConfigTest case for IMAGES_PATH override (independent of documentsPath)
- [ ] Add AppConfigTest case for both overrides set together
- [ ] Add AppConfigTest case for no overrides (defaults only)
- [ ] Update .env.example comment block to explain the default derivation and when overrides are needed
- [ ] Add Configuration section to README.md listing DATA_PATH, DATABASE_PATH, DOCUMENTS_PATH, IMAGES_PATH with defaults
- [ ] Run `./gradlew test` and confirm AppConfigTest passes

---

### Task 5: Create data models for document processing

Create Kotlin data classes in `backend/src/main/kotlin/com/aos/chatbot/models/`:

**Document.kt** — represents a document record from the `documents` table:
```kotlin
data class Document(
    val id: Long = 0,
    val filename: String,
    val fileType: String,       // "docx" or "pdf"
    val fileSize: Long? = null,
    val fileHash: String,       // SHA-256; always populated by DocumentService. Non-null at the model layer even though the DB schema keeps the column nullable (see Task 3 for the deferred-hardening rationale).
    val chunkCount: Int = 0,
    val imageCount: Int = 0,
    val indexedAt: String? = null,
    val createdAt: String? = null
)
```

**Chunk.kt** — represents a chunk record from the `chunks` table:
```kotlin
data class Chunk(
    val id: Long = 0,
    val documentId: Long,
    val content: String,
    val contentType: String,    // "text" | "table" | "troubleshoot" | "process"
    val pageNumber: Int? = null,
    val sectionId: String? = null,
    val heading: String? = null,
    val embedding: ByteArray? = null, // stored as BLOB, null until embedded in Phase 3
    val imageRefs: List<String> = emptyList(), // in-memory list; serialized to JSON at the ChunkRepository boundary, empty list → NULL in chunks.image_refs
    val createdAt: String? = null
)
```

**ExtractedImage.kt** — represents an image record from the `images` table:
```kotlin
data class ExtractedImage(
    val id: Long = 0,
    val documentId: Long,
    val filename: String,
    val path: String,
    val pageNumber: Int? = null,
    val caption: String? = null,
    val description: String? = null,
    val embedding: ByteArray? = null,
    val createdAt: String? = null
)
```

**ParsedContent.kt** — intermediate parsing result (not persisted directly):
```kotlin
data class ParsedContent(
    val textBlocks: List<TextBlock>,
    val images: List<ImageData>,
    val metadata: Map<String, String> = emptyMap()
)

data class TextBlock(
    val content: String,
    val type: String = "text",     // "text" | "table" | "troubleshoot" | "process"
    val pageNumber: Int? = null,
    val sectionId: String? = null,
    val heading: String? = null,
    val imageRefs: List<String> = emptyList()
)

data class ImageData(
    val filename: String,
    val data: ByteArray,
    val pageNumber: Int? = null,
    val caption: String? = null
)
```

All models use kotlinx.serialization `@Serializable` where needed for API responses (Document, Chunk). ParsedContent and ImageData are internal-only and do not need serialization.

#### Image linkage contract (normative for all downstream tasks)

The pipeline must not lose the association between extracted images and the text they relate to. Every extracted image must be reachable from at least one `TextBlock` (and, after chunking, from at least one `Chunk`) via `imageRefs`. This is the contract that Tasks 7, 8, 9, 10, 11, and 12 all implement — they are not free to reinvent it.

**Stable identifier.** The `filename` on `ImageData` is the stable handle for an image throughout the entire pipeline:
- Parser generates it.
- It lives unchanged on `TextBlock.imageRefs` and the resulting `Chunk.imageRefs` as a Kotlin `List<String>` throughout the domain / model layer. JSON serialization happens only at the `ChunkRepository` boundary when binding the value to `chunks.image_refs`.
- `ImageExtractor` writes the file to disk using this exact filename — no renaming.
- The `images` table row (Task 6's `ImageRepository`) stores this exact filename.

**Per-document namespace.** Filenames are scoped per document: disk layout is `{imagesPath}/{documentId}/{filename}`, and the `images` table uses `(document_id, filename)` as the logical lookup tuple. Therefore parsers can safely reuse a simple scheme like `img_001.png`, `img_002.png`, ... per document without collision between documents.

**Naming conventions (parsers MUST follow):**
- WordParser: `img_{NNN}.{ext}` where `NNN` is a 3-digit sequence starting at `001`, in document traversal order, and `ext` matches the blob's MIME type (`png`, `jpg`, `gif`, etc.).
- PdfParser: `img_p{PAGE}_{NNN}.{ext}` — page number embedded in the name so the page origin is recoverable from the filename alone. Sequence `NNN` resets per page.

**Referential integrity invariant.** For any `ParsedContent` produced by a parser:
- Every filename appearing in any `TextBlock.imageRefs` MUST appear as the `filename` of exactly one `ImageData` in `ParsedContent.images`.
- Every `ImageData` in `ParsedContent.images` MUST appear in exactly one `TextBlock.imageRefs` (no orphans, no duplicates across blocks). Exception: PdfParser may produce a synthetic empty `TextBlock` solely to carry image refs for an image-only page (see Task 9).
- DocumentService (Task 13) validates this invariant before persisting; violations cause pipeline failure with the standard rollback/compensation path.

**Preservation through the pipeline:**
- AosParser post-processing (Task 11) MUST NOT drop or rewrite `imageRefs` when converting a block's `type` (e.g., text → troubleshoot). The field passes through unchanged.
- ChunkingService (Task 10) MUST replicate the full `imageRefs` list onto every chunk produced by splitting a parent `TextBlock`. Do not "assign to first chunk only" — duplication is cheap (a short JSON array of filenames) and ensures retrieval via any matching chunk surfaces the related images.
- `ChunkRepository` (Task 6) serializes `imageRefs: List<String>` to a JSON array string when binding to `chunks.image_refs` on insert, and deserializes the JSON back into `List<String>` on read. Empty list serializes to `NULL` (not `"[]"`) to keep the DB column semantically consistent. This is the single boundary where JSON crosses into/out of the domain model — no other layer touches the JSON form.

#### pageNumber population policy

`pageNumber: Int?` on `TextBlock`, `Chunk`, `ExtractedImage`, and `ImageData` is populated only when the source format can provide it reliably.

- **PdfParser (Task 9)** sets `pageNumber = N` on every TextBlock and ImageData emitted from page N, 1-indexed from PDFBox.
- **WordParser (Task 8)** sets `pageNumber = null` on every emitted TextBlock and ImageData. Apache POI does not expose reliable rendered page numbers for .docx, and Phase 2 does NOT use heuristics or approximations (paragraph counts, explicit page breaks, section properties, or any other derived value). Adding reliable .docx page numbers would require an external layout engine and is out of scope for Phase 2; changing this rule requires explicit design review.
- **ChunkingService (Task 10), AosParser (Task 11), ImageExtractor (Task 12), DocumentService (Task 13)** pass `pageNumber` through unchanged. Null stays null, N stays N. No transformation.

Downstream consumers (Phase 3+ RAG, future UI citation) must handle null as "page unknown" and must not fabricate a default.

- [ ] Create Document.kt data class
- [ ] Create Chunk.kt data class
- [ ] Create ExtractedImage.kt data class
- [ ] Create ParsedContent.kt with TextBlock and ImageData
- [ ] Document the image linkage contract in a KDoc comment on ParsedContent.kt so downstream implementers see it when they open the file
- [ ] Document the pageNumber population policy in a KDoc comment on TextBlock and ImageData

---

### Task 6: Create DocumentRepository and ChunkRepository

Create repository classes in `backend/src/main/kotlin/com/aos/chatbot/db/repositories/` that operate on the existing SQLite schema (tables already created by V1 migration).

**DocumentRepository.kt:**
- `insert(doc: Document): Long` — insert document, return generated ID
- `findById(id: Long): Document?`
- `findAll(): List<Document>` — ordering: `ORDER BY created_at DESC, id DESC` (see "Deterministic ordering contract" below)
- `findByHash(hash: String): Document?` — for deduplication
- `updateChunkCount(id: Long, chunkCount: Int, imageCount: Int)`
- `updateIndexedAt(id: Long)`
- `delete(id: Long)` — cascades to chunks and images via FK

**ChunkRepository.kt:**
- `insertBatch(chunks: List<Chunk>): List<Long>` — bulk insert for performance
- `findByDocumentId(documentId: Long): List<Chunk>` — ordering: `ORDER BY id ASC`
- `findAll(): List<Chunk>` — needed for in-memory search later — ordering: `ORDER BY id ASC`
- `deleteByDocumentId(documentId: Long)`
- `count(): Int`

**ImageRepository.kt:**
- `insert(image: ExtractedImage): Long`
- `findByDocumentId(documentId: Long): List<ExtractedImage>` — ordering: `ORDER BY id ASC`
- `deleteByDocumentId(documentId: Long)`

All repositories take `java.sql.Connection` as constructor parameter (same pattern as existing code).
Use `PreparedStatement` for all queries to prevent SQL injection.

**`Chunk.imageRefs` JSON serialization at the repository boundary.** The `Chunk` domain model carries `imageRefs: List<String>` (Task 5); the DB column `chunks.image_refs` carries a JSON array string. `ChunkRepository` is the **only** layer that converts between them:

- On `insertBatch`: read `chunk.imageRefs` (a `List<String>`), serialize to a JSON array string (e.g., `["img_001.png","img_002.png"]`), and bind it to the `image_refs` parameter of the PreparedStatement. If the list is empty, bind SQL `NULL` (not `"[]"`) — empty list maps to NULL to keep the column semantically consistent with "no image references".
- On `findByDocumentId` / `findAll`: read the `image_refs` column, deserialize the JSON array string back into a `List<String>`. If the column is NULL, return `emptyList()` on the constructed `Chunk`. Never surface a nullable `List<String>?` to callers.

Use `kotlinx.serialization.json.Json` for both directions (same library already used for API responses). No external JSON adapter, no reflection, no custom type token — two lines each direction.

No other Phase 2 component touches the JSON form. DocumentService, ChunkingService, AosParser, and the parsers all work with `List<String>`. This keeps the storage format leak-free at the domain boundary.

#### Deterministic ordering contract

SQLite (and SQL in general) gives **no ordering guarantee** without an explicit `ORDER BY` clause. Any `SELECT` without `ORDER BY` may return rows in any order the query planner finds convenient — and that order may change across SQLite versions, schema changes, or even identical queries on the same data. Phase 2 consumers (ChunkingService, SearchService in Phase 3, admin UI, debug prints, route tests, failure-mode tests) all start depending on result order from this phase on. Lock the ordering now.

**Rules (every list-returning repository method must emit these clauses verbatim):**

| Method | ORDER BY clause | Rationale |
|---|---|---|
| `DocumentRepository.findAll()` | `ORDER BY created_at DESC, id DESC` | Newest-first matches standard admin UI expectations ("recent uploads at the top"). `id DESC` is the tie-breaker for same-second uploads — without it, two documents created in the same second would have non-deterministic relative order. |
| `ChunkRepository.findByDocumentId(documentId)` | `ORDER BY id ASC` | Insertion order. Because `insertBatch` preserves parser traversal order, `id ASC` reproduces the original document reading order — heading 1, body, heading 2, body, table, etc. Used by Phase 3 RAG retrieval for context reconstruction and by debug prints for human-readable output. |
| `ChunkRepository.findAll()` | `ORDER BY id ASC` | Global insertion order. Documents appear in contiguous blocks (because `insertBatch` is atomic per document within a single transaction), and chunks within a document appear in reading order. Required by the in-memory vector search path in Phase 3 — shuffled order would make test outputs non-reproducible. |
| `ImageRepository.findByDocumentId(documentId)` | `ORDER BY id ASC` | Insertion order matches the parser's image numbering convention (`img_001.png`, `img_002.png`, ... from Task 5 contract). Sorting by filename would work too but requires string-sort semantics; `id ASC` is simpler and equivalent. |
| `findById`, `findByHash` | — | Single-row query, no ordering needed. |
| `count()` | — | Aggregate, no ordering needed. |

**Why not `page_number ASC, id ASC` for chunks?** Tempting for PDF semantics, but pure `id ASC` gives the same result under Phase 2's insert path (parser produces TextBlocks in page/traversal order, `insertBatch` preserves that order, auto-increment `id` is monotonic). Adding `page_number ASC` as a primary sort would only matter if chunks could be inserted out of order — and they cannot in Phase 2. The simpler clause is preferred.

**Admin list endpoint contract.** `GET /api/admin/documents` (Task 14) returns whatever order `DocumentRepository.findAll()` returns — the route handler is a pass-through and must NOT re-sort. That keeps a single source of truth for ordering and prevents route-level code from drifting away from repository behavior. If the admin UI wants a different order, that becomes a future repository variant (e.g., `findAllByFilename()`), not an ad-hoc `.sortedBy` in the route.

**Test implications.** Every repository's list method needs an explicit ordering test that inserts rows in a known non-sorted order and asserts the returned order. Without such tests, a future refactor could drop the `ORDER BY` clause and all the parsing/chunking tests would still pass (because small test data sets often happen to come back in insert order), but production ordering would silently break.

**Lifecycle — repositories are operation-scoped, NOT singletons.** A repository instance is created for a single unit of work (one `processDocument` call, one `listDocuments` call, one test case) and discarded when the connection is closed. Creating a repository is cheap — just a field assignment. The repository does NOT own the connection's lifecycle: the caller (DocumentService, test, route handler) opens and closes the connection. A repository instance MUST NOT outlive the connection it was given. See the "Connection lifecycle and ownership" section in Task 13 for the full contract.

Write unit tests:
- `backend/src/test/kotlin/com/aos/chatbot/db/repositories/DocumentRepositoryTest.kt`
- `backend/src/test/kotlin/com/aos/chatbot/db/repositories/ChunkRepositoryTest.kt`
- `backend/src/test/kotlin/com/aos/chatbot/db/repositories/ImageRepositoryTest.kt`

Tests may use in-memory SQLite (`:memory:`) with a single connection shared across the test (same pattern as `MigrationsTest.kt`). This works at the repository level because each test exercises exactly one unit of work on one connection. Do NOT mimic this in `DocumentServiceTest` — that layer opens connections per operation and needs a file-based DB (see Task 13).

- [ ] Create DocumentRepository with CRUD operations
- [ ] DocumentRepository.findAll() emits `ORDER BY created_at DESC, id DESC` — newest first with id tie-breaker
- [ ] Create ChunkRepository with batch insert
- [ ] ChunkRepository.findByDocumentId emits `ORDER BY id ASC` — insertion order preserves parser traversal order
- [ ] ChunkRepository.findAll() emits `ORDER BY id ASC` — global insertion order, required for reproducible RAG tests in Phase 3
- [ ] Create ImageRepository
- [ ] ImageRepository.findByDocumentId emits `ORDER BY id ASC` — insertion order matches parser image numbering
- [ ] Document in KDoc on each repository class that instances are operation-scoped and must not outlive the injected Connection
- [ ] Document in KDoc on each list-returning method the exact ORDER BY clause it uses, so the contract is visible at the call site (not only in this plan)
- [ ] Write DocumentRepositoryTest
- [ ] Test: DocumentRepository.findAll() — insert 3 documents with explicitly controlled created_at values (NOT in chronological order — e.g., middle first, then oldest, then newest), assert findAll() returns them newest-first by created_at
- [ ] Test: DocumentRepository.findAll() tie-breaker — insert 2 documents with IDENTICAL created_at (use explicit timestamp parameter, not auto), assert the higher `id` appears first (id DESC tie-breaker)
- [ ] Test: DocumentRepository.findAll() on empty table returns empty list (not null, not throws)
- [ ] Write ChunkRepositoryTest
- [ ] Test: ChunkRepository.findByDocumentId returns chunks in ascending id order — insert a batch of 5 chunks, assert the returned list is sorted by id ascending
- [ ] Test: ChunkRepository.findByDocumentId across two separate batches — insert batch A (3 chunks) for doc1, then batch B (2 chunks) for doc1, then call findByDocumentId(doc1), assert all 5 chunks in insertion order (batch A first, then batch B)
- [ ] Test: ChunkRepository.findAll() returns chunks from multiple documents in global id-ascending order (insert doc1's chunks, then doc2's chunks, assert doc1's chunks come first)
- [ ] Test: ChunkRepository.findAll() on empty table returns empty list
- [ ] Test: Chunk.imageRefs JSON round-trip at the repository boundary — insert a Chunk with `imageRefs = listOf("img_001.png", "img_002.png")`, read it back via `findByDocumentId`, assert the returned `imageRefs` equals the original list; and insert a Chunk with `imageRefs = emptyList()`, assert the raw `chunks.image_refs` column is SQL NULL (via a raw `SELECT image_refs`), then read via `findByDocumentId` and assert the returned `imageRefs` equals `emptyList()` (not null)
- [ ] Write ImageRepositoryTest
- [ ] Test: ImageRepository.findByDocumentId returns images in ascending id order
- [ ] Test: ImageRepository.findByDocumentId on a document with no images returns empty list (not null)

---

### Task 7: Create DocumentParser interface and ParserFactory

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/DocumentParser.kt`:

```kotlin
interface DocumentParser {
    fun parse(file: File): ParsedContent
    fun supportedExtensions(): List<String>
}
```

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/ParserFactory.kt`:

```kotlin
class ParserFactory {
    private val parsers: Map<String, DocumentParser>

    fun getParser(filename: String): DocumentParser
}
```

ParserFactory selects parser based on file extension:
- `.docx` -> WordParser
- `.pdf` -> PdfParser
- Throws `IllegalArgumentException` for unsupported formats

Write `ParserFactoryTest.kt` — test correct parser selection and error for unknown extensions.

- [ ] Create DocumentParser interface
- [ ] Create ParserFactory
- [ ] Write ParserFactoryTest

---

### Task 8: Implement WordParser (Apache POI)

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/WordParser.kt`.

WordParser uses Apache POI (XWPFDocument) to parse `.docx` files and extract:
1. **Paragraphs** — text with heading detection (style-based: Heading1, Heading2, etc.)
2. **Tables** — preserved as structured text with `|` column separators, content_type = "table"
3. **Images** — extracted as `ImageData` with auto-generated filenames (`img_001.png`, etc.)

Key behavior:
- Track current heading hierarchy as context for each text block
- Detect section numbers from heading text (e.g., "3.2.1 Component Setup" -> sectionId = "3.2.1")
- Concatenate consecutive body paragraphs into a single TextBlock (don't create one block per paragraph)
- When a new heading is encountered, flush the accumulated text as a block and start a new one
- Tables become their own TextBlock with type "table"
- Images are extracted via `XWPFPictureData`, saved to ImageData list
- Handle empty paragraphs gracefully (skip them)
- Use SLF4J logger for warnings on parse errors
- **pageNumber = null on every emitted TextBlock and ImageData** (Apache POI does not expose reliable rendered page numbers for .docx — see Task 5 "pageNumber population policy"). No heuristics in Phase 2.

#### Image linkage rule (implements the contract from Task 5)

Walk the document body in traversal order (`XWPFDocument.bodyElements` or equivalent, not the flat paragraph list — traversal order matters). Maintain two pieces of state during the walk:

1. `currentTextBlock: StringBuilder?` — the text block currently being accumulated (nullable: may be empty between a flush and the next run of body paragraphs).
2. `pendingImageRefs: MutableList<String>` — image filenames observed while no text block is open; they will be attached to the **next** regular text TextBlock that opens.

Rules:
- When an image run is encountered (e.g., a picture inside a `XWPFRun`):
    - Generate the next filename `img_{NNN}.{ext}` (pad to 3 digits, sequential per document, starting at `001`). Extension comes from the POI `XWPFPictureData.pictureType` / MIME.
    - Append the `ImageData` to the ParsedContent images list immediately.
    - If `currentTextBlock` exists AND its eventual type will be `"text"`: append the filename to the imageRefs of the TextBlock that this accumulation will flush into.
    - Else (we are between blocks, or inside a table/heading flush boundary): append the filename to `pendingImageRefs`.
- When a new text TextBlock is opened (first body paragraph after a flush), drain `pendingImageRefs` into its imageRefs before accumulating any content.
- **Tables do not receive imageRefs.** An image whose POI traversal position falls inside a table cell or between a table and the next element: add its filename to `pendingImageRefs` so it attaches to the next regular text TextBlock instead. Table TextBlocks always have `imageRefs = emptyList()` even if the table contained an inline picture.
- **Leading images** (image appears before any text at document start): hold in `pendingImageRefs` and attach to the first text block that opens.
- **Trailing images** (image appears after the last text block, with no more text following): create a synthetic empty-content text TextBlock at end-of-document whose only purpose is to carry those imageRefs. Log at DEBUG level. This keeps the referential integrity invariant from Task 5 intact (every ImageData reachable from exactly one TextBlock).

Create test file: `backend/src/test/kotlin/com/aos/chatbot/parsers/WordParserTest.kt`
- Create minimal .docx files programmatically using POI in test setup (XWPFDocument) covering:
    - A heading paragraph
    - Body text paragraphs
    - A simple table (2x2)
- Assert correct number of TextBlocks and types
- Assert heading and sectionId extraction
- Assert table content preservation

**Image linkage tests (each is a separate test case):**
- Text → image → more text (same paragraph flow): the image filename appears in the `imageRefs` of the single text TextBlock that contains both surrounding runs.
- Heading → text → image → heading → text: image attaches to the first (pre-second-heading) text block, not to the second.
- Image → text (leading image at document start): first text block's imageRefs contains the image filename.
- Table containing an inline image, followed by a text paragraph: the table TextBlock's imageRefs is empty; the following text TextBlock's imageRefs contains the image filename.
- Text → image (trailing image at document end): a synthetic empty text TextBlock exists whose imageRefs contains the image filename.
- Two images in the same text block: both filenames appear in that block's imageRefs in order `img_001`, `img_002`.
- **Referential integrity invariant** (per the Task 5 contract): for every test doc above, assert that `parsed.images.map { it.filename }.toSet() == parsed.textBlocks.flatMap { it.imageRefs }.toSet()` and that the flat list of all `imageRefs` across blocks has no duplicates.
- Assert filenames follow the `img_{NNN}.{ext}` convention with the correct extension derived from the picture MIME type.

**pageNumber policy tests:**
- WordParserTest case: a .docx with a heading, body paragraphs, and an embedded image — assert every emitted TextBlock and every ImageData has `pageNumber == null`.
- Suite-level invariant (`@AfterEach` or shared helper called from every test): `parsed.textBlocks.all { it.pageNumber == null } && parsed.images.all { it.pageNumber == null }`. Fails any test that accidentally produces a non-null value.

#### Error translation — unreadable content (implements the unreadable-document contract from Task 13)

POI can throw a variety of library-specific exceptions when a `.docx` file is corrupted, truncated, encrypted, or otherwise unparseable. These must NOT propagate as-is — raw POI exceptions leaking to routes would couple the API surface to the library's error vocabulary and break on POI upgrades. Translate every known failure mode into `UnreadableDocumentException` (type defined in Task 13).

**Catch/translate block.** Wrap the parser body in a try/catch:

```kotlin
override fun parse(file: File): ParsedContent {
    return try {
        XWPFDocument(file.inputStream()).use { doc ->
            // ... traversal logic ...
        }
    } catch (e: org.apache.poi.EncryptedDocumentException) {
        throw UnreadableDocumentException(UnreadableReason.EncryptedDocument("docx"), e)
    } catch (e: org.apache.poi.openxml4j.exceptions.InvalidFormatException) {
        throw UnreadableDocumentException(UnreadableReason.CorruptedDocx(e.message ?: "invalid OOXML format"), e)
    } catch (e: org.apache.poi.ooxml.POIXMLException) {
        throw UnreadableDocumentException(UnreadableReason.CorruptedDocx(e.message ?: "OOXML parse error"), e)
    } catch (e: java.util.zip.ZipException) {
        // .docx is a ZIP container; zip-level corruption surfaces here
        throw UnreadableDocumentException(UnreadableReason.CorruptedDocx("ZIP container corrupted: ${e.message}"), e)
    } catch (e: java.io.IOException) {
        // General I/O errors during read — truncated files, stream errors.
        // NOTE: only translate if the file *exists* and is readable at the OS level.
        // If the file is missing entirely, that is a DocumentService / filesystem bug, not unreadable content — let IOException propagate as a genuine failure.
        if (!file.exists() || !file.canRead()) throw e
        throw UnreadableDocumentException(UnreadableReason.CorruptedDocx("I/O error: ${e.message}"), e)
    }
}
```

**Do NOT catch `Exception` broadly.** Only catch the specific POI/IO types listed above. Other exceptions (NPE, IllegalStateException from our own code) must propagate as genuine failures so they are treated as bugs, not as user-facing 400s. Same reasoning as the cleanup/rollback policy in Task 13.

**Never log these at ERROR.** The upload is bad input, not a server fault. Log at INFO with the filename (sanitized) and reason code. A raw stack trace should only appear at DEBUG level if at all.

**Corrupted-input test cases (each is a separate test):**
- Pass a file whose bytes are `"not a real docx file"` (plain ASCII, not a ZIP): expect `UnreadableDocumentException` with `reason is UnreadableReason.CorruptedDocx`. Assert the exception's `cause` is a `ZipException` or `POIXMLException` or `InvalidFormatException` (any of the translated sources).
- Pass a valid ZIP file that is NOT an OOXML document (e.g., a zipped text file): expect `CorruptedDocx`.
- Pass a truncated `.docx` — create a valid XWPFDocument in-memory, serialize to bytes, truncate the last 100 bytes, write to temp file, parse: expect `CorruptedDocx`.
- Pass an empty file (0 bytes): this case is caught earlier by DocumentService `InvalidUploadException(EmptyFile)` and should never reach WordParser. Add a test at parser level with a 0-byte file anyway (defense in depth) — assert `CorruptedDocx` or at least a deterministic `UnreadableDocumentException`, not a raw POI exception.
- Pass a password-protected `.docx`: generate one via POI in the test setup (XWPFDocument with encryption), assert `UnreadableDocumentException` with `reason is UnreadableReason.EncryptedDocument` and `reason.format == "docx"`.
- Assert that for every corrupted-input test, the exception type is **exactly** `UnreadableDocumentException` — never a raw `POIXMLException`, `IOException`, `ZipException`, or `EncryptedDocumentException`. This is the decoupling guarantee; tests break if a future refactor lets a raw library exception leak.

- [ ] Implement WordParser with paragraph extraction and heading tracking
- [ ] Implement table extraction in WordParser
- [ ] Implement image extraction in WordParser
- [ ] Implement image-to-text-block linkage with pendingImageRefs staging
- [ ] Ensure tables never carry imageRefs; defer images-in-tables to the next text block
- [ ] Create synthetic trailing empty TextBlock for images at end-of-document
- [ ] Wrap parse body in try/catch and translate POI library exceptions into UnreadableDocumentException with specific UnreadableReason variants
- [ ] Do NOT catch generic Exception — only translate the specific POI/IO types listed
- [ ] Log unreadable-content events at INFO with sanitized filename and reason code, never ERROR
- [ ] Write WordParserTest with programmatically created test documents
- [ ] Write WordParserTest cases for each image-linkage scenario listed above
- [ ] Assert referential integrity invariant in WordParserTest (no orphans, no duplicates)
- [ ] Test: parser throws UnreadableDocumentException(CorruptedDocx) on plain-ASCII file posing as docx
- [ ] Test: parser throws UnreadableDocumentException(CorruptedDocx) on valid ZIP that is not OOXML
- [ ] Test: parser throws UnreadableDocumentException(CorruptedDocx) on truncated docx
- [ ] Test: parser throws UnreadableDocumentException(CorruptedDocx) or similar on 0-byte file (defense in depth)
- [ ] Test: parser throws UnreadableDocumentException(EncryptedDocument("docx")) on password-protected docx
- [ ] Test: every corrupted-input test asserts the exception type is exactly UnreadableDocumentException, never a raw POI/IO exception (decoupling guarantee)
- [ ] WordParser sets `pageNumber = null` on every emitted TextBlock and every ImageData (no heuristics)
- [ ] Write WordParserTest asserting `pageNumber == null` on a .docx with heading + body + embedded image
- [ ] Add a suite-level invariant check that `parsed.textBlocks.all { it.pageNumber == null } && parsed.images.all { it.pageNumber == null }` runs after every WordParserTest case

---

### Task 9: Implement PdfParser (Apache PDFBox)

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/PdfParser.kt`.

PdfParser uses Apache PDFBox to parse `.pdf` files as fallback (when no .docx is available):
1. **Text extraction** — page by page using PDFTextStripper
2. **Basic heading detection** — heuristic: lines in ALL CAPS or short lines followed by body text
3. **Images** — extract embedded images via PDDocument page resources
4. **Page tracking** — each TextBlock records its page number

Key behavior:
- Extract text page by page, setting `pageNumber = N` on every TextBlock and ImageData emitted from page N. **PdfParser is the only parser in Phase 2 that populates non-null pageNumber** — this is the counterpart to WordParser's strict `pageNumber = null` policy. See Task 5 "pageNumber population policy" for the full rationale.
- Attempt to detect section headers by pattern (numbered sections like "3.2.1", ALL CAPS lines)
- Tables in PDF are treated as plain text (PDFBox doesn't reliably detect table structure)
- Images extracted from each page's resources (PDImageXObject)
- The parser is simpler than WordParser because PDF structure is less reliable

#### Image linkage rule (implements the contract from Task 5)

PDFBox does not give reliable spatial coordinates for text-image association. Use **page-level association** as the minimum guarantee:

- Process one page at a time. For each page N:
    1. Extract text for the page (PDFTextStripper with `startPage=N` / `endPage=N`) and produce one or more TextBlocks whose `pageNumber = N`.
    2. Walk `PDPage.resources` for `PDImageXObject` instances. For each image found on page N:
        - Generate the next filename `img_p{N}_{NNN}.{ext}` where `NNN` is a 3-digit sequence that resets at the start of each page, and `ext` is derived from the image object's type (`png`, `jpg`, ...).
        - Append the `ImageData` to ParsedContent images with `pageNumber = N`.
        - Append the filename to the imageRefs of the **first** TextBlock produced from page N.
- **Image-only page** (no text extracted on page N, but images present): create one synthetic empty-content TextBlock with `pageNumber = N` and empty `content`, whose `imageRefs` carries all image filenames from that page. This preserves the Task 5 invariant that every ImageData is reachable from exactly one TextBlock.
- **Text-only page** (no images): no synthetic block needed; TextBlocks carry `imageRefs = emptyList()`.
- Do NOT attempt to guess inline position within a page — spatial correlation is explicitly out of scope for Phase 2. If a future phase improves this, it can refine the linkage without breaking the contract.

Create test file: `backend/src/test/kotlin/com/aos/chatbot/parsers/PdfParserTest.kt`
- Create minimal PDFs programmatically using PDFBox in test setup (PDDocument + PDPage + PDPageContentStream)
- Assert text extraction works
- Assert page numbers are tracked

**Image linkage tests (each is a separate test case):**
- Single-page PDF with text + one image: the single TextBlock has that image's filename in imageRefs, filename is `img_p1_001.{ext}`.
- Two-page PDF, each page has text + one image: page 1's first TextBlock carries `img_p1_001`, page 2's first TextBlock carries `img_p2_001`. Sequence resets per page.
- Image-only page (page 2 of 3 has no text, only an image): a synthetic empty TextBlock exists with `pageNumber = 2` and `imageRefs = ["img_p2_001.{ext}"]`.
- Page with text and multiple images: first TextBlock on the page carries all image filenames in order.
- Text-only page: resulting TextBlocks have `imageRefs = emptyList()`.
- **Referential integrity invariant** (same as WordParserTest): for every test doc, assert set-equality between `parsed.images.map { it.filename }` and `parsed.textBlocks.flatMap { it.imageRefs }`, and that there are no duplicate filenames across blocks.
- Assert filenames follow the `img_p{PAGE}_{NNN}.{ext}` convention and that `ext` matches the image MIME type.

#### Error translation — unreadable content (implements the unreadable-document contract from Task 13)

Same principle as WordParser: translate PDFBox library exceptions into `UnreadableDocumentException` so the route layer never sees raw PDFBox types. This decouples the API from PDFBox's error vocabulary.

**Catch/translate block:**

```kotlin
override fun parse(file: File): ParsedContent {
    return try {
        org.apache.pdfbox.Loader.loadPDF(file).use { doc ->
            if (doc.isEncrypted) {
                throw UnreadableDocumentException(UnreadableReason.EncryptedDocument("pdf"))
            }
            // ... text and image extraction logic ...
        }
    } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        throw UnreadableDocumentException(UnreadableReason.EncryptedDocument("pdf"), e)
    } catch (e: java.io.IOException) {
        // PDFBox surfaces most structural corruption as IOException with messages like
        // "Header doesn't contain versioninfo", "Error: End-of-File, expected line", etc.
        // NOTE: only translate if the file *exists* and is readable; if the path is missing,
        // that is a DocumentService / filesystem bug and IOException must propagate as a genuine failure.
        if (!file.exists() || !file.canRead()) throw e
        throw UnreadableDocumentException(UnreadableReason.CorruptedPdf("I/O error: ${e.message}"), e)
    }
}
```

**Same rules as WordParser:**
- Do NOT catch `Exception` broadly. Only the specific PDFBox/IO types above.
- Do NOT log at ERROR. Use INFO with the sanitized filename and reason code.
- `UnreadableDocumentException` is re-raised without wrapping so the type survives through DocumentService's rollback/compensation path (Task 13) and reaches the route layer (Task 14) intact.
- The explicit `doc.isEncrypted` check at the start is necessary because some encrypted PDFs load successfully and only fail later when text extraction is attempted — checking upfront gives a clean, deterministic error. If the check is omitted, an encrypted PDF might slip through and fail inside `PDFTextStripper` with a confusing downstream exception.

**Corrupted-input test cases (each is a separate test):**
- Pass a file whose bytes are plain text starting with `"not a real pdf"`: expect `UnreadableDocumentException` with `reason is UnreadableReason.CorruptedPdf`. Assert `cause` is the translated `IOException` from PDFBox.
- Pass a truncated PDF — create a valid PDF via PDFBox in test setup, serialize, truncate trailing bytes (remove the xref/trailer), write to temp file, parse: expect `CorruptedPdf`.
- Pass a PDF with a valid header but corrupted xref table (overwrite the xref offset with garbage): expect `CorruptedPdf`.
- Pass an empty file (0 bytes): defense-in-depth test; expect `UnreadableDocumentException` (not a raw `IOException`).
- Pass a password-protected PDF — generate one via PDFBox `StandardProtectionPolicy` in test setup, assert `UnreadableDocumentException` with `reason is UnreadableReason.EncryptedDocument` and `reason.format == "pdf"`. Verify this works both when PDFBox throws `InvalidPasswordException` on load AND when `doc.isEncrypted` is true after load but before extraction — the catch-all `isEncrypted` check must cover the second case.
- Assert that for every corrupted-input test, the exception type is **exactly** `UnreadableDocumentException` — never a raw `IOException`, `InvalidPasswordException`, or other PDFBox type. Same decoupling guarantee as WordParser.

- [ ] Implement PdfParser with page-by-page text extraction
- [ ] Implement basic heading detection heuristic in PdfParser
- [ ] Implement image extraction in PdfParser
- [ ] Wrap parse body in try/catch and translate PDFBox library exceptions into UnreadableDocumentException with specific UnreadableReason variants
- [ ] Explicit `doc.isEncrypted` check at load time, throws EncryptedDocument("pdf") before any extraction
- [ ] Do NOT catch generic Exception — only translate PDFBox/IO types listed
- [ ] Log unreadable-content events at INFO with sanitized filename and reason code, never ERROR
- [ ] Write PdfParserTest with programmatically created test PDF
- [ ] Test: parser throws UnreadableDocumentException(CorruptedPdf) on plain-text file posing as pdf
- [ ] Test: parser throws UnreadableDocumentException(CorruptedPdf) on truncated pdf
- [ ] Test: parser throws UnreadableDocumentException(CorruptedPdf) on pdf with corrupted xref table
- [ ] Test: parser throws UnreadableDocumentException on 0-byte file (defense in depth)
- [ ] Test: parser throws UnreadableDocumentException(EncryptedDocument("pdf")) on password-protected pdf via InvalidPasswordException path
- [ ] Test: parser throws UnreadableDocumentException(EncryptedDocument("pdf")) via the doc.isEncrypted upfront check path
- [ ] Test: every corrupted-input test asserts the exception type is exactly UnreadableDocumentException, never a raw PDFBox/IO exception (decoupling guarantee)
- [ ] PdfParser sets `pageNumber = N` on every TextBlock and ImageData emitted from page N (1-indexed from PDFBox)
- [ ] Test: three-page PDF — TextBlocks and images from each page carry the matching pageNumber (1, 2, 3), no cross-contamination
- [ ] Add a suite-level invariant check that `parsed.textBlocks.all { it.pageNumber != null } && parsed.images.all { it.pageNumber != null }` runs after every PdfParserTest case

---

### Task 10: Implement ChunkingService

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/ChunkingService.kt`.

ChunkingService splits TextBlocks into appropriately sized chunks for embedding.

Parameters (from ARCHITECTURE.md section 8.3):
- `maxChunkTokens`: 500 (approximate: 1 token ~ 4 chars for English, estimate accordingly)
- `overlapTokens`: 50
- `minChunkTokens`: 100

Key behavior:
- Input: `List<TextBlock>` from a parser
- Output: `List<TextBlock>` (same structure, but content split to size)
- **Preserve sentence boundaries** — don't split mid-sentence. Use `.`, `!`, `?` followed by space/newline as sentence boundaries
- **Don't split special types** — TextBlocks with type "table" or "troubleshoot" are kept as-is even if they exceed max size (they need structural integrity)
- **Overlap** — when splitting, include `overlapTokens` worth of text from end of previous chunk at start of next
- **Preserve metadata** — split chunks inherit `heading`, `sectionId`, `pageNumber`, `type`, `imageRefs` from parent
- **Skip tiny chunks** — if a remaining piece is less than `minChunkTokens`, merge it with the previous chunk
- Token estimation: use `text.length / 4` as a simple approximation (no tokenizer library needed)

#### imageRefs preservation rule (implements the contract from Task 5)

When a parent TextBlock is split into multiple chunks:
- **Every resulting chunk inherits the full `imageRefs` list of the parent, by value (copy), not by reference.** Do not "assign imageRefs only to the first split chunk" — that would make retrieval of later chunks lose the text-to-image linkage. A small JSON array is cheap to duplicate; losing the link is not.
- When a tiny trailing chunk is merged into the previous chunk (min-chunk merge rule), imageRefs of both are **unioned, preserving order, with duplicates removed**. The merged chunk's imageRefs is `previous.imageRefs + trailing.imageRefs.filterNot { it in previous.imageRefs }`.
- When a chunk is not split (content already under `maxChunkTokens`), its imageRefs passes through unchanged, identical to input.
- For `"table"` and `"troubleshoot"` blocks (which are never split), imageRefs passes through unchanged.
- The `"trailing empty text block"` synthetic blocks created by parsers (WordParser trailing images, PdfParser image-only pages) MUST NOT be filtered out by ChunkingService just because their content is empty or below `minChunkTokens`. They carry imageRefs and must survive into the output as-is. Add an explicit exception: a block with empty/short content but non-empty imageRefs is kept as a standalone chunk.

Create test file: `backend/src/test/kotlin/com/aos/chatbot/parsers/ChunkingServiceTest.kt`
- Test that long text is split at sentence boundaries
- Test that short text is not split
- Test that overlap is applied correctly
- Test that tables/troubleshoot blocks are not split
- Test min chunk merge behavior

**imageRefs preservation tests (each is a separate test case):**
- Long text block with `imageRefs = ["img_001.png", "img_002.png"]` that splits into 3 chunks: all 3 output chunks carry the same imageRefs list `["img_001.png", "img_002.png"]`.
- Short text block with imageRefs passes through unchanged (same instance content).
- Table block with imageRefs (even though parsers don't normally produce this) passes through unchanged when not split.
- Troubleshoot block with imageRefs passes through unchanged.
- Min-chunk merge: parent splits into a large chunk `["img_001"]` and a trailing small chunk `["img_002"]`; the small one merges into the large one, and the merged chunk's imageRefs is `["img_001", "img_002"]` (union, order preserved).
- Min-chunk merge with overlapping imageRefs: previous has `["img_001", "img_002"]`, trailing has `["img_002", "img_003"]`; merged is `["img_001", "img_002", "img_003"]` (no duplicates).
- Synthetic empty block with empty content and `imageRefs = ["img_p2_001.png"]` (image-only PDF page): survives chunking as a standalone output chunk with the same imageRefs — NOT dropped by the min-chunk rule or empty-content filter.
- **Referential integrity end-to-end:** given an input `List<TextBlock>` where the union of all imageRefs is set S, the union of all output chunks' imageRefs is also set S (no image filenames lost).

- [ ] Implement ChunkingService with sentence-boundary-aware splitting
- [ ] Implement overlap between chunks
- [ ] Implement min chunk merging with imageRefs union (order preserved, no duplicates)
- [ ] Handle special types (table, troubleshoot) that should not be split, passing imageRefs through
- [ ] Preserve synthetic empty blocks that carry imageRefs (do not drop by min-chunk or empty-content rules)
- [ ] Write ChunkingServiceTest for all splitting / merging / passthrough scenarios
- [ ] Write ChunkingServiceTest cases for imageRefs preservation across every rule above
- [ ] Assert end-to-end imageRefs set equality in ChunkingServiceTest
- [ ] ChunkingService preserves `pageNumber` unchanged from parent to all output chunks (null → null, N → N); on min-chunk merge the previous parent's value wins
- [ ] Test: split a TextBlock with `pageNumber = 5` into multiple chunks and a TextBlock with `pageNumber = null` into multiple chunks — assert pageNumber is preserved on every output chunk in both cases

---

### Task 11: Implement AOS-specific parsers

Create AOS-specific parsers in `backend/src/main/kotlin/com/aos/chatbot/parsers/aos/`:

**AosParser.kt** — orchestrator that post-processes `ParsedContent` from WordParser/PdfParser:
```kotlin
class AosParser {
    fun process(content: ParsedContent): ParsedContent
}
```

**TroubleshootParser.kt** — detects and parses MA-XX troubleshooting codes:
- Pattern: `MA-\d{2,3}` at start of line or heading
- Extracts: code, symptom (after "Symptom:"), cause (after "Cause:" or "Ursache:"), solution (after "Solution:" or "Losung:")
- Output: TextBlock with type "troubleshoot", sectionId = "MA-XX"
- Handles both English and German labels
- If structure is incomplete (e.g., no explicit Symptom/Cause/Solution labels), keeps the entire block as-is

**ComponentParser.kt** — detects component property tables:
- Looks for TextBlocks of type "table" that contain component-related headers
- Enriches the type/metadata but primarily relies on WordParser's table extraction
- Preserves table structure in content

#### imageRefs passthrough rule (implements the contract from Task 5)

Post-processing in AosParser, TroubleshootParser, and ComponentParser MUST NOT drop, truncate, or rewrite `imageRefs` on any TextBlock. Allowed transformations:
- Change `type` (e.g., `"text"` → `"troubleshoot"`).
- Change or set `sectionId` (e.g., to `"MA-03"`).
- Rewrite `content` (e.g., normalize Symptom/Cause/Solution formatting).
- Merge multiple input TextBlocks into one (rare): union their imageRefs, preserving order, removing duplicates — same rule as ChunkingService's min-chunk merge.
- Split a TextBlock into multiple (e.g., one MA-XX block spans what was originally one paragraph but contains two codes): replicate imageRefs onto every resulting TextBlock — same rule as ChunkingService's split.

The `ParsedContent.images` list MUST pass through AosParser unchanged. AosParser never touches images.

Create test files:
- `backend/src/test/kotlin/com/aos/chatbot/parsers/aos/AosParserTest.kt`
- `backend/src/test/kotlin/com/aos/chatbot/parsers/aos/TroubleshootParserTest.kt`

Test TroubleshootParser:
- Input with MA-03 block including Symptom/Cause/Solution -> correct parsing
- Input with German labels (Symptom/Ursache/Losung) -> correct parsing
- Input without MA-XX pattern -> pass through unchanged
- Incomplete MA-XX block -> kept as-is with troubleshoot type

**imageRefs passthrough tests (each is a separate test case):**
- TroubleshootParser: input text TextBlock with `imageRefs = ["img_001.png"]` that gets converted to `type = "troubleshoot"`; assert the output's imageRefs is unchanged `["img_001.png"]`.
- ComponentParser: input table TextBlock with imageRefs (parser-provided) passes through with imageRefs intact after type/metadata enrichment.
- AosParser merge case: two adjacent input TextBlocks with `["img_001"]` and `["img_002"]` merged into one — merged block has `["img_001", "img_002"]` (order preserved).
- AosParser passthrough case: a TextBlock with no AOS-specific markers and `imageRefs = ["img_001"]` is emitted unchanged.
- AosParser.process does not modify the `images` list: `input.images === output.images` (or deep-equals) holds for every test doc.
- **Referential integrity end-to-end:** union of imageRefs across output TextBlocks equals union across input TextBlocks.

- [ ] Create AosParser orchestrator
- [ ] Implement TroubleshootParser with MA-XX pattern detection
- [ ] Implement TroubleshootParser German label support
- [ ] Implement ComponentParser for table enrichment
- [ ] Preserve imageRefs through every AosParser / TroubleshootParser / ComponentParser transformation
- [ ] Pass ParsedContent.images through AosParser unchanged
- [ ] Write AosParserTest
- [ ] Write TroubleshootParserTest
- [ ] Write imageRefs passthrough tests for AosParser and TroubleshootParser
- [ ] Preserve `pageNumber` unchanged through AosParser / TroubleshootParser / ComponentParser transformations (pass-through, no modification); on merge the first input's value wins
- [ ] Test: TroubleshootParser preserves `pageNumber` (both `null` and non-null cases) when converting a TextBlock to `type = "troubleshoot"`

---

### Task 12: Implement image extraction and file saving

Create `backend/src/main/kotlin/com/aos/chatbot/parsers/ImageExtractor.kt`:

Responsible for saving extracted images to disk and recording them in the database.

```kotlin
class ImageExtractor(
    private val imagesBasePath: String,  // injected from config.imagesPath (Task 4)
    private val imageRepository: ImageRepository
) {
    fun saveImages(documentId: Long, images: List<ImageData>): List<ExtractedImage>
}
```

The `imagesBasePath` constructor parameter is populated from `AppConfig.imagesPath` (introduced in Task 4). Do NOT hardcode path literals or recompose paths from `dataPath` inside this class — take whatever value comes in.

**Lifecycle — ImageExtractor is operation-scoped, NOT a singleton.** Because it holds an `ImageRepository` (which holds a `Connection`), an `ImageExtractor` instance is bound to exactly one connection and one unit of work. DocumentService constructs a fresh `ImageExtractor` inside each `processDocument` call, using the per-operation `ImageRepository` wired to that operation's connection. Do NOT wire a singleton `ImageExtractor` in Application.kt. See Task 13 "Connection lifecycle and ownership" for the full contract.

Key behavior:
- Creates directory `{imagesBasePath}/{documentId}/` if it doesn't exist
- Saves each `ImageData.data` (ByteArray) to `{imagesBasePath}/{documentId}/{filename}` via **temp-write + atomic move** (see "Atomic image file write" below) — never writes directly to the final path. The `ImageData.filename` is used **verbatim** for the final path; only the temp path has a UUID suffix.
- The filename is a hard requirement of the Task 5 image linkage contract: it is the stable identifier tying `chunks.image_refs` to rows in the `images` table and to bytes on disk. Renaming would silently break retrieval.
- Inserts `ExtractedImage` record in database with the same verbatim filename and `path = "{imagesBasePath}/{documentId}/{filename}"` (the final path, NOT the temp path).
- Returns list of saved `ExtractedImage` records
- **IO error handling — policy aligned with Task 13 cleanup/rollback:** do NOT swallow IO errors on image writes. A failed write on any image must throw, so that DocumentService's transaction + compensation path can roll back cleanly (partial image state is one of the explicit failure modes in the Task 13 failure table). The earlier "log warning, skip image, continue" behavior is explicitly withdrawn. Log at ERROR with filename and rethrow.
- Cleans up directory on document deletion (called from DocumentService via `compensateFailedUpload`)

#### Atomic image file write — temp + move (consistency with source file write)

The source file at step 5 uses temp-write + atomic move (Task 13 "Atomic source file write" subsection). For consistency of the filesystem model, image files use the **same pattern**. A naive `Files.write(finalImagePath, bytes)` leaves a half-written image file at the final path on any mid-write interruption (disk full, IOException, process kill, SIGKILL). Under narrow-txn + compensation that partial file would be cleaned by `compensateFailedUpload`'s recursive directory delete — but that only works if compensation runs to completion. Temp+move gives a stronger invariant: **the final image path either contains the full bytes or does not exist**, regardless of whether compensation fires.

**Concrete pattern (applied per image in the saveImages loop):**

```kotlin
fun saveImages(documentId: Long, images: List<ImageData>): List<ExtractedImage> {
    val docDir = Paths.get(imagesBasePath, documentId.toString())
    Files.createDirectories(docDir)

    val saved = mutableListOf<ExtractedImage>()
    for (image in images) {
        val finalPath = docDir.resolve(image.filename)
        val tempPath = docDir.resolve("${image.filename}.tmp.${UUID.randomUUID()}")

        // 1. Write bytes to the temp path.
        try {
            Files.write(tempPath, image.data)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tempPath) }
            logger.error("Failed to write temp image file for documentId=$documentId filename=${image.filename}", e)
            throw e
        }

        // 2. Atomic move temp → final.
        try {
            Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: FileAlreadyExistsException) {
            // CONTRACT VIOLATION — not a benign race like in source file case.
            // Images are only written by the race winner (after documentRepository.insert
            // at step 13 succeeded without UNIQUE violation). The per-document directory
            // is fresh. A collision here means either:
            //   (a) parser generated duplicate filenames within one document (parser bug,
            //       violates the Task 5 linkage contract which mandates unique filenames
            //       per document), or
            //   (b) the per-document directory contains residue from a previous failed
            //       upload that reused the same documentId (should not happen because
            //       SQLite autoincrement is monotonic, but defensive).
            // Either way this is a bug, NOT a race we can silently absorb. Log ERROR,
            // clean up the temp, and rethrow to trigger rollback + compensation.
            runCatching { Files.deleteIfExists(tempPath) }
            logger.error("Image file collision at $finalPath — parser contract violation or leftover residue", e)
            throw e
        } catch (e: AtomicMoveNotSupportedException) {
            // Same reasoning as source file: temp and final are in the same directory,
            // so same-filesystem move is guaranteed. If this fires, deployment is misconfigured.
            runCatching { Files.deleteIfExists(tempPath) }
            throw IllegalStateException(
                "Atomic move not supported in '$docDir' — filesystem config issue", e)
        }

        // 3. Insert DB row referencing the final path. Order is: file visible at
        // final path FIRST, DB row SECOND. Under rollback + compensation, the row
        // is removed by transaction rollback and the file is removed by
        // compensateFailedUpload's recursive directory delete.
        val record = ExtractedImage(
            documentId = documentId,
            filename = image.filename,
            path = finalPath.toString(),
            pageNumber = image.pageNumber,
            caption = image.caption
        )
        val id = imageRepository.insert(record)
        saved += record.copy(id = id)
    }
    return saved
}
```

**Ordering — file first, then DB row.** After the atomic move, the final image path contains the full bytes. Then the DB row is inserted referencing that path. If the DB insert fails, the image file is still on disk (will be cleaned by `compensateFailedUpload`'s recursive directory delete after rollback). If the file write fails, no DB row is inserted (throw happens before repository call). Both ordering alternatives end up in the same compensation-cleaned state, but "file first, row second" reads more naturally: the row is a database record of a disk artifact, and the artifact exists before the record is made.

**Partial image-save failure (step 14 in the pipeline — N of M images written, then the (N+1)th throws).** Under the temp+move pattern, the first N images are already atomic-moved to their final paths AND have DB rows. The (N+1)th image may have a partial temp file but never a partial final path. When the exception propagates to DocumentService, the rollback+compensation path fires:
- `conn.rollback()` removes all N image rows (plus the Document row).
- `compensateFailedUpload(sourceFile, documentId)` recursively deletes `{imagesBasePath}/{documentId}/`, which removes all N atomic-moved final files AND any leftover `*.tmp.*` files from the failing (N+1)th write.

This is the same end-state as before the atomic-write pattern, but with a cleaner intermediate invariant: at NO point is a partial file visible at a final image path.

**Why temp path has UUID suffix for images.** Multiple images per document are written sequentially, not concurrently. So the race-uniqueness argument that applied to source file doesn't fully apply here. But UUID suffix is kept for three reasons:
1. Consistency with source file strategy (one pattern everywhere is simpler to remember and enforce).
2. Defensive against retry scenarios where a failed upload is retried and might collide with residue.
3. The startup orphan scan (Task 14) uses the `.tmp.` substring as its cleanup marker — keeping the same naming shape makes the scan rule trivial.

**Anti-patterns — explicitly forbidden:**
- ❌ `Files.write(finalPath, image.data)` or `FileOutputStream(finalPath).use { ... }` — direct write to the final image path.
- ❌ Catching `FileAlreadyExistsException` at the image move and silently treating it as benign (like the source file case does) — for images this is a contract violation and must propagate as an error.
- ❌ Writing multiple images' DB rows first then all files in a second loop — the `ExtractedImage.path` would reference a file that might not exist yet, and a failure in the file loop would leave DB rows pointing at nothing (even inside the transaction, until rollback).
- ❌ Temp path outside `docDir` (e.g., system temp directory) — cross-filesystem move is not atomic.
- ❌ Temp path without `.tmp.` substring — breaks the startup orphan scan rule.

Create test file: `backend/src/test/kotlin/com/aos/chatbot/parsers/ImageExtractorTest.kt`
- Use temp directory for test images
- Test that images are written to correct path
- Test that DB records are created
- Test that filenames from `ImageData.filename` are used verbatim on disk and in the `images` table (e.g., input `img_001.png` produces a file literally named `img_001.png` and a row with `filename = "img_001.png"`)
- Test that `path` stored in the DB row matches `{imagesBasePath}/{documentId}/{filename}` exactly (the FINAL path, not a temp path)
- Test IO error handling: a failed write throws (does NOT swallow), so the caller can roll back

**Atomic image write tests (each is a separate test case):**
- Happy path: save 3 images, assert exactly 3 files exist in `{imagesBasePath}/{documentId}/` at their final paths, zero `*.tmp.*` files remain, and 3 DB rows were inserted.
- Temp write failure for the 2nd of 3 images: inject a filesystem error on the second `Files.write` call (e.g., via a test double that fails at a specific invocation count). Assert: first image's final file exists, second image has NO final file AND NO surviving temp file, third image was never attempted. The exception from the second write propagates.
- Atomic move failure simulation — `FileAlreadyExistsException`: pre-create a file at `{imagesBasePath}/{documentId}/img_001.png` before calling saveImages. Call saveImages with an `ImageData` whose filename is `img_001.png`. Assert: ERROR was logged, `FileAlreadyExistsException` propagates, the pre-existing file content is unchanged (the collision handler deletes the temp but does NOT overwrite the pre-existing final file), no DB row was inserted for this image.
- Verbatim filename on disk: input `ImageData(filename = "img_001.png", ...)` produces a file at exactly `{imagesBasePath}/{documentId}/img_001.png` — NOT `img_001.png.tmp.{uuid}`, NOT any other transform.
- Verbatim filename in DB row: the inserted `ExtractedImage.filename == "img_001.png"` and `ExtractedImage.path` ends with `/img_001.png` (no tmp suffix leaked into the DB).
- Temp path regex: spy on `Files.write` call, assert the temp target matches `.*/img_001\.png\.tmp\.[0-9a-f-]{36}$` (final filename + `.tmp.` + valid UUID).
- Final path never partially written: inject a half-write failure, assert `Files.exists(finalPath) == false` immediately after the failure — the partial bytes are contained in the temp file and deleted.
- After a successful saveImages call, assert no files matching `*.tmp.*` exist in `{imagesBasePath}/{documentId}/`.

- [ ] Implement ImageExtractor for saving images to disk
- [ ] Implement directory creation per document via `Files.createDirectories`
- [ ] Use ImageData.filename verbatim for the final on-disk path AND the DB row (`filename`, `path` columns)
- [ ] Every image write uses temp path `{final}.tmp.{UUID.randomUUID()}` in the same per-document directory
- [ ] Every image write uses `Files.move(temp, final, StandardCopyOption.ATOMIC_MOVE)` — no direct writes to the final path
- [ ] Do NOT pass `StandardCopyOption.REPLACE_EXISTING` — treat `FileAlreadyExistsException` as a contract violation
- [ ] Catch `FileAlreadyExistsException` at the move step, log ERROR with filename and documentId, delete the temp file, rethrow
- [ ] Catch `AtomicMoveNotSupportedException` at the move step, delete temp, throw `IllegalStateException` with setup-error message
- [ ] On IOException during the temp-write phase, delete the partial temp file via `runCatching { Files.deleteIfExists }`, log ERROR, and rethrow
- [ ] Ordering inside the per-image loop: file first (temp write → atomic move), DB row second (`imageRepository.insert`)
- [ ] Direct writes to `{imagesBasePath}/{documentId}/{filename}` via `Files.write`, `FileOutputStream`, `writeBytes`, etc. are forbidden — the ONLY code path that creates a final image file is the atomic `Files.move` inside saveImages
- [ ] Static / review-time guard: grep `ImageExtractor.kt` for direct writes to any path without `.tmp.` — fails the review if found
- [ ] Write ImageExtractorTest happy path (3 images → 3 final files, zero temp files, 3 DB rows)
- [ ] Write ImageExtractorTest for temp write failure on 2nd of 3 images (first saved, second no final no temp, third not attempted)
- [ ] Write ImageExtractorTest for `FileAlreadyExistsException` at atomic move (pre-existing final file collision → error propagates, pre-existing file unchanged, no DB row inserted)
- [ ] Write ImageExtractorTest asserting verbatim filenames on disk AND in DB row (no tmp suffix leaks)
- [ ] Write ImageExtractorTest asserting temp path regex matches `.*\.tmp\.[0-9a-f-]{36}$`
- [ ] Write ImageExtractorTest asserting final path is never partially written (inject half-write failure → `Files.exists(finalPath) == false` after failure)
- [ ] Write ImageExtractorTest asserting no `*.tmp.*` files remain after successful saveImages

---

### Task 13: Create DocumentService to orchestrate the parsing pipeline

Create `backend/src/main/kotlin/com/aos/chatbot/services/DocumentService.kt`:

DocumentService orchestrates the full document processing pipeline. **Narrow transaction boundary** — all CPU-heavy work (parse, post-process, chunk, validate, empty-check) runs OUTSIDE the SQLite transaction. The transaction opens immediately before the first DB write and closes immediately after the last. See "Transaction scope — narrow txn" below for the design rationale.

**Phase A — preparation (outside transaction, no DB lock held):**

1. **Validate upload** (see "Upload validation and filename normalization" below) — reject empty bytes, missing/unsafe filenames, unsupported extensions, oversized files by throwing `InvalidUploadException`. Zero side effects.
2. Compute SHA-256 hash from `fileBytes` (still in memory — no disk write yet).
3. Check `documentRepository.findByHash(hash)`. If hit → return `UploadResult.Duplicate(existing)` **immediately**. No file write, no DB mutation, no transaction ever opened. This is the deduplication contract (see below). `findByHash` is a single SELECT — it does NOT take a write lock even though it happens on a writable connection.
4. Generate server-side storage name: `{hash}.{ext}` (e.g., `a1b2c3....docx`). The name used on disk; the client-provided filename is preserved separately for display.
5. Save uploaded file to `{documentsPath}/{storageName}` via **temp-write + atomic move** (see "Atomic source file write" below) — never write bytes directly to the final path. documentsPath is injected from `config.documentsPath` (Task 4). **Outside transaction** — filesystem I/O, not SQLite.
6. Parse file using ParserFactory → ParsedContent. **Outside transaction** — this is the slowest step (POI/PDFBox), and it MUST NOT hold the writer lock.
7. Post-process with AosParser → ParsedContent (enriched). **Outside transaction.**
8. Run **Checkpoint A** image linkage validation. **Outside transaction.** On violation: throw `IllegalStateException`, compensation deletes source file. No rollback needed (no txn yet).
9. Chunk with ChunkingService → List<TextBlock>. **Outside transaction.**
10. Run **Checkpoint B** image linkage validation. **Outside transaction.** Same failure semantics as Checkpoint A.
11. **Empty-content check** — if `chunked.isEmpty()` AND `images.isEmpty()`, throw `EmptyDocumentException(NoExtractableContent)`. **Outside transaction.** Compensation deletes source file; no rollback needed.

At this point all CPU work is done, the source file is on disk, and the only remaining work is DB writes plus small image file writes that depend on documentId.

**Phase B — persistence (inside transaction, narrow lock window):**

12. **BEGIN TRANSACTION** — set `conn.autoCommit = false`. The SQLite writer lock has NOT been acquired yet (deferred mode); it will be acquired on the next INSERT/UPDATE.
13. `documentRepository.insert(newDoc)` → get generated `documentId`. **First DB write** — writer lock acquired here. Race-condition UNIQUE violation on `file_hash` is caught at this step (see "Race condition handling" below).
14. Save image files to disk via `ImageExtractor.saveImages(documentId, images)`. **Inside transaction intentionally** because the target directory is `{imagesPath}/{documentId}/` and `documentId` is only known after step 13. File writes are small (extracted image blobs) and fast compared to parsing. The ImageExtractor also inserts image rows into the `images` table (step 15 is interleaved with step 14 inside `saveImages`).
15. Image row inserts happen inside `ImageExtractor.saveImages` alongside the file writes. Both use the same operation-scoped `Connection`.
16. Convert TextBlocks to Chunks (embedding is null, will be filled in Phase 3). `Chunk.imageRefs` stays as `List<String>` — no JSON serialization at this step. Serialization to the JSON column form happens inside `ChunkRepository.insertBatch` at step 17 (see Task 6 "`Chunk.imageRefs` JSON serialization at the repository boundary"). This step is pure in-memory mapping but sits here because it needs `documentId` and it's immediately consumed by step 17.
17. Batch insert Chunks via `chunkRepository.insertBatch`.
18. Update document `chunk_count` and `image_count` via `documentRepository.updateChunkCount`.
19. Update document `indexed_at` via `documentRepository.updateIndexedAt`, then `conn.commit()` — **transaction closed, writer lock released**.
20. Return `UploadResult.Created(document)`.

**Finally block** (always runs): restore `conn.autoCommit = true`. The outer `database.connect().use { conn -> ... }` then closes the connection.

Step numbers 1–20 are referenced throughout this task's subsections (Transaction scope, Race condition handling, Cleanup/rollback policy, failure-mode table, tests). If this list changes, every cross-reference in Task 13 must be updated in the same commit.

#### Transaction scope — narrow txn (acceptance criteria, not optional)

The transaction window in `processDocument` is **narrow by design**. It opens at step 12 (immediately before the first DB write at step 13) and closes at step 19 (after the last DB write, via `conn.commit()`). Steps 1–11 and step 20 execute **outside** the transaction — no `autoCommit = false` state, no writer lock held.

**Why narrow.** SQLite in WAL mode allows readers concurrent with writers, but **writers serialize database-wide** — only one writer holds the reserved lock at a time. Parsing a large .docx via POI can take seconds to tens of seconds. If parsing ran inside the transaction (as in an earlier draft of this plan), every other writer in the database — any concurrent upload, any delete, any counter update — would block for that full duration. Narrow txn compresses the writer-lock window to DB writes plus small image file writes, which is typically milliseconds.

**Rules — every implementer and reviewer MUST enforce these:**

1. **Transaction opens immediately before the first DB write.** `conn.autoCommit = false` happens at step 12, not earlier. Setting it at the top of `processDocument` "for consistency" is forbidden — that would silently widen the lock window.
2. **All CPU-heavy steps stay outside txn.** Parse (step 6), AosParser (step 7), Chunk (step 9) are the three expensive operations. None may be moved into the transaction window. If a future refactor introduces new CPU work (e.g., an embeddings step moved from Phase 3 into Phase 2), it must go into Phase A, not Phase B.
3. **No hidden DB writes before step 12.** `findByHash` at step 3 is a `SELECT`, which does NOT acquire a writer lock even though it uses a writable connection — this is intentional. Any method that performs an `INSERT`/`UPDATE`/`DELETE` must live at step 13 or later. If a new helper method is added, audit it for writes. Repositories are constructed inside the `use { }` block alongside the `conn`, but their construction does NOT implicitly write anything (verify this in tests).
4. **Image file writes remain inside txn intentionally.** They happen at step 14 because `ImageExtractor` needs `documentId` (known only after step 13's insert) to compose `{imagesPath}/{documentId}/{filename}`. Do NOT try to move them outside the transaction "for symmetry with the source file write at step 5" — that would require either generating a temporary path and renaming (race conditions on directory rename), or generating `documentId` before the insert (breaks auto-increment semantics). The current placement is correct and documented here so future editors don't undo it.
5. **Checkpoint A, Checkpoint B, and Empty-content check all fire OUTSIDE txn.** They throw before any DB write has happened. The compensation path for these throws is simpler than for in-txn throws: just delete the source file via `compensateFailedUpload(sourceFile, documentId = null)`. There is NO `conn.rollback()` call because no transaction was open. Tests must cover this distinction.
6. **Anti-pattern — forbidden:** wrapping the entire `processDocument` body in `database.connect().use { conn -> conn.autoCommit = false; ... try { ... } }`. This looks simpler but re-widens the transaction. The correct pattern is: open the connection for the whole method, but only flip `autoCommit = false` at step 12.

**Connection scope vs transaction scope.** These are TWO different windows:
- **Connection scope**: the entire `database.connect().use { conn -> ... }` block. Runs from just after step 1 (validation) to just after step 20 (return). The connection is held for the whole operation including parse — that's fine because a connection with `autoCommit = true` does not hold any lock.
- **Transaction scope**: the `conn.autoCommit = false` window from step 12 to step 19. Narrow by design.

Holding the connection across the whole operation (but with autoCommit=true) is strictly OK in SQLite — connections are cheap and don't lock unless a write happens. It avoids re-acquiring a connection between steps 11 and 12.

Alternatively, a stricter two-connection pattern could be used: one connection for the read at step 3 (`findByHash`), close it, do CPU work outside any connection, then open a second connection at step 12 for the writes. This is marginally more pure but adds complexity and an extra `connect()` call. **Not chosen for Phase 2** — single-connection-held-across-operation with narrow autoCommit window is simpler and sufficient.

#### Connection lifecycle and ownership (acceptance criteria, not optional)

**The rule.** `DocumentService` is constructed once at application startup. Every stateful DB resource — `java.sql.Connection`, the three repositories, and `ImageExtractor` — is **operation-scoped**: created at the start of each public service call, used inside exactly one unit of work, and torn down before the call returns. No shared long-lived connection.

Under a shared-connection model, concurrent `processDocument` calls would corrupt each other's `autoCommit` / transaction state, and the Task 13 race-condition dedup (which presumes independent connections) would not work. Per-operation scope is the only safe pattern.

**Pattern.** `DocumentService` takes `Database` (the factory from Phase 1) in its constructor — NOT a `Connection`, NOT pre-built repositories, NOT `ImageExtractor`. Every public method opens its own connection via `database.connect().use { conn -> ... }` and constructs repositories (and `ImageExtractor` when needed) inside that block:

```kotlin
class DocumentService(
    private val database: Database,
    private val parserFactory: ParserFactory,
    private val aosParser: AosParser,
    private val chunkingService: ChunkingService,
    private val documentsPath: String,  // from config.documentsPath (Task 4)
    private val imagesPath: String      // from config.imagesPath (Task 4)
) {
    suspend fun processDocument(filename: String, fileBytes: ByteArray): UploadResult =
        withContext(Dispatchers.IO) {
            val sanitizedName = validateAndSanitizeUpload(filename, fileBytes)  // step 1 — before connect
            database.connect().use { conn ->
                val documentRepo = DocumentRepository(conn)
                val chunkRepo = ChunkRepository(conn)
                val imageRepo = ImageRepository(conn)
                // Phase A (steps 2–11): autoCommit stays true. On throw → compensate source file, no rollback.
                // Phase B (steps 12–19): autoCommit = false → insert Document → ImageExtractor(imagesPath, imageRepo)
                //     → chunks → counters → commit → return Created.
                // Exception in Phase B → rollback + compensate + rethrow. Finally → restore autoCommit = true.
                // Full pipeline body: see the Task 13 pipeline list and "Transaction scope — narrow txn" subsection.
            }
        }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        database.connect().use { conn -> /* transactional delete + filesystem compensation */ }
    }

    suspend fun getDocument(id: Long): Document? = withContext(Dispatchers.IO) {
        database.connect().use { conn -> DocumentRepository(conn).findById(id) }
    }

    suspend fun listDocuments(): List<Document> = withContext(Dispatchers.IO) {
        database.connect().use { conn -> DocumentRepository(conn).findAll() }
    }
}
```

Step 1 validation runs **before** `database.connect()` so rejected uploads don't acquire a connection.

**Forbidden:** injecting a pre-opened `Connection`, a repository, or an `ImageExtractor` into any long-lived object (route handler, DocumentService field, module-level val); sharing a connection across coroutines or threads; caching a connection inside `Database`. Phase 1's `Database.connect()` returns a fresh connection per call via `DriverManager.getConnection` — this task depends on that behavior; re-examine the contract if `Database` is ever refactored.

**Test infrastructure.** `DocumentServiceTest` cannot use `:memory:` — each `connect()` on `:memory:` creates a separate empty DB. Use a file-based temp DB in a JUnit `@TempDir`: `Database("${tempDir}/test.db")`. Apply migrations once at `setUp` on a dedicated connection, then pass the same `Database` instance to the service. `MigrationsTest` and repository tests can still use `:memory:` because each uses exactly one connection.

#### Empty content contract (step 11 — explicit 400, not silent success)

A document that parses cleanly but produces zero chunks AND zero images is a valid file but a useless one for RAG. Locking this behavior explicitly prevents drift between "valid upload" and "indexable document" and avoids zombie rows in `documents` that will never be retrieved by any query.

**Reachability analysis.** Under the existing Phase 2 parser rules:
- WordParser (Task 8) creates a synthetic trailing empty TextBlock for images that appear after the last text block, so "document with only images" still produces ≥1 TextBlock.
- PdfParser (Task 9) creates a synthetic empty TextBlock with `pageNumber = N` for image-only pages, so "PDF with only images" still produces ≥1 TextBlock.
- ChunkingService (Task 10) explicitly preserves synthetic empty blocks that carry imageRefs — they survive as standalone chunks.

Therefore, after chunking, `chunks.isEmpty()` is equivalent to `images.isEmpty()`. The only reachable "empty" state is **both zero**. A hypothetical "0 chunks + N images" case is unreachable — if it ever occurs, it means a parser violated its contract, and Checkpoint A / Checkpoint B will catch the orphaned images before step 11.

**Decision — reject with 400, don't silently accept.**

Three options were considered:
- **Accept 0 chunks as valid** (201 Created with `chunkCount: 0`): simpler, but creates invisible-to-search documents. Admin sees "uploaded successfully" and wonders why search never finds anything. Silent failure mode.
- **Accept-but-flag** (201 with a `warning` field): non-standard response shape; admins still have to read the warning; adds a sometimes-present field that clients must handle.
- **Reject with typed 400** ← chosen. Direct feedback, reuses the existing rollback/compensation infrastructure, consistent with the established error vocabulary (`InvalidUploadException`, `UnreadableDocumentException`).

**Why a distinct exception from `UnreadableDocumentException`.** Semantically different: an unreadable document cannot be parsed by the library; an empty document was parsed fine but contributed nothing. The admin response is different too — "the file is corrupted, regenerate it" vs "the file is blank or needs OCR". Different `error` discriminator at the route layer lets clients show different UX.

**Check placement — step 11, after Checkpoint B.** Checkpoint B (step 10) guarantees that `chunks.imageRefs` and `ParsedContent.images` are consistent with each other. Only after that do we know the empty state is a "real" empty, not a parser linkage bug. The check is a two-line:

```kotlin
// Step 11 — empty content guard (Phase A, outside transaction)
if (chunked.isEmpty() && images.isEmpty()) {
    throw EmptyDocumentException(EmptyDocumentReason.NoExtractableContent)
}
```

No intermediate state, no partial commit, no threshold configuration (hardcoded `isEmpty()` check). If future phases want to distinguish "blank document" from "scanned PDF without OCR" for better messaging, the sealed `EmptyDocumentReason` class is extensible — add variants without breaking callers.

**Side-effect guarantee.** `EmptyDocumentException` is thrown at step 11 — **outside the transaction** (Phase A). No DB writes have happened yet (no Document row, no chunks, no images). The cleanup is filesystem-only: `compensateFailedUpload(sourceFile, documentId = null)` deletes the source file at `{documentsPath}/{hash}.{ext}`. There is NO `conn.rollback()` call because no transaction is open. This is simpler than the wide-txn alternative — same user-visible outcome (400 empty_content, no residue), less work at the exception path.

**Logging.** Empty content is user error, not server fault. Log at INFO with sanitized filename and reason code. Never ERROR. This matches the logging rule for `UnreadableDocumentException`.

#### Atomic source file write — temp + move (step 5)

The source file at `{documentsPath}/{hash}.{ext}` is a shared resource on the filesystem that must never be observed in a partial or inconsistent state. A naive `Files.write(finalPath, fileBytes)` leaves a half-written file at the final path if the write is interrupted (disk full, process SIGKILL, power loss, IOException mid-stream). Under the hash-based storage scheme, a partial file at the final path is particularly dangerous: a subsequent upload of the same bytes would `findByHash` → miss → try to "save" → find a partial file that nothing in the DB references.

**The rule.** Step 5 writes bytes to a **temp path in the same directory** as the final target, then performs an **atomic move** to the final path. Direct writes straight to the final target path are forbidden as an anti-pattern.

**Concrete pattern:**

```kotlin
val finalPath = Paths.get(documentsPath, "${hash}.${ext}")
val tempPath = Paths.get(documentsPath, "${hash}.${ext}.tmp.${UUID.randomUUID()}")

// 1. Write bytes to the temp path. Partial write on this path is acceptable —
// the temp path is unique per upload (UUID suffix) and will be cleaned up on
// any failure below, never mistaken for a valid source file.
try {
    Files.write(tempPath, fileBytes)
} catch (e: IOException) {
    // Partial temp file may exist; clean up idempotently and rethrow.
    runCatching { Files.deleteIfExists(tempPath) }
    throw e
}

// 2. Atomic move temp → final. On POSIX this maps to rename(2), atomic by the
// kernel. On Windows NTFS this maps to MoveFileEx with MOVEFILE_WRITE_THROUGH.
try {
    Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
} catch (e: FileAlreadyExistsException) {
    // Race hit: a concurrent upload of the same bytes already landed the final
    // file. Because storage is hash-based, the two files are BYTE-IDENTICAL —
    // no data mismatch possible. Delete our temp (no-op of bytes) and proceed.
    // The Document insert at step 13 will either succeed (if we get there before
    // the winner inserts) or hit the UNIQUE constraint and take the race-dedup
    // path to return Duplicate. Either way is correct.
    runCatching { Files.deleteIfExists(tempPath) }
} catch (e: AtomicMoveNotSupportedException) {
    // Must not happen: temp and final are in the SAME directory, so the move
    // is same-filesystem. If this fires, the deployment has misconfigured
    // documentsPath to straddle filesystems somehow. Fail loudly with setup error.
    runCatching { Files.deleteIfExists(tempPath) }
    throw IllegalStateException(
        "Atomic move not supported in '$documentsPath' — temp and final must be on the same filesystem", e)
}
```

**Why temp path has a UUID suffix.** Two concurrent `processDocument` calls with identical bytes both pass `findByHash` at step 3 (neither has inserted yet), then both attempt step 5. If the temp name were a deterministic function of the hash (e.g., `{hash}.{ext}.tmp`), both would write to the same temp file and stomp on each other mid-stream. UUID suffix makes each temp path unique per call; both uploads get clean separate temp files, and then race only at the atomic-move step where the race is benign (bytes are identical).

**Why `ATOMIC_MOVE` without `REPLACE_EXISTING`.** The `REPLACE_EXISTING + ATOMIC_MOVE` combination is documented as implementation-specific across platforms — some JDK + OS combinations degrade it to a non-atomic copy+delete. Since our race-hit case has identical bytes on both sides, we do not **need** to replace: we catch `FileAlreadyExistsException` explicitly and treat it as a no-op of bytes. This avoids depending on the REPLACE_EXISTING semantics at all and keeps the atomicity guarantee clean on every platform.

**fsync is NOT in scope for Phase 2.** POSIX `rename(2)` is atomic for visibility (readers see either old or new, never partial) but not for durability (post-crash, the rename might not be on disk yet). Adding `fsync(tempfd)` before rename and `fsync(dirfd)` after rename would close that gap, but:
- Phase 2 does not have strong crash-durability requirements (single-admin RAG tool, not a financial transaction system).
- SQLite WAL already fsyncs at commit time, so the authoritative DB state survives crashes.
- An out-of-sync source file after a crash is recoverable via offline re-index — the admin can delete the orphaned Document row and re-upload.
- The JVM has no portable API for directory fsync; the workaround is JNI or `FileChannel.force(true)` on a `FileChannel.open(dirPath, READ)`, which is platform-quirky.

Leave fsync as a `// TODO(phase-4+): add fsync for crash durability if needed` comment on the move code. Revisit if an incident shows it's necessary.

**Temp file leak on process crash.** If the JVM is killed between step 5.1 (temp write) and step 5.2 (atomic move), the temp file `{hash}.{ext}.tmp.{uuid}` remains on disk with no DB row referencing it. This is an orphan. Address via a **startup orphan scan** in Application.kt (Task 14): at process start, walk `documentsPath` and delete any files matching `*.tmp.*`. This is safe because:
- Phase 2 architecture mandates single backend process per `documentsPath` — no concurrent writer whose temp file we could mistakenly delete.
- Temp files are only ever valid mid-`processDocument`-call, and by startup no processDocument is in flight.
- The scan is cheap (one `Files.list` + filter) and idempotent.

**Anti-patterns — explicitly forbidden:**
- ❌ `Files.write(finalPath, fileBytes)` or equivalent direct write to the final target path.
- ❌ `FileOutputStream(finalPath).use { it.write(bytes) }` — same problem, partial file visible at final path mid-write.
- ❌ Temp path without UUID suffix — concurrent identical-bytes uploads would collide.
- ❌ Temp path in a different directory (e.g., `/tmp/...`) — cross-filesystem move is not atomic, and `AtomicMoveNotSupportedException` fires.
- ❌ Catching `FileAlreadyExistsException` and silently proceeding WITHOUT deleting the temp file — leaks a temp file per race hit.
- ❌ Using `REPLACE_EXISTING` as a substitute for `FileAlreadyExistsException` handling — works but depends on platform-specific atomicity semantics.

#### Upload validation and filename normalization

A client-provided filename from multipart is untrusted input. It may be empty, contain path separators, traverse out of `documentsPath`, contain control characters, or point at an unsupported format. Every invalid upload must be rejected **before any disk write or DB call**, with a distinct, API-visible outcome. The filename the service trusts on disk is always server-generated.

**Design choices:**

- **Typed exception, not a sealed result.** `Duplicate` is a valid business outcome (caller branches on it). `Invalid` is bad input (caller should fail fast). Introducing a third `UploadResult.Invalid` variant would dilute the success path. Use `InvalidUploadException` with a sealed `InvalidUploadReason` so the vocabulary is explicit but the happy path stays `Created | Duplicate`.
- **Storage name is hash-based.** Disk layout: `{documentsPath}/{sha256}.{ext}` (e.g., `{documentsPath}/a1b2c3d4e5f6....docx`). No two files with different content can ever collide on disk — the storage name is derived from the content. The original client filename is preserved in `documents.filename` for display in the admin UI; the disk path is reconstructible from `{documentsPath}/{file_hash}.{file_type}` without a new column.
- **Defense in depth.** Validation lives in DocumentService, not AdminRoutes. Rationale: if any other code path (future admin CLI, migration scripts, tests) calls `processDocument`, it must go through the same validator. AdminRoutes is a thin mapper that catches `InvalidUploadException` and emits HTTP 400.
- **File size limit is hardcoded for Phase 2.** Use a `private const val MAX_UPLOAD_SIZE_BYTES = 50L * 1024L * 1024L` in DocumentService with a `// TODO(phase-4): make configurable via env var` comment. Making it a full config surface is scope creep for this phase.

**Sealed reason vocabulary and exception types:**

```kotlin
sealed class InvalidUploadReason(val code: String, val httpMessage: String) {
    data object EmptyFile : InvalidUploadReason(
        "empty_file",
        "Uploaded file is empty (0 bytes)"
    )
    data object MissingFilename : InvalidUploadReason(
        "missing_filename",
        "Filename is missing or empty"
    )
    data class UnsafeFilename(val detail: String) : InvalidUploadReason(
        "unsafe_filename",
        "Filename contains unsafe characters or path components: $detail"
    )
    data class UnsupportedExtension(val ext: String) : InvalidUploadReason(
        "unsupported_extension",
        "Unsupported file extension: '$ext'. Supported: docx, pdf"
    )
    data class FileTooLarge(val sizeBytes: Long, val limitBytes: Long) : InvalidUploadReason(
        "file_too_large",
        "File size $sizeBytes exceeds limit of $limitBytes bytes"
    )
}

class InvalidUploadException(val reason: InvalidUploadReason)
    : IllegalArgumentException(reason.httpMessage)

sealed class UnreadableReason(val code: String, val httpMessage: String) {
    data class CorruptedDocx(val detail: String) : UnreadableReason(
        "corrupted_docx",
        "The uploaded .docx file could not be parsed: $detail"
    )
    data class CorruptedPdf(val detail: String) : UnreadableReason(
        "corrupted_pdf",
        "The uploaded .pdf file could not be parsed: $detail"
    )
    data class EncryptedDocument(val format: String) : UnreadableReason(
        "encrypted_document",
        "The uploaded $format is password-protected or encrypted and cannot be parsed"
    )
}

class UnreadableDocumentException(
    val reason: UnreadableReason,
    cause: Throwable? = null
) : RuntimeException(reason.httpMessage, cause)

sealed class EmptyDocumentReason(val code: String, val httpMessage: String) {
    data object NoExtractableContent : EmptyDocumentReason(
        "no_extractable_content",
        "The uploaded document contains no text, tables, or images that can be indexed. " +
        "It may be a blank document, a scanned PDF without OCR, or a file with only metadata."
    )
}

class EmptyDocumentException(val reason: EmptyDocumentReason)
    : RuntimeException(reason.httpMessage)
```

Place all three exception hierarchies in the same file as `DocumentService` (or a neighboring `UploadResult.kt`) so the full upload/parse error vocabulary is colocated with the service that propagates them.

**Distinction between the three exception types:**
- `InvalidUploadException` is thrown at **step 1** (before any I/O or DB call). Zero side effects. Caused by bad metadata — filename, size, extension, empty body. The contract guarantees it never leaves residue. Route maps to `400 Bad Request` with `error: "invalid_upload"`.
- `UnreadableDocumentException` is thrown from **inside the parser** at **step 6**, by WordParser or PdfParser, when the file bytes pass filename/extension/size validation but cannot be structurally parsed by POI or PDFBox. **Outside the transaction** (Phase A) — compensation is filesystem-only: delete the source file, no `rollback()`. Route maps to `400 Bad Request` with `error: "unreadable_document"`. User error (corrupted/encrypted client file), not server error.
- `EmptyDocumentException` is thrown at **step 11** (after parse, AosParser, Checkpoint A, chunking, Checkpoint B), when the pipeline produced zero chunks AND zero images. **Also outside the transaction** (Phase A) — filesystem-only compensation. The file was valid, readable, and structurally sound, but contained nothing indexable. Route maps to `400 Bad Request` with `error: "empty_content"`. User feedback: "this document has nothing to index — is it blank or a scanned PDF without OCR?" Distinct from unreadable — the file parsed successfully, it just contributed nothing to the RAG corpus.
- Do NOT merge these into a single exception hierarchy. All three are 400 at the route layer, but they have different throw points and side-effect guarantees (`InvalidUpload` = zero side effects, thrown before Phase A starts; `Unreadable` and `Empty` = source file on disk, but no DB state, because they throw in Phase A before the transaction opens). Collapsing them into one type would blur the vocabulary and make metrics/logs less useful.

`UnreadableDocumentException` is thrown by WordParser (Task 8) and PdfParser (Task 9) at step 6. `EmptyDocumentException` is thrown by DocumentService at step 11. `InvalidUploadException` is thrown by DocumentService at step 1. All three are thrown in Phase A and propagate through the Phase A cleanup path (delete source file, no rollback) — DocumentService does not need to catch and re-throw them. The type definitions all live next to each other in Task 13 only because that keeps the API error vocabulary in one file.

**Validation pipeline (step 1, before any I/O or DB call):**

```kotlin
private fun validateAndSanitizeUpload(rawFilename: String, fileBytes: ByteArray): String {
    // 1. Empty bytes
    if (fileBytes.isEmpty()) {
        throw InvalidUploadException(InvalidUploadReason.EmptyFile)
    }

    // 2. Size cap
    if (fileBytes.size > MAX_UPLOAD_SIZE_BYTES) {
        throw InvalidUploadException(
            InvalidUploadReason.FileTooLarge(fileBytes.size.toLong(), MAX_UPLOAD_SIZE_BYTES)
        )
    }

    // 3. Missing filename
    val trimmed = rawFilename.trim()
    if (trimmed.isEmpty()) {
        throw InvalidUploadException(InvalidUploadReason.MissingFilename)
    }

    // 4. Strip any path components — take only the basename.
    // Handle BOTH separators because multipart uploads may come from Windows clients
    // that send raw backslashes (older IE/Edge legacy), and Unix clients that send slashes.
    val basename = trimmed.substringAfterLast('/').substringAfterLast('\\')
    if (basename.isEmpty()) {
        throw InvalidUploadException(InvalidUploadReason.MissingFilename)
    }

    // 5. Defense in depth: after basename extraction, the result must not still contain
    // separators or null bytes or control characters.
    if (basename.contains('/') || basename.contains('\\')) {
        throw InvalidUploadException(InvalidUploadReason.UnsafeFilename("path separators"))
    }
    if (basename.any { it == '\u0000' }) {
        throw InvalidUploadException(InvalidUploadReason.UnsafeFilename("null byte"))
    }
    if (basename.any { it.isISOControl() }) {
        throw InvalidUploadException(InvalidUploadReason.UnsafeFilename("control character"))
    }

    // 6. Reject "." and ".." and Windows-reserved names (CON, PRN, AUX, NUL, COM1-9, LPT1-9)
    // since the basename might be placed in other contexts later.
    if (basename == "." || basename == "..") {
        throw InvalidUploadException(InvalidUploadReason.UnsafeFilename("reserved name '$basename'"))
    }
    val stem = basename.substringBeforeLast('.', basename).uppercase()
    if (stem in windowsReservedNames) {
        throw InvalidUploadException(InvalidUploadReason.UnsafeFilename("Windows-reserved name '$stem'"))
    }

    // 7. Extension whitelist
    val ext = basename.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) {
        throw InvalidUploadException(InvalidUploadReason.UnsupportedExtension(""))
    }
    if (ext !in supportedExtensions) {
        throw InvalidUploadException(InvalidUploadReason.UnsupportedExtension(ext))
    }

    return basename
}

private companion object {
    const val MAX_UPLOAD_SIZE_BYTES = 50L * 1024L * 1024L  // TODO(phase-4): make configurable via env var
    val supportedExtensions = setOf("docx", "pdf")
    val windowsReservedNames = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}
```

The validator returns the **sanitized basename**. This becomes the value stored in `documents.filename` for display. It is NEVER used to derive a filesystem path.

**Storage name generation (step 4):**

```kotlin
private fun storageNameFor(hash: String, ext: String): String = "$hash.$ext"
```

Used only to compose `{documentsPath}/{storageName}` for disk writes. The service never accepts a client-derived name for a disk path.

**Side-effect guarantee on invalid upload.** `InvalidUploadException` is thrown at step 1, before:
- any hash computation (cheap, but still skipped),
- any `findByHash` call,
- any disk write,
- any DB transaction.

No filesystem compensation or rollback is needed because nothing has happened yet. The cleanup/rollback policy below does not apply to the invalid-upload path.

**Route layer — HTTP mapping for invalid uploads (implemented in Task 14).** `AdminRoutes` catches `InvalidUploadException` and responds with `400 Bad Request` and a JSON body:

```json
{
  "error": "invalid_upload",
  "reason": "unsafe_filename",
  "message": "Filename contains unsafe characters or path components: path separators"
}
```

The `reason` field comes from `InvalidUploadReason.code` and is one of the closed set listed above, so clients can programmatically branch. Do NOT return 500 (invalid input is not a server error), do NOT return 422 (we are not validating against a schema, we are rejecting at the boundary), do NOT silently accept and rename.

#### Deduplication contract (API-visible, not silent)

A duplicate (same SHA-256 of file bytes as an already-indexed document) is NOT silently skipped and NOT treated as success. It is an explicit, API-visible outcome distinct from both "created" and "failed". Admins must DELETE the existing document first if they want to re-index — re-upload of identical bytes is rejected.

**Service layer — sealed result type.** `processDocument` returns a sealed class, not a plain `Document`:

```kotlin
sealed class UploadResult {
    data class Created(val document: Document) : UploadResult()
    data class Duplicate(val existing: Document) : UploadResult()
}

suspend fun processDocument(filename: String, fileBytes: ByteArray): UploadResult
```

Rationale for a sealed result vs. throwing an exception on duplicate: exceptions are reserved for failure modes (parse errors, IO errors, DB errors) which flow through the rollback/compensation path. A duplicate is not a failure — it is a valid, expected outcome that the caller must branch on. Using a sealed result keeps the two paths distinct and forces every caller (routes, tests) to handle both explicitly.

**Side-effect guarantee on dedup hit.** When `findByHash` returns a match at step 3:
- No file is written to `documentsPath` (step 5 is never reached).
- No DB mutation occurs (steps 12–19 never run; transaction is never opened).
- The existing `Document` is returned unchanged — `indexed_at`, `chunk_count`, `image_count` on the existing row are NOT touched.
- No filesystem compensation is needed because nothing was written. The cleanup/rollback policy below does not apply to the dedup-hit path.

Because hashing at step 2 happens before the file save at step 5 and before the transaction opens at step 12, the dedup-hit path is genuinely zero-effect — no temp files to clean up, no state to reason about.

**Route layer — HTTP mapping (implemented in Task 14).** `POST /api/admin/documents` maps `UploadResult` to HTTP:
- `Created(doc)` → `201 Created` with the `Document` as JSON body.
- `Duplicate(existing)` → `409 Conflict` with JSON body:
    ```json
    {
        "error": "duplicate_document",
        "message": "A document with identical content has already been indexed. Delete the existing document first if you want to re-index.",
        "existing": {
            "id": 42,
            "filename": "troubleshooting_v2.docx",
            "indexed_at": "2026-03-28T14:12:03Z"
        }
    }
    ```
    The `existing` object includes enough info for the admin UI to link to the existing document and decide whether to DELETE + re-upload.

Do NOT map `Duplicate` to `200 OK` (that would conflate create and dedup semantically), and do NOT map it to `500` (that would conflate duplicate with actual failure).

#### Race condition handling (DB-level dedup defense)

`findByHash` at step 3 is the primary dedup check, but it cannot prevent a race: two concurrent `processDocument` calls with identical bytes can both observe `findByHash` returning null before either inserts. Without DB-level enforcement, both would proceed and create two rows with the same hash. The V003 migration (Task 3) adds `CREATE UNIQUE INDEX idx_documents_file_hash_unique ON documents(file_hash)` as the last line of defense. DocumentService must translate a unique-hash violation at insert time back into the same `UploadResult.Duplicate` outcome — the user-visible contract is identical regardless of whether dedup was caught at step 3 (findByHash) or step 13 (insert).

**Detection helper:**

```kotlin
private fun isUniqueFileHashViolation(e: SQLException): Boolean {
    // SQLite primary result code for constraint violations is 19 (SQLITE_CONSTRAINT).
    // The extended code for UNIQUE is 2067 (SQLITE_CONSTRAINT_UNIQUE).
    // The JDBC driver surfaces this in the message as "UNIQUE constraint failed: documents.file_hash".
    val msg = e.message ?: return false
    return msg.contains("UNIQUE", ignoreCase = true) &&
           msg.contains("documents.file_hash")
}
```

Matching on both `UNIQUE` and the fully-qualified column `documents.file_hash` prevents misidentifying unrelated UNIQUE violations (e.g., on `users.username`) as a file-hash dedup hit.

**Handling inside the transaction (at step 13, `documentRepository.insert` — the first write inside Phase B):**

```kotlin
try {
    val insertedId = documentRepository.insert(newDoc)
    // ... continue pipeline
} catch (e: SQLException) {
    if (isUniqueFileHashViolation(e)) {
        // Race condition: another upload of the same bytes committed first.
        connection.rollback()
        compensateFailedUpload(savedSourceFile = uploadedPath, documentId = null)
        val winner = documentRepository.findByHash(hash)
            ?: error("UNIQUE constraint on file_hash fired but findByHash returned null — broken invariant")
        return UploadResult.Duplicate(winner)
    }
    throw e  // any other SQL error propagates as a genuine failure through the normal rollback path
}
```

Note: the `findByHash` call happens **after** `rollback()` (outside the rolled-back transaction) so it sees the committed winning row. The transaction is over at that point; the service simply reads and returns.

**Filesystem cleanup on race hit.** The source file at `{documentsPath}/{hash}.{ext}` was written at step 5 before the insert failure. Because storage names are hash-based (see "Upload validation and filename normalization" above), both the losing upload and the already-committed winning upload would use the **same** storage path — their file bytes are identical by definition (same hash). So `compensateFailedUpload` deleting the file at that path would also delete the winner's file on disk. **Solution:** on race hit, do NOT call the standard `compensateFailedUpload` for the source file; the file on disk is already the correct content for the winning document and is already referenced by the winning row. Simply leave the file alone. The `documentId = null` argument to `compensateFailedUpload` is therefore called with the source file also set to null — i.e., it becomes a no-op for this specific path. Document this explicitly in the code with a comment, because it is a subtle inversion of the normal compensation rule and must not be "fixed" by a future editor who assumes leaks.

**The race-hit outcome is NOT a failure.** From the caller's perspective, `UploadResult.Duplicate` means "this hash already exists" — indistinguishable from a step-3 dedup hit. The only observable difference is:
- Step-3 dedup: zero side effects ever, not even a file write.
- Step-6 dedup (race): the losing upload did briefly write to the same hash-based storage path as the winner. Because both files have identical bytes, this is an idempotent overwrite — no leak, no conflict. Left in place.

Both return `UploadResult.Duplicate(winner)`; neither logs an ERROR. Log at INFO level when the race path is taken, with a message like "Concurrent upload race detected, dedup caught at DB layer; winner id=$winner.id" — this is observability, not an alert.

#### Image linkage integrity validation (two checkpoints)

Enforce the Task 5 contract at **two** distinct points — once at the parser boundary (strict uniqueness) and once after chunking (set equality, duplicates allowed because ChunkingService legitimately replicates imageRefs across split chunks per Task 10).

**Checkpoint A — post-AosParser, pre-chunking (strict, including uniqueness):**

```kotlin
private fun validateParsedLinkage(parsed: ParsedContent) {
    val declaredRefs = parsed.textBlocks.flatMap { it.imageRefs }
    val declaredSet = declaredRefs.toSet()
    val availableSet = parsed.images.map { it.filename }.toSet()

    val orphanedRefs = declaredSet - availableSet
    val unreferencedImages = availableSet - declaredSet
    val duplicateRefs = declaredRefs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys

    require(orphanedRefs.isEmpty()) { "Parser/AosParser produced TextBlocks referencing images that were not extracted: $orphanedRefs" }
    require(unreferencedImages.isEmpty()) { "Parser/AosParser extracted images not referenced by any TextBlock: $unreferencedImages" }
    require(duplicateRefs.isEmpty()) { "Parser/AosParser referenced the same image from multiple TextBlocks: $duplicateRefs" }
}
```

This runs as step 8 in the pipeline — between step 7 (AosParser) and step 9 (Chunk). Catches bugs in WordParser/PdfParser/AosParser immediately, with a message pointing at the right layer. **Outside the transaction** (Phase A): a violation throws `IllegalStateException` which triggers only source-file compensation, no `conn.rollback()` call because no transaction is open yet.

**Checkpoint B — post-chunking, pre-persist (set equality only):**

```kotlin
private fun validateChunkedLinkage(chunked: List<TextBlock>, images: List<ImageData>) {
    val declaredSet = chunked.flatMap { it.imageRefs }.toSet()
    val availableSet = images.map { it.filename }.toSet()

    val orphanedRefs = declaredSet - availableSet
    val unreferencedImages = availableSet - declaredSet

    require(orphanedRefs.isEmpty()) { "Chunks reference images that were not extracted: $orphanedRefs" }
    require(unreferencedImages.isEmpty()) { "Extracted images are not referenced by any chunk: $unreferencedImages" }
}
```

No duplicate check here — duplicates across chunks are expected and correct (ChunkingService replicates imageRefs when splitting a parent, per the Task 10 rule). This runs as step 10 in the pipeline, right after chunking and before the empty-content check (step 11). Also **outside the transaction** (Phase A).

Violations at either checkpoint throw `IllegalStateException`. Because both checkpoints run in Phase A (before the transaction opens at step 12), the cleanup is filesystem-only: `compensateFailedUpload(sourceFile, documentId = null)` deletes the source file at `{documentsPath}/{hash}.{ext}`. No `conn.rollback()` call and no DB compensation — there is nothing to roll back. The exception still propagates to AdminRoutes where it maps to `500 Internal Server Error` (parser/chunking bugs are server faults, not user-facing 4xx). Strict on purpose — a violation indicates a parser or chunking bug and should be caught in Phase 2 testing, not silently masked in production.

**Constructor** — see the full signature in "Connection lifecycle and ownership" above. DocumentService takes the `Database` factory plus stateless dependencies (`ParserFactory`, `AosParser`, `ChunkingService`) and both config paths. It does NOT take repositories, a `Connection`, or an `ImageExtractor` — all four are operation-scoped and constructed inside each public method.

Do NOT compose `"{dataPath}/documents"` inside DocumentService or take `dataPath` as a constructor param — consume `config.documentsPath` directly so path resolution lives in exactly one place (application.conf via Task 4). Same rule for `config.imagesPath`.

Methods (all are `suspend` — see "Execution model — blocking work on Dispatchers.IO" below):
- `suspend fun processDocument(filename: String, fileBytes: ByteArray): UploadResult` (sealed — `Created` or `Duplicate`, see deduplication contract above)
- `suspend fun deleteDocument(id: Long)` — removes DB records + files on disk
- `suspend fun getDocument(id: Long): Document?`
- `suspend fun listDocuments(): List<Document>`

#### Execution model — blocking work on Dispatchers.IO (acceptance criteria, not optional)

Every public method of `DocumentService` performs blocking operations — filesystem I/O (source file save, image writes), Apache POI / PDFBox library calls (parse), JDBC calls (every repository method). Ktor request handlers with the Netty or CIO engine run on an **event loop thread pool**. Blocking on that thread pool causes head-of-line blocking for other requests, thread starvation under load, and cascading slowdowns across the application. This must never happen by accident.

**The rule.** Every public `suspend` method of `DocumentService` that touches blocking resources starts with:

```kotlin
suspend fun processDocument(...): UploadResult = withContext(Dispatchers.IO) {
    // validation, hash, findByHash, file save, parse, ..., commit, return
}
```

The `withContext(Dispatchers.IO)` boundary wraps the **entire** method body. No blocking code runs before the boundary; no "optimization" that skips the boundary for simple read methods is allowed.

**Why `Dispatchers.IO` and not a custom dispatcher.** `Dispatchers.IO` is designed for blocking work: it's backed by an elastic pool that scales to 64 threads by default (configurable via `kotlinx.coroutines.io.parallelism`), distinct from the event loop, and shared across the application. That is exactly the right shape for our workload:
- POI/PDFBox parsing: blocking I/O + CPU → IO.
- JDBC calls (SQLite): blocking I/O → IO.
- Filesystem writes (source file, images): blocking I/O → IO.
- AosParser, ChunkingService, Checkpoints: pure CPU, cheap (microseconds for typical docs). Technically these would belong on `Dispatchers.Default`, but running them on IO is fine — the IO dispatcher is elastic, a brief CPU burst does not starve anything, and splitting into a nested `withContext(Dispatchers.Default)` inside the IO block adds context-switch overhead and error-handling complexity for no real benefit.

**Why one `withContext` wrapping the whole body, not fine-grained boundaries.**
- Context switches are not free. Switching between IO and Default on every step adds dozens of microseconds per step, which is more than the step itself costs for most steps.
- A single top-level boundary is dead simple to enforce and reason about: one rule, one line of code to find, one place to review.
- Splitting the pipeline across dispatcher boundaries would complicate exception-path reasoning — each `withContext` block is its own structured-concurrency scope, and failures propagating across boundaries need to interleave correctly with the transaction window and compensation helper. One boundary eliminates that class of mistake.

**The real `Connection` invariant (NOT "same physical thread").** The JDBC `Connection` obtained at the top of `processDocument` is **operation-scoped** — it lives for exactly one service call and is not shared with any other coroutine, thread, or service call. The invariants this gives us are:

1. **No concurrent access.** No two coroutines or threads ever touch the same `Connection` simultaneously. This is the only hard requirement of the JDBC spec — `Connection` is not thread-safe for concurrent use, but it IS safe for sequential use across threads as long as there is happens-before ordering between the accesses.
2. **Sequential code inside the `withContext(Dispatchers.IO)` block.** All statements that touch the `Connection` run sequentially in one suspend function, without any `launch { }` / `async { }` inside that would introduce parallel access.
3. **Happens-before across suspension points.** Kotlin coroutines guarantee memory-visibility ordering when a continuation resumes (even if it resumes on a different worker thread of `Dispatchers.IO`). Sequential suspend code that touches the `Connection` and transaction state is observed consistently regardless of which physical IO worker the continuation happens to land on.

**Same physical thread is NOT required and must not be asserted.** A common misconception is that JDBC connections are "tied to" the thread that created them — for general JDBC and specifically for SQLite JDBC (xerial `sqlite-jdbc`), this is not true. The spec forbids **concurrent** access, not **cross-thread** access. The `Dispatchers.IO` pool has 64 workers by default, and a coroutine's continuation may legitimately resume on a different worker after a suspension point; that's a normal, safe execution pattern for JDBC work inside a single `withContext` block.

Tests MUST NOT assert "parser thread === repository thread" or similar same-thread conditions. Asserting such an invariant would:
- Encode a false runtime property (coroutines do not guarantee it).
- Break on any JVM or Kotlin-coroutines update that changes how IO dispatch schedules continuations.
- Be brittle under load (the test might happen to pass when there's low pool pressure and fail under parallel test execution).
- Give no real safety benefit — the actual safety comes from the operation-scoped Connection + sequential access, NOT from thread identity.

The dispatcher regression guard tests below assert the correct invariants: (a) blocking work runs off the calling `runBlocking` thread, (b) no concurrent access is introduced, (c) the Connection's lifetime matches one operation. They do NOT assert same-thread execution across pipeline steps.

**Why not inject `CoroutineDispatcher` for testability.** Not in Phase 2 scope. `Dispatchers.IO` is hardcoded. Tests use `runBlocking { }` on the real IO dispatcher and assert dispatcher switching via thread-identity comparison (see test section). If a future phase wants `UnconfinedTestDispatcher` for controlled coroutine testing, injection can be added as a single ctor parameter with no API break.

**Route layer policy (implemented in Task 14).** Ktor handles `suspend` route handlers natively — calling `documentService.processDocument(...)` from a route handler is standard. The route handler itself does **NOT** wrap the service call in `withContext(Dispatchers.IO)`. The service owns the dispatcher contract; duplicating it at the route layer is error-prone (easy to forget on a new endpoint) and adds a redundant context switch. If a reviewer sees `withContext(Dispatchers.IO) { documentService.X() }` in a route, that is a red flag — the service is supposed to already be on IO.

**Anti-patterns — explicitly forbidden:**
- ❌ A public method of `DocumentService` that is not `suspend` and touches JDBC, filesystem, or parser library. Even "cheap" single-query methods like `getDocument` and `listDocuments` must be `suspend`-wrapped — they are still blocking JDBC calls.
- ❌ A `suspend` method of `DocumentService` that does NOT wrap its body in `withContext(Dispatchers.IO)`. `suspend` alone does NOT move work off the calling thread — it only allows suspension. Without an explicit dispatcher switch, the body runs on whichever dispatcher the caller is on, which for a Ktor route handler is the Netty event loop.
- ❌ `runBlocking { ... }` inside a route handler or service method to call blocking code. This defeats the purpose of `suspend`.
- ❌ `withContext(Dispatchers.Default)` for blocking I/O. `Default` is sized for CPU work (typically num-cores threads) and is NOT elastic — blocking on `Default` can starve the entire CPU-bound work pool.
- ❌ Launching work with `GlobalScope.launch { ... }` or a detached scope. Upload is synchronous per the Phase 2 execution model (Task 14) — fire-and-forget is wrong and also loses error propagation.

**Testability — thread-identity regression guard.** The canonical test: inject a mock parser that captures `Thread.currentThread()` at the moment `parse()` is called. In `runBlocking { }` the calling thread is the `runBlocking` event loop thread. If `processDocument` does `withContext(Dispatchers.IO)`, the parser runs on an IO worker thread, which is a **different** Thread instance. Assert `parseThread !== callingThread`. This test fails if a future editor removes or weakens the `withContext` call.

```kotlin
@Test
fun `processDocument runs parser off the calling thread`() = runBlocking {
    val callingThread = Thread.currentThread()
    lateinit var parseThread: Thread
    val parser = mockk<DocumentParser> {
        every { parse(any()) } answers {
            parseThread = Thread.currentThread()
            ParsedContent(/* minimal */)
        }
    }
    // ... wire service with this parser, @TempDir DB, etc. ...
    service.processDocument("test.docx", testBytes)
    assertNotSame(callingThread, parseThread,
        "parser ran on the runBlocking calling thread — withContext(Dispatchers.IO) is missing or removed")
}
```

The assertion is robust: it does not depend on Kotlin thread-name conventions (which can change across versions) or on whether IO dispatcher is backed by a specific pool. It only checks the invariant "the parser is not on the calling thread", which is exactly what matters.

#### Cleanup / rollback policy (acceptance criteria, not optional)

The pipeline produces multiple side effects across two persistence layers (filesystem and SQLite). A partial failure at any step MUST NOT leave orphaned files or partial DB state. This is explicit acceptance criteria for Phase 2, not a nice-to-have.

**Transaction boundary — narrow.** Wrap only the DB mutations of `processDocument` (Phase B, steps 12–19) in a single SQLite transaction via the **operation-scoped** `java.sql.Connection`. Phase A (steps 1–11) runs with `autoCommit = true` — no writer lock during parse/chunk. See "Transaction scope — narrow txn" above for the full rationale and rules.

- Set `conn.autoCommit = false` at **step 12** — immediately before `documentRepository.insert`. NOT at the top of `processDocument`. NOT at the top of the `use { }` block.
- Include in the transaction window: `documentRepository.insert` (step 13), every `imageRepository.insert` inside `ImageExtractor.saveImages` (steps 14–15), `chunkRepository.insertBatch` (step 17), `documentRepository.updateChunkCount` (step 18), `documentRepository.updateIndexedAt` (step 19).
- Exclude from the transaction window: hash computation, `findByHash` (SELECT, no lock), source file save, parse, AosParser, Checkpoint A, chunk, Checkpoint B, empty-content check. If any of these throws, there is no transaction to roll back — just filesystem compensation.
- On success at step 19: `conn.commit()`, then return `UploadResult.Created` at step 20.
- On any exception **inside the Phase B transaction window** (steps 13–19): `conn.rollback()`, then run filesystem compensation, then rethrow. The in-progress Document row and any written image files are cleaned up.
- On any exception **in Phase A** (steps 5–11, after source file save): NO `conn.rollback()` call (there is no open transaction). Run filesystem compensation (delete the source file) and rethrow.
- **Always** restore `conn.autoCommit = true` in a `finally` block, regardless of which phase threw. The outer `database.connect().use { conn -> ... }` block then closes the connection.

Because the connection is operation-scoped, `autoCommit = false` affects only this invocation — concurrent `processDocument` calls each have their own `conn` and cannot interfere with each other's transaction state. Because `autoCommit = false` is set as late as possible (step 12, not earlier), the SQLite writer lock is held for the minimum time needed.

ImageExtractor is constructed **inside** the transaction window (step 14) using the per-operation `ImageRepository`, and shares the same per-operation `Connection`. It must NOT open its own transaction. It is NOT constructed in Phase A — there is no need for it there.

**Filesystem compensation.** Filesystems are not transactional. Track every path the pipeline wrote and clean them up on failure:
- The uploaded source file at `{documentsPath}/{hash}.{ext}` (server-generated hash-based storage name from step 4; see "Upload validation and filename normalization" above).
- The per-document images directory `{imagesPath}/{documentId}/` (if any images were written before the failure).

Introduce a private helper:
```kotlin
private fun compensateFailedUpload(savedSourceFile: Path?, documentId: Long?)
```
It deletes the source file if present and recursively deletes `{imagesPath}/{documentId}/` if `documentId != null`. Both operations MUST be idempotent — missing files/dirs are not errors, they are logged at DEBUG level and swallowed. Do not let compensation throw; a compensation exception masks the real failure. Use `runCatching { ... }` around each delete.

`deleteDocument(id)` reuses the same helper to ensure one code path for both explicit deletion and failure cleanup. Ordering for explicit delete: begin transaction → delete DB rows (cascades handle chunks/images) → commit → filesystem compensation. If filesystem compensation partially fails after DB commit, log a WARN with the orphaned path and continue (DB is already authoritative).

**Failure-mode table — each row is a required test case.** The pipeline steps are numbered 1–20 above. Step numbers reference the "DocumentService orchestrates the full document processing pipeline" list at the top of Task 13. Steps 1–11 run in **Phase A** (outside transaction); steps 12–19 run in **Phase B** (inside transaction); step 20 is the success return. A failure in Phase A needs **filesystem compensation only** (no `rollback()`, there is no transaction). A failure in Phase B needs **both** `conn.rollback()` **and** filesystem compensation.

| Failure point | Phase | Expected state after failure |
|---|---|---|
| Invalid upload at step 1 (`InvalidUploadException` — any `InvalidUploadReason`) | A (pre-I/O) | Zero side effects: no hash computed, no file written, no `findByHash` call, no connection even acquired. Exception propagates to the caller (AdminRoutes maps to 400). No compensation needed. |
| Fail at step 3 (`findByHash` throws unexpected SQL error) | A | Exception propagates. No file on disk yet (step 5 not reached), no DB rows, no transaction opened. Nothing to compensate. |
| Fail at step 5.1 (temp write throws before atomic move, e.g., disk full mid-write) | A | Partial temp file at `{documentsPath}/{hash}.{ext}.tmp.{uuid}` is cleaned up inline by the step-5 code (inside a `runCatching { deleteIfExists }`). **The final path `{hash}.{ext}` is never touched** — this is the whole point of the temp+move strategy. No DB rows, no transaction. Exception propagates. A subsequent retry of the same upload finds a clean slate. |
| Step 5.2 race (atomic move throws `FileAlreadyExistsException`) | A | NOT a failure. A concurrent upload of the same bytes already wrote the final file. Our temp file is deleted, the pipeline continues to step 6 (parse). At step 13 the Document insert will either succeed (winner hasn't inserted yet) or hit the UNIQUE constraint (race-dedup path). Logged at DEBUG. |
| Fail at step 5.2 (atomic move throws `AtomicMoveNotSupportedException`) | A | Configuration error, not a race. Temp file is cleaned up, `IllegalStateException` is raised with a setup-error message. Propagates as 500. This row exists only as a defensive guard — it should never fire in a correctly-deployed environment where `documentsPath` is a regular same-filesystem directory. |
| Fail at step 6 (parser throws `UnreadableDocumentException` — corrupted/encrypted file) | A | Source file at `{documentsPath}/{hash}.{ext}` deleted via compensation. No DB rows. No `rollback()` called (transaction not open). `UnreadableDocumentException` propagates up unchanged. DocumentService logs at INFO (user input, not server fault). AdminRoutes maps to `400 Bad Request` with `UnreadableDocumentResponse` body. |
| Fail at step 6 (parser throws any other exception — NPE, raw IOException that slipped through the translator, etc.) | A | Source file deleted. No DB rows. No `rollback()` called. Exception is a genuine bug; propagates as 500. |
| Fail at step 7 (AosParser throws) | A | Source file deleted. No DB rows. No `rollback()` called. Exception propagates; if it is `IllegalStateException` from a bug it becomes 500, if the AosParser threw `UnreadableDocumentException` (rare, but allowed) it becomes 400. |
| Fail at step 8 (Checkpoint A throws `IllegalStateException` — orphaned/unreferenced/duplicate imageRefs) | A | Source file deleted. No DB rows. No `rollback()` called. Exception propagates as 500 (parser/chunking bug). |
| Fail at step 9 (chunk throws) | A | Source file deleted. No DB rows. No `rollback()` called. Exception propagates as 500. |
| Fail at step 10 (Checkpoint B throws `IllegalStateException`) | A | Source file deleted. No DB rows. No `rollback()` called. Exception propagates as 500. |
| Empty content at step 11 (both `chunked.isEmpty()` AND `images.isEmpty()` after Checkpoint B) | A | `EmptyDocumentException(NoExtractableContent)` thrown. Source file at `{documentsPath}/{hash}.{ext}` deleted via compensation. No DB rows, no `rollback()` called (transaction not open yet). Exception propagates up to AdminRoutes which maps it to `400 Bad Request` with `EmptyDocumentResponse` body (`error = "empty_content"`). NOT a 500. Logged at INFO, not ERROR. |
| Fail at step 13 (`documentRepository.insert` throws a non-race SQL error) | B | `conn.rollback()` called, source file deleted via compensation, no DB rows. Exception propagates as 500. (The race case — UNIQUE violation on file_hash — is handled separately as Duplicate, NOT a failure; see "Race condition handling" above.) |
| Fail at step 14 partway through image save (3 of 5 images fully atomic-moved to final path with DB rows, then 4th throws on temp-write or atomic-move) | B | Partial temp file for image 4 (if any) is deleted inline by saveImages before the throw propagates — the final image 4 path is never touched. `conn.rollback()` then removes all 3 image rows and the Document row. `compensateFailedUpload` recursively deletes `{imagesPath}/{documentId}/` removing the 3 fully-written final image files. Source file at `{documentsPath}/{hash}.{ext}` deleted. Exception propagates as 500. **Invariant: at no point is a partial image visible at a final image path** — temp+move keeps half-written bytes in `*.tmp.*` files which are either cleaned inline or caught by the recursive dir delete. |
| Fail at step 17 (`chunkRepository.insertBatch` throws) | B | `conn.rollback()` called (no chunks, no image rows), images directory removed, source file deleted, Document row gone. Exception propagates as 500. |
| Fail at step 18 (`updateChunkCount` throws) | B | Full rollback + compensation: no Document row, no chunks, no images in DB, filesystem clean. |
| Fail at step 19 (`updateIndexedAt` or `commit()` throws) | B | Same full rollback + compensation. |
| Successful path (no failure, reaches step 20) | — | Document row with correct `chunk_count`, `image_count`, `indexed_at` set to non-null; source file present at `{documentsPath}/{hash}.{ext}`; `documents.filename` column holds the **sanitized original client filename** (NOT the storage name); images present under `{imagesPath}/{documentId}/`; all chunk and image rows inserted. Transaction committed at step 19; `autoCommit` restored to true in finally. |
| Deduplication hit at step 3 (findByHash returns existing row) | — (pre-Phase-B) | `UploadResult.Duplicate(existing)` returned at step 3. Steps 4–20 never run. Zero side effects: no file written to `documentsPath`, no DB transaction opened, no mutation of the existing Document row. Not a failure — NO exception thrown, NO compensation run. |
| Race-condition dedup at step 13 (findByHash at step 3 returned null, then insert at step 13 hits UNIQUE violation on file_hash) | B → A | `UploadResult.Duplicate(winner)` returned after fetching the winner via a post-rollback `findByHash`. `conn.rollback()` called. Source file written at step 5 is **left in place** (not deleted) because the winning row already references the same hash-based path and the bytes are identical — see "Filesystem cleanup on race hit" in the Race condition handling section. No image directory existed yet (insert failed before image save at step 14). Not a failure — NO exception propagates, INFO log "Concurrent upload race detected" is emitted. The winner's existing files on disk are untouched. |

`indexed_at` being non-null is the authoritative "processing succeeded" marker. Do not set it until the transaction is ready to commit.

#### Document row lifecycle — success-only model (conscious design choice)

Phase 2 uses a **success-only** model for the `documents` table: a row exists in `documents` **if and only if** the upload pipeline completed all steps successfully and the transaction committed. There is no `status` column, no `pending`/`indexing`/`failed` states, no `failed_at` / `error_code` / `error_message` columns, no persisted failure trail. This is a **deliberate scope decision**, not an accidental side effect of the rollback policy.

**The invariant stated precisely:**
- A row in `documents` implies: source file on disk at `{documentsPath}/{file_hash}.{file_type}`, image files on disk under `{imagesPath}/{id}/` (if any), chunk rows in `chunks`, image rows in `images`, and `indexed_at` set to a non-null timestamp. All six are committed atomically by the Phase B transaction (with the documented hard-crash exception for filesystem artifacts, see "Known limitation" below).
- The **absence** of a row in `documents` implies: the upload either never reached step 13 (rejected in Phase A), failed mid-pipeline and was rolled back, or never happened at all. These three cases are indistinguishable from a DB query — they all look like "no row".

**Failure observability in Phase 2 (no persisted state):**
- **In-band HTTP response.** `POST /api/admin/documents` returns the terminal outcome synchronously (`201 Created`, `400 Bad Request` with `invalid_upload` / `unreadable_document` / `empty_content` reason, `409 Conflict`, `500 Internal Server Error`). The admin sees the failure immediately on the request that caused it. No polling, no status endpoint, no delayed discovery.
- **Log records.** Every compensation action logs at INFO with `documentId` (if assigned), file paths cleaned, and the triggering exception's message. Genuine server faults (`IllegalStateException` from Checkpoint A/B, unexpected `SQLException`) log at ERROR. Log retention is the only historical record of past failures.
- **No DB query for failure history.** An admin cannot run `SELECT filename, failed_at FROM documents WHERE status = 'failed'` because `status` and `failed_at` do not exist. Historical failure analysis requires scraping logs, not SQL.

**Consequences the admin / operator needs to understand:**
- A retry of the same bytes after a failed upload is indistinguishable from a first-time upload — there is no "previously failed, now retrying" state.
- A retry of the same bytes after a *successful* upload returns `409 Conflict` (dedup hit), which IS distinguishable from a first-time upload.
- "How many uploads failed last week?" is not answerable from the DB alone — only from logs.
- "What was wrong with this upload attempt yesterday?" requires log search by timestamp.
- UI cannot show a "recent failures" list. This is not a UX bug — it is a documented scope boundary.

**Why success-only is acceptable for Phase 2:**

1. **Low upload frequency, single admin.** Target deployments are single-admin sites with tens to hundreds of uploads total, not high-throughput ingestion. Failures are rare and immediately observable by the one person who triggered them — there is no queue of pending uploads that a different person might need to check.
2. **Failures are immediate and in-band.** Synchronous HTTP (Task 14 "Execution model — synchronous-only") guarantees the admin sees the error on the same request that caused it, not hours later via some dashboard. The feedback loop is already as tight as it gets; adding a DB row for the failure does not improve it.
3. **Adding status would require a state machine and cleanup.** A persisted `status` column means: pending rows that never transitioned need garbage collection; failed rows accumulate and need retention policy; the upload pipeline needs to handle "document row exists but processing hasn't finished" as a distinct path; retries must check for existing failed rows and decide whether to resume or supersede. Each of these is a new code path, a new failure mode, and a new test surface. None of that is justified by Phase 2's actual observability needs.
4. **Retry model is trivial without persisted state.** If an upload failed, the admin re-uploads the same file. The pipeline runs end-to-end from scratch. No "resume from step N" logic, no "pick up where we left off". Simple to implement, simple to reason about.
5. **Consistency with the narrow-txn + rollback + compensation design.** All three of those mechanisms assume that a failure means "undo everything". Adding a persisted failed state would contradict that — some state would survive failure, some would not, and the boundary would be subtle.

**What Phase 2 does NOT do (and why none of these are oversights):**
- ❌ No `status` column on `documents`.
- ❌ No `failed_at`, `error_code`, `error_message`, or `retry_count` columns.
- ❌ No separate `upload_attempts` table recording each upload try.
- ❌ No persisted "pending" row inserted at the start of the pipeline and updated at the end.
- ❌ No distinct state transition from "indexing" to "indexed" (there is only "indexed", because rows are inserted as part of the commit that makes them indexed).
- ❌ No admin UI endpoint for listing failed uploads.

**Evolution path if failure persistence is ever needed** (NOT implemented in Phase 2, reference sketch only):

A future phase that needs persisted failure observability would add, in order:
1. A new migration (V00N) adding `status TEXT NOT NULL DEFAULT 'indexed'` and nullable `failed_at TIMESTAMP`, `error_code TEXT`, `error_message TEXT` columns. Existing rows are backfilled with `status = 'indexed'` (which matches today's "row exists means success" semantics exactly).
2. A new code path in `processDocument` that optionally inserts a `status = 'pending'` row **before** Phase B (or a `status = 'failed'` row in a separate small transaction from the catch block), then transitions it on success/failure. This is a second transaction per upload — a cost the failure-persistence feature pays, not the happy path.
3. A background cleanup job that deletes `status = 'pending'` rows older than a threshold (to handle crash-mid-upload cases where the pending row survived but the actual processing did not).
4. A new read endpoint `GET /api/admin/documents?status=failed` filtering by status.
5. Admin UI surface for listing failed uploads, retrying them, dismissing them, etc.

None of this is built in Phase 2. When the time comes, the migration is backwards-compatible (default `status = 'indexed'` preserves existing semantics), and the existing Phase 2 code path remains valid — it just produces rows with `status = 'indexed'` by default, same as today.

**Anti-patterns that would accidentally break success-only — explicitly forbidden in Phase 2:**
- ❌ Inserting a Document row **before** parse completes. Under the current narrow-txn design this is already impossible (insert is step 13, parse is step 6, insert lives inside Phase B txn), but a future refactor that "prepares" a row early to reserve an id would break the invariant — a parse failure would then leave a row in the DB. If this is ever proposed, it requires explicit approval because it changes the lifecycle model.
- ❌ Catching an exception in `processDocument` and writing a "failed" row as a side effect. Exceptions must propagate and the rollback must delete the row; writing a failed row would survive the rollback (or would need a second, nested transaction to survive).
- ❌ Using `processDocument` return type variants like `UploadResult.Failed(reason, id)` where `id` points at a persisted failed row. The current `UploadResult` has only `Created` and `Duplicate` (Task 13) — no `Failed` variant, and adding one must go through design review.
- ❌ Adding a `status` field to the `Document` data class even if the DB schema has none. The data class and the table schema must match, and the schema has no status.

**Observable invariants that tests should assert (regression guards):**
- After any failure-mode row from the Task 13 failure-mode table, `documentRepository.findAll()` returns exactly the pre-call set of documents — no extra "failed" row exists. This is already covered by the existing rollback tests but the assertion should be phrased explicitly as "no row exists for the failed upload" rather than just "transaction rolled back".
- `Document` data class has no `status`, `failedAt`, `errorCode`, or `errorMessage` field (compile-time guard).
- `documents` schema has no `status`, `failed_at`, `error_code`, or `error_message` column (query `PRAGMA table_info(documents)` in a migration test and assert the column list exactly matches the V001/V002/V003 expected columns).

**Logging.** Every compensation action logs at INFO level with `documentId`, the path(s) cleaned, and the triggering exception's message. This is the only non-error observability for partial failures — intentional under the success-only model.

#### Known limitation — hard-crash orphan artifacts (accepted for Phase 2)

If the process is killed hard (SIGKILL, OOM killer, power loss, Docker stop-timeout escalation) between a filesystem write and the transaction commit, the `finally { compensateFailedUpload(...) }` block does not run and filesystem artifacts can be left behind:

1. **Source file orphan.** Crash between step 5 (source file atomic-moved to `{documentsPath}/{hash}.{ext}`) and step 12 (BEGIN TRANSACTION). A future upload of identical bytes adopts the orphan via the step-5 `FileAlreadyExistsException` path; otherwise it persists.
2. **Image directory orphan.** Crash between step 14 (images atomic-moved to `{imagesPath}/{documentId}/*`) and step 19 (`conn.commit()`). SQLite WAL recovery discards the uncommitted rows, but `sqlite_sequence` does not roll back, so the orphan `{imagesPath}/{failedId}/` directory is never reused or adopted.

This is an accepted Phase 2 limitation, not an oversight.

**What Phase 2 does NOT do:**
- No pre-flight FS scan on `processDocument` entry.
- No post-commit FS verification.
- No DB-vs-FS reconciliation inside `processDocument`.
- No startup reconciliation scan for final artifacts. Task 14's startup scan is scoped to `*.tmp.*` temp files only — see Task 14 for the scope boundary.

**What Phase 2 DOES do:**
- Documents the limitation here so operators know the failure mode exists.
- Ships the startup `*.tmp.*` cleanup in Task 14.

Reconciliation of orphan final artifacts is deferred to a later operational tool / later phase. No Phase 2 decision precludes it.

#### Tests (each failure-mode row above is a separate test)

Create `backend/src/test/kotlin/com/aos/chatbot/services/DocumentServiceTest.kt`:

- Use a **file-based SQLite database in a JUnit `@TempDir`** (e.g., `Database("${tempDir}/test.db")`), NOT `:memory:`. DocumentService opens a fresh connection per `processDocument` call, and `:memory:` creates a separate empty DB on each `connect()`, which would break every test. The file-based temp DB shares state across connections correctly and is automatically cleaned up by JUnit `@TempDir` after the test.
- Apply migrations once at test setup on a separate `Database.connect()` call, then close that connection. The service is then constructed with the same `Database` instance (NOT with a pre-opened connection) and exercised normally.
- Mock only `ParserFactory`, the parser instances, and `AosParser` — the rest (DocumentService, repositories constructed by the service per operation, ImageExtractor constructed per operation, ChunkingService) run against real DB state. `@TempDir` holds `documentsPath`, `imagesPath`, and the DB file.
- Construct `DocumentService(database, parserFactory, aosParser, chunkingService, documentsPath, imagesPath)`. Do NOT pre-build repositories in the test — the service owns that concern.
- Configure MockK parsers to throw at the step under test.
- For the partial-image-save case, use a spy/custom test double on `ImageRepository.insert` that succeeds N times then throws, to simulate the N-of-M scenario deterministically.
- After each failure test, assert all of: `documentRepository.findAll().isEmpty()`, `chunkRepository.count() == 0`, `imageRepository.findByDocumentId(anyId).isEmpty()`, `documentsPath` temp dir contains no files matching the uploaded filename, `imagesPath/{documentId}` directory does not exist.
- Add one test that explicitly calls `compensateFailedUpload` twice in a row on the same state to prove idempotency — the second call is a no-op and does not throw.
- Add the successful-path test: asserts `indexed_at` is non-null, `chunk_count` and `image_count` match the parsed content, files are where expected.
- Add deduplication tests (each is a separate test case):
    - First call returns `UploadResult.Created(doc)` with `doc.id != 0`.
    - Second call with identical bytes returns `UploadResult.Duplicate(existing)` where `existing.id` equals the first call's `doc.id`.
    - After the second call: DB has exactly one row in `documents`, `chunks` count is unchanged from after the first call, `imagesPath/{existingId}/` directory contents are unchanged (compare file listing before and after the second call), no new file appears under `documentsPath` (compare directory listing before and after).
    - `existing.indexedAt`, `existing.chunkCount`, `existing.imageCount` are byte-for-byte identical to the state after the first call — dedup does not mutate the existing row.
    - A third call with **different** bytes (different hash) but the **same `filename` parameter** returns `UploadResult.Created` (filename is not the dedup key — hash is). Both documents coexist.
    - A call with the same bytes but a different `filename` parameter returns `UploadResult.Duplicate` — filename is irrelevant for dedup.

- [ ] Wrap all DB mutations of processDocument in a single transaction (autoCommit=false → commit/rollback → finally restore)
- [ ] Implement compensateFailedUpload private helper (idempotent, swallows errors via runCatching, INFO logging)
- [ ] Route all exceptions through rollback + compensation + rethrow
- [ ] Set indexed_at only as the final DB mutation before commit
- [ ] deleteDocument reuses compensateFailedUpload for filesystem cleanup
- [ ] Implement DocumentService.processDocument pipeline
- [ ] Define sealed `UploadResult` with `Created(Document)` and `Duplicate(Document)` variants in the same file as DocumentService
- [ ] Change `processDocument` return type to `UploadResult`
- [ ] Compute SHA-256 hash from in-memory `fileBytes` **before** any disk write
- [ ] Implement deduplication check at step 3 (findByHash): on hit, return `UploadResult.Duplicate(existing)` immediately with zero side effects (no file, no DB txn, no mutation of existing row)
- [ ] Implement file saving at step 5, only after dedup miss at step 3, and BEFORE parse at step 6
- [ ] Step 5 writes bytes to `{documentsPath}/{hash}.{ext}.tmp.{UUID.randomUUID()}` first (temp path with UUID suffix in the same directory as the final target)
- [ ] Step 5 then performs `Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE)` to publish the bytes atomically
- [ ] Step 5 does NOT pass `StandardCopyOption.REPLACE_EXISTING` — race-hit case is handled via explicit `FileAlreadyExistsException` catch
- [ ] Step 5 catches `FileAlreadyExistsException`, deletes the temp file, and proceeds to step 6 (treat as race-winner scenario, no error)
- [ ] Step 5 catches `AtomicMoveNotSupportedException`, deletes the temp file, and throws `IllegalStateException` with a setup-error message naming `documentsPath`
- [ ] Step 5 cleans up the temp file (`Files.deleteIfExists` inside `runCatching`) on any IOException during the temp-write phase — final path is never touched
- [ ] Direct write to `{documentsPath}/{hash}.{ext}` via `Files.write`, `FileOutputStream`, `file.writeBytes`, or any other API is forbidden — the ONLY code path that creates the final file is the atomic `Files.move` at step 5
- [ ] Static / review-time guard: grep `DocumentService.kt` for direct writes to any path that doesn't include `.tmp.` — if a future refactor introduces one, the review flags it
- [ ] Test (atomic write — happy path): successful processDocument leaves exactly `{documentsPath}/{hash}.{ext}` on disk and zero `*.tmp.*` files (assert directory listing is exactly 1 file)
- [ ] Test (atomic write — temp-write failure): inject a mock byte source that throws midway (or use a `@TempDir` that exceeds quota), assert that NO file exists at `{documentsPath}/{hash}.{ext}` AND no `*.tmp.*` file survives after the failed call (cleanup is idempotent)
- [ ] Test (atomic write — race hit on final path): pre-create `{documentsPath}/{hash}.{ext}` with identical bytes before calling processDocument, call processDocument, assert: (a) no exception from step 5, (b) pipeline continues to step 6, (c) no `*.tmp.*` file remains after the move attempt, (d) the final file content is unchanged (the pre-created file wins)
- [ ] Test (atomic write — UUID uniqueness): call processDocument twice concurrently via `async { } + async { }` in `runBlocking` with identical fileBytes, assert both calls either complete (one Created + one Duplicate) or one succeeds and one hits race-dedup — crucially, assert no `*.tmp.*` files remain at the end of the test
- [ ] Test (atomic write — temp file naming): spy on `Files.write` / `Files.move` calls via a wrapper test double, assert the write target matches the regex `.*\.tmp\.[0-9a-f-]{36}$` (valid UUID form) and the move source matches the same temp path while the move target is the final path
- [ ] Test (atomic write — final file never partially written): inject a byte source that writes the first half then throws; assert `Files.exists(finalPath)` returns false after the failure (the partial bytes are contained in the temp file and get deleted)
- [ ] Write DocumentServiceTest with real in-memory DB + @TempDir (not mocked repositories)
- [ ] Test: failure at file-save step leaves zero state
- [ ] Test: failure at findByHash leaves zero state with source file cleaned up
- [ ] Test: failure at documentRepository.insert rolls back and cleans up source file
- [ ] Test: failure during parsing rolls back Document row and cleans up source file
- [ ] Test: failure partway through image save (N of M) — full rollback, images dir removed, source file removed
- [ ] Test: failure at chunkRepository.insertBatch rolls back all DB state and cleans up filesystem
- [ ] Test: failure at counter/indexed_at update rolls back full transaction
- [ ] Test: successful path sets indexed_at, counters, files on disk, all DB rows
- [ ] Test: service-level NOT NULL invariant — a successful `processDocument` returns `UploadResult.Created(doc)` where `doc.fileHash` is non-null AND non-blank (length > 0), and the persisted row fetched via `documentRepository.findById(doc.id)` also has `fileHash` non-null and non-blank. Asserted explicitly so that the Phase 2 contract ("file_hash is always populated by the service, even though the schema does not enforce NOT NULL") is regression-guarded by a test, not only by convention.
- [ ] Test: first upload returns UploadResult.Created; second upload with identical bytes returns UploadResult.Duplicate referencing the same id
- [ ] Test: dedup hit does not write any file under documentsPath (directory listing before/after is identical)
- [ ] Test: dedup hit does not mutate existing document's indexed_at / chunk_count / image_count
- [ ] Test: dedup hit does not create a new DB transaction (no rows inserted or updated)
- [ ] Test: dedup is keyed on file hash only — same bytes with different filename still returns Duplicate
- [ ] Test: dedup is not keyed on filename — different bytes with same filename returns Created (both documents coexist)
- [ ] Implement `isUniqueFileHashViolation(SQLException): Boolean` helper matching on both "UNIQUE" and "documents.file_hash" in the message
- [ ] Wrap `documentRepository.insert` in try/catch; on unique-hash violation: rollback, compensateFailedUpload, findByHash, return `UploadResult.Duplicate(winner)` — do NOT rethrow
- [ ] Log at INFO when the race path is taken with winner id ("Concurrent upload race detected...")
- [ ] Test: race path returns `UploadResult.Duplicate(winner)` when insert fails with simulated UNIQUE violation on file_hash (simulate by pre-inserting a row with the same hash via DocumentRepository directly, bypassing findByHash — this mimics the concurrent-winner scenario deterministically)
- [ ] Test: race path leaves the source file at `{documentsPath}/{hash}.{ext}` in place (byte-for-byte identical to the winner's file) and does NOT delete it — verify by comparing file contents before and after the race-hit call
- [ ] Test: race path does NOT touch the winner's row (winner's `indexed_at`, `chunk_count`, `image_count` unchanged before and after)
- [ ] Test: race path does NOT create an image directory for the loser (no `{imagesPath}/{any-id}/` beyond the winner's)
- [ ] Test: race path does NOT throw an exception; caller receives `UploadResult.Duplicate`
- [ ] Test: an unrelated UNIQUE violation (e.g., simulated on a different column like `users.username` if applicable, or crafted SQLException with non-matching message) is NOT reclassified as Duplicate — it propagates as a failure through the normal rollback path
- [ ] Test: if isUniqueFileHashViolation matches but post-rollback findByHash somehow returns null, processDocument throws `IllegalStateException` with the "broken invariant" message (defensive guard against schema drift)
- [ ] Define sealed `InvalidUploadReason` with `EmptyFile`, `MissingFilename`, `UnsafeFilename(detail)`, `UnsupportedExtension(ext)`, `FileTooLarge(size, limit)` variants, each with `code` and `httpMessage` fields
- [ ] Define `InvalidUploadException(reason)` extending `IllegalArgumentException`
- [ ] Implement `validateAndSanitizeUpload(rawFilename, fileBytes): String` as the first step of processDocument
- [ ] Add `MAX_UPLOAD_SIZE_BYTES = 50L * 1024L * 1024L` constant with TODO(phase-4) comment
- [ ] Add `windowsReservedNames` set and `supportedExtensions = setOf("docx", "pdf")` constants
- [ ] Validation rejects empty fileBytes with EmptyFile
- [ ] Validation rejects fileBytes over MAX_UPLOAD_SIZE_BYTES with FileTooLarge
- [ ] Validation rejects empty/whitespace-only rawFilename with MissingFilename
- [ ] Validation strips both forward and backslash path components via substringAfterLast chain
- [ ] Validation rejects basename still containing `/` or `\` after stripping with UnsafeFilename("path separators")
- [ ] Validation rejects basename with null byte (`\u0000`) with UnsafeFilename("null byte")
- [ ] Validation rejects basename with ISO control characters with UnsafeFilename("control character")
- [ ] Validation rejects basename `.` or `..` with UnsafeFilename("reserved name")
- [ ] Validation rejects Windows-reserved stems (CON, PRN, AUX, NUL, COM1-9, LPT1-9) case-insensitively
- [ ] Validation rejects missing extension with UnsupportedExtension("")
- [ ] Validation rejects extensions not in supportedExtensions with UnsupportedExtension(ext)
- [ ] Sanitized basename is stored in `documents.filename` (for display), NOT used as a disk path
- [ ] Implement `storageNameFor(hash, ext)` returning `"$hash.$ext"`
- [ ] File save writes to `{documentsPath}/{hash}.{ext}`, never to `{documentsPath}/{client-filename}`
- [ ] Test: empty fileBytes throws InvalidUploadException(EmptyFile); no file written, no DB row, no hash computed
- [ ] Test: oversized fileBytes throws FileTooLarge; no side effects
- [ ] Test: empty/whitespace rawFilename throws MissingFilename
- [ ] Test: rawFilename with `/etc/passwd` → InvalidUploadException (rejected as MissingFilename if basename ends up empty, else UnsafeFilename; assert the exception is thrown and no file is written)
- [ ] Test: rawFilename with `..\..\..\windows\system32\config.docx` → basename extracted and rejected (UnsafeFilename or UnsupportedExtension depending on whether the .docx survives — assert nothing lands in documentsPath beyond the sanitized basename path composition, and no file exists at any traversed location outside documentsPath)
- [ ] Test: rawFilename `..` → UnsafeFilename("reserved name")
- [ ] Test: rawFilename `.` → UnsafeFilename("reserved name")
- [ ] Test: rawFilename `CON.docx` → UnsafeFilename("Windows-reserved name 'CON'")
- [ ] Test: rawFilename `report\u0000.docx` (embedded null byte) → UnsafeFilename("null byte")
- [ ] Test: rawFilename `report\ttab.docx` (ISO control char) → UnsafeFilename("control character")
- [ ] Test: rawFilename `report.exe` → UnsupportedExtension("exe")
- [ ] Test: rawFilename `report` (no extension) → UnsupportedExtension("")
- [ ] Test: rawFilename `report.DOCX` (uppercase extension) → accepted, stored lowercased as ext
- [ ] Test: successful upload — file on disk is at `{documentsPath}/{hash}.docx`, DB `documents.filename` holds the sanitized original (e.g., "troubleshooting_v2.docx"), NOT the storage name
- [ ] Test: successful upload with original filename containing spaces or Unicode (e.g., "Отчёт о работе v2.docx") — accepted, stored verbatim in DB `filename` column after basename extraction, disk uses hash-based storage name
- [ ] Test: any `InvalidUploadException` produces zero side effects — compare FS and DB state before and after the failed call, assert they are identical
- [ ] Test: `InvalidUploadException` is thrown BEFORE hash computation (verify by mocking hash computation to throw a marker exception; the marker should not fire for invalid uploads)
- [ ] Define sealed `UnreadableReason` with `CorruptedDocx(detail)`, `CorruptedPdf(detail)`, `EncryptedDocument(format)` variants, each with `code` and `httpMessage` fields, colocated with InvalidUploadException in the DocumentService file
- [ ] Define `UnreadableDocumentException(reason, cause)` extending `RuntimeException`
- [ ] DocumentService does NOT catch UnreadableDocumentException — let it propagate through the normal rollback/compensation path
- [ ] Log UnreadableDocumentException at INFO level in DocumentService (not ERROR) — it is bad user input, not server fault
- [ ] Test: parser (mocked to throw UnreadableDocumentException(CorruptedDocx)) triggers full rollback — no Document row, no chunks, no images, source file deleted
- [ ] Test: UnreadableDocumentException propagates up from processDocument unchanged (not wrapped, still exactly UnreadableDocumentException with the same reason)
- [ ] Test: parser throwing UnreadableDocumentException(EncryptedDocument("docx")) triggers full rollback, exception propagates with reason.format == "docx"
- [ ] Test: parser throwing UnreadableDocumentException(CorruptedPdf) from a mocked PdfParser triggers full rollback, exception propagates
- [ ] Test: parser throwing generic IllegalStateException is NOT reclassified as UnreadableDocumentException — it propagates as a genuine failure (would map to 500 at route layer)
- [ ] Define sealed `EmptyDocumentReason` with `NoExtractableContent` variant (code=`no_extractable_content`, httpMessage mentions blank / scanned-without-OCR / metadata-only hints), colocated with InvalidUploadException and UnreadableDocumentException in the DocumentService file
- [ ] Define `EmptyDocumentException(reason)` extending `RuntimeException`
- [ ] Implement empty-content check at step 11 (in Phase A, before BEGIN TRANSACTION at step 12): if chunked.isEmpty() AND images.isEmpty() → throw EmptyDocumentException(NoExtractableContent)
- [ ] Check runs AFTER Checkpoint B (so any parser linkage bug has already been caught and cannot masquerade as empty content)
- [ ] EmptyDocumentException flows through the standard rollback/compensation path — NOT caught inside DocumentService
- [ ] Log EmptyDocumentException at INFO level in DocumentService (not ERROR) — it is user-facing bad input, not server fault
- [ ] Test: parser returns empty `ParsedContent(textBlocks=[], images=[])` → EmptyDocumentException(NoExtractableContent) thrown from processDocument
- [ ] Test: on empty content, full rollback — no Document row, no chunks, no images, source file deleted from documentsPath, no image directory created
- [ ] Test: empty content does NOT create chunks OR images in DB (asserts zero rows in each table after the failed call)
- [ ] Test: empty content exception is exactly `EmptyDocumentException`, NOT reclassified as `UnreadableDocumentException` or generic `IllegalStateException`
- [ ] Test: parser returns a synthetic empty TextBlock with imageRefs=["img_p1_001.png"] and images=[ImageData(filename="img_p1_001.png", ...)] (image-only PDF page scenario) — pipeline succeeds, processDocument returns `UploadResult.Created`, NOT `EmptyDocumentException` (the document has an image and a linking chunk, so it IS indexable)
- [ ] Test: parser returns 1 non-empty TextBlock with no images → successful path, Created
- [ ] Test: parser returns empty TextBlocks but non-empty images list (impossible under existing parser contract, but defense-in-depth) — processDocument should fail Checkpoint A (unreferenced images) at step 8 BEFORE reaching step 11, so the caller receives `IllegalStateException` from Checkpoint A, NOT `EmptyDocumentException`. Asserts the check ordering: linkage integrity is validated before emptiness.
- [ ] DocumentService constructor signature: `(database: Database, parserFactory, aosParser, chunkingService, documentsPath, imagesPath)` — no repositories, no Connection, no ImageExtractor as fields
- [ ] Every public method (processDocument, deleteDocument, getDocument, listDocuments) opens its own connection via `database.connect().use { conn -> ... }` and tears it down before returning
- [ ] Repositories and ImageExtractor are constructed inside the `use { }` block using `conn`, never as fields of DocumentService
- [ ] Step 1 upload validation runs BEFORE `database.connect()` — no connection is acquired for rejected uploads
- [ ] Transaction (`autoCommit = false`, commit, rollback, finally restore) operates on the per-operation `conn`, never on a shared Connection
- [ ] Test: DocumentServiceTest uses a file-based temp DB (`Database("${tempDir}/test.db")`), NOT `:memory:`
- [ ] Test: two sequential processDocument calls succeed independently — neither leaks autoCommit state from one invocation to the next (simulate by calling processDocument, then calling it again, then asserting the second call's transaction semantics are clean)
- [ ] Test: processDocument opens exactly one connection per invocation — assert by wrapping the `Database` in a spy that counts `connect()` calls, verify count increments by 1 per processDocument, and that the connection was closed afterwards
- [ ] Test: a rejected upload (InvalidUploadException at step 1) opens ZERO connections — assert the spy count did NOT increment
- [ ] Test: getDocument, listDocuments, deleteDocument each open and close their own connection
- [ ] Test: DocumentService constructor does NOT accept a Connection, a DocumentRepository, or an ImageExtractor — verify by compile-time (the ctor parameters are documented as `Database`, parsers, chunker, paths only)
- [ ] Narrow transaction: `conn.autoCommit = false` is set at step 12 (immediately before `documentRepository.insert`), NOT at the top of the `use { }` block
- [ ] Narrow transaction: parse / AosParser / ChunkingService / Checkpoints / empty-check run with `conn.autoCommit == true`
- [ ] Narrow transaction: transaction closes at step 19 via `conn.commit()`
- [ ] Narrow transaction: `finally` restores `conn.autoCommit = true` on all exit paths (success, Phase A exception, Phase B exception)
- [ ] Phase A exception handling: on exception at steps 5–11, NO `conn.rollback()` is called (no transaction open), only `compensateFailedUpload` fires. On exception at steps 13–19, BOTH `conn.rollback()` and compensation fire.
- [ ] Test (narrow txn guarantee): wrap the injected `Connection` with a test double that records every `setAutoCommit(false)`, `commit()`, `rollback()` call with a monotonic timestamp. Run a successful processDocument. Assert: `setAutoCommit(false)` was called exactly once, AFTER `parser.parse()` completed; `commit()` was called exactly once, AFTER `chunkRepo.insertBatch()`; no `rollback()` calls on the success path.
- [ ] Test (narrow txn guarantee — parse is outside txn): use the connection spy from the previous test with a MockK parser that captures `conn.autoCommit` at the moment `parse()` is called. Assert `conn.autoCommit == true` at parse-entry. This is the primary regression guard against a future editor accidentally re-widening the transaction.
- [ ] Test (narrow txn guarantee — Checkpoint A failure): mock parser output to produce orphaned imageRefs, run processDocument, assert `IllegalStateException` is thrown AND the connection spy records **zero** `setAutoCommit(false)` calls, **zero** `commit()` calls, **zero** `rollback()` calls. The transaction must never have opened.
- [ ] Test (narrow txn guarantee — Checkpoint B failure): same as above but with linkage that fails Checkpoint B. Connection spy shows zero transaction activity.
- [ ] Test (narrow txn guarantee — Empty content): mock parser to return `ParsedContent(textBlocks=[], images=[])`, run processDocument, assert `EmptyDocumentException` thrown AND connection spy shows zero transaction activity (no `setAutoCommit(false)`, no `rollback()`). This confirms step 11 fires before step 12.
- [ ] Test (narrow txn guarantee — source file compensation without rollback): Phase A exception test — connection spy shows zero `rollback()` calls; filesystem spy confirms source file was deleted. These two together prove the Phase A cleanup path is filesystem-only.
- [ ] Test (narrow txn guarantee — parser throwing UnreadableDocumentException): confirm the exception path goes through Phase A cleanup (source file deleted, no `rollback()`), propagates up as `UnreadableDocumentException` (not wrapped), and the connection spy shows zero transaction activity.
- [ ] Success-only invariant: no `status`, `failed_at`, `error_code`, or `error_message` column exists on the `documents` table (verify via `PRAGMA table_info(documents)` in a migration test, assert the column set equals the V001+V002+V003 expected columns exactly, no extras)
- [ ] Success-only invariant: `Document` data class has no `status`, `failedAt`, `errorCode`, or `errorMessage` field (compile-time guard — adding one would require changing this checkbox and gating the change on explicit design-review approval)
- [ ] Success-only invariant: `UploadResult` sealed class has exactly two variants — `Created` and `Duplicate` — no `Failed` variant (failures propagate as exceptions, not as a success-like result)
- [ ] Success-only regression test: after every failure-mode test in the Task 13 failure-mode table, explicitly assert `documentRepository.findAll().none { it.fileHash == uploadedHash }` — phrased as "no row exists for the failed upload", not just "transaction was rolled back". This makes the success-only invariant the assertion, not a side effect of a different assertion.
- [ ] Success-only regression test: after a failed upload attempt, a follow-up `listDocuments()` call returns exactly the same list as before the attempt — no stale "failed" rows pollute the admin list endpoint
- [ ] Success-only regression test: code path review — grep `DocumentService.kt` for any string containing `"failed"`, `"status"`, `"error_code"` used in a DB insert/update context. If a future refactor adds failure-state persistence, this grep catches it at review time.
- [ ] Every public method of DocumentService is `suspend` — `processDocument`, `deleteDocument`, `getDocument`, `listDocuments`
- [ ] Every public `suspend` method wraps its entire body in `withContext(Dispatchers.IO) { ... }` — no blocking code outside the boundary
- [ ] `Dispatchers.IO` is hardcoded (not injected as a ctor parameter) — Phase 2 does not need dispatcher injection
- [ ] No `runBlocking { }` calls inside DocumentService or inside repositories
- [ ] No `GlobalScope.launch { }` or detached-scope launches — upload is synchronous per the execution model
- [ ] No `withContext(Dispatchers.Default)` anywhere in the blocking pipeline — Default is sized for CPU work, not blocking I/O
- [ ] Test (dispatcher regression guard — parser): mock `DocumentParser.parse` to capture `Thread.currentThread()`, run `processDocument` in `runBlocking { }`, assert `parseThread !== callingThread`. Fails if `withContext(Dispatchers.IO)` is removed or weakened.
- [ ] Test (dispatcher regression guard — JDBC): wrap `DocumentRepository.insert` in a spy that captures `Thread.currentThread()` at call time, assert the captured thread is NOT the `runBlocking` calling thread — proves the transaction portion also runs off the event loop
- [ ] Test (dispatcher regression guard — getDocument): call `getDocument(id)` in `runBlocking { }` with a repository spy that captures thread identity, assert off-calling-thread execution — the single-query read path is covered too
- [ ] Test (dispatcher regression guard — listDocuments): same as above for the list endpoint path
- [ ] Test (dispatcher regression guard — deleteDocument): same as above for the delete path
- [ ] Test (explicit NEGATIVE assertion — false invariant guard): the test suite MUST NOT assert that parser thread === repository thread within a single `processDocument` call. Same physical thread across pipeline steps is NOT a guaranteed invariant of `withContext(Dispatchers.IO)` — the IO dispatcher has 64 worker threads and continuations may resume on a different worker after any suspension point. Asserting same-thread execution would encode a false runtime property and create brittle tests that break on JVM/coroutines updates. The safety of the operation-scoped Connection comes from sequential access + no concurrent use, NOT from thread identity. If a reviewer sees a test asserting `parseThread === repositoryThread` or equivalent, it must be removed.
- [ ] Test: compensateFailedUpload is idempotent when called twice
- [ ] Implement validateParsedLinkage (Checkpoint A) between AosParser and ChunkingService
- [ ] Implement validateChunkedLinkage (Checkpoint B) between ChunkingService and ImageExtractor
- [ ] Convert TextBlock → Chunk as pure in-memory mapping with `imageRefs: List<String>` (no JSON at this step); JSON serialization lives in ChunkRepository per Task 6
- [ ] Test: Checkpoint A rejects orphaned refs (TextBlock references filename not in ParsedContent.images) with full rollback and source-file cleanup
- [ ] Test: Checkpoint A rejects unreferenced images (ImageData not linked from any TextBlock) with full rollback
- [ ] Test: Checkpoint A rejects duplicate refs (same filename in two TextBlocks) with full rollback
- [ ] Test: Checkpoint B rejects orphaned refs after chunking with full rollback and source-file cleanup
- [ ] Test: Checkpoint B accepts duplicate imageRefs across chunks when they result from legitimate ChunkingService splitting (does NOT reject)
- [ ] Test: successful path with a parent TextBlock split into 3 chunks — all 3 resulting chunk rows carry identical image_refs JSON, and the single underlying image file is stored once on disk and once in the images table
- [ ] Test: successful path with trailing synthetic empty TextBlock (image-only PDF page) — the empty-content chunk survives, its image_refs is set, and the image is persisted once

---

### Task 14: Wire DocumentService into Application.kt and add admin routes stub

Update `backend/src/main/kotlin/com/aos/chatbot/Application.kt`:
- Instantiate the stateless Phase 2 dependencies (`ParserFactory`, `AosParser`, `ChunkingService`) in `module()` and the singleton `DocumentService`. Wire with constructor DI.
- Only instantiate document processing components when mode is FULL or ADMIN (not CLIENT).
- **Startup orphan scan**: at startup, after migrations are applied and BEFORE any request handler is registered, walk both `config.documentsPath` and `config.imagesPath` and delete any files matching `*.tmp.*`. These are orphaned temp files from previous crashed processes — produced by the atomic-write patterns in Task 13 (source file) and Task 12 (image files). The scan covers two shapes:
    1. **Source file temps** at the root of `documentsPath`: files like `{documentsPath}/{hash}.{ext}.tmp.{uuid}`.
    2. **Image file temps** inside per-document subdirectories of `imagesPath`: files like `{imagesPath}/{documentId}/img_001.png.tmp.{uuid}`. One level of nesting — scan each child directory of `imagesPath` and filter for `*.tmp.*` files inside. Do NOT recurse deeper; the image directory structure is flat per document.
    
    The scan is safe because Phase 2 architecture mandates one backend process per data directory — no concurrent writer whose temp file could be mistakenly deleted. Implement as a function `cleanupOrphanTempFiles(documentsPath: String, imagesPath: String): Int` that returns a combined count and accepts both paths. Invoke it from `module()` conditional on mode (FULL/ADMIN only, since CLIENT has no uploads). Log the count at INFO: `"Startup temp-file cleanup: N orphaned temp files removed (sources: A, images: B)"` with a breakdown so operators can spot anomalies (e.g., a consistently non-zero image count suggests something is crashing mid-saveImages).

    **Out of scope — orphan final artifacts from hard crashes.** The startup scan deliberately cleans up ONLY `*.tmp.*` files, NOT final artifacts (fully-named source files in `documentsPath` or complete image files inside `{imagesPath}/{documentId}/`). Final artifacts that orphan after a hard process crash (SIGKILL between filesystem write and DB commit) are an **accepted limitation of Phase 2**, documented in Task 13's "Known limitation — hard-crash orphan artifacts" subsection. Addressing them requires a DB-vs-FS consistency check (reconciliation tool), which is a separate operational facility deferred to a later phase — see the design sketch at the end of the "Known limitation" subsection. Do NOT extend this startup scan to walk DB state and compare against FS — that is a different tool with different safety requirements (dry-run by default, explicit `--delete` flag, etc.) and does not belong on the hot startup path.

**Wiring contract — enforce operation-scoped connection ownership** (see Task 13 "Connection lifecycle and ownership"):

- `DocumentService` receives the `Database` factory (already constructed in Phase 1 wiring), the stateless parser/chunker dependencies, and the two path strings from `AppConfig`. It does NOT receive a `Connection`, any repository, or an `ImageExtractor`. Those are all operation-scoped and built inside `DocumentService`'s public methods.
- `DocumentService` is a single long-lived instance for the process. Construct it once in `module()` and share it across route handlers — this is safe because it holds no stateful DB resource as a field.
- Do NOT instantiate `DocumentRepository`, `ChunkRepository`, or `ImageRepository` in `Application.kt`. Their only legitimate construction site is inside `DocumentService`'s `database.connect().use { conn -> ... }` blocks.
- Do NOT instantiate `ImageExtractor` in `Application.kt` for the same reason — it holds an `ImageRepository` which holds a `Connection`.
- Do NOT call `database.connect()` anywhere in `Application.kt`. The factory is passed; connections are owned by operations.
- Applying `Migrations(conn)` at startup (a Phase 1 concern that already exists) is the **only** place in the whole application where `Application.kt` is allowed to touch a `Connection` directly — and even there, it opens a dedicated connection via `database.connect().use { conn -> Migrations(conn).apply() }` and closes it before any request handler sees it.

**Anti-patterns in Application.kt — explicitly forbidden:**
- ❌ `val conn = database.connect(); val docRepo = DocumentRepository(conn); val service = DocumentService(docRepo, ...)` — locks the whole app to one connection.
- ❌ `val imageExtractor = ImageExtractor(imagesPath, ImageRepository(database.connect()))` injected into DocumentService — same problem, plus a leaked connection that never closes.
- ❌ Any field of type `Connection`, `DocumentRepository`, `ChunkRepository`, `ImageRepository`, or `ImageExtractor` on a long-lived object (route handler, service other than DocumentService, module-level val).

A quick audit rule for the review phase: in `Application.kt`, the only Phase 2 types that should appear in `module()`'s constructor calls are `Database`, `ParserFactory`, `AosParser`, `ChunkingService`, `DocumentService`, and `AdminRoutes` (or its equivalent route registration function). Repositories, Connection, and ImageExtractor should not appear anywhere in `Application.kt`.

#### Execution model — synchronous-only in Phase 2

`POST /api/admin/documents` is a **synchronous** endpoint in Phase 2. The HTTP request blocks until `DocumentService.processDocument` has fully completed — hash computation, file save, parse, chunking, image extraction, DB commit — and the response reflects the terminal outcome (`201 Created`, `400 Bad Request`, `409 Conflict`, or `500 Internal Server Error`). There is no `jobId`, no `status: "indexing"`, no polling endpoint, no background worker.

**Deviation from an earlier ARCHITECTURE.md draft.** An earlier version of `docs/ARCHITECTURE.md` section 7 described this endpoint as returning `{id, filename, status: "indexing", jobId: "abc123"}` — an asynchronous job model. That shape is NOT implemented in Phase 2 and the architecture doc is updated as part of this task to reflect the synchronous contract actually shipping. An async job model may return in a later phase but is explicitly out of scope now.

**Why synchronous for Phase 2.**
- Typical AOS documents parse in seconds (Word via POI, PDF via PDFBox) on the target hardware — well within reasonable HTTP timeout budgets.
- Async adds significant complexity that Phase 2 does not need: a job store (new table or Artemis queue coupling), background worker threads/coroutines, job lifecycle states, a polling endpoint, and route tests that have to wait or mock the async boundary.
- The admin UI surface is small and upload frequency is low — there is no throughput pressure that justifies backgrounding the work.
- Errors (invalid upload, unreadable docx/pdf, duplicate) are far more useful when returned **in-band** on the upload request. With async, the UI has to poll to learn that the upload it just submitted was rejected — a worse UX for a human admin than a direct `400`.
- The synchronous contract is strictly simpler to test: one request, one response, no timing concerns. Route tests in `AdminRoutesTest` assert final status codes directly, no job-status mocking.

**Forward migration path (NOT implemented now, documented to prevent shape drift).** When async becomes desirable in a later phase, do NOT mutate the shape of this endpoint's success response. Instead, pick one of these non-breaking migrations:

1. **New endpoint.** Add `POST /api/admin/documents/async` that returns `{id, jobId, status: "indexing"}` and a companion `GET /api/admin/documents/jobs/{jobId}`. Clients opt in by choosing the endpoint. The synchronous endpoint remains available forever.
2. **`Prefer` header (RFC 7240).** Add support for `Prefer: respond-async` on the existing endpoint. When present, the server responds `202 Accepted` with a `Location` header pointing at the job status resource. When absent, current synchronous behavior is preserved. This is the most idiomatic HTTP option.

Both migrations are strictly additive. Neither removes the `201 Created` + `Document` response shape that Phase 2 locks in. Clients written against Phase 2 continue to work unchanged.

**Anti-patterns to avoid now, so the migration stays open:**
- ❌ Adding `status: "ready"` or `jobId: null` fields to the Phase 2 success response "for future compatibility". The future endpoint will have its own shape; padding the current one pollutes a clean contract.
- ❌ Wrapping the synchronous work in a coroutine `launch { }` and returning early. That is a fake-async that loses error reporting and breaks the rollback/compensation guarantees from Task 13.
- ❌ Spawning a background thread for parsing while holding the HTTP response open — no upside over plain blocking.

The route handler is a thin synchronous mapper from `UploadResult` / `InvalidUploadException` / `UnreadableDocumentException` to HTTP status + DTO, and nothing more.

Create `backend/src/main/kotlin/com/aos/chatbot/routes/AdminRoutes.kt` with basic document endpoints:
- `POST /api/admin/documents` — accept multipart file upload, call `DocumentService.processDocument`, branch on outcome:
    - `UploadResult.Created(doc)` → respond `201 Created` with the `Document` serialized as JSON body.
    - `UploadResult.Duplicate(existing)` → respond `409 Conflict` with JSON body:
        ```json
        {
          "error": "duplicate_document",
          "message": "A document with identical content has already been indexed. Delete the existing document first if you want to re-index.",
          "existing": {
            "id": 42,
            "filename": "troubleshooting_v2.docx",
            "indexed_at": "2026-03-28T14:12:03Z"
          }
        }
        ```
        This is the API-visible side of the deduplication contract defined in Task 13 — it must match exactly. Do NOT return 200 or 2xx for `Duplicate`, and do NOT silently overwrite.
    - `InvalidUploadException` thrown by the service → respond `400 Bad Request` with JSON body:
        ```json
        {
          "error": "invalid_upload",
          "reason": "unsafe_filename",
          "message": "Filename contains unsafe characters or path components: path separators"
        }
        ```
        The `reason` field is `InvalidUploadReason.code` (one of `empty_file`, `missing_filename`, `unsafe_filename`, `unsupported_extension`, `file_too_large`). The `message` field is `InvalidUploadReason.httpMessage`. Use a `StatusPages` exception handler or an explicit try/catch around the service call — either is acceptable, but the mapping must be localized (one place, not sprinkled across handlers). Do NOT return 500 (invalid input is not a server error), do NOT return 422, do NOT attempt to "fix" the input and retry.
    - `UnreadableDocumentException` thrown by a parser and propagated through DocumentService → respond `400 Bad Request` with JSON body:
        ```json
        {
          "error": "unreadable_document",
          "reason": "corrupted_docx",
          "message": "The uploaded .docx file could not be parsed: ZIP container corrupted: unexpected end of file"
        }
        ```
        The `error` field is the stable discriminator `"unreadable_document"` — distinct from `"invalid_upload"` so clients can branch programmatically. The `reason` field is `UnreadableReason.code` (one of `corrupted_docx`, `corrupted_pdf`, `encrypted_document`). The `message` field is `UnreadableReason.httpMessage`. Same rules as above: localize the mapping, do NOT return 500, do NOT return 422, do NOT leak the wrapped library exception (POI/PDFBox type names or stack traces MUST NOT appear in the response body).
    - `EmptyDocumentException` thrown by DocumentService at step 11 (empty-content guard in Phase A) → respond `400 Bad Request` with JSON body:
        ```json
        {
          "error": "empty_content",
          "reason": "no_extractable_content",
          "message": "The uploaded document contains no text, tables, or images that can be indexed. It may be a blank document, a scanned PDF without OCR, or a file with only metadata."
        }
        ```
        The `error` field is the stable discriminator `"empty_content"` — distinct from `"invalid_upload"` AND `"unreadable_document"`. Three distinct `error` values, all under HTTP 400, so clients can show three different UX flows (fix the filename, regenerate the file, or OCR the scan). The `reason` field is `EmptyDocumentReason.code`. The `message` field is `EmptyDocumentReason.httpMessage`. Same rules: do NOT return 500, do NOT return 201, do NOT silently accept the upload.
    - Any other exception → 500 with a generic error body. Duplicate, Invalid, Unreadable, and Empty are each distinct from generic failure.
- `GET /api/admin/documents` — list all documents, returns `200 OK` with JSON array. **Ordering is newest-first** (`created_at DESC, id DESC`) — inherited from `DocumentRepository.findAll()` per the Task 6 deterministic ordering contract. The route handler is a pass-through and MUST NOT re-sort, reverse, or otherwise re-order the list. If an admin UI wants a different order, that is a new repository method, not a route-level transformation.
- `DELETE /api/admin/documents/{id}` — delete document, returns `204 No Content` on success, `404` if not found.

Define response DTOs so kotlinx.serialization has concrete types to emit — do not use raw `Map`s:
- `DuplicateDocumentResponse` for the 409 body
- `InvalidUploadResponse` for the 400 body for `InvalidUploadException`, with fields `error: String` (constant `"invalid_upload"`), `reason: String`, `message: String`
- `UnreadableDocumentResponse` for the 400 body for `UnreadableDocumentException`, with fields `error: String` (constant `"unreadable_document"`), `reason: String`, `message: String`
- `EmptyDocumentResponse` for the 400 body for `EmptyDocumentException`, with fields `error: String` (constant `"empty_content"`), `reason: String`, `message: String`

All four DTOs live alongside `AdminRoutes.kt` or in `routes/dto/`. Do NOT collapse the three 400-response DTOs (`InvalidUploadResponse`, `UnreadableDocumentResponse`, `EmptyDocumentResponse`) into a single DTO with a shared `error` enum — keeping them as distinct classes makes the mapping from exception type to DTO type strictly 1:1 and hard to accidentally merge. If a future refactor wants a shared supertype, it should be a sealed base class, not a single DTO with a mutable `error` field.

**Filename handling at the route layer.** The route extracts the client-provided filename from multipart (e.g., `PartData.FileItem.originalFileName`). If the multipart part has no filename, pass an **empty string** to `DocumentService.processDocument(filename="", ...)` — the service's validator will reject it as `MissingFilename`. Do NOT default to a placeholder like `"upload.bin"` or the current timestamp; that would mask the client bug and produce files with misleading display names. Do NOT sanitize at the route layer; the service owns the sanitization contract (defense in depth).

**Dispatcher policy at the route layer.** The route handler calls `documentService.processDocument(...)` (and the other `suspend` methods) **directly** without wrapping in `withContext(Dispatchers.IO)`. Ktor handles `suspend` route handlers natively, and `DocumentService` already moves its body onto `Dispatchers.IO` per the Task 13 "Execution model" contract. Wrapping at the route layer would either:
- Add a redundant context switch (IO → IO is still a dispatch), OR
- Tempt future editors to think the route owns the dispatcher policy, which means a new endpoint that forgets the wrapper would silently block the event loop.

If a reviewer sees `withContext(Dispatchers.IO) { documentService.X() }` in a route, that is a red flag — the service already guarantees IO; duplicating it at the route is an anti-pattern and should be removed in review. Similarly, calling a non-`suspend` method of DocumentService from the route is impossible because all public methods are `suspend` per the Task 13 contract.

These routes should only be registered when mode is FULL or ADMIN.

Register the routes in `routing { }` block.

#### Security boundary — unprotected admin routes (conscious temporary limitation)

`/api/admin/*` routes in Phase 2 are **unauthenticated**. No JWT, no bearer token, no session, no authorization check. Authentication work is deferred to a later phase (Phase 4 per ARCHITECTURE.md). This is a conscious temporary scope decision, not an oversight.

**Rules:**
- Admin routes MUST NOT be exposed directly to the public internet before Phase 4 auth lands. Acceptable deployment environments: localhost, trusted/private networks, or behind an ingress / firewall with IP-level allowlisting.
- Admin routes are registered only when `config.mode in (FULL, ADMIN)`. `MODE=client` does NOT register them at all — that is the acceptable mode for any public-facing deployment before Phase 4.
- When the process starts in `MODE=full` or `MODE=admin`, it MUST emit a **prominent startup WARN log once** (via SLF4J at `WARN` severity, not INFO/DEBUG) stating that `/api/admin/*` is unauthenticated in Phase 2 and directing operators not to expose it publicly. Exact wording is up to the implementer; the acceptance criteria are severity (WARN), timing (once at startup in `Application.module()`), and content intent (unauthenticated state + "do not expose publicly" directive).
- No placeholder / stub auth, no `AUTH_DISABLED` or similar env var, no dev-only bypass. Either auth is fully implemented (Phase 4) or explicitly absent with the startup WARN (Phase 2).

Create test file: `backend/src/test/kotlin/com/aos/chatbot/routes/AdminRoutesTest.kt`. Use Ktor `testApplication` and a **mocked `DocumentService`** (MockK) — this test layer validates HTTP mapping, not the parsing pipeline (that is covered by `DocumentServiceTest` in Task 13).

- Test `GET /api/admin/documents` returns 200 with JSON array.
- Test `GET /api/admin/documents` preserves newest-first ordering — mock `DocumentService.listDocuments()` to return `[doc_id_5, doc_id_3, doc_id_1]` (a specific order from the service), assert the HTTP response body has documents in exactly `[5, 3, 1]` order. The route must NOT reverse, re-sort, or otherwise transform the order.
- Test `POST /api/admin/documents` with multipart when the mocked service returns `UploadResult.Created(doc)`:
    - Response status is `201 Created`.
    - Response body is the JSON serialization of the `Document`.
    - `Content-Type` is `application/json`.
- Test `POST /api/admin/documents` with multipart when the mocked service returns `UploadResult.Duplicate(existing)`:
    - Response status is `409 Conflict`.
    - Response body parses as the duplicate DTO with `error == "duplicate_document"`.
    - `existing.id`, `existing.filename`, `existing.indexed_at` in the body match the `Document` the mock returned.
    - The response is NOT `200 OK` (explicit negative assertion — guards against accidental success mapping).
- Test `POST /api/admin/documents` when the mocked service throws `IllegalStateException` (e.g., from Checkpoint A): returns `500 Internal Server Error` with a generic error body. Duplicate, Invalid, Unreadable, and Empty are each distinct from failure.
- Test `POST /api/admin/documents` when the mocked service throws `EmptyDocumentException(NoExtractableContent)`:
    - Response status is `400 Bad Request`.
    - Response body parses as `EmptyDocumentResponse` with `error == "empty_content"` and `reason == "no_extractable_content"`.
    - Response `message` field contains the OCR/blank/metadata hint.
    - Response is NOT `500`, NOT `201`, NOT `409` (explicit negative assertions — empty content is neither failure nor success nor duplicate).
- Test: the three 400 paths are distinguishable by `error` field — `InvalidUploadException` → `"invalid_upload"`, `UnreadableDocumentException` → `"unreadable_document"`, `EmptyDocumentException` → `"empty_content"`. Each exception produces exactly one `error` constant, never any of the others.
- Test `POST /api/admin/documents` when the mocked service throws `UnreadableDocumentException(CorruptedDocx("ZIP container corrupted"))`:
    - Response status is `400 Bad Request`.
    - Response body parses as `UnreadableDocumentResponse` with `error == "unreadable_document"` and `reason == "corrupted_docx"`.
    - Response `message` field contains the detail passed to the reason.
    - Response is NOT `500` (explicit negative assertion — guards against generic fallthrough).
    - Response body does NOT contain any POI-specific type names or stack trace fragments (e.g., "ZipException", "POIXMLException", "at org.apache.poi"). Scan the serialized body for these strings and assert absence.
- Test `POST /api/admin/documents` for `UnreadableDocumentException(CorruptedPdf("Header doesn't contain versioninfo"))` → `reason == "corrupted_pdf"`, body leaks no PDFBox internals.
- Test `POST /api/admin/documents` for `UnreadableDocumentException(EncryptedDocument("docx"))` → `reason == "encrypted_document"`, message contains "docx".
- Test `POST /api/admin/documents` for `UnreadableDocumentException(EncryptedDocument("pdf"))` → same, message contains "pdf".
- Test: the `error` field is a stable discriminator. `InvalidUploadException` paths always produce `error == "invalid_upload"`; `UnreadableDocumentException` paths always produce `error == "unreadable_document"`. Both are 400 but must be distinguishable via `error`.
- Test `POST /api/admin/documents` when the mocked service throws `InvalidUploadException(EmptyFile)`:
    - Response status is `400 Bad Request`.
    - Response body parses as `InvalidUploadResponse` with `error == "invalid_upload"` and `reason == "empty_file"`.
    - Response is NOT `500` (explicit negative assertion).
- Test `POST /api/admin/documents` for each remaining `InvalidUploadReason` — one test per variant: `MissingFilename` → `reason == "missing_filename"`, `UnsafeFilename("path separators")` → `reason == "unsafe_filename"` and `message` contains the detail, `UnsupportedExtension("exe")` → `reason == "unsupported_extension"` and `message` contains `"exe"`, `FileTooLarge(...)` → `reason == "file_too_large"` and `message` contains the size and limit.
- Test `POST /api/admin/documents` with a multipart part that has no filename header: the route passes an empty string to the service, the mocked service is invoked with `filename == ""`, and assumes the route layer does NOT substitute a default name.
- Test `DELETE /api/admin/documents/{id}` returns 204 on success, 404 when the id does not exist.
- Test routes are NOT registered in CLIENT mode (request to `POST /api/admin/documents` returns `404 Not Found` — the route does not exist in that mode).

- [ ] POST /api/admin/documents is synchronous: route handler awaits DocumentService.processDocument to terminal outcome before responding, no launch { }, no background job
- [ ] Route handler does NOT add `status` or `jobId` fields to the Created response — Document DTO shape only
- [ ] Update `docs/ARCHITECTURE.md` section 7 (POST /api/admin/documents) to match the synchronous contract actually shipping: replace the async example with 201/400/409 response shapes and note that async is deferred (done as part of this task, not a separate doc edit)
- [ ] Write AdminRoutesTest asserting no `jobId` or `status` field appears in any 201 response body (explicit negative assertion guarding against accidental shape drift)
- [ ] Wire stateless Phase 2 dependencies in Application.kt: ParserFactory, AosParser, ChunkingService, DocumentService (single long-lived instance)
- [ ] DocumentService receives `database: Database` (factory), NOT a Connection or pre-built repositories
- [ ] Application.kt does NOT instantiate DocumentRepository / ChunkRepository / ImageRepository / ImageExtractor / Connection — enforce via code review that none of these types appear in `module()`
- [ ] Application.kt calls `database.connect()` ONLY inside the existing migration setup block (one dedicated scoped connection for `Migrations.apply()`), never for runtime request handling
- [ ] Implement `cleanupOrphanTempFiles(documentsPath: String, imagesPath: String): Int` — scans source file temps at root of documentsPath AND image file temps one level deep in imagesPath, deletes `*.tmp.*` files, returns combined count
- [ ] Scan source temps: `Files.list(documentsPath)` filtered on `*.tmp.*`
- [ ] Scan image temps: `Files.list(imagesPath)` → filter isDirectory → for each child directory, `Files.list(childDir)` filtered on `*.tmp.*`
- [ ] Do NOT recurse deeper into `imagesPath` subdirectories — scan exactly one level down (per-document dirs contain flat image files, no nested dirs)
- [ ] Each delete is idempotent (runCatching { Files.delete }) so a failure on one orphan does not abort the whole scan
- [ ] Call `cleanupOrphanTempFiles` from Application.kt `module()` at startup, AFTER migrations and BEFORE route registration, conditional on mode in (FULL, ADMIN)
- [ ] Log breakdown at INFO: `"Startup temp-file cleanup: N orphaned temp files removed (sources: A, images: B)"` — operators can spot anomalies via the breakdown
- [ ] Skip the orphan scan entirely when mode is CLIENT (read-only, no uploads, shouldn't touch documentsPath or imagesPath)
- [ ] Write test: pre-populate documentsPath with valid source files (`abc123.docx`), source temp files (`abc123.docx.tmp.550e8400-e29b-41d4-a716-446655440000`), and unrelated files (`README.md`); pre-populate imagesPath with subdirectories `1/`, `2/` each containing valid images (`img_001.png`) and temp image files (`img_001.png.tmp.{uuid}`); call cleanup; assert ONLY the `*.tmp.*` files were deleted from both paths (sources and images), all valid files and unrelated files are untouched, and the returned count equals the total number of temp files removed (source temps + image temps)
- [ ] Write test: empty documentsPath and empty imagesPath → returns 0, does not throw
- [ ] Write test: no temp files present in either path → returns 0, all valid files untouched
- [ ] Write test: imagesPath contains a non-directory file at its root (should not happen in practice, but defensive) — the scan skips it cleanly without throwing
- [ ] Write test: one temp file undeletable (e.g., missing permissions simulated via a test hook) — the scan logs a warning, continues with other files, and the final count reflects only successfully-deleted files
- [ ] Create AdminRoutes with document CRUD endpoints
- [ ] Branch on UploadResult in POST handler: Created → 201, Duplicate → 409
- [ ] Catch InvalidUploadException in POST handler and map to 400 with InvalidUploadResponse body (reason = InvalidUploadReason.code, message = InvalidUploadReason.httpMessage)
- [ ] Catch UnreadableDocumentException in POST handler and map to 400 with UnreadableDocumentResponse body (error = "unreadable_document", reason = UnreadableReason.code, message = UnreadableReason.httpMessage)
- [ ] Do NOT leak POI/PDFBox exception types or stack trace fragments into the 400 body for UnreadableDocumentException
- [ ] Define DuplicateDocumentResponse DTO for the 409 body (no raw Map)
- [ ] Define InvalidUploadResponse DTO for the 400 body (fields: error="invalid_upload", reason, message)
- [ ] Define UnreadableDocumentResponse DTO for the 400 body (fields: error="unreadable_document", reason, message) — distinct class from InvalidUploadResponse, not a union
- [ ] Route passes raw client filename to service without sanitizing or substituting defaults (empty string if multipart has no filename)
- [ ] Register AdminRoutes conditionally based on mode
- [ ] Write AdminRoutesTest for 201 Created path
- [ ] Write AdminRoutesTest for 409 Conflict path (body shape + negative assertion on status)
- [ ] Write AdminRoutesTest for 400 on InvalidUploadException(EmptyFile)
- [ ] Write AdminRoutesTest for 400 on InvalidUploadException(MissingFilename)
- [ ] Write AdminRoutesTest for 400 on InvalidUploadException(UnsafeFilename) with path-separator detail
- [ ] Write AdminRoutesTest for 400 on InvalidUploadException(UnsupportedExtension)
- [ ] Write AdminRoutesTest for 400 on InvalidUploadException(FileTooLarge)
- [ ] Write AdminRoutesTest for 500 on generic service exception (IllegalStateException etc.) — Invalid/Duplicate/Unreadable must NOT reach this path
- [ ] Write AdminRoutesTest for 400 on UnreadableDocumentException(CorruptedDocx) — body error == "unreadable_document", reason == "corrupted_docx"
- [ ] Write AdminRoutesTest for 400 on UnreadableDocumentException(CorruptedPdf) — reason == "corrupted_pdf"
- [ ] Write AdminRoutesTest for 400 on UnreadableDocumentException(EncryptedDocument("docx")) — reason == "encrypted_document"
- [ ] Write AdminRoutesTest for 400 on UnreadableDocumentException(EncryptedDocument("pdf")) — reason == "encrypted_document"
- [ ] Write AdminRoutesTest asserting UnreadableDocumentException body does NOT contain POI/PDFBox type names ("ZipException", "POIXMLException", "InvalidPasswordException", "at org.apache.poi", "at org.apache.pdfbox")
- [ ] Write AdminRoutesTest asserting `error` field stability — InvalidUpload path always yields "invalid_upload", Unreadable path always yields "unreadable_document", Empty path always yields "empty_content" (all three 400)
- [ ] Catch EmptyDocumentException in POST handler and map to 400 with EmptyDocumentResponse body (error = "empty_content", reason = EmptyDocumentReason.code, message = EmptyDocumentReason.httpMessage)
- [ ] Define EmptyDocumentResponse DTO for the 400 body (fields: error="empty_content", reason, message) — distinct class from InvalidUploadResponse and UnreadableDocumentResponse
- [ ] Write AdminRoutesTest for 400 on EmptyDocumentException(NoExtractableContent) — body error == "empty_content", reason == "no_extractable_content", message contains OCR hint
- [ ] Write AdminRoutesTest asserting 400-empty path response is NOT 201 (negative assertion — empty is not silently successful)
- [ ] Write AdminRoutesTest asserting route passes empty-string filename when multipart part has no filename header (no default substitution)
- [ ] Write AdminRoutesTest for GET list endpoint
- [ ] Write AdminRoutesTest asserting GET list preserves the order returned by DocumentService.listDocuments (pass-through; no route-level re-sort)
- [ ] Route handlers call DocumentService suspend methods directly — NO `withContext(Dispatchers.IO) { documentService.X() }` wrapping at the route layer
- [ ] Route handlers do NOT call `runBlocking { }`, do NOT launch detached coroutines, and do NOT wrap service calls in any additional dispatcher switch
- [ ] Static / review-time guard: grep AdminRoutes.kt for `withContext`, `Dispatchers`, `runBlocking`, `GlobalScope`, `launch {` — none of these tokens should appear in the file. If a future phase adds legitimate use, that file is the place to revisit this rule.
- [ ] Emit a prominent startup WARN log when `config.mode in (FULL, ADMIN)` announcing that `/api/admin/*` routes are unauthenticated and warning against public exposure (see Task 14 "Security boundary" for the acceptance criteria — severity, timing, intent)
- [ ] The WARN log fires via SLF4J at severity `WARN` (not INFO, not DEBUG) so it is not filtered out by standard log level configurations
- [ ] The WARN log fires exactly once at startup inside `Application.module()`, NOT on every request or periodically
- [ ] The WARN log is NOT emitted in `MODE=client` (admin routes are not registered in that mode, so there is nothing to warn about)
- [ ] No placeholder / stub / commented-out authentication code in AdminRoutes.kt or any related route file — either auth is absent (Phase 2, this task) or fully implemented (Phase 4, out of scope)
- [ ] No `AUTH_DISABLED` env var, no `DEV_BYPASS_AUTH` config flag, no "skip auth in debug mode" branch — the unprotected state is a property of Phase 2 code, not of config
- [ ] Static / review-time guard: grep the whole backend source tree for `Authentication`, `BearerAuth`, `JWT`, `authenticate {`, `principal` — these tokens should not appear anywhere in Phase 2 code. If any appears, it must be Phase 4 work that leaked into Phase 2 and must be reverted or deferred.
- [ ] Write AdminRoutesTest that captures the startup log output (via Logback test appender or equivalent) and asserts the WARN line is emitted exactly once when the test application starts in `MODE=full`
- [ ] Write AdminRoutesTest asserting the WARN line is NOT emitted when the test application starts in `MODE=client`
- [ ] Write AdminRoutesTest asserting an unauthenticated request to `POST /api/admin/documents` in `MODE=full` is accepted and reaches the service (negative confirmation — proves the absence of auth is tested behavior, not untested behavior). Pair this with a comment referencing the "Security boundary" subsection so a future reviewer knows this test exists on purpose.
- [ ] Write AdminRoutesTest for DELETE endpoint (204 success, 404 missing)
- [ ] Write AdminRoutesTest asserting routes are not registered in CLIENT mode

---

### Task 15: Final verification and cleanup

- Ensure all tests pass: `./gradlew test`
- Ensure build succeeds: `./gradlew build`
- Verify no compiler warnings or deprecation issues
- Check that all new files follow the project's package structure
- Verify import statements use named imports (no wildcard `*`)

- [ ] All tests pass
- [ ] Build succeeds without warnings
- [ ] No wildcard imports
- [ ] Code follows existing patterns from Phase 1
- [ ] Confirm Task 14's startup `*.tmp.*` scan explicitly documents its scope boundary: it handles temp files, NOT orphan final artifacts, and cross-references the Task 13 limitation section
- [ ] Confirm no code path under `processDocument` attempts to detect or heal orphan final artifacts (no pre-flight FS scan, no post-commit FS verification, no reconciliation logic on the hot path) — this is an accepted limitation, not a quietly-fixed one
- [ ] Confirm the "Document row lifecycle — success-only model" subsection in Task 13 is present, unchanged, and documents the conscious design choice — a late edit that adds `status` / `failed_at` / `error_code` columns, or a `UploadResult.Failed` variant, or any persisted failure state changes the lifecycle model and must be flagged in review for explicit approval
- [ ] Confirm `documents` schema as delivered by V001+V002+V003 has NO `status`, `failed_at`, `error_code`, `error_message`, or `retry_count` columns (query `PRAGMA table_info(documents)` and diff against expected column set)
- [ ] Confirm `Document` data class has NO `status`, `failedAt`, `errorCode`, or `errorMessage` field (compile-time check — no ctor param, no property)
- [ ] Confirm `UploadResult` sealed class has exactly two variants: `Created(Document)` and `Duplicate(Document)` — no `Failed` variant
- [ ] Confirm every failure-mode test from the Task 13 table includes an explicit "no row for the failed upload exists in documents" assertion, phrased as a success-only-invariant check, not only as a transaction-rollback side effect
- [ ] Confirm the startup WARN log fires in MODE=full and MODE=admin at WARN severity, once at startup (run the app in each mode and inspect log output, or verify via the AdminRoutesTest that captures log output)
- [ ] Confirm the startup WARN log does NOT fire in MODE=client
- [ ] Grep the whole backend source tree for `Authentication`, `BearerAuth`, `JWT`, `authenticate {`, `principal`, `Authorization` header handling — no matches expected in Phase 2 code. Any match is Phase 4 work that leaked in and must be reverted.
- [ ] Grep the whole backend source tree for `AUTH_DISABLED`, `DEV_BYPASS_AUTH`, `SKIP_AUTH`, `NO_AUTH` — no matches expected. The unprotected state is unconditional in Phase 2, not a runtime toggle.
- [ ] Confirm no placeholder / commented-out auth code exists in AdminRoutes.kt or Application.kt — unprotected is unprotected, not "almost authenticated"
- [ ] Confirm Phase 2 deployment documentation (when it lands — CLAUDE.md or README.md or a new DEPLOYMENT.md) does NOT recommend `MODE=full` or `MODE=admin` for internet-facing deployment, and explicitly states that `MODE=client` is the only acceptable mode for public exposure before Phase 4 auth. (If the deployment doc is not updated in Phase 2, this checkbox becomes a Phase 4 prerequisite.)
- [ ] Confirm WordParserTest and PdfParserTest have their suite-level pageNumber invariant checks in place and passing
