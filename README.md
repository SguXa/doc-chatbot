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

Health check: http://localhost:8080/api/health

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
