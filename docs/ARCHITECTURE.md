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
| **Frontend** | React + Vite | React 18, Vite 5 | SPA with TypeScript |
| **UI Components** | shadcn/ui + Tailwind | Latest | Modern, accessible components |
| **Database** | SQLite | 3.x | Documents, chunks, users, embeddings |
| **State Management** | TanStack Query | v5 | Server state, caching |

### 2.2 AI / ML Stack

| Component | Technology | Model | Purpose |
|-----------|------------|-------|---------|
| **LLM** | Ollama | qwen2.5:7b-instruct-q4_K_M | Response generation (~6 GB RAM) |
| **Embeddings** | Ollama | bge-m3 | Multilingual embeddings (~2 GB RAM) |
| **Vector Search** | In-memory Kotlin | — | Cosine similarity (<10ms for 1000 chunks) |
| **Vision (Phase 2)** | Ollama | LLaVA / Qwen-VL | Image understanding (~8 GB RAM) |

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
│  └───────────────┘   │ - users       │   │                          │
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
- `config/system-prompt.txt` — System prompt configuration
- `version.txt` — Package version and metadata

**Not included:**
- Original Word/PDF documents (not needed for inference)
- Ollama models (installed separately on each server)

---

## 5. Project Structure

```
aos-chatbot/
├── docker-compose.yml
├── docker-compose.dev.yml
├── docker-compose.admin.yml
├── docker-compose.client.yml
├── .env.example
├── README.md
├── ARCHITECTURE.md
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
│       │   │   │   ├── ChatRoutes.kt        # POST /api/chat (SSE)
│       │   │   │   ├── AdminRoutes.kt       # Document management
│       │   │   │   ├── AuthRoutes.kt        # Login/logout
│       │   │   │   ├── HealthRoutes.kt      # Health checks
│       │   │   │   └── ConfigRoutes.kt      # System prompt CRUD
│       │   │   │
│       │   │   ├── services/
│       │   │   │   ├── ChatService.kt       # RAG orchestration
│       │   │   │   ├── EmbeddingService.kt  # Ollama BGE-M3
│       │   │   │   ├── SearchService.kt     # In-memory vector search
│       │   │   │   ├── LlmService.kt        # Ollama qwen2.5
│       │   │   │   ├── QueueService.kt      # Artemis JMS
│       │   │   │   ├── DocumentService.kt   # CRUD operations
│       │   │   │   ├── AuthService.kt       # JWT handling
│       │   │   │   └── ExportService.kt     # Knowledge base export
│       │   │   │
│       │   │   ├── parsers/
│       │   │   │   ├── DocumentParser.kt    # Interface
│       │   │   │   ├── WordParser.kt        # Apache POI
│       │   │   │   ├── PdfParser.kt         # Apache PDFBox
│       │   │   │   ├── ChunkingService.kt   # Text chunking
│       │   │   │   └── aos/
│       │   │   │       ├── AosParser.kt     # AOS-specific logic
│       │   │   │       ├── ComponentParser.kt
│       │   │   │       ├── TroubleshootParser.kt  # MA-XX codes
│       │   │   │       └── ProcessParser.kt       # Dataflows
│       │   │   │
│       │   │   ├── models/
│       │   │   │   ├── Document.kt
│       │   │   │   ├── Chunk.kt
│       │   │   │   ├── ChatMessage.kt
│       │   │   │   ├── User.kt
│       │   │   │   └── QueueEvent.kt
│       │   │   │
│       │   │   └── db/
│       │   │       ├── Database.kt          # SQLite connection
│       │   │       ├── Migrations.kt        # Schema migrations
│       │   │       └── repositories/
│       │   │           ├── DocumentRepository.kt
│       │   │           ├── ChunkRepository.kt
│       │   │           └── UserRepository.kt
│       │   │
│       │   └── resources/
│       │       ├── application.conf         # Ktor config
│       │       ├── logback.xml              # Logging config
│       │       └── db/
│       │           └── migration/           # SQL migrations
│       │
│       └── test/
│           └── kotlin/com/aos/chatbot/
│               ├── parsers/
│               │   ├── WordParserTest.kt
│               │   ├── ChunkingServiceTest.kt
│               │   └── AosParserTest.kt
│               ├── services/
│               │   ├── SearchServiceTest.kt
│               │   └── ChatServiceTest.kt
│               └── routes/
│                   └── ChatRoutesTest.kt
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
    ├── aos.db                            # SQLite database
    ├── documents/                        # Original files (admin only)
    ├── images/                           # Extracted images
    └── config/
        └── system-prompt.txt
```

---

## 6. Database Schema

### 6.1 SQLite Schema

```sql
-- Users table (for JWT auth)
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',  -- 'admin' | 'user'
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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
    embedding BLOB NOT NULL,            -- Float32 array as bytes
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
    description TEXT,                   -- Phase 2: LLaVA description
    embedding BLOB,                     -- Phase 2: description embedding
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
data: {"sources": [{"document": "Manual.docx", "section": "3.2", "page": 15}]}

event: done
data: {"totalTokens": 256}
```

#### GET /api/chat/sources
Get sources from the last response.

**Response:**
```json
{
  "sources": [
    {
      "documentId": 1,
      "documentName": "AOS_Manual.docx",
      "section": "3.2.1",
      "page": 15,
      "snippet": "MA-03 indicates a connection timeout..."
    }
  ]
}
```

### 7.2 Admin Endpoints

#### GET /api/admin/documents
List all documents.

**Response:**
```json
{
  "documents": [
    {
      "id": 1,
      "filename": "AOS_Manual.docx",
      "fileType": "docx",
      "fileSize": 2048576,
      "chunkCount": 124,
      "imageCount": 15,
      "indexedAt": "2026-04-01T10:30:00Z"
    }
  ],
  "total": 5
}
```

