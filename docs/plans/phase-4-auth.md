# Phase 4: Auth (single admin)

## Overview

Introduce JWT-based authentication for the admin surface. A single administrator authenticates with a password supplied via `ADMIN_PASSWORD` environment variable; the password is bcrypt-hashed in memory at startup and never persisted. Successful login returns an HS256-signed JWT (24 h expiry, `iss=aos-chatbot`) that clients carry as `Authorization: Bearer <token>` on every admin request. Chat (`POST /api/chat`) and health (`/api/health/*`) remain public — there are no per-user tokens or user-role enforcement on those endpoints. The pre-Phase-4 startup WARN about unprotected admin routes (introduced by Phase 2 per ADR 0005) is removed. The legacy `users` table from V001 is dropped via a new migration since the single-admin design does not persist any user records. No admin UI, no chat UI, no user management endpoints, no signup, no token revocation, no role hierarchy.

## Context

- Files involved: New code under `backend/src/main/kotlin/com/aos/chatbot/{config,services,routes,routes/dto}/`; one new migration under `resources/db/migration/`; existing `Application.kt`, `AppConfig.kt`, `application.conf`, `build.gradle.kts`, `.env.example`, `docker-compose.dev.yml` extended. New `docs/adr/0007-*.md`. Edits to `docs/ARCHITECTURE.md` §6.1, §7.4, §11.1–§11.3, §12.1 and to `CLAUDE.md` "Phase Discipline".
- Related patterns: Manual constructor DI, coroutines, named exports, JUnit 5 + MockK, kotlinx.serialization, operation-scoped repositories, stable string error discriminators, mode-gated route registration. All conventions inherit from Phase 1/2/3.
- Source of truth: `docs/ARCHITECTURE.md` §6.1 (schema), §7.4 (auth API), §11 (auth contract), §12.1 (env vars).
- Architectural decisions: see `docs/adr/` — ADR 0005 (auth deferred out of Phase 2 — its 4-step Phase-4 punch-list drives this plan), **ADR 0007 (this phase — single-admin auth without persisted user records)**.

## Design Decisions

- **Single admin, no user records.** `ADMIN_PASSWORD` env variable is the only pre-shared secret. On startup `AuthService.init` calls `PasswordHasher.hash` once and keeps the resulting bcrypt hash in memory. Password rotation = change env var, restart. There is no `users` table, no `User` model, no `UserRepository`, and no role-claim in JWT. `/api/admin/*` requires "valid token"; that's the only authorization decision.
- **Chat is public; ARCHITECTURE.md §11.2 is amended.** The original §11.2 listed `/api/chat/*` as `user, admin` — this is dropped. Phase 4 makes `/api/chat/*` and `/api/health/*` unconditionally public. Only `/api/admin/*` is protected. `/api/auth/*` is public by definition (login is the entry point). This decision is recorded in ADR 0007.
- **Stateless logout.** `POST /api/auth/logout` returns 204; the server keeps no session state. JWTs naturally expire after 24 h. No revocation list, no `token_version` column, no migration. If forced revocation is ever required, that lands in a separate ADR.
- **HS256, 24 h, no audience, no role claim.** `JwtConfig.sign()` produces a token with `iss=aos-chatbot`, `iat=now`, `exp=now+86400s`, `sub="admin"`. Verification checks signature, issuer, and `exp`. No `aud`, no `role`, no `nbf` — every claim that does not change behavior in Phase 4 is omitted to keep the surface tight.
- **`LoginRequest{username, password}` is preserved as a wire contract; `username` is ignored server-side.** ARCHITECTURE.md §7.4 already documents `{username, password}`. Keeping the field lets a Phase-5+ frontend show a "Username" input (UX clue for operators) and lets us add multi-user later without breaking the wire format. The server reads only `password`.
- **Mode gating drives auth wiring.** In `MODE=full` and `MODE=admin`, `AuthService` is instantiated, `install(Authentication) { jwt("jwt-admin") {...} }` runs, admin routes are wrapped in `authenticate("jwt-admin") { ... }`, and auth routes are registered under `/api/auth/`. In `MODE=client`, none of these run — `AuthService` is never constructed, `JWT_SECRET` and `ADMIN_PASSWORD` are not consumed, and `/api/auth/login` returns 404. This preserves the "MODE=client needs no secrets" deployment property.
- **Fail-fast configuration validation.** When `mode in {FULL, ADMIN}`: `require(jwtSecret.length >= 32)` and `require(adminPassword.isNotBlank())` at startup. The application refuses to boot rather than serve nullable-secret tokens. In `MODE=client`, both checks are skipped.
- **Anti-timing is moot.** With one user and an in-memory hash, `login(password)` always runs exactly one `bcrypt.verify` call. There is no DB lookup that could leak "user exists" via timing. The naive `if (verify) sign() else null` is sufficient.
- **`users` table is dropped.** V001 created `users(id, username, password_hash, role, created_at)` for a future scenario that Phase 4 has now decided not to enter. V005 drops the table. Migrations stay immutable: V001–V004 are not edited.
- **Auth WARN line is removed.** Phase 2 added `log.warn("Admin routes are unprotected — auth is deferred to Phase 4. Restrict this deployment to internal networks.")` (Application.kt:187). ADR 0005 step 3 explicitly requires removal once auth lands. Phase 4 removes it.
- **No frontend changes.** The current `frontend/src/App.tsx` is a stub showing `/api/health` status. There is no admin UI to log into yet (Phase 5). Building a login page in Phase 4 would produce dead routes. All Phase 4 admin operations during the gap are exercised via curl or other HTTP clients.
- **Library choice: `at.favre.lib:bcrypt:0.10.2`.** Single-jar, zero transitive deps, cost=12 default. JWT signing/verification uses `io.ktor:ktor-server-auth-jwt` (which embeds `com.auth0:java-jwt`); we do not pull `java-jwt` as a separate dep.

