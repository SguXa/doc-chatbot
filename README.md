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

```bash
# Production
docker compose build
docker compose up -d

# Development (with live reload)
docker compose -f docker-compose.dev.yml up
```

Health endpoints:
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

## Configuration

Path-related environment variables derive from a single base (`DATA_PATH`). Setting only `DATA_PATH` is sufficient for normal deployments.

| Variable | Default | Description |
|----------|---------|-------------|
| `DATA_PATH` | `./data` | Base data directory |
| `DATABASE_PATH` | `./data/aos.db` | SQLite database file |
| `DOCUMENTS_PATH` | `${DATA_PATH}/documents` | Uploaded source documents |
| `IMAGES_PATH` | `${DATA_PATH}/images` | Extracted images |

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