#### POST /api/admin/documents
Upload and index a document.

**Request:** `multipart/form-data` with file

**Response:**
```json
{
  "id": 6,
  "filename": "NewDoc.docx",
  "status": "indexing",
  "jobId": "abc123"
}
```

#### DELETE /api/admin/documents/{id}
Delete document and all associated chunks/images.

#### POST /api/admin/reindex
Reindex all documents.

#### GET /api/admin/export
Download knowledge base as ZIP.

#### POST /api/admin/import
Import knowledge base from ZIP.

### 7.3 Config Endpoints

#### GET /api/config/system-prompt
Get current system prompt.

**Response:**
```json
{
  "prompt": "You are a helpful assistant...",
  "updatedAt": "2026-04-01T10:00:00Z"
}
```

#### PUT /api/config/system-prompt
Update system prompt.

**Request:**
```json
{
  "prompt": "You are an AOS documentation expert..."
}
```

### 7.4 Auth Endpoints

#### POST /api/auth/login
Authenticate user.

**Request:**
```json
{
  "username": "admin",
  "password": "secret"
}
```

**Response:**
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

#### POST /api/auth/logout
Invalidate token.

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
    "status": "up",
    "pending": 0
  }
}
```

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
- (Phase 2) LLaVA generates textual description

### 8.3 Chunking Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Max chunk size | 500 tokens | Fits in context window |
| Overlap | 50 tokens | Context continuity |
| Min chunk size | 100 tokens | Avoid tiny fragments |
| Preserve boundaries | Yes | Don't split mid-sentence |

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

```kotlin
class QueueService(private val config: ArtemisConfig) {
    
    private val connectionFactory = ActiveMQConnectionFactory(config.brokerUrl)
    private val connection = connectionFactory.createConnection()
    private val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
    private val requestQueue = session.createQueue("aos.chat.requests")
    private val responseQueue = session.createQueue("aos.chat.responses")
    
    suspend fun enqueue(request: ChatRequest): String {
        val requestId = UUID.randomUUID().toString()
        val producer = session.createProducer(requestQueue)
        val message = session.createTextMessage(Json.encodeToString(request))
        message.setStringProperty("requestId", requestId)
        producer.send(message)
        return requestId
    }
    
    fun getPosition(requestId: String): Int {
        // Query queue browser for position
    }
}
```

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

---

## 11. Authentication

### 11.1 JWT Configuration

| Parameter | Value |
|-----------|-------|
| Algorithm | HS256 |
| Expiration | 24 hours |
| Issuer | aos-chatbot |

### 11.2 Protected Routes

| Route Pattern | Required Role |
|---------------|---------------|
| `/api/chat/*` | user, admin |
| `/api/admin/*` | admin |
| `/api/config/*` | admin |
| `/api/health/*` | (public) |
| `/api/auth/*` | (public) |

### 11.3 Default Admin User

Created on first startup:
- Username: `admin`
- Password: from `ADMIN_PASSWORD` env var or generated

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

# Auth
JWT_SECRET=your-secret-key-min-32-chars
ADMIN_PASSWORD=initial-admin-password

# Paths
DATA_PATH=/data
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
    ollama {
        url = ${OLLAMA_URL}
        llmModel = ${OLLAMA_LLM_MODEL}
        embedModel = ${OLLAMA_EMBED_MODEL}
    }
}
```

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

On startup, send a dummy request to load models into memory:

```kotlin
// Application.kt
fun Application.warmupOllama() {
    launch {
        log.info("Warming up Ollama models...")
        embeddingService.embed("warmup")
        llmService.generate("warmup", emptyList())
        log.info("Ollama models loaded")
    }
}
```

---

## 15. Implementation Plan

### Phase 1: Foundation (Week 1-2)

- [ ] Project setup (Gradle, Vite, Docker)
- [ ] Basic Ktor server with routing
- [ ] SQLite database with migrations
- [ ] React app with routing
- [ ] Health check endpoints

### Phase 2: Document Processing (Week 3-4)

- [ ] WordParser (Apache POI)
- [ ] PdfParser (Apache PDFBox)
- [ ] AosParser (troubleshooting, tables)
- [ ] ChunkingService
- [ ] Image extraction
- [ ] Unit tests for parsers

### Phase 3: RAG Pipeline (Week 5-6)

- [ ] EmbeddingService (Ollama BGE-M3)
- [ ] SearchService (in-memory)
- [ ] LlmService (Ollama qwen2.5)
- [ ] ChatService (orchestration)
- [ ] SSE streaming
- [ ] Queue integration (Artemis)

### Phase 4: Admin Panel (Week 7)

- [ ] JWT authentication
- [ ] Document upload UI
- [ ] Document list/delete
- [ ] System prompt editor
- [ ] Export/Import

### Phase 5: Chat UI (Week 8)

- [ ] Chat interface
- [ ] Message streaming
- [ ] Source badges
- [ ] Queue status display
- [ ] History (session only)

### Phase 6: Polish (Week 9-10)

- [ ] Error handling
- [ ] Loading states
- [ ] MODE switching
- [ ] Documentation
- [ ] Integration tests
- [ ] Performance testing

---

## 16. Future Enhancements

### Phase 2 Features

| Feature | Description | Priority |
|---------|-------------|----------|
| **Vision LLM** | LLaVA/Qwen-VL for image understanding | High |
| **Feedback** | 👍👎 on responses for quality tracking | Medium |
| **Chat History** | Persist conversations (optional) | Low |
| **Keycloak** | SSO integration for some clients | Medium |
| **Multi-language UI** | DE + EN interface | Low |

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