## Development Approach

- **Testing approach**: Regular (code first, then tests).
- Complete each task fully before moving to the next.
- Each task produces a compilable/runnable increment.
- **CRITICAL: each functional increment must include appropriate tests.** Unit tests with MockK for business logic; Ktor `TestApplication` for route shape and `authenticate { ... }` wiring; embedded SQLite for migration tests.
- **CRITICAL: all tests must pass before starting next task.**
- Phase 4 introduces no `@Tag("integration")` tests (no real Ollama or Artemis dependency for auth verification).

## Validation Commands

- `cd backend && ./gradlew test`
- `cd backend && ./gradlew build`
- (manual) `curl -X POST http://localhost:8080/api/admin/documents` → expect 401; with valid `Authorization: Bearer ...` → expect business response (e.g., 400 on missing multipart, 200 on valid upload).

## Implementation Steps

### Task 1: Add Ktor auth and bcrypt dependencies

**Files:**
- Modify: `backend/build.gradle.kts`

- [x] Add `io.ktor:ktor-server-auth:$ktorVersion` to `implementation`
- [x] Add `io.ktor:ktor-server-auth-jwt:$ktorVersion` to `implementation`
- [x] Add `at.favre.lib:bcrypt:0.10.2` to `implementation`
- [x] Verify: `cd backend && ./gradlew build` succeeds
- [x] Verify: `cd backend && ./gradlew test` still green (no new tests yet)

### Task 2: Extend AppConfig with AuthConfig

**Files:**
- Modify: `backend/src/main/resources/application.conf`
- Modify: `backend/src/main/kotlin/com/aos/chatbot/config/AppConfig.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/config/AppConfigTest.kt`
- Modify: `.env.example`

`JWT_SECRET` and `ADMIN_PASSWORD` already exist in `.env.example` (commented, marked "Phase 4+"). This task makes them real on the Kotlin side.

