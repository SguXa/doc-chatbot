# AOS Documentation Chatbot

An offline RAG chatbot for AOS technical documentation. Users ask questions, the system retrieves relevant chunks from indexed Word/PDF documents and generates answers using a local LLM.

## Prerequisites

- JDK 17+
- Node.js 20+
- Docker and Docker Compose (for containerized deployment)

## Quick Start (Local Development)

### Backend

```bash
cd backend
./gradlew run
```

The backend starts on http://localhost:8080. Verify with:

```bash
curl http://localhost:8080/api/health
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend starts on http://localhost:5173 and proxies `/api` requests to the backend.

## Docker Usage

Copy `.env.example` to `.env` and adjust values as needed.

**Production (`docker-compose.yml`) — requires external infrastructure.** The production stack (MODE=client) does NOT bundle Ollama or Artemis; operators point `OLLAMA_URL` and `ARTEMIS_BROKER_URL` at pre-existing shared infrastructure reachable from the backend container (see ARCHITECTURE.md §13.2). Before `docker compose up`, set these in `.env`:

```
OLLAMA_URL=http://<your-ollama-host>:11434
ARTEMIS_BROKER_URL=tcp://<your-artemis-host>:61616
```

Without reachable hosts at those URLs, `/api/health/ready` returns 503 and chat is refused. The development stack (`docker-compose.dev.yml`) bundles Artemis and reaches the host's Ollama via `host.docker.internal`, so no external setup is needed there.

```bash
# Production (external Ollama + Artemis required — see above)
docker compose build
docker compose up -d

# Development (full mode with admin routes exposed; Artemis bundled, Ollama on host)
docker compose -f docker-compose.dev.yml up
```

Health endpoints (production — via frontend proxy on port 3000):
- Liveness: http://localhost:3000/api/health
- Readiness: http://localhost:3000/api/health/ready

Health endpoints (dev — backend port exposed directly):
- Liveness: http://localhost:8080/api/health
- Readiness: http://localhost:8080/api/health/ready

Stop with:

```bash
docker compose down
```

## Running Tests

### Backend

```bash
cd backend
./gradlew test
```

### Integration testing

Integration tests tagged `@Tag("integration")` exercise the full stack against a locally running Ollama. They are excluded from the default `test` task; run them explicitly:

```bash
cd backend
OLLAMA_TEST_URL=http://localhost:11434 ./gradlew integrationTest
```

Without `OLLAMA_TEST_URL`, the suite is skipped cleanly via an `@EnabledIfEnvironmentVariable` guard. Ollama must have both `bge-m3` and `qwen2.5:7b-instruct-q4_K_M` pulled locally. Expected runtime: ~60 s on warm models.

### Frontend

```bash
cd frontend
npm test
```

### Linting

```bash
cd frontend
npm run lint
```

## Document Management (Admin)

In `MODE=full` or `MODE=admin`, the following admin endpoints are available:

- `POST /api/admin/documents` — Upload and index a document (multipart/form-data, max 100 MB). Blocked with 503 while a reindex is running.
- `GET /api/admin/documents` — List all indexed documents
- `DELETE /api/admin/documents/{id}` — Delete a document and its chunks/images. Blocked with 503 while a reindex is running.
- `POST /api/admin/reindex` — Clear every chunk embedding and re-embed via Ollama. Fire-and-forget; returns 202 immediately. While running, uploads/deletes return 503 and `POST /api/chat` returns 503 `not_ready`.

Supported document formats: `.docx` (Word) and `.pdf`.

Admin routes are unprotected until Phase 4 (auth). Restrict admin-mode deployments to internal networks.

## Chat

In `MODE=full` or `MODE=client`, `POST /api/chat` streams a Server-Sent Events response of a RAG answer over the indexed documents. Request body: `{"message": "...", "history": [{"role": "user|assistant", "content": "..."}]}`. Emits `queued`, `processing`, `token`, `sources`, `done`, or `error` events. Returns 503 `not_ready` while the embedding backfill or a reindex is in progress, and 503 `queue_unavailable` if the Artemis broker is unreachable.

## Observability

- `GET /api/health` — liveness (always 200 `healthy`)
- `GET /api/health/ready` — readiness (503 while backfill/reindex is incomplete or Ollama/Artemis are unreachable)
- `GET /api/stats` — document/chunk/image counts, embedding dimension, DB size, and uptime

## Configuration

Path-related environment variables derive from a single base (`DATA_PATH`). Setting only `DATA_PATH` is sufficient for normal deployments.

| Variable | Default | Description |
|----------|---------|-------------|
| `MODE` | `full` | Application mode: `full`, `admin`, or `client` |
| `PORT` | `8080` | HTTP server port |
| `HOST` | `0.0.0.0` | HTTP server bind address |
| `DATA_PATH` | `./data` | Base data directory |
| `DATABASE_PATH` | `./data/aos.db` | SQLite database file |
| `DOCUMENTS_PATH` | `${DATA_PATH}/documents` | Uploaded source documents |
| `IMAGES_PATH` | `${DATA_PATH}/images` | Extracted images |
| `OLLAMA_URL` | `http://ollama:11434` | Ollama HTTP endpoint |
| `OLLAMA_LLM_MODEL` | `qwen2.5:7b-instruct-q4_K_M` | Chat generation model |
| `OLLAMA_EMBED_MODEL` | `bge-m3` | Embedding model (1024-dim) |
| `ARTEMIS_BROKER_URL` | `tcp://artemis:61616` | JMS broker URL |
| `ARTEMIS_USER` | (empty) | Broker user; empty = anonymous |
| `ARTEMIS_PASSWORD` | (empty) | Broker password; empty = anonymous |
| `LOG_LEVEL` | `INFO` | Logging level |
| `LOG_FORMAT` | `text` | Log output format (`text` or `json`) |

`DOCUMENTS_PATH` and `IMAGES_PATH` may be overridden independently when ops needs to mount them on separate volumes. See `.env.example` for all available variables.

## Project Structure

```
aos-chatbot/
├── backend/          # Kotlin + Ktor API server
├── frontend/         # React + Vite + TypeScript UI
├── docs/             # Architecture and plan documents
├── docker-compose.yml
└── CLAUDE.md
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full technical details.
