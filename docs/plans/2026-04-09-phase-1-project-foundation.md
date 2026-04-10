# Phase 1: Project Foundation

## Overview

Bootstrap the AOS Documentation Chatbot with a minimal, runnable project foundation: Kotlin/Ktor backend, React/Vite frontend with routing, SQLite database with migrations, Docker Compose setup, and health check endpoints. No business logic -- just the scaffolding for all future phases.

## Context

- Files involved: All new files -- project currently has only CLAUDE.md, docs/ARCHITECTURE.md, and empty README.md
- Related patterns: Conventions from CLAUDE.md (coroutines, named exports, manual DI, shadcn/ui, TanStack Query, Zustand)
- Dependencies: Kotlin 1.9+, Ktor 2.x, Gradle 8, React 18, Vite 5, TypeScript, SQLite, Docker
- Source of truth: docs/ARCHITECTURE.md sections 5, 6.1, 12, 13, 14.2, 15 (Phase 1)

## Design Decisions

- SQLite access: Using plain SQLite JDBC plus manual SQL migrations for Phase 1 -- minimal foundation without ORM complexity
- Shadow JAR plugin for fat JAR packaging (matches ARCHITECTURE.md section 13.3 Dockerfile)
- Frontend API client uses fetch (not axios) -- ARCHITECTURE.md section 5 lists "Axios/fetch" for client.ts; we choose fetch to minimize dependencies
- Migrations use plain SQL files in resources/db/migration/ as documented in section 5
- application.conf HOCON: env vars referenced in section 12.2 that don't exist in Phase 1 (OLLAMA_URL, OLLAMA_LLM_MODEL, OLLAMA_EMBED_MODEL) are omitted from application.conf; they will be added in the phase that introduces Ollama
- docker-compose.yml omits the ollama service from section 13.1 since Ollama is out of Phase 1 scope
- docker-compose.admin.yml and docker-compose.client.yml from section 5 are deferred to later phases
- React Router is included per Phase 1 requirement "React app with routing"
- TanStack Query is scaffolded as it's the documented server state management (section 2.1)

## Development Approach

- **Testing approach**: Regular (code first, then tests)
- Complete each task fully before moving to the next
- Each task produces a compilable/runnable increment
- **CRITICAL: each functional increment should include appropriate tests**
- **CRITICAL: all tests must pass before starting next task**

## Implementation Steps

### Task 1: Backend project setup with Gradle and Ktor

**Files:**
- Create: `backend/build.gradle.kts`
- Create: `backend/settings.gradle.kts`
- Create: `backend/gradle.properties`
- Create: `backend/src/main/kotlin/com/aos/chatbot/Application.kt`
- Create: `backend/src/main/resources/application.conf`
- Create: `backend/src/main/resources/logback.xml`
- Create: `backend/src/main/kotlin/com/aos/chatbot/config/AppConfig.kt`
- Create: `.gitignore`

- [x] Initialize Gradle wrapper in backend/ (gradlew, gradle/wrapper/)
- [x] Create build.gradle.kts with: Kotlin 1.9, Ktor 2.x (server-core, server-netty, server-content-negotiation, server-status-pages, kotlinx-serialization-json), SQLite JDBC, shadow JAR plugin, JUnit 5, MockK, ktor-server-test-host
- [x] Create settings.gradle.kts with project name "aos-chatbot-backend"
- [x] Create application.conf per section 12.2 but only with ktor deployment (port/host with defaults) and app.mode, app.database.path -- omit ollama section
- [x] Create AppConfig data class reading MODE, PORT, HOST, DATABASE_PATH, DATA_PATH from environment with defaults (MODE=full, PORT=8080, DATABASE_PATH=./data/aos.db, DATA_PATH=./data). MODE is an enum of full/admin/client
- [x] Create Application.kt with Ktor EngineMain/application.conf startup and an Application.module installing ContentNegotiation (JSON) and StatusPages
- [x] Create logback.xml with console appender, text format
- [x] Create root .gitignore covering Gradle (build/, .gradle/), Node (node_modules/, dist/), IDE (.idea/, .vscode/), data files (*.db, data/), environment (.env)
- [x] Write tests: smoke test starting/stopping Ktor test application, AppConfig defaults, AppConfig mode parsing, invalid mode handling
- [x] Verify: `cd backend && ./gradlew test` passes

### Task 2: SQLite database and migrations

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/Database.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/db/Migrations.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/config/DatabaseConfig.kt`
- Create: `backend/src/main/resources/db/migration/V001__initial_schema.sql`

- [x] Create V001__initial_schema.sql with the full schema from section 6.1: users table, documents table, chunks table (with embedding BLOB), images table, config table, plus all indexes (idx_chunks_document, idx_chunks_content_type, idx_chunks_section, idx_images_document)
- [x] Create Database.kt: connection factory using SQLite JDBC, enables WAL mode and foreign keys via PRAGMA statements
- [x] Create Migrations.kt: reads SQL files from classpath resources/db/migration/ in version order, tracks applied migrations in a schema_version table, applies pending ones
- [x] Create DatabaseConfig.kt: creates data directory if needed, initializes database connection using AppConfig.databasePath, runs migrations
- [x] Wire database initialization into Application.kt module
- [x] Write tests using in-memory SQLite: migration applies cleanly on fresh DB, migration is idempotent, all expected tables exist after migration, foreign keys are enforced
- [x] Verify: `cd backend && ./gradlew test` passes

### Task 3: Health check endpoints

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/HealthRoutes.kt`