- [x] Add `app.auth { jwtSecret = ""; jwtSecret = ${?JWT_SECRET}; adminPassword = ""; adminPassword = ${?ADMIN_PASSWORD} }` block in `application.conf`
- [x] Add `data class AuthConfig(jwtSecret: String, adminPassword: String)` to `AppConfig.kt` with masked `toString()` (mirror `ArtemisConfig`'s pattern: `"AuthConfig(jwtSecret=***, adminPassword=***)"`)
- [x] Add `auth: AuthConfig` field to `AppConfig`
- [x] `AppConfig.from(environment)` reads both properties; empty values resolve as empty strings, never nulls
- [x] Uncomment `JWT_SECRET=` and `ADMIN_PASSWORD=` lines in `.env.example`; add comment "required in MODE=full and MODE=admin; ignored in MODE=client"
- [x] AppConfigTest: defaults to empty strings when env vars unset
- [x] AppConfigTest: each env var overrides its default independently
- [x] AppConfigTest: `AuthConfig.toString()` masks both fields
- [x] Verify: `cd backend && ./gradlew test`

### Task 3: Implement PasswordHasher

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/PasswordHasher.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/PasswordHasherTest.kt`

A thin wrapper over `at.favre.lib:bcrypt`. Stateless `object` since there is no per-instance state.

- [x] Create `object PasswordHasher`
- [x] `fun hash(password: String, cost: Int = 12): String` — returns the modular-crypt-format string (`$2a$12$...`); throws `IllegalArgumentException` if `password.isBlank()`
- [x] `fun verify(password: String, hash: String): Boolean` — uses `BCrypt.verifyer().verify(password.toCharArray(), hash).verified`; never throws on bad input, returns false instead
- [x] KDoc: cost=12 ≈ 100 ms on dev hardware; that is the budget per login. No need to expose cost configuration in Phase 4 (single-admin, low login frequency)
- [x] Test: `hash` produces a non-empty string starting with `$2a$12$`
- [x] Test: `hash` + `verify` round-trip succeeds for a non-trivial password
- [x] Test: `verify` returns false for the wrong password
- [x] Test: `verify` returns false on a malformed hash string instead of throwing
- [x] Test: `hash("")` throws `IllegalArgumentException`
- [x] Verify: `cd backend && ./gradlew test`

### Task 4: Implement JwtConfig

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/config/JwtConfig.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/config/JwtConfigTest.kt`

Encapsulates HS256 signing/verification with fixed issuer and 24 h TTL. Operates over `com.auth0.jwt.JWT` (transitively from `ktor-server-auth-jwt`).

- [x] Constructor `JwtConfig(secret: String, issuer: String = "aos-chatbot", ttlSeconds: Long = 86_400, clock: Clock = Clock.systemUTC())`
- [x] `init { require(secret.length >= 32) { "JWT secret must be >= 32 chars" } }` — internal invariant. The user-facing fail-fast lives in `Application.module()` (Task 7) which has access to mode info and produces a clearer error message; this `init` check is defense-in-depth and is not exercised in user-facing tests
- [x] `fun sign(): String` — issues a token with `iss=issuer`, `sub="admin"`, `iat=clock.instant()`, `exp=iat+ttl`. Returns the signed token string
- [x] `fun verify(token: String): Boolean` — uses `JWT.require(Algorithm.HMAC256(secret)).withIssuer(issuer).build().verify(token)`; catches `JWTVerificationException` and returns `false`. Never throws
- [x] `fun verifier(): com.auth0.jwt.interfaces.JWTVerifier` — declared return type is explicit (no inference) so the public surface is obvious; returns the configured verifier so `Application.kt`'s `install(Authentication) { jwt(...) { verifier(jwtConfig.verifier()) } }` can plug in directly
- [x] KDoc: deliberately omits `aud`, `role`, `nbf`. Only signature + issuer + expiry are checked. **No clock leeway** is configured (`acceptLeeway` is left at the `java-jwt` default of 0 seconds); deployments with skewed clocks must run NTP. This must be stated explicitly so future operators know not to expect skew tolerance
- [x] Test: `sign` then `verify` round-trip returns true on the same instance
- [x] Test: `verify` returns false on a token signed with a different secret
- [x] Test: `verify` returns false on a token with a different issuer
- [x] Test: `verify` returns false on an expired token (use a `Clock.fixed()` set in the past for signing, then `Clock.systemUTC()` for verification)
- [x] Test: `verify` returns false on garbage strings (`""`, `"not-a-jwt"`, `"a.b.c"` with bogus payload)
- [x] Test: constructor throws on a 31-char secret; accepts 32-char
- [x] Verify: `cd backend && ./gradlew test`

### Task 5: Implement AuthService

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/services/AuthService.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/services/AuthServiceTest.kt`

Owns the in-memory password hash and the login decision. JWT issuance is delegated to `JwtConfig`.

- [x] Constructor `AuthService(authConfig: AuthConfig, jwtConfig: JwtConfig, hasher: PasswordHasher = PasswordHasher)`
- [x] `init { require(authConfig.adminPassword.isNotBlank()) { "ADMIN_PASSWORD must be set" } }` — internal invariant only. The user-facing fail-fast lives in `Application.module()` (Task 7); this check is defense-in-depth and is not the primary error reported to operators
- [x] `private val passwordHash: String = hasher.hash(authConfig.adminPassword)` — computed once at startup
- [x] `fun login(password: String): String?` — returns `jwtConfig.sign()` on a verified password, `null` otherwise
- [x] KDoc: `username` from `LoginRequest` is intentionally not a parameter — the wire format keeps it for forward-compat (see ARCHITECTURE.md §7.4) but the server ignores it
- [x] Test: `login(correctPassword)` returns a non-null token that `jwtConfig.verify(...)` accepts
- [x] Test: `login(wrongPassword)` returns null
- [x] Test: constructor throws when `authConfig.adminPassword` is empty or blank
- [x] Test: `login(blankPassword)` returns null (does not throw, since `PasswordHasher.verify` swallows malformed input)
- [x] Verify: `cd backend && ./gradlew test`

### Task 6: Implement AuthRoutes

**Files:**
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/dto/AuthRequests.kt`
- Create: `backend/src/main/kotlin/com/aos/chatbot/routes/AuthRoutes.kt`
- Create: `backend/src/test/kotlin/com/aos/chatbot/routes/AuthRoutesTest.kt`

Public route bundle. Wired in `Application.module()` only when `AuthService` is constructed (FULL/ADMIN modes).

- [x] Create `LoginRequest(username: String, password: String)`, `LoginResponse(token: String, expiresIn: Long, user: UserInfo)`, `UserInfo(username: String, role: String)`, `InvalidLoginResponse(error: String = "invalid_credentials")`, `InvalidRequestResponse(error: String = "invalid_request", reason: String)` — all `@Serializable`. Two error shapes for two failure classes (auth-401 vs format-400) with stable string discriminators per project convention. The success response mirrors §7.4 exactly: `user.username = "admin"`, `user.role = "admin"` (constants — there is no other user)
- [x] `fun Route.authRoutes(authService: AuthService, ttlSeconds: Long)` extension
- [x] `POST /api/auth/login`:
  - [x] Receive `LoginRequest`; on deserialization failure → 400 `{"error": "invalid_request", "reason": "malformed_body"}`
  - [x] Validate `password.isNotBlank()` → otherwise 400 `{"error": "invalid_request", "reason": "empty_password"}`
  - [x] `username` field is read but ignored server-side (KDoc explains why)
  - [x] Call `authService.login(body.password)`; if null → 401 `InvalidLoginResponse`
  - [x] On success → 200 `LoginResponse(token, expiresIn = ttlSeconds, UserInfo("admin", "admin"))`
- [x] `POST /api/auth/logout`:
  - [x] Always 204 No Content. No body, no auth required, no server-side state mutation
- [x] AuthRoutesTest with `TestApplication`. All assertions decode response bodies into the typed `LoginResponse` / `InvalidLoginResponse` / `InvalidRequestResponse` data classes via `Json.decodeFromString<...>` rather than asserting on raw JSON keys — this locks the wire format at compile time:
  - [x] Login with correct password → 200, decoded `LoginResponse`: `token` is non-empty, `expiresIn == 86400`, `user.username == "admin"`, `user.role == "admin"`
  - [x] Login with wrong password → 401, decoded `InvalidLoginResponse`: `error == "invalid_credentials"`
  - [x] Login with empty password → 400, decoded `InvalidRequestResponse`: `error == "invalid_request"`, `reason == "empty_password"`
  - [x] Login with malformed JSON body → 400, decoded `InvalidRequestResponse`: `reason == "malformed_body"`
  - [x] Login with `username != "admin"` but correct password → 200 (username ignored — proves the contract decision)
  - [x] Logout → 204 with no body
- [x] Verify: `cd backend && ./gradlew test`

### Task 7: Wire auth into Application.module

**Files:**
- Modify: `backend/src/main/kotlin/com/aos/chatbot/Application.kt`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/ApplicationTest.kt`

Bring up the auth object graph in `FULL`/`ADMIN`, wrap admin routes in `authenticate("jwt-admin")`, register auth routes, remove the pre-auth WARN.

- [x] Add `import io.ktor.server.auth.*` and `import io.ktor.server.auth.jwt.*`
- [x] Immediately after `val appConfig = AppConfig.from(environment)`, add a fail-fast block:
  ```kotlin
  if (appConfig.mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
      require(appConfig.auth.jwtSecret.length >= 32) {
          "JWT_SECRET must be set and >= 32 characters in MODE=${appConfig.mode}"
      }
      require(appConfig.auth.adminPassword.isNotBlank()) {
          "ADMIN_PASSWORD must be set in MODE=${appConfig.mode}"
      }
  }
  ```
- [x] Construct `JwtConfig` and `AuthService` only in FULL/ADMIN; declare them as `val authService: AuthService? = ...` so MODE=client paths stay null-clean. The `Application.module()`-level `require` calls above are the **primary** fail-fast site (clear error message tied to mode); the `init` checks inside `JwtConfig` and `AuthService` (Tasks 4 and 5) are defense-in-depth invariants and report-the-internal-message-only
- [x] In FULL/ADMIN, install JWT authentication BEFORE `routing { ... }`:
  ```kotlin
  install(Authentication) {
      jwt("jwt-admin") {
          // verifier already enforces issuer = "aos-chatbot" via JWT.require(...).withIssuer(...)
          verifier(jwtConfig!!.verifier())
          // validate {} runs only AFTER verifier passes signature + issuer + exp.
          // Returning JWTPrincipal here just makes the principal accessible inside
          // route handlers; returning null would 401, but verifier failure already
          // handles the 401 path. We do not re-check issuer here (already enforced).
          validate { credential -> JWTPrincipal(credential.payload) }
          challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized) }
      }
  }
  ```
- [x] **`registerModeGatedRoutes` signature change** (so admin auth-wrapping is explicit and testable, not done implicitly at the call site): change the existing internal helper in `Application.kt` to accept an optional auth-provider name:
  ```kotlin
  internal fun Route.registerModeGatedRoutes(
      mode: AppMode,
      adminAuthName: String? = null,
      adminRegistrar: (Route.() -> Unit)? = null,
      chatRegistrar: (Route.() -> Unit)? = null
  ) {
      if (mode in listOf(AppMode.FULL, AppMode.ADMIN)) {
          val registerAdmin: Route.() -> Unit = { adminRegistrar?.invoke(this) }
          if (adminAuthName != null) authenticate(adminAuthName, build = registerAdmin) else registerAdmin()
      }
      if (mode in listOf(AppMode.FULL, AppMode.CLIENT)) chatRegistrar?.invoke(this)
  }
  ```
  In `Application.module()`, pass `adminAuthName = "jwt-admin"` only in FULL/ADMIN; in MODE=client (no admin routes register at all) the parameter is irrelevant.
- [x] In `routing { ... }`:
  - [x] Register `authRoutes(authService!!, ttlSeconds = 86_400)` only in FULL/ADMIN. Auth routes are NOT wrapped in `authenticate { }` — login is the entry point
  - [x] Call `registerModeGatedRoutes(mode = appConfig.mode, adminAuthName = "jwt-admin", adminRegistrar = { adminRoutes(...) }, chatRegistrar = { chatRoutes(...) })`
  - [x] Chat and health routes stay as-is (public)
- [x] **Delete** the line `log.warn("Admin routes are unprotected — auth is deferred to Phase 4...")` (Application.kt:187 in current code) — implements ADR 0005 step 3
- [x] ApplicationTest: existing assertions still pass (`/api/health` returns 200; mode-gating still routes correctly to chat/admin)
- [x] ApplicationTest: in FULL/ADMIN, missing `JWT_SECRET` (or < 32 chars) → application fails to start with the documented message; covered by a test that loads a custom HOCON
- [x] ApplicationTest: in FULL/ADMIN, missing `ADMIN_PASSWORD` → application fails to start
- [x] ApplicationTest: in `MODE=client`, missing both env vars → application starts cleanly (no auth construction)
- [x] ApplicationTest: pre-auth WARN line is no longer emitted in any mode (capture logs and assert absence)
- [x] ApplicationTest: assert admin routes are BOTH mode-gated AND auth-gated — in MODE=full, `GET /api/admin/documents` without token returns 401; in MODE=client the same path returns 404 (mode gate fires before auth). This proves `registerModeGatedRoutes`'s auth-wrapping does not bypass the mode check
- [x] Verify: `cd backend && ./gradlew test`

### Task 8: ApplicationAuthWiringTest — cross-cutting auth coverage

**Files:**
- Create: `backend/src/test/kotlin/com/aos/chatbot/ApplicationAuthWiringTest.kt`

The single most important test in Phase 4 — implements ADR 0005 step 4 ("Add integration tests proving every protected route returns 401 without a valid token"). Boots a `TestApplication` in `MODE=full` with the real auth wiring; mocks Ollama/Artemis collaborators that admin handlers transitively touch (via MockK on `DocumentService`, `EmbeddingBackfillJob`, `QueueService` constructor deps) so admin handlers can return business responses without external services. **Not** named `*IntegrationTest` and **not** tagged `@Tag("integration")` — runs in the default suite (the project reserves the "Integration" suffix and tag for tests that hit a real Ollama; this test exercises only auth wiring and Ktor).

- [x] Test: `POST /api/auth/login` with correct password → 200, captures `token` for reuse
- [x] **Admin route inventory must be data-driven, not hard-coded**: at the top of the test class, declare `val adminRoutes = listOf(HttpMethod.Post to "/api/admin/documents", HttpMethod.Get to "/api/admin/documents", HttpMethod.Delete to "/api/admin/documents/1", HttpMethod.Post to "/api/admin/reindex")`. Each negative-token test case iterates this list. A KDoc comment instructs future contributors: "If you add a new admin route, add it to this list — otherwise this test will not protect it." This guards against silent bypass when new admin routes land
- [x] Test: every admin endpoint without `Authorization` header → 401 (iterate `adminRoutes`)
- [x] Test: every admin endpoint with `Authorization: Bearer <expired-token>` → 401 (use `JwtConfig` with `Clock.fixed()` set far in the past to synthesize)
- [x] Test: every admin endpoint with `Authorization: Bearer not.a.jwt` → 401
- [x] Test: every admin endpoint with `Authorization: Bearer <token-signed-by-different-secret>` → 401
- [x] Test: every admin endpoint with valid token → response is NOT 401 (may be 4xx/5xx by business logic — e.g., 415 for missing multipart, 503 for backfill running — but NOT 401)
- [x] Test: chat/health/auth public surface, no token attached:
  - [x] `GET /api/health` → 200
  - [x] `GET /api/health/ready` → 200 or 503 (business; mocks decide), never 401
  - [x] `POST /api/chat {valid body}` → not 401 (may be 503 `not_ready` per mocks, or 200 SSE per ChatRoutes contract)
  - [x] `POST /api/auth/login {wrong password}` → 401 with body `error=invalid_credentials` (auth domain 401, not middleware 401 — both have status 401 but the body shape differs; assert the discriminator)
  - [x] `POST /api/auth/logout` → 204
- [x] Test (regression guard): `POST /api/chat` with `Authorization: Bearer not.a.jwt` → still NOT 401 — chat ignores the Authorization header completely. This catches a future change that accidentally wires chat into `authenticate("jwt-admin")`
- [x] Test: in `MODE=client`, `/api/auth/login` is not registered → 404; `/api/admin/*` is not registered → 404. This test sets up a separate `TestApplication` with `MODE=client` HOCON
- [x] Test: in `MODE=admin`, `/api/chat` is not registered → 404; `/api/admin/*` is registered AND requires auth
- [x] Verify: `cd backend && ./gradlew test`

### Task 9: V005 migration — DROP TABLE users

**Files:**
- Create: `backend/src/main/resources/db/migration/V005__drop_unused_users_table.sql`
- Modify: `backend/src/test/kotlin/com/aos/chatbot/db/MigrationsTest.kt`

The `users` table from V001 was speculatively added for the original §11 design. Phase 4 has decided to keep auth single-admin and stateless, so the table is unused. V005 drops it. V001–V004 stay immutable.

- [ ] Create `V005__drop_unused_users_table.sql` with a single statement: `DROP TABLE IF EXISTS users;` (idempotent — SQLite's plain `DROP TABLE` errors on missing table, and the `IF EXISTS` guard keeps migrations replayable on repaired databases)
- [ ] Do NOT modify V001, V002, V003, or V004
- [ ] **Update `MigrationsTest.kt:49` `expectedTables`**: remove `"users"` from the `setOf(...)`. After this edit the line reads `val expectedTables = setOf("documents", "chunks", "images", "config", "schema_version")`. Without this edit the existing `all expected tables exist after migration` test fails immediately on V005
- [ ] MigrationsTest case: after V005 applies, `SELECT name FROM sqlite_master WHERE type='table' AND name='users'` returns no rows
- [ ] MigrationsTest case: `schema_version` records version 5
- [ ] MigrationsTest case: V005 is idempotent — running `Migrations(conn).apply()` twice on the same connection does not throw (proves the `IF EXISTS` guard)
- [ ] MigrationsTest case: existing assertions on documents/chunks/images/config tables still pass after V005
- [ ] Verify: `cd backend && ./gradlew test`

### Task 10: Update docker-compose.dev.yml and .env.example

**Files:**
- Modify: `docker-compose.dev.yml`
- Modify: `.env.example`

Dev compose runs `MODE=full` → both `JWT_SECRET` and `ADMIN_PASSWORD` are required. Production compose (`docker-compose.yml`) runs `MODE=client` → no auth env vars needed; intentionally not modified.

- [ ] In `docker-compose.dev.yml`, add to the `backend` service `environment` block:
  ```yaml
  - JWT_SECRET=${JWT_SECRET:-dev-jwt-secret-change-me-min-32-chars-padding-padding}
  - ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin}
  ```
  Defaults are deliberately weak so a developer can `docker compose -f docker-compose.dev.yml up` without setting up `.env` first; comment in YAML explains they are placeholders for local dev only. **Verify the literal default value is ≥32 characters** before committing (`echo -n 'dev-jwt-secret-change-me-min-32-chars-padding-padding' | wc -c` must print ≥32) — otherwise the fail-fast in Application.module rejects the dev-default and `docker compose up` breaks for everyone
- [ ] `docker-compose.yml` is NOT modified — production runs `MODE=client` and consumes neither variable
- [ ] `.env.example` already updated in Task 2 (uncomment + comment lines); verify the comment makes the dev/client distinction explicit: "required in MODE=full and MODE=admin; ignored in MODE=client"
- [ ] No code changes; no new tests (compose-file edits are validated by Task 11's manual smoke check)

### Task 11: Create ADR 0007

**Files:**
- Create: `docs/adr/0007-single-admin-no-persisted-users.md`
- Modify: `docs/adr/README.md`

Records the auth design choice and the §11.2 amendment.

- [ ] Follow the format of existing ADRs in `docs/adr/` — Status, Date, Context, Decision, Consequences, Alternatives
- [ ] Status: Accepted (Phase 4); Date: today
- [ ] Context: ARCHITECTURE.md §11 originally specified a `users` table, multi-role auth (`user|admin`), and a default admin bootstrap procedure. Implementation experience and a narrower product scope (single internal operator using the chatbot tooling on the local network) made the multi-user surface speculative. Phase 4 must either build the speculative surface or formally narrow the contract
- [ ] Decision: One administrator, password from `ADMIN_PASSWORD` env, hashed in memory at startup, never persisted. JWT token has no role claim. `/api/chat/*` is public — chat is unauthenticated for any caller on the network, consistent with the deployment story (the only public-facing mode is `MODE=client`, which exposes chat only). The `users` table is dropped in V005 since no row is ever written to it
- [ ] Consequences:
  - Operating cost: rotating the admin password is a single-step `env-var change + restart`. No DB writes; no migration; no `--reset-admin` script
  - Single point of failure: anyone with shell access to the runtime environment can read `ADMIN_PASSWORD`. This is identical to the existing risk model (the same operator already has DB access)
  - No multi-user upgrade path without follow-up work: adding user accounts later requires a fresh migration to recreate `users`, a new `UserRepository`, new login logic that selects by `username`, and a `role`-aware authorization decision. ARCHITECTURE.md §11 should be revisited if and when that demand materializes
  - The original §11.2 entry stating chat requires `user|admin` is amended to `chat = public`. Health remains public. Auth endpoints are public (login is the entry point)
- [ ] Alternatives considered:
  - Keep the speculative `users`-table design and bootstrap one row from `ADMIN_PASSWORD`: rejected — a single-row table is not a "users system", just a glorified env-var redirect with extra moving parts
  - Argon2 instead of bcrypt: rejected — Argon2's JVM bindings require a native library, which complicates the fat-JAR distribution. Bcrypt cost=12 is adequate for a single-login-per-day surface
  - Server-side token revocation (token-version column): rejected — there is no scenario where a 24-h JWT outliving the operator's intent is a meaningful threat, given that the operator can also restart the JVM
  - Audience claim (`aud`): rejected — there is exactly one consumer of these tokens (the same backend that issued them) and one issuer; an `aud` claim adds no security and one more thing to verify and document
  - Protected chat (`user|admin`): rejected — the deployment story routes public chat through the `MODE=client` frontend and keeps admin behind internal-network or VPN access; adding a chat token means every chat client needs auth which the product never asked for
- [ ] Update `docs/adr/README.md` index with a line for ADR 0007

### Task 12: Update ARCHITECTURE.md

**Files:**
- Modify: `docs/ARCHITECTURE.md`

Six targeted edits.

- [ ] §2.1 — in the SQLite row's "Purpose" column, drop the `users` mention. Current text: "Documents, chunks, users, embeddings" → new: "Documents, chunks, embeddings"
- [ ] §3.1 — in the ASCII deployment diagram, remove the `- users` line from the SQLite contents block (current line ~124: `│  └───────────────┘   │ - users       │`). Either delete the line entirely and re-pad the box, or replace it with a remaining-table line if the box width is tied to that row
- [ ] §6.1 — remove the `users` table block from the schema listing (CREATE TABLE users at line ~430). Replace with a one-line note: "_The `users` table created by V001 was dropped in V005 — see ADR 0007._"
- [ ] §7.4 — add a sentence under `POST /api/auth/login`: "_The `username` field is accepted for forward-compatibility with future multi-user support but is ignored by the server in Phase 4. Only `password` is validated. See ADR 0007._" Update the example response so `user.username = "admin"` and `user.role = "admin"` are clearly constants, not variables
- [ ] §11.1 — simplify the JWT configuration table to: Algorithm HS256, Expiration 24 hours, Issuer `aos-chatbot`. Drop the "(Planned for Phase 4)" tag. Add a sentence: "No `aud` claim. No `role` claim — the single administrator is identified by token validity alone. See ADR 0007."
- [ ] §11.2 — rewrite the protected-routes table to:
  | Route Pattern | Auth |
  |---------------|------|
  | `/api/admin/*` | required |
  | `/api/config/*` | required (when registered in Phase 5) |
  | `/api/chat/*` | public |
  | `/api/health/*` | public |
  | `/api/auth/*` | public |
  Drop the `(Planned for Phase 4)` tag. Add: "Chat is intentionally public — see ADR 0007 for the rationale and ADR 0005 for the deployment-mode constraint that contains this exposure to internal networks. `/api/config/*` endpoints are not registered in Phase 4 (see §7.3); they will be admin-protected by the same `jwt-admin` provider when Phase 5 ships them."
- [ ] §11.3 — replace "Default Admin User" subsection with: "**Single administrator.** A single operator authenticates with the password supplied via the `ADMIN_PASSWORD` environment variable. The password is bcrypt-hashed in memory at startup and is **not persisted**. To rotate the password, change the env var and restart the service. There is no `users` table in the database — see ADR 0007."
- [ ] §12.1 — for `JWT_SECRET` and `ADMIN_PASSWORD`, change the inline note from "Auth (Phase 4+) — not consumed by the backend until auth lands" to "Required in `MODE=full` and `MODE=admin`; ignored in `MODE=client`. The application refuses to start if either is missing in the relevant modes."
- [ ] §15 — find the Phase 4 checklist (if it exists) and mark items `[x]` as appropriate. If the section is a static listing, leave it alone; this is a docs-hygiene step, not a contract change

### Task 13: Update CLAUDE.md and README.md

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] In `CLAUDE.md` "Phase Discipline" section:
  - [ ] Replace the "Auth is Phase 4 work..." bullet with a past-tense Phase 4 entry: "**Auth (Phase 4):** single admin via JWT. `/api/admin/*` requires `Authorization: Bearer <token>`; `/api/chat/*` and `/api/health/*` are public. Password from `ADMIN_PASSWORD` env (required in `MODE=full`/`MODE=admin`), hashed in memory, not persisted. See [ADR 0005](docs/adr/0005-auth-deferred-out-of-phase-2.md) for the deferral context and [ADR 0007](docs/adr/0007-single-admin-no-persisted-users.md) for the single-admin design."
  - [ ] Drop the deployment caveat about `MODE=full`/`MODE=admin` requiring internal-network restriction: it is no longer literally true after Phase 4 (admin is now token-protected). Replace with: "`MODE=full` and `MODE=admin` should still be restricted to operator workstations or VPN — chat remains public on the same listener and admin tokens, while signed, do not include rate limiting in Phase 4."
- [ ] In `README.md`:
  - [ ] Under "Document Management (Admin)" — change "Admin routes are unprotected until Phase 4 (auth)..." to "Admin routes require `Authorization: Bearer <token>`. Obtain a token via `POST /api/auth/login` with the `ADMIN_PASSWORD`. Tokens are valid for 24 hours."
  - [ ] Add a short curl example showing the login → admin-call flow. Build the JSON body via `jq` so passwords containing `'`, `"`, `\`, or newlines do not break shell quoting:
    ```bash
    BODY=$(jq -nc --arg p "$ADMIN_PASSWORD" '{username:"admin", password:$p}')
    TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
      -H 'Content-Type: application/json' \
      -d "$BODY" | jq -r .token)
    curl -X POST http://localhost:8080/api/admin/documents \
      -H "Authorization: Bearer $TOKEN" \
      -F file=@manual.pdf
    ```
  - [ ] In the Configuration table (the `JWT_SECRET` and `ADMIN_PASSWORD` rows), add a final column note: "required in MODE=full/admin"

### Task 14: Verify acceptance criteria

- [ ] Verify all four ADR 0005 punch-list items are implemented:
  - [ ] `AuthService` and `JwtConfig` exist and are wired (Tasks 4, 5, 7)
  - [ ] Admin routes are wrapped in `authenticate("jwt-admin") { ... }` (Task 7)
  - [ ] The pre-auth WARN line is removed from `Application.kt` (Task 7)
  - [ ] Cross-cutting test proving every admin endpoint returns 401 without a valid token (Task 8)
- [ ] Verify all tests pass: `cd backend && ./gradlew test`
- [ ] Verify build succeeds: `cd backend && ./gradlew build`
- [ ] Verify no Phase 5+ surface slipped in: grep backend source for `/api/admin/users`, `/api/auth/signup`, `/api/config/system-prompt` (HTTP route — KDoc references for future work are allowed) — zero matches in actual route registration
- [ ] Verify V001–V004 unchanged: `git diff main -- backend/src/main/resources/db/migration/V001__*.sql V002__*.sql V003__*.sql V004__*.sql` is empty
- [ ] Verify no `Co-Authored-By: Claude` trailers in any new commits on this branch
- [ ] Manual smoke check: start backend in `MODE=full` with `JWT_SECRET=$(openssl rand -hex 32)` and `ADMIN_PASSWORD=test1234`; `curl -X POST :8080/api/admin/documents` returns 401; `curl -X POST :8080/api/auth/login -d '{"username":"admin","password":"test1234"}'` returns a token; using that token returns the expected business response on admin endpoints
- [ ] Manual smoke check: start backend in `MODE=full` without `JWT_SECRET` → application refuses to start with the documented message
- [ ] Manual smoke check: start backend in `MODE=client` with no auth env vars → starts cleanly, `/api/auth/login` returns 404

### Task 15: Move plan to completed

- [ ] Once all checkboxes above are green, move this plan to `docs/plans/completed/<today's date>-phase-4-auth.md` (use the date the phase actually finishes, in `YYYY-MM-DD` form, matching the convention of `2026-04-09-phase-1-project-foundation.md` etc.)
- [ ] Verify the moved plan still renders correctly (no relative-path breakage to `docs/adr/`)

## Post-Completion

**Manual verification** (not code, not automated):
- Confirm a real operator can log in from a remote workstation: `curl` over the local network from a developer machine to a `MODE=full` deployment, observe a successful token exchange, and confirm an upload works end-to-end.
- Verify token expiry behavior under real clocks: obtain a token, wait 24 h + a minute, retry an admin call, observe 401. (Optional — `JwtConfigTest` already covers this with a fixed clock; production confirmation is informational.)
- Re-read `ARCHITECTURE.md` §11 in full after the edits — confirm the chat-public amendment reads coherently with the rest of the auth section and with §13 (deployment).

**External system updates** (none in Phase 4 — everything is in-repo):
- No consumer projects to notify.
- No shared infrastructure changes (Artemis, Ollama config unchanged).
- Phase 5 (admin UI + chat UI) will consume `POST /api/auth/login` from the browser; the wire contract is finalized in Task 6.
