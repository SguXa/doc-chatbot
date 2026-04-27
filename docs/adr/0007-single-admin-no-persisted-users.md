# ADR 0007: Single administrator, no persisted user records

**Status:** Accepted (Phase 4)
**Date:** 2026-04-27

## Context

`docs/ARCHITECTURE.md` §11 originally specified a `users` table (created by V001 with columns `id, username, password_hash, role, created_at`), a multi-role auth surface (`user | admin`), `/api/chat/*` requiring `user|admin`, and a "default admin user" bootstrap procedure that inserts a row on first start.

Phase 4 owns the implementation of this surface. ADR 0005 deferred all auth work out of Phase 2 with the explicit instruction that Phase 4 would either build §11 in full or formally narrow the contract.

By the time Phase 4 began, the product scope around the chatbot had narrowed:

- Deployment is a single internal operator running the admin tooling on the local network. Public-facing access is restricted to `MODE=client`, which exposes chat only.
- There is no demand for multiple administrator accounts, no role hierarchy beyond "is the admin", and no signup flow.
- The original `users` table is unused: V001 created it speculatively, no row has ever been written to it, and no service references the schema.

Phase 4 therefore has to choose between building the speculative multi-user surface or formally collapsing it to what the product actually needs.

## Decision

One administrator. The password is supplied via the `ADMIN_PASSWORD` environment variable, bcrypt-hashed in memory at startup by `AuthService.init`, and never persisted. Successful login at `POST /api/auth/login` issues an HS256 JWT (`iss=aos-chatbot`, `sub="admin"`, `exp=iat+86_400s`, no `aud`, no `role`) that the client carries as `Authorization: Bearer <token>` on every admin request.

Concretely:

1. The `users` table is dropped via migration `V005__drop_unused_users_table.sql`. V001–V004 stay immutable.
2. There is no `User` model, no `UserRepository`, no `users`-related code path. The only authorization decision is "valid token → admin route allowed".
3. `/api/chat/*` is **public**. The original §11.2 entry mapping chat to `user | admin` is amended to `chat = public`. `/api/health/*` is public. `/api/auth/*` is public (login is the entry point). Only `/api/admin/*` is protected.
4. `LoginRequest{username, password}` keeps `username` on the wire for forward-compatibility with a hypothetical future multi-user surface, but the server reads only `password`. The success response constants `user.username = "admin"` and `user.role = "admin"`.
5. Logout is stateless: `POST /api/auth/logout` returns 204 and the server keeps no session state. JWTs naturally expire after 24 hours. There is no revocation list and no `token_version` column.
6. Auth wiring is mode-gated. In `MODE=full` and `MODE=admin` the JWT plugin is installed and admin routes are wrapped in `authenticate("jwt-admin") { ... }`. In `MODE=client` no auth code runs and `JWT_SECRET` / `ADMIN_PASSWORD` are not consumed.
7. `Application.module()` fails fast in `MODE=full`/`MODE=admin` if `JWT_SECRET` is shorter than 32 characters or `ADMIN_PASSWORD` is blank.

## Rationale

- **The `users` table never earned its keep.** A single-row table populated from an env var is a glorified env-var redirect with extra moving parts. Dropping it removes a real failure mode (drift between the env var and the row) without losing functionality.
- **Public chat matches the deployment story.** The only public-facing mode is `MODE=client`, which exposes chat only. Adding a chat token would force every chat client to authenticate and would buy nothing the deployment topology does not already give us. Admin remains behind the same internal-network or VPN access that has always contained the admin surface.
- **Stateless tokens fit the threat model.** The administrator is also the operator who can restart the JVM. There is no scenario where a 24-hour JWT outliving operator intent is meaningfully worse than rotating the password and restarting.
- **HS256 is sufficient.** Issuer and one verifier; no key distribution problem. RS256 / ES256 would add key-management work for no tenant separation.
- **Bcrypt cost=12 is the right compromise.** ~100 ms per login on dev hardware is invisible at single-admin login frequency and resists offline cracking adequately for a hash that never leaves memory.

## Consequences

- **Operating cost is minimal.** Rotating the admin password is a single-step `env-var change + restart`. No DB writes, no migration, no `--reset-admin` script.
- **Single point of failure is the env var.** Anyone with shell access to the runtime environment can read `ADMIN_PASSWORD`. This is identical to the existing risk model — the same operator already has DB access — so we are not introducing new exposure.
- **No multi-user upgrade path without follow-up work.** Adding user accounts later requires a fresh migration to recreate `users`, a `UserRepository`, login logic that selects by `username`, and `role`-aware authorization. ARCHITECTURE.md §11 should be revisited if and when that demand materializes; this ADR would be amended or superseded at that point.
- **§11.2 is amended.** The original `chat = user | admin` row becomes `chat = public`. The amendment is recorded both here and in ARCHITECTURE.md §11.2 directly.
- **Token expiry is the only revocation.** A leaked token is valid until `exp`. Forced revocation is out of scope for Phase 4. If it ever becomes required, that lands in a separate ADR (a `token_version` column or a revocation list with a TTL).
- **No clock leeway.** `JwtConfig` uses `java-jwt`'s default `acceptLeeway=0`. Deployments with skewed clocks must run NTP. This is documented in `JwtConfig`'s KDoc.
- **Pre-auth WARN line is removed.** ADR 0005's third punch-list item required removing the unprotected-admin-routes warning once auth landed; Phase 4 does that. Operators no longer see the warning in `MODE=full`/`MODE=admin` startup logs.

## Alternatives considered

- **Keep the speculative `users`-table design and bootstrap one row from `ADMIN_PASSWORD`.** Rejected. A single-row table is not a "users system", just the env-var redirect described above. It also adds drift hazards (table can be edited out-of-band) and a migration debt that has to be paid back the day the table is finally dropped.
- **Argon2 instead of bcrypt.** Rejected. Argon2's JVM bindings require a native library, which complicates the fat-JAR distribution model used by this project. Bcrypt cost=12 is adequate for a single-login-per-day surface.
- **Server-side token revocation (`token_version` column on `users`, or a revocation list).** Rejected. With one administrator who can also restart the JVM, there is no scenario where forced revocation is meaningfully different from rotating `ADMIN_PASSWORD` and restarting. Adding the column reintroduces the `users` table this ADR is removing.
- **Audience claim (`aud`).** Rejected. There is exactly one consumer of these tokens (the same backend that issued them) and one issuer; an `aud` claim adds no security and one more thing to verify and document.
- **Protected chat (`/api/chat/*` requires `user | admin`).** Rejected. The deployment story routes public chat through the `MODE=client` frontend and keeps admin behind internal-network or VPN access. Adding a chat token means every chat client needs auth, which the product never asked for, and forces a user-account model whose only real consumer would be the chat surface.
- **Per-request rate limiting on auth endpoints.** Deferred. With single-admin and a 100 ms bcrypt cost, brute-force resistance is dominated by the cost factor, not by request rate. If the auth surface ever broadens, rate limiting comes back on the table.
