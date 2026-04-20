# ADR 0005: Authentication deferred out of Phase 2

**Status:** Accepted (Phase 2)
**Date:** 2026-04-14

## Context

`docs/ARCHITECTURE.md` §11 specifies JWT-based authentication with a `users` table, an `/api/auth/login` endpoint, and protected admin/chat routes. This is real product scope — but it is **Phase 4 work**, not Phase 2 work. Phase 2's job is the document parsing pipeline and admin upload routes.

Phase 2 ships `POST /api/admin/documents`, `GET /api/admin/documents`, and `DELETE /api/admin/documents/{id}` with no authentication.

## Decision

- Phase 2 does **not** implement any part of the auth system.
- Admin routes registered by Phase 2 are **unprotected**.
- There is no placeholder auth code, no commented-out `authenticate { ... }` block, no `AUTH_DISABLED` toggle. Unprotected is unconditional, not a feature flag.
- Application startup emits a prominent `WARN` log line in `MODE=full` and `MODE=admin` announcing that `/api/admin/*` is unauthenticated. This warning fires once at startup, never per request.
- The `MODE=client` deployment registers no admin routes at all and does not emit the warning.

## Rationale

- Auth is a cross-cutting concern that touches routes, services, the DB schema (the `users` table from V001), tests, the frontend, and Docker configuration. Bundling it into Phase 2 would inflate scope and delay parsing work that other phases depend on.
- Phase 2 is testable end-to-end without auth. Adding auth would also force Phase 2 tests to set up JWT fixtures, which would obscure the parsing work being tested.
- The temporary unprotected window is **acceptable only because** Phase 2 is run in admin/full mode by trusted operators on internal networks. It is **not** acceptable for any public-facing deployment. See `docs/ARCHITECTURE.md` §7.2 deployment note.

## Consequences

- **Pre-auth deployment rule:** Until Phase 4 lands, the only acceptable public-facing mode is `MODE=client`, which exposes chat only and registers no admin routes. `MODE=full` and `MODE=admin` must be restricted to internal networks during the Phase 2/Phase 3 window.
- The startup WARN log makes the temporary state loud. Operators cannot deploy a Phase 2 admin/full instance without seeing the warning in logs.
- Phase 4 will introduce the auth system in one focused phase. Its plan should:
  1. Implement `AuthService`, `JwtConfig`, login/logout routes.
  2. Wrap admin/chat routes with `authenticate { ... }`.
  3. Remove the unprotected-mode WARN log emitted by Phase 2.
  4. Add integration tests proving every protected route returns 401 without a valid token.
- Phase 2 task verification (see Task 15) greps the source tree to confirm no auth code (`Authentication`, `BearerAuth`, `JWT`, `authenticate {`, `principal`, `Authorization`, `AUTH_DISABLED`, `DEV_BYPASS_AUTH`, `SKIP_AUTH`) leaks in early. This is checked, not assumed.
