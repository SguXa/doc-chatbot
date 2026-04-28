# AOS Documentation Chatbot — Architecture Document

> **Version:** 1.0  
> **Date:** April 2026  
> **Status:** Ready for Implementation

---

## Table of Contents

1. [Overview](#1-overview)
2. [Technology Stack](#2-technology-stack)
3. [System Architecture](#3-system-architecture)
4. [Application Modes](#4-application-modes)
5. [Project Structure](#5-project-structure)
6. [Database Schema](#6-database-schema)
7. [API Contract](#7-api-contract)
8. [Document Parsing](#8-document-parsing)
9. [RAG Pipeline](#9-rag-pipeline)
10. [Queue System](#10-queue-system)
11. [Authentication](#11-authentication)
12. [Configuration](#12-configuration)
13. [Docker Setup](#13-docker-setup)
14. [Operations](#14-operations)
15. [Implementation Plan](#15-implementation-plan)
16. [Future Enhancements](#16-future-enhancements)

---

## 1. Overview

### 1.1 Purpose

AOS Documentation Chatbot is an **offline RAG (Retrieval-Augmented Generation) system** that allows users to ask questions about AOS technical documentation and receive accurate, context-aware answers with source references.

### 1.2 Key Features

- **Offline operation** — no internet required, all processing on-premise
- **Multilingual support** — German and English queries/responses
- **Document parsing** — Word (.docx) and PDF with structure preservation
- **AOS-specific parsing** — MA-XX troubleshooting codes, component tables, dataflow diagrams
- **Admin panel** — document management, system prompt editor, export/import
- **Queue system** — handles multiple concurrent users via Apache Artemis
- **Three deployment modes** — full (dev), admin (preparation), client (production)

### 1.3 Target Environment

| Resource | Requirement |
|----------|-------------|
| RAM | 32 GB (server has ~12 GB free after system processes) |
| CPU | Multi-core, no GPU required |
| Storage | ~10 GB for models + documents |
| Network | Offline (internal network only) |
| Existing Infrastructure | Apache Artemis broker |

---

## 2. Technology Stack

### 2.1 Core Stack

| Layer | Technology | Version | Purpose |
|-------|------------|---------|---------|
| **Backend** | Kotlin + Ktor | Kotlin 1.9+, Ktor 2.x | REST API, SSE streaming, coroutines |
| **Frontend** | React + Vite | React 19, Vite 8 | SPA with TypeScript |
| **UI Components** | shadcn/ui + Tailwind | Latest | Modern, accessible components |
| **Database** | SQLite | 3.x | Documents, chunks, embeddings |
| **State Management** | TanStack Query | v5 | Server state, caching |

### 2.2 AI / ML Stack

| Component | Technology | Model | Purpose |
|-----------|------------|-------|---------|
| **LLM** | Ollama | qwen2.5:7b-instruct-q4_K_M | Response generation (~6 GB RAM) |
| **Embeddings** | Ollama | bge-m3 | Multilingual embeddings (~2 GB RAM) |
| **Vector Search** | In-memory Kotlin | — | Cosine similarity (<10ms for 1000 chunks) |
| **Vision (future)** | Ollama | LLaVA / Qwen-VL | Image understanding (~8 GB RAM) — see §16 |

### 2.3 Document Processing

| Format | Library | Features |
|--------|---------|----------|
| **Word (.docx)** | Apache POI | Tables, styles, images, structure |
| **PDF** | Apache PDFBox | Text extraction, fallback when no Word |

### 2.4 Infrastructure

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Message Queue** | Apache Artemis (JMS) | Request queuing, existing infrastructure |
| **Containerization** | Docker Compose | Deployment |
| **Logging** | SLF4J + Logback | Structured logging to stdout |

---

## 3. System Architecture

### 3.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Docker Compose                               │
│                                                                      │
│  ┌─────────────┐     ┌──────────────────────────────────────────┐  │
│  │   Nginx     │     │           Kotlin + Ktor                   │  │
│  │   :3000     │────▶│              :8080                        │  │
│  │             │     │                                           │  │
│  │ React SPA   │     │  ┌─────────┐ ┌─────────┐ ┌─────────────┐ │  │
│  │ - Chat UI   │     │  │  Chat   │ │  Doc    │ │   Search    │ │  │
│  │ - Admin UI  │     │  │ Service │ │ Parser  │ │   Service   │ │  │
│  └─────────────┘     │  └────┬────┘ └────┬────┘ └──────┬──────┘ │  │
│                      │       │           │             │         │  │
│                      │  ┌────▼───────────▼─────────────▼──────┐ │  │
│                      │  │           QueueService (JMS)         │ │  │
│                      │  └────────────────┬─────────────────────┘ │  │
│                      └───────────────────┼───────────────────────┘  │
│                                          │                          │
│  ┌───────────────┐   ┌───────────────┐   │   ┌───────────────────┐ │
│  │    Ollama     │   │    SQLite     │   │   │      Files        │ │
│  │    :11434     │   │    aos.db     │   │   │  /data/images/    │ │
│  │               │   │               │   │   │  /data/documents/ │ │
│  │ - qwen2.5:7b  │   │ - documents   │   │   └───────────────────┘ │
│  │ - bge-m3     │   │ - chunks      │   │                          │
│  └───────────────┘   │ - images      │   │                          │
│                      │ - embeddings  │   │                          │
│                      └───────────────┘   │                          │
└──────────────────────────────────────────┼──────────────────────────┘
                                           │
                            ┌──────────────▼──────────────┐
                            │      Apache Artemis         │
                            │    (Existing Infrastructure) │
                            │         :61616              │
                            └─────────────────────────────┘
```

### 3.2 Request Flow

```
User Question
     │
     ▼
┌─────────────────┐
│ POST /api/chat  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  Enqueue to     │────▶│  Artemis Queue  │
│  Artemis (JMS)  │     │  aos.requests   │
└─────────────────┘     └────────┬────────┘
                                 │
         ┌───────────────────────┘
         ▼
┌─────────────────┐
│  Worker picks   │
│  from queue     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Generate query  │────▶│  Ollama BGE-M3  │
│ embedding       │     │  (embedding)    │
└────────┬────────┘     └─────────────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Vector search   │────▶│  In-memory      │
│ (top-k chunks)  │     │  cosine sim     │
└────────┬────────┘     └─────────────────┘
         │
         ▼
┌─────────────────┐
│ Build prompt    │
│ with context    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│ Generate answer │────▶│ Ollama qwen2.5  │
│ (streaming)     │     │ (LLM)           │
└────────┬────────┘     └─────────────────┘
         │
         ▼
┌─────────────────┐
│ SSE stream to   │
│ client          │
└─────────────────┘
```

---

## 4. Application Modes

The application supports three modes controlled by `MODE` environment variable:

### 4.1 Mode Comparison

| Feature | MODE=full | MODE=admin | MODE=client |
|---------|-----------|------------|-------------|
| Chat UI | ✅ | ❌ | ✅ |
| Admin UI | ✅ | ✅ | ❌ |
| Document Upload | ✅ | ✅ | ❌ |
| Document Parsing | ✅ | ✅ | ❌ |
| Export Knowledge Base | ✅ | ✅ | ❌ |
| Import Knowledge Base | ✅ | ✅ | ✅ |
| System Prompt Editor | ✅ | ✅ | ❌ |
| Database Mode | Read/Write | Read/Write | Read-only |
| **Use Case** | Development | Preparation | Production |

### 4.2 Deployment Scenarios

```
┌─────────────────────────────────────────────────────────────────┐
│                     Your Machine (Admin)                         │
│                                                                  │
│   MODE=full or MODE=admin                                        │
│   - Upload documents                                             │
│   - Parse and index                                              │
│   - Configure system prompt                                      │
│   - Test queries                                                 │
│   - Export: aos-knowledge.zip                                    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Transfer aos-knowledge.zip
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Client Server (Production)                   │
│                                                                  │
│   MODE=client                                                    │
│   - Chat UI only                                                 │
│   - Read-only database                                           │
│   - No admin access                                              │
│   - Import knowledge base on startup                             │
└─────────────────────────────────────────────────────────────────┘
```

### 4.3 Knowledge Base Package

**aos-knowledge.zip** contains:
- `aos.db` — SQLite database with chunks and embeddings
- `images/` — Extracted images from documents
- `version.txt` — Package version and metadata

The system prompt is stored in the `config` table of `aos.db` and ships inside the database file; there is no separate config text file.

**Not included:**
- Original Word/PDF documents (not needed for inference)
- Ollama models (installed separately on each server)

---

## 5. Project Structure

```
aos-chatbot/
├── docker-compose.yml
├── docker-compose.dev.yml
├── .env.example
├── README.md
├── CLAUDE.md
│
├── backend/                              # Kotlin + Ktor
│   ├── build.gradle.kts
│   ├── Dockerfile
│   ├── settings.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── kotlin/com/aos/chatbot/
│       │   │   ├── Application.kt           # Entry point
│       │   │   │
│       │   │   ├── config/
│       │   │   │   ├── AppConfig.kt         # MODE, env vars
│       │   │   │   ├── DatabaseConfig.kt    # SQLite setup
│       │   │   │   └── ArtemisConfig.kt     # JMS connection
│       │   │   │
│       │   │   ├── routes/
│       │   │   │   ├── AdminRoutes.kt       # Document management
│       │   │   │   ├── HealthRoutes.kt      # Health checks
│       │   │   │   ├── dto/
│       │   │   │   │   └── AdminResponses.kt # Response DTOs
│       │   │   │   ├── ChatRoutes.kt        # POST /api/chat (SSE) (Phase 3)
│       │   │   │   ├── AuthRoutes.kt        # Login/logout (Phase 4)
│       │   │   │   └── ConfigRoutes.kt      # System prompt CRUD (Phase 5)
│       │   │   │
│       │   │   ├── services/
│       │   │   │   ├── DocumentService.kt   # Upload pipeline orchestration
│       │   │   │   ├── UploadResult.kt      # Created | Duplicate
│       │   │   │   ├── InvalidUploadException.kt
│       │   │   │   ├── EmptyDocumentException.kt
│       │   │   │   ├── ChatService.kt       # RAG orchestration (Phase 3)
│       │   │   │   ├── EmbeddingService.kt  # Ollama BGE-M3 (Phase 3)
│       │   │   │   ├── SearchService.kt     # In-memory vector search (Phase 3)
│       │   │   │   ├── LlmService.kt        # Ollama qwen2.5 (Phase 3)
│       │   │   │   ├── QueueService.kt      # Artemis JMS (Phase 3+)
│       │   │   │   ├── AuthService.kt       # JWT handling (single admin, see §11)
│       │   │   │   └── ExportService.kt     # Knowledge base export (Phase 5)
│       │   │   │
│       │   │   ├── parsers/
│       │   │   │   ├── DocumentParser.kt    # Interface
│       │   │   │   ├── ParserFactory.kt     # Extension → parser mapping
│       │   │   │   ├── WordParser.kt        # Apache POI
│       │   │   │   ├── PdfParser.kt         # Apache PDFBox
│       │   │   │   ├── ChunkingService.kt   # Text chunking
│       │   │   │   ├── ImageExtractor.kt    # Atomic image persistence
│       │   │   │   ├── UnreadableDocumentException.kt
│       │   │   │   └── aos/
│       │   │   │       ├── AosParser.kt     # AOS-specific logic
│       │   │   │       ├── ComponentParser.kt
│       │   │   │       └── TroubleshootParser.kt  # MA-XX codes
│       │   │   │
│       │   │   ├── models/
│       │   │   │   ├── Document.kt
│       │   │   │   ├── Chunk.kt
│       │   │   │   ├── ExtractedImage.kt
│       │   │   │   ├── ParsedContent.kt     # TextBlock + ImageData
│       │   │   │   ├── ChatMessage.kt       # Phase 3
│       │   │   │   └── QueueEvent.kt        # Phase 3+
│       │   │   │
│       │   │   └── db/
│       │   │       ├── Database.kt          # SQLite connection
│       │   │       ├── Migrations.kt        # Schema migrations
│       │   │       └── repositories/
│       │   │           ├── DocumentRepository.kt
│       │   │           ├── ChunkRepository.kt
│       │   │           ├── ImageRepository.kt
│       │   │           └── UserRepository.kt   # Phase 4
│       │   │
│       │   └── resources/
│       │       ├── application.conf         # Ktor config
│       │       ├── logback.xml              # Logging config
│       │       └── db/
│       │           └── migration/           # SQL migrations
│       │
│       └── test/
│           └── kotlin/com/aos/chatbot/
│               ├── ApplicationTest.kt
│               ├── CleanupOrphanTempFilesTest.kt
│               ├── config/
│               │   └── AppConfigTest.kt
│               ├── db/
│               │   ├── DatabaseTest.kt
│               │   ├── MigrationsTest.kt
│               │   └── repositories/
│               │       ├── DocumentRepositoryTest.kt
│               │       ├── ChunkRepositoryTest.kt
│               │       └── ImageRepositoryTest.kt
│               ├── parsers/
│               │   ├── WordParserTest.kt
│               │   ├── PdfParserTest.kt
│               │   ├── ChunkingServiceTest.kt
│               │   ├── ImageExtractorTest.kt
│               │   ├── ParserFactoryTest.kt
│               │   └── aos/
│               │       ├── AosParserTest.kt
│               │       └── TroubleshootParserTest.kt
│               ├── services/
│               │   └── DocumentServiceTest.kt
│               └── routes/
│                   ├── AdminRoutesTest.kt
│                   └── HealthRoutesTest.kt
│
├── frontend/                             # React + Vite
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── Dockerfile
│   ├── nginx.conf
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       │
│       ├── components/
│       │   ├── chat/
│       │   │   ├── ChatContainer.tsx
│       │   │   ├── MessageList.tsx
│       │   │   ├── MessageBubble.tsx
│       │   │   ├── ChatInput.tsx
│       │   │   ├── SourceBadge.tsx
│       │   │   └── QueueStatus.tsx
│       │   │
│       │   ├── admin/
│       │   │   ├── AdminLayout.tsx
│       │   │   ├── DocumentList.tsx
│       │   │   ├── DocumentUpload.tsx
│       │   │   ├── SystemPromptEditor.tsx
│       │   │   ├── ExportImport.tsx
│       │   │   └── SystemStatus.tsx
│       │   │
│       │   ├── auth/
│       │   │   ├── LoginForm.tsx
│       │   │   └── ProtectedRoute.tsx
│       │   │
│       │   └── ui/                      # shadcn/ui components
│       │       ├── button.tsx
│       │       ├── input.tsx
│       │       ├── card.tsx
│       │       └── ...
│       │
│       ├── hooks/
│       │   ├── useChat.ts               # SSE streaming
│       │   ├── useDocuments.ts
│       │   ├── useAuth.ts
│       │   └── useQueueStatus.ts
│       │
│       ├── api/
│       │   ├── client.ts                # Axios/fetch setup
│       │   └── endpoints.ts
│       │
│       ├── stores/
│       │   └── authStore.ts             # Zustand for auth state
│       │
│       └── lib/
│           └── utils.ts
│
└── data/                                 # Mounted volume
    ├── aos.db                            # SQLite database (including `config` table — see §4.3)
    ├── documents/                        # Original files (admin only)
    └── images/                           # Extracted images
```

---

## 6. Database Schema

### 6.1 SQLite Schema

_The `users` table created by V001 was dropped in V005 — see [ADR 0007](adr/0007-single-admin-no-persisted-users.md)._

```sql
-- Documents table
CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    file_type TEXT NOT NULL,           -- 'docx' | 'pdf'
    file_size INTEGER,
    file_hash TEXT,                     -- SHA256 for deduplication
    chunk_count INTEGER DEFAULT 0,
    image_count INTEGER DEFAULT 0,
    indexed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Chunks table
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_type TEXT NOT NULL,         -- 'text' | 'table' | 'troubleshoot' | 'process'
    page_number INTEGER,
    section_id TEXT,                    -- '3.2.1' | 'MA-03'
    heading TEXT,                       -- Section heading for context
    embedding BLOB,                     -- Float32 array as bytes (nullable until Phase 3)
    image_refs TEXT,                    -- JSON array of image filenames
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- Images table
CREATE TABLE images (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    filename TEXT NOT NULL,             -- 'img_001.png'
    path TEXT NOT NULL,                 -- '/data/images/{doc_id}/img_001.png'
    page_number INTEGER,
    caption TEXT,
    description TEXT,                   -- Reserved for future vision-LLM description (see §16)
    embedding BLOB,                     -- Reserved for future image-description embedding (see §16)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- System configuration
CREATE TABLE config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_chunks_document ON chunks(document_id);
CREATE INDEX idx_chunks_content_type ON chunks(content_type);
CREATE INDEX idx_chunks_section ON chunks(section_id);
CREATE INDEX idx_images_document ON images(document_id);
CREATE UNIQUE INDEX idx_documents_file_hash_unique ON documents(file_hash);
```

### 6.2 Embedding Storage

Embeddings are stored as BLOBs (binary large objects) containing Float32 arrays:

```kotlin
// Storing embedding
fun storeEmbedding(embedding: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(embedding.size * 4)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    embedding.forEach { buffer.putFloat(it) }
    return buffer.array()
}

// Loading embedding
fun loadEmbedding(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buffer.getFloat() }
}
```

---

## 7. API Contract

### 7.1 Chat Endpoints

#### POST /api/chat
Start a chat query (returns SSE stream).

**Request:**
```json
{
  "message": "What is MA-03 error code?",
  "history": [
    { "role": "user", "content": "Previous question" },
    { "role": "assistant", "content": "Previous answer" }
  ]
}
```

**Response (SSE):**
```
event: queued
data: {"position": 2, "estimatedWait": 30}

event: processing
data: {"status": "Searching documents..."}

event: token
data: {"text": "MA-03"}

event: token
data: {"text": " is"}

event: sources
data: {"sources": [{"documentId": 1, "documentName": "Manual.docx", "section": "3.2", "page": 15, "snippet": "..."}]}

event: done
data: {"totalTokens": 256}
```

A terminal `event: error` frame with body `{"message": "..."}` replaces `event: done` on mid-stream failures.

**Error responses (before SSE opens):**

- `400 Bad Request` with `{"error": "invalid_request", "reason": "malformed_body" | "empty_message" | "message_too_long" | "history_too_long" | "invalid_history_role" | "history_entry_too_long"}` — request validation failed. `message` is capped at 4000 characters; `history` is capped at 20 entries, each with role in `{user, assistant}` and content up to 4000 characters.
- `503 Service Unavailable` + `Retry-After: 10` with `{"error": "not_ready", "reason": "embedding_backfill_in_progress"}` — backfill has not completed.
- `503 Service Unavailable` with `{"error": "not_ready", "reason": "embedding_backfill_failed", "message": "..."}` — the startup backfill terminated in a failure state; no `Retry-After` is sent because the condition is not self-healing. An operator must trigger `POST /api/admin/reindex` to clear it.
- `503 Service Unavailable` with `{"error": "queue_unavailable"}` — Artemis is unreachable.

### 7.2 Admin Endpoints

#### GET /api/admin/documents
List all documents. Ordering: **newest first** (`created_at DESC, id DESC`) — most recently uploaded documents appear first. The `id DESC` component is the deterministic tie-breaker for uploads within the same second. This ordering is enforced at the repository layer (`DocumentRepository.findAll()`); the route handler does not re-sort.

**Response:**
```json
{
  "documents": [
    {
      "id": 1,
      "filename": "AOS_Manual.docx",
      "fileType": "docx",
      "fileSize": 2048576,
      "fileHash": "a1b2c3d4e5...",
      "chunkCount": 124,
      "imageCount": 15,
      "indexedAt": "2026-04-01T10:30:00Z",
      "createdAt": "2026-04-01T10:30:00Z"
    }
  ],
  "total": 5
}
```

#### POST /api/admin/documents
Upload, parse, and index a document **synchronously**. The response is returned after parsing, chunking, image extraction, and all DB writes complete.

**Request:** `multipart/form-data` with file

**Responses:**

`201 Created` — document was successfully parsed and indexed:
```json
{
  "id": 6,
  "filename": "NewDoc.docx",
  "fileType": "docx",
  "fileSize": 182394,
  "fileHash": "a1b2c3...",
  "chunkCount": 42,
  "imageCount": 3,
  "indexedAt": "2026-04-14T12:34:56Z",
  "createdAt": "2026-04-14T12:34:56Z"
}
```

`400 Bad Request` — upload rejected at validation, unparseable content, or empty-after-parse. Body contains a stable `error` discriminator (`invalid_upload`, `unreadable_document`, or `empty_content`) and a `reason` subcode for client branching. The `invalid_upload` reasons are `unsupported_extension`, `malformed_multipart`, and the one-off 413/415 subcodes (`file_too_large`, `invalid_content_type`). Examples:
```json
{
  "error": "invalid_upload",
  "reason": "unsupported_extension",
  "message": "Unsupported file extension: 'exe'. Supported: docx, pdf"
}
```
```json
{
  "error": "invalid_upload",
  "reason": "malformed_multipart",
  "message": "Malformed multipart request body"
}
```
```json
{
  "error": "unreadable_document",
  "reason": "corrupted_docx",
  "message": "The uploaded .docx file could not be parsed: ..."
}
```
```json
{
  "error": "empty_content",
  "reason": "no_extractable_content",
  "message": "The uploaded document contains no text, tables, or images that can be indexed. It may be a blank document, a scanned PDF without OCR, or a file with only metadata."
}
```

`413 Payload Too Large` — file exceeds the 100 MB upload limit:
```json
{
  "error": "invalid_upload",
  "reason": "file_too_large",
  "message": "File exceeds maximum upload size of 100 MB"
}
```

`415 Unsupported Media Type` — request is not `multipart/form-data`:
```json
{
  "error": "invalid_upload",
  "reason": "invalid_content_type",
  "message": "Request must be multipart/form-data"
}
```

`409 Conflict` — a document with identical content (same SHA-256) already exists:
```json
{
  "error": "duplicate_document",
  "message": "A document with identical content has already been indexed. Delete the existing document first if you want to re-index.",
  "existing": {
    "id": 42,
    "filename": "troubleshooting_v2.docx",
    "indexedAt": "2026-03-28T14:12:03Z"
  }
}
```

`503 Service Unavailable` — a reindex is in flight and uploads are temporarily blocked:
```json
{
  "error": "reindex_in_progress",
  "message": "Reindex is running; uploads are temporarily blocked. Please retry shortly."
}
```

`503 Service Unavailable` — the inline embedding call to Ollama failed during indexing; the upload was rolled back and the source file removed. Retry once Ollama is reachable again (verify via `/api/health/ready`):
```json
{
  "error": "ollama_unavailable",
  "message": "Embedding service (Ollama) is unavailable; upload cannot be indexed. Please retry shortly."
}
```

**Execution model — synchronous (durable contract).** The endpoint blocks for the full parse/persist pipeline and returns the final outcome in one request/response. There is no `jobId`, no `status: "indexing"` polling, and no separate job status endpoint. If a future phase needs async upload it will introduce a **new endpoint**, not mutate the shape of `POST /api/admin/documents`. See [ADR 0001](adr/0001-synchronous-document-upload.md) for the full rationale.

**Deployment note.** As of Phase 4, admin routes require `Authorization: Bearer <token>` (§11). `MODE=full` and `MODE=admin` should still be restricted to operator workstations or VPN — chat remains public on the same listener and there is no rate limiting in Phase 4. See [ADR 0005](adr/0005-auth-deferred-out-of-phase-2.md) for the deferral context and [ADR 0007](adr/0007-single-admin-no-persisted-users.md) for the single-admin design.

#### DELETE /api/admin/documents/{id}
Delete document and all associated chunks/images. Also removes the source file from disk and the image directory.

**Responses:**

`204 No Content` — document and associated files deleted successfully.

`400 Bad Request` — invalid document ID:
```json
{"error": "Invalid document ID"}
```

`404 Not Found` — no document with the given ID:
```json
{"error": "Document not found"}
```

`503 Service Unavailable` — a reindex is in flight and deletes are temporarily blocked:
```json
{
  "error": "reindex_in_progress",
  "message": "Reindex is running; deletes are temporarily blocked. Please retry shortly."
}
```

#### POST /api/admin/reindex
Clear every chunk's stored embedding and re-embed all chunks via Ollama. Fire-and-forget — the response returns immediately and the job runs in the background. While it runs, `POST /api/admin/documents` returns `503` (`reindex_in_progress`) and `POST /api/chat` returns `503` (`not_ready`).

**Responses:**

`202 Accepted` — job accepted:
```json
{"status": "started"}
```

`202 Accepted` — a reindex is already in flight (idempotent):
```json
{"status": "already_running"}
```

See [ADR 0006](adr/0006-queue-chat-dispatch-with-in-memory-bus.md) for the interaction between inline-upload-embedding and reindex.

#### GET /api/admin/export
Download knowledge base as ZIP.

#### POST /api/admin/import
Import knowledge base from ZIP.

### 7.3 Config Endpoints

Admin-protected; require `Authorization: Bearer <token>` (see §11.2).

#### GET /api/config/system-prompt
Get current system prompt.

**Response:**
```json
{
  "prompt": "You are a helpful assistant...",
  "updatedAt": "2026-04-01 10:00:00"
}
```

`updatedAt` is the SQLite-native `YYYY-MM-DD HH:MM:SS` UTC string from the `config.updated_at` column (no `T` separator, no `Z` suffix). UI consumers must format for display.

**Error responses:**

- `500 Internal Server Error` with `{"error": "config_missing"}` — the seeded `system_prompt` row is absent (V004 normally guarantees it; this is a defensive fail-loud branch for manual DB edits).

#### PUT /api/config/system-prompt
Update system prompt.

**Request:**
```json
{
  "prompt": "You are an AOS documentation expert..."
}
```

**Response:** same shape as GET — the saved prompt and the new `updatedAt` timestamp.

**Error responses:**

- `400 Bad Request` with `{"error": "invalid_request", "reason": "malformed_body" | "empty_prompt" | "prompt_too_long"}` — body is not valid JSON, prompt is blank, or prompt exceeds the 8000-character cap (UTF-16 code units).
- `500 Internal Server Error` with `{"error": "config_missing"}` — defensive (post-write read returned no row).

### 7.4 Auth Endpoints

#### POST /api/auth/login
Authenticate the administrator. _The `username` field is accepted for forward-compatibility with future multi-user support but is ignored by the server in Phase 4. Only `password` is validated. See [ADR 0007](adr/0007-single-admin-no-persisted-users.md)._

**Request:**
```json
{
  "username": "admin",
  "password": "secret"
}
```

**Response (`username` and `role` in the response are constants — there is no other user):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "expiresIn": 86400,
  "user": {
    "username": "admin",
    "role": "admin"
  }
}
```

**Error responses:**

- `400 Bad Request` with `{"error": "invalid_request", "reason": "malformed_body" | "empty_password"}` — request validation failed.
- `401 Unauthorized` with `{"error": "invalid_credentials"}` — wrong password.

Only registered in `MODE=full` and `MODE=admin`. In `MODE=client` the route returns 404.

#### POST /api/auth/logout
Stateless logout — always returns `204 No Content`. The server keeps no session state; JWTs naturally expire after 24 hours. See [ADR 0007](adr/0007-single-admin-no-persisted-users.md).

### 7.5 Health Endpoints

#### GET /api/health
Basic health check.

**Response:**
```json
{
  "status": "healthy"
}
```

#### GET /api/health/ready
Readiness check (all dependencies up).

**Response:**
```json
{
  "status": "ready",
  "ollama": {
    "status": "up",
    "models": ["qwen2.5:7b-instruct-q4_K_M", "bge-m3"]
  },
  "database": {
    "status": "up",
    "documents": 5,
    "chunks": 847
  },
  "queue": {
    "status": "up"
  },
  "backfill": {
    "status": "ready"
  }
}
```

Returns `503 Service Unavailable` with the same body shape whenever any of `ollama.status`, `queue.status`, or `backfill.status` is not `up`/`ready`. The `backfill.status` field is `"ready"` once the startup embedding backfill has completed, `"running"` while a backfill or reindex is in flight, `"idle"` before the job starts, and `"failed"` if the backfill terminated in an unrecoverable error (an operator must then trigger `POST /api/admin/reindex`).

#### GET /api/stats
System statistics.

**Response:**
```json
{
  "documents": 5,
  "chunks": 847,
  "images": 42,
  "embeddingDimension": 1024,
  "databaseSize": "52 MB",
  "uptime": "2d 5h 32m"
}
```

---

## 8. Document Parsing

### 8.1 Parsing Strategy

```
Document Upload
      │
      ▼
┌─────────────────┐
│ Detect Format   │
│ .docx or .pdf   │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌───────┐
│ Word  │ │  PDF  │
│ (POI) │ │(PDFBox│
└───┬───┘ └───┬───┘
    │         │
    └────┬────┘
         ▼
┌─────────────────┐
│  AosParser      │
│  (specialized)  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ChunkingService │
│ - Split text    │
│ - Preserve ctx  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ EmbeddingService│
│ - BGE-M3        │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Store in SQLite │
└─────────────────┘
```

### 8.2 AOS-Specific Parsing

#### Troubleshooting Codes (MA-XX)

**Input:**
```
MA-03: Connection Timeout
Symptom: Device does not respond within 30 seconds.
Cause: Network cable disconnected or firewall blocking.
Solution: 
1. Check network cable connection
2. Verify firewall rules
3. Restart the service
```

**Parsed Chunk:**
```json
{
  "content_type": "troubleshoot",
  "section_id": "MA-03",
  "content": "MA-03: Connection Timeout\nSymptom: Device does not respond within 30 seconds.\nCause: Network cable disconnected or firewall blocking.\nSolution: 1. Check network cable connection 2. Verify firewall rules 3. Restart the service",
  "heading": "Troubleshooting Codes"
}
```

#### Component Tables

Tables are preserved as structured text with clear column/row relationships.

#### Process/Dataflow Diagrams

- Images extracted and saved to `/data/images/{doc_id}/`
- Reference stored in chunk's `image_refs` field
- *Future enhancement (see §16)*: vision LLM generates a textual description that gets embedded for image-aware retrieval

### 8.3 Chunking Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Max chunk size | 500 tokens | Fits in context window |
| Overlap | 50 tokens | Context continuity |
| Min chunk size | 100 tokens | Avoid tiny fragments |
| Preserve boundaries | Yes | Don't split mid-sentence |

### 8.4 Image Linkage Contract

The pipeline must not lose the association between extracted images and the text they relate to. This is a durable contract enforced by every parsing component (parsers, chunking service, AOS post-processors, image extractor, document service).

**Stable identifier.** `ImageData.filename` is the stable handle for an image throughout the entire pipeline:

- Parsers generate it. It lives unchanged on `TextBlock.imageRefs` and the resulting `Chunk.imageRefs` as a Kotlin `List<String>` throughout the domain layer.
- `ImageExtractor` writes the file to disk using this exact filename — no renaming.
- The `images` table row stores this exact filename.
- JSON serialization happens **only** at the `ChunkRepository` boundary when binding to `chunks.image_refs`. No other layer touches the JSON form.

**Per-document namespace.** Filenames are scoped per document:

- Disk layout: `{imagesPath}/{documentId}/{filename}`.
- The `images` table uses `(document_id, filename)` as the logical lookup tuple.
- Parsers reuse simple per-document schemes without collision between documents.

**Filename conventions parsers MUST follow:**

- **Word (.docx)** — `img_{NNN}.{ext}` where `NNN` is a 3-digit sequence starting at `001`, in document traversal order, and `ext` matches the blob's MIME type (`png`, `jpg`, `gif`, ...).
- **PDF** — `img_p{PAGE}_{NNN}.{ext}` with the page number embedded in the name. Sequence `NNN` resets per page.

**Referential integrity invariant** (validated before persistence):

- Every filename appearing in any `TextBlock.imageRefs` MUST appear as the `filename` of exactly one `ImageData` in `ParsedContent.images`.
- Every `ImageData` MUST appear in exactly one `TextBlock.imageRefs` (no orphans, no duplicates across blocks). Exception: PDF parser may emit a synthetic empty `TextBlock` solely to carry image refs for an image-only page.
- Violations cause pipeline failure with the standard rollback/compensation path — they do not silently drop images.

**Preservation through the pipeline:**

- AOS post-processors MUST NOT drop or rewrite `imageRefs` when converting a block's `type` (e.g., text → troubleshoot). The field passes through unchanged.
- `ChunkingService` MUST replicate the full `imageRefs` list onto every chunk produced by splitting a parent `TextBlock`. Duplication is cheap and ensures retrieval via any matching chunk surfaces the related images.
- `ChunkRepository` serializes `List<String>` to a JSON array string when binding to `chunks.image_refs`. **Empty list serializes to SQL `NULL`**, not `"[]"`, to keep the column semantically consistent with "no image references". On read, `NULL` deserializes back to `emptyList()` — no nullable `List<String>?` is surfaced to callers.

### 8.5 pageNumber Population Policy

`pageNumber: Int?` on `TextBlock`, `Chunk`, `ExtractedImage`, and `ImageData` is populated only when the source format provides it reliably.

| Component | pageNumber behavior |
|-----------|---------------------|
| **PdfParser** | Sets `pageNumber = N` on every TextBlock and ImageData emitted from page N (1-indexed from PDFBox). |
| **WordParser** | Sets `pageNumber = null` on every emitted block. Apache POI does not expose reliable rendered page numbers for `.docx`, and no heuristic substitute is permitted (paragraph counts, explicit page breaks, section properties, etc. all give wrong answers under common document layouts). |
| **ChunkingService, AOS post-processors, ImageExtractor, DocumentService** | Pass `pageNumber` through unchanged. `null` stays `null`, `N` stays `N`. No transformation. |

Downstream consumers (RAG retrieval, future UI citation) MUST handle `null` as "page unknown" and MUST NOT fabricate a default. Changing the WordParser policy to fabricate page numbers requires explicit design review — it is not a quiet refactor.

---

## 9. RAG Pipeline

### 9.1 Retrieval

```kotlin
class SearchService(private val chunks: List<ChunkWithEmbedding>) {
    
    fun search(queryEmbedding: FloatArray, topK: Int = 5): List<SearchResult> {
        return chunks
            .map { chunk ->
                SearchResult(
                    chunk = chunk,
                    score = cosineSimilarity(queryEmbedding, chunk.embedding)
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
```

### 9.2 Prompt Template

```
System: {system_prompt}

Context from AOS documentation:
---
{chunk_1}
[Source: {document_1}, Section {section_1}, Page {page_1}]

{chunk_2}
[Source: {document_2}, Section {section_2}, Page {page_2}]

{chunk_3}
[Source: {document_3}, Section {section_3}, Page {page_3}]
---

Conversation history:
User: {previous_question}
Assistant: {previous_answer}

Current question: {user_question}

Instructions:
- Answer based ONLY on the provided context
- If the answer is not in the context, say "I don't have information about this"
- Cite sources using [Source: Document, Section X.X]
- Respond in the same language as the question
```

### 9.3 Default System Prompt

```
You are an AOS Documentation Assistant. Your role is to help users find information 
in the AOS technical documentation.

Guidelines:
- Provide accurate, concise answers based on the documentation
- Always cite your sources with document name and section
- For troubleshooting codes (MA-XX), provide the full symptom, cause, and solution
- If information is not available, clearly state that
- Respond in German if the question is in German, otherwise in English
- Format code and technical terms appropriately
```

---

## 10. Queue System

### 10.1 Artemis Integration

Only the request queue `aos.chat.requests` is used — response streaming goes through an in-process bus (see §10.3 and [ADR 0006](adr/0006-queue-chat-dispatch-with-in-memory-bus.md)). Each request is tagged with a `correlationId` set on `JMSCorrelationID`; the same id keys the `ChatResponseBus` channel used by the SSE handler. Producers create a short-lived `Session`/`MessageProducer` per `enqueue` (JMS sessions are not thread-safe); consumers use a dedicated `CLIENT_ACKNOWLEDGE` session with a manual receive loop rather than a `MessageListener`. `QueueService.getPosition(correlationId)` uses a `QueueBrowser` snapshot — the returned position may already be stale by the time the caller reads it; a value of `-1` means the message has already been consumed and the route clamps it to `0`. See `backend/src/main/kotlin/com/aos/chatbot/services/QueueService.kt` for the full implementation.

### 10.2 Queue Events (SSE)

```kotlin
sealed class QueueEvent {
    data class Queued(val position: Int, val estimatedWait: Int) : QueueEvent()
    data class Processing(val status: String) : QueueEvent()
    data class Token(val text: String) : QueueEvent()
    data class Sources(val sources: List<Source>) : QueueEvent()
    data class Done(val totalTokens: Int) : QueueEvent()
    data class Error(val message: String) : QueueEvent()
}
```

### 10.3 Token Delivery Path

The `aos.chat.responses` JMS queue shown conceptually in earlier drafts is **not** implemented. Only the initial `ChatRequest` travels through Artemis (`aos.chat.requests`), carrying a `correlationId` used for fair ordering across concurrent chat sessions.

The consumer is a coroutine running inside the same JVM as the HTTP server. Once it dequeues a request, it runs the RAG pipeline (embed → search → LLM stream) and publishes each `QueueEvent` onto an in-memory bus keyed by `correlationId`. The SSE route handler subscribes to the same key and forwards events to the client. Tokens never traverse JMS, so per-token latency stays at microsecond range.

A `Channel<QueueEvent>(capacity = Channel.UNLIMITED)` is used rather than a `SharedFlow` so emissions produced before the SSE route starts collecting are buffered, eliminating the subscribe-before-enqueue race.

See [ADR 0006](adr/0006-queue-chat-dispatch-with-in-memory-bus.md) for the rationale, the consequences for deployment (SSE handler and consumer must share a JVM), and the known limitation that client disconnects do not currently cancel the in-flight Ollama HTTP call.

---

## 11. Authentication

Phase 4 introduces JWT-based authentication for the admin surface. Single administrator, single pre-shared password from `ADMIN_PASSWORD`. See [ADR 0005](adr/0005-auth-deferred-out-of-phase-2.md) for the deferral context and [ADR 0007](adr/0007-single-admin-no-persisted-users.md) for the single-admin design.

### 11.1 JWT Configuration

| Parameter | Value |
|-----------|-------|
| Algorithm | HS256 |
| Expiration | 24 hours |
| Issuer | aos-chatbot |

No `aud` claim. No `role` claim — the single administrator is identified by token validity alone. See [ADR 0007](adr/0007-single-admin-no-persisted-users.md).

### 11.2 Protected Routes

| Route Pattern | Auth |
|---------------|------|
| `/api/admin/*` | required |
| `/api/config/*` | required |
| `/api/chat/*` | public |
| `/api/health/*` | public |
| `/api/auth/*` | public |

Chat is intentionally public — see [ADR 0007](adr/0007-single-admin-no-persisted-users.md) for the rationale and [ADR 0005](adr/0005-auth-deferred-out-of-phase-2.md) for the deployment-mode constraint that contains this exposure to internal networks. `/api/config/*` endpoints are registered alongside `/api/admin/*` inside the `jwt-admin` `authenticate { }` block in `Application.kt`.

### 11.3 Single Administrator

**Single administrator.** A single operator authenticates with the password supplied via the `ADMIN_PASSWORD` environment variable. The password is bcrypt-hashed in memory at startup and is **not persisted**. To rotate the password, change the env var and restart the service. There is no `users` table in the database — see [ADR 0007](adr/0007-single-admin-no-persisted-users.md).

---

## 12. Configuration

### 12.1 Environment Variables

```bash
# Application Mode
MODE=full|admin|client

# Server
PORT=8080
HOST=0.0.0.0

# Database
DATABASE_PATH=/data/aos.db

# Ollama
OLLAMA_URL=http://ollama:11434
OLLAMA_LLM_MODEL=qwen2.5:7b-instruct-q4_K_M
OLLAMA_EMBED_MODEL=bge-m3

# Artemis
ARTEMIS_BROKER_URL=tcp://artemis:61616
ARTEMIS_USER=
ARTEMIS_PASSWORD=

# Auth — required in MODE=full and MODE=admin; ignored in MODE=client. The
# application refuses to start if either is missing in the relevant modes.
# See ADR 0005 (deferral context) and ADR 0007 (single-admin design).
JWT_SECRET=your-secret-key-min-32-chars
ADMIN_PASSWORD=initial-admin-password

# Paths
DATA_PATH=/data
# DOCUMENTS_PATH and IMAGES_PATH default to ${DATA_PATH}/documents and
# ${DATA_PATH}/images respectively. Override only when ops needs to mount
# them on separate volumes from DATA_PATH.
DOCUMENTS_PATH=/data/documents
IMAGES_PATH=/data/images

# Logging
LOG_LEVEL=INFO
LOG_FORMAT=json|text
```

### 12.2 Config Files

**backend/src/main/resources/application.conf:**
```hocon
ktor {
    deployment {
        port = ${PORT}
        host = ${HOST}
    }
    application {
        modules = [ com.aos.chatbot.ApplicationKt.module ]
    }
}

app {
    mode = ${MODE}
    database {
        path = ${DATABASE_PATH}
    }
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
    ollama {
        url = ${OLLAMA_URL}
        llmModel = ${OLLAMA_LLM_MODEL}
        embedModel = ${OLLAMA_EMBED_MODEL}
    }
    artemis {
        brokerUrl = ${ARTEMIS_BROKER_URL}
        user = ${?ARTEMIS_USER}
        password = ${?ARTEMIS_PASSWORD}
    }
    auth {
        jwtSecret = ""
        jwtSecret = ${?JWT_SECRET}
        adminPassword = ""
        adminPassword = ${?ADMIN_PASSWORD}
    }
}
```

### 12.3 Path Defaults and Override Behavior

Path-related env vars derive from a single base, `DATA_PATH`. Setting only `DATA_PATH` is sufficient for normal deployments — the other paths auto-derive via HOCON substitution.

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATA_PATH` | `./data` | Base data directory |
| `DATABASE_PATH` | `./data/aos.db` | SQLite database file |
| `DOCUMENTS_PATH` | `${DATA_PATH}/documents` | Uploaded source documents |
| `IMAGES_PATH` | `${DATA_PATH}/images` | Extracted images |

`DOCUMENTS_PATH` and `IMAGES_PATH` may be overridden independently when ops needs to mount them on separate volumes. They are read by `AppConfig` from `app.paths.documents` and `app.paths.images`; no Kotlin code composes these paths ad-hoc.

---

## 13. Docker Setup

### 13.1 docker-compose.yml (Full)

```yaml
version: '3.8'

services:
  backend:
    build: ./backend
    container_name: aos-backend
    environment:
      - MODE=full
      - PORT=8080
      - DATABASE_PATH=/data/aos.db
      - OLLAMA_URL=http://ollama:11434
      - ARTEMIS_BROKER_URL=tcp://artemis:61616
      # Required in MODE=full/admin; the backend refuses to start without them.
      # See ADR 0007 (single-admin design).
      - JWT_SECRET=${JWT_SECRET}
      - ADMIN_PASSWORD=${ADMIN_PASSWORD}
    volumes:
      - aos-data:/data
    ports:
      - "8080:8080"
    depends_on:
      - ollama
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped

  frontend:
    build: ./frontend
    container_name: aos-frontend
    ports:
      - "3000:80"
    depends_on:
      - backend
    restart: unless-stopped

  ollama:
    image: ollama/ollama:latest
    container_name: aos-ollama
    volumes:
      - ollama-models:/root/.ollama
    ports:
      - "11434:11434"
    deploy:
      resources:
        limits:
          memory: 12G
    restart: unless-stopped

volumes:
  aos-data:
  ollama-models:

networks:
  default:
    external: true
    name: aos-network
```

### 13.2 docker-compose.client.yml

```yaml
version: '3.8'

services:
  backend:
    image: aos-chatbot:latest
    environment:
      - MODE=client
      - ARTEMIS_BROKER_URL=tcp://artemis.internal:61616
    volumes:
      - ./data:/data:ro  # Read-only!
    # ... rest same as above

  # No admin frontend in client mode
```

### 13.3 Backend Dockerfile

```dockerfile
FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 13.4 Frontend Dockerfile

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

---

## 14. Operations

### 14.1 Logging

- **Format:** JSON for production, text for development
- **Output:** stdout (Docker collects)
- **Levels:** ERROR, WARN, INFO, DEBUG

```kotlin
// Example log output
{
  "timestamp": "2026-04-01T10:30:00.123Z",
  "level": "INFO",
  "logger": "ChatService",
  "message": "Query processed",
  "requestId": "abc123",
  "duration": 1250,
  "chunksFound": 5
}
```

### 14.2 Health Checks

| Endpoint | Purpose | Used By |
|----------|---------|---------|
| `/api/health` | Basic liveness | Docker HEALTHCHECK |
| `/api/health/ready` | Full readiness | Load balancer |
| `/api/stats` | Metrics | Monitoring |

### 14.3 Backup Strategy

```bash
# Cron job (daily at 2 AM)
0 2 * * * cp /data/aos.db /backup/aos_$(date +\%Y\%m\%d).db

# Keep last 7 days
find /backup -name "aos_*.db" -mtime +7 -delete
```

### 14.4 Model Warm-up

On startup, `ModelWarmup.warmupAsync(scope)` fires one dummy embed and one dummy
LLM call to load both models into Ollama's RAM before the first real request.
Warmup is fire-and-forget and does NOT gate `/api/health/ready`; failures log at
WARN and are swallowed. See `services/ModelWarmup.kt`.

---

## 15. Implementation Plan

> This section is the **high-level roadmap only**. Detailed task-by-task execution lives in `docs/plans/`. Architectural decisions with longer rationale live in `docs/adr/`.

### Phase 1: Foundation (Week 1-2)

- [x] Project setup (Gradle, Vite, Docker)
- [x] Basic Ktor server with routing
- [x] SQLite database with migrations
- [x] React app with routing
- [x] Health check endpoints

### Phase 2: Document Processing (Week 3-4)

- [x] WordParser (Apache POI)
- [x] PdfParser (Apache PDFBox)
- [x] AosParser (troubleshooting, tables)
- [x] ChunkingService
- [x] Image extraction
- [x] Unit tests for parsers

### Phase 3: RAG Pipeline (Week 5-6)

- [x] EmbeddingService (Ollama BGE-M3)
- [x] SearchService (in-memory)
- [x] LlmService (Ollama qwen2.5)
- [x] ChatService (orchestration)
- [x] SSE streaming
- [x] Queue integration (Artemis)

### Phase 4: Auth (single admin) (Week 7)

- [x] JWT authentication
- [x] Admin route protection (`/api/admin/*`, `/api/config/*` behind `jwt-admin`)

### Phase 5: Admin UI (Week 7-8)

- [x] Document upload UI
- [x] Document list/delete
- [x] System prompt editor
- [x] Reindex UI

> Export/Import deferred to Phase 6.

### Phase 6: Chat UI + Export/Import (Week 8-9)

- [ ] Chat interface
- [ ] Message streaming
- [ ] Source badges
- [ ] Queue status display
- [ ] History (session only)
- [ ] Export/Import knowledge base

### Phase 7: Polish (Week 9-10)

- [ ] Error handling
- [ ] Loading states
- [ ] MODE switching
- [ ] Documentation
- [ ] Integration tests
- [ ] Performance testing

---

## 16. Future Enhancements

> Items in this section are **not** scoped to any current implementation phase. They are forward-looking enhancements to be planned separately. Do not confuse them with the active `Phase N` work tracked in `docs/plans/`.

### Future Feature Backlog

| Feature | Description | Priority |
|---------|-------------|----------|
| **Vision LLM** | LLaVA/Qwen-VL for image understanding | High |
| **Feedback** | 👍👎 on responses for quality tracking (implementation will key feedback on the `correlationId` already produced by the chat pipeline; no conversation storage is required for rating) | Medium |
| **Chat History** | Persist conversations (optional) | Low |
| **Keycloak** | SSO integration for some clients | Medium |
| **Multi-language UI** | DE + EN interface | Low |
| **System Prompt Preview** | Render the final prompt with retrieval context without sending it to the LLM, for debugging prompt formulations | Medium |
| **Document Inspect mode** | Read-only chunk viewer per document, so operators can verify parse quality after upload (candidate for Phase 7) | Medium |
| **PDF tables extraction** | Use `tabula-java` to recognize tabular structure in PDFs (currently text becomes mush) | Medium |
| **PDF OCR** | Use `tess4j` for scanned PDFs that currently produce `empty_content` | Low |

### Scalability Options

- **PostgreSQL + pgvector** — if >10,000 chunks
- **Redis Queue** — if multiple backend instances needed
- **Kubernetes** — for high availability

---

## Appendix A: Sample AOS Document Structure

```
AOS Technical Manual
├── 1. Introduction
├── 2. Installation
│   ├── 2.1 Requirements
│   └── 2.2 Setup Steps
├── 3. Components
│   ├── 3.1 Component A
│   │   └── [Table: Properties]
│   └── 3.2 Component B
├── 4. Troubleshooting
│   ├── MA-01: Error Description
│   ├── MA-02: Error Description
│   └── MA-03: Error Description
├── 5. Processes
│   └── [Dataflow Diagrams]
└── Appendices
```

---

## Appendix B: Technology Versions

| Technology | Version | Notes |
|------------|---------|-------|
| Kotlin | 1.9.x | |
| Ktor | 2.3.x | |
| React | 18.x | |
| Vite | 5.x | |
| TypeScript | 5.x | |
| SQLite | 3.x | |
| Docker | 24.x | |
| Ollama | Latest | |
| Apache POI | 5.x | |
| Apache PDFBox | 3.x | |
| SLF4J | 2.x | |
| Logback | 1.4.x | |
| JUnit | 5.x | |

---

*Document generated: April 2026*
*Version: 1.0*