- [x] Create HealthRoutes.kt as an extension function on Route, with: GET /api/health returning {"status":"healthy"} (liveness, used by Docker HEALTHCHECK per section 14.2), GET /api/health/ready checking database connectivity (returns 200 with {"status":"ready"} or 503 with {"status":"unavailable"})
- [x] Register health routes in Application.kt routing block
- [x] Write tests using Ktor testApplication: /api/health returns 200 with correct JSON structure, /api/health/ready returns 200 when DB is available
- [x] Verify: `cd backend && ./gradlew test` passes

### Task 4: Frontend project setup with React, Vite, TypeScript, and Tailwind

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/index.html`
- Create: `frontend/src/main.tsx`, `frontend/src/App.tsx`, `frontend/src/vite-env.d.ts`
- Create: `frontend/postcss.config.js`, `frontend/tailwind.config.ts`, `frontend/src/index.css`
- Create: `frontend/eslint.config.js`

- [ ] Scaffold frontend with `npm create vite@latest` using React + TypeScript template, or create equivalent files manually
- [ ] Add and configure Tailwind CSS v3 with postcss
- [ ] Configure vite.config.ts with dev proxy: /api/* routes to localhost:8080
- [ ] Create minimal App.tsx with app title text
- [ ] Add vitest, @testing-library/react, @testing-library/jest-dom, jsdom to devDependencies; configure vitest in vite.config.ts
- [ ] Write smoke test: App component renders without crashing
- [ ] Verify: `cd frontend && npm run build` succeeds
- [ ] Verify: `cd frontend && npm test` passes

### Task 5: Frontend routing, TanStack Query, and API client

**Files:**
- Create: `frontend/src/lib/utils.ts`
- Create: `frontend/src/api/client.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/main.tsx`

- [ ] Install react-router-dom; set up BrowserRouter in main.tsx and a basic route structure in App.tsx with a "/" route (placeholder page showing app title and health status)
- [ ] Install @tanstack/react-query; add QueryClientProvider in main.tsx
- [ ] Create api/client.ts: typed fetch wrapper with base URL from env or relative path, JSON response parsing, error handling (throws on non-ok responses)
- [ ] Update App.tsx to fetch GET /api/health on mount using the API client and display the result
- [ ] Write tests: App renders with router, api/client.ts constructs URLs correctly and throws on error responses
- [ ] Verify: `cd frontend && npm test` passes
- [ ] Verify: `cd frontend && npm run build` succeeds

### Task 6: Docker and environment configuration

**Files:**
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`
- Create: `docker-compose.yml`
- Create: `docker-compose.dev.yml`
- Create: `.env.example`

- [ ] Create backend Dockerfile per section 13.3: multi-stage with gradle:8-jdk17 build stage, eclipse-temurin:17-jre-alpine runtime, shadow JAR
- [ ] Create frontend Dockerfile per section 13.4: multi-stage with node:20-alpine build stage, nginx:alpine runtime
- [ ] Create nginx.conf: serve static files from /usr/share/nginx/html, proxy /api/* to backend:8080
- [ ] Create docker-compose.yml based on section 13.1 with backend and frontend services, aos-data volume, healthcheck on backend -- omit ollama service and ollama-models volume
- [ ] Create docker-compose.dev.yml extending docker-compose.yml with source volume mounts for live reload and exposed ports for direct access
- [ ] Create .env.example with all environment variables from section 12.1 (commented, with defaults or example values)
- [ ] Verify: `docker compose build` succeeds
- [ ] Verify: `docker compose up -d && curl http://localhost:8080/api/health && docker compose down` returns healthy response

### Task 7: Verify acceptance criteria

- [ ] Run full backend test suite: `cd backend && ./gradlew test`
- [ ] Run full frontend test suite: `cd frontend && npm test`
- [ ] Run linter: `cd frontend && npm run lint`
- [ ] Verify backend starts standalone: `cd backend && ./gradlew run` responds to GET /api/health with 200
- [ ] Verify frontend builds: `cd frontend && npm run build`
- [ ] Verify Docker build and run: `docker compose build && docker compose up -d` then health check responds, then `docker compose down`
- [ ] Verify project structure matches section 5 for Phase 1 scope

### Task 8: Update documentation

- [ ] Update README.md with: project description, prerequisites (JDK 17, Node 20, Docker), quick-start for local dev (backend + frontend), Docker usage, running tests
- [ ] Move this plan to `docs/plans/completed/`
