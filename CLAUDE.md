# AOS Documentation Chatbot

An offline RAG chatbot for AOS technical documentation. Users ask questions, the system retrieves relevant chunks from indexed Word/PDF documents and generates answers using a local LLM.

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

## Architecture

**Read `docs/ARCHITECTURE.md` for complete technical details** — it contains:
- Full system architecture with diagrams
- Database schema (SQLite)
- All API endpoints with request/response examples
- Document parsing strategy (AOS-specific)
- RAG pipeline details
- Queue system (Artemis)
- Docker configuration
- Implementation plan

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Kotlin 1.9 + Ktor 2.x |
| Frontend | React 18 + Vite 5 + TypeScript |
| UI | shadcn/ui + Tailwind CSS |
| Database | SQLite (embedded) |
| LLM | Ollama qwen2.5:7b-instruct-q4_K_M |
| Embeddings | Ollama bge-m3 |
| Queue | Apache Artemis (JMS) |
| Doc Parsing | Apache POI (Word), PDFBox (PDF) |

## Project Structure

```
aos-chatbot/
├── backend/                    # Kotlin + Ktor
│   ├── src/main/kotlin/com/aos/chatbot/
│   │   ├── Application.kt      # Entry point
│   │   ├── config/             # AppConfig, DatabaseConfig
│   │   ├── routes/             # ChatRoutes, AdminRoutes, AuthRoutes
│   │   ├── services/           # ChatService, SearchService, LlmService
│   │   ├── parsers/            # WordParser, PdfParser, AosParser
│   │   ├── models/             # Document, Chunk, User
│   │   └── db/                 # Database, repositories
│   └── build.gradle.kts
│
├── frontend/                   # React + Vite
│   ├── src/
│   │   ├── components/
│   │   │   ├── chat/           # ChatContainer, MessageList, ChatInput
│   │   │   ├── admin/          # DocumentList, SystemPromptEditor
│   │   │   └── ui/             # shadcn components
│   │   ├── hooks/              # useChat, useAuth, useDocuments
│   │   └── api/                # API client
│   └── package.json
│
├── docs/
│   └── ARCHITECTURE.md         # Full technical specification
│
├── docker-compose.yml
└── CLAUDE.md                   # This file
```

## Application Modes

The app supports three modes via `MODE` environment variable:

| Mode | Use Case | Features |
|------|----------|----------|
| `MODE=full` | Development/Testing | Chat + Admin + Parsing |
| `MODE=admin` | Preparation server | Admin + Parsing (no chat) |
| `MODE=client` | Production client | Chat only (read-only DB) |

## Key Conventions

### Kotlin Backend
- Use coroutines for all async operations
- Named exports, no wildcards
- Services are injected via constructor (manual DI)
- All API routes under `/api/`
- SSE for streaming responses
- JWT for authentication

### React Frontend
- Functional components with TypeScript
- TanStack Query for server state
- Zustand for auth state only
- shadcn/ui components (don't reinvent)
- Named exports from all files

### Database
- SQLite with embeddings stored as BLOB
- Migrations in `backend/src/main/resources/db/migration/`
- Foreign keys with CASCADE delete

### Testing
- Backend: JUnit 5 + MockK
- Frontend: Vitest + React Testing Library
- Test files next to source: `*.test.kt`, `*.test.tsx`

## AOS-Specific Parsing

The system handles special AOS document structures:

1. **MA-XX Troubleshooting Codes** — Parse as structured chunks with code, symptom, cause, solution
2. **Component Tables** — Preserve table structure, don't flatten to text
3. **Process/Dataflow Diagrams** — Extract images, link to surrounding text chunks

See `docs/ARCHITECTURE.md` Section 8 for details.

## Environment Variables

```bash
# Required
MODE=full|admin|client
JWT_SECRET=min-32-characters
ADMIN_PASSWORD=initial-admin-password

# Ollama
OLLAMA_URL=http://localhost:11434
OLLAMA_LLM_MODEL=qwen2.5:7b-instruct-q4_K_M
OLLAMA_EMBED_MODEL=bge-m3

# Artemis (existing infrastructure)
ARTEMIS_BROKER_URL=tcp://localhost:61616

# Paths
DATABASE_PATH=/data/aos.db
DATA_PATH=/data
```

## Common Tasks

### Add a new API endpoint
1. Create route in `backend/src/.../routes/`
2. Register in `Application.kt`
3. Add to `docs/ARCHITECTURE.md` API section

### Add a new document parser
1. Implement `DocumentParser` interface
2. Add to `ParserFactory`
3. Write tests with sample documents

### Add UI component
1. Check if shadcn/ui has it: `npx shadcn-ui@latest add <component>`
2. If custom, add to `frontend/src/components/`
3. Use Tailwind for styling

## Important Notes

- **Offline operation** — No external API calls, everything runs locally
- **Language** — UI is English only, but LLM handles DE+EN queries
- **Queue** — Artemis broker already exists on target servers, reuse it
- **No chat history persistence** — Sessions only, no DB storage of conversations
- **Vector search** — In-memory cosine similarity, not a separate vector DB

## Getting Started

1. Read `docs/ARCHITECTURE.md` thoroughly
2. Set up development environment (see Section 13 in ARCHITECTURE.md)
3. Start with Phase 1: Project setup and basic Ktor server
4. Follow the implementation plan in Section 15

---

*For any architectural questions, refer to `docs/ARCHITECTURE.md`*
