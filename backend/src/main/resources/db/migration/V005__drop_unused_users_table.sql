-- V005: Drop the unused users table.
-- The users table created by V001 was speculatively added for a multi-user
-- design that Phase 4 has decided not to enter. Single-admin auth uses an
-- in-memory bcrypt hash of ADMIN_PASSWORD; no row is ever written here.
-- The IF EXISTS guard keeps this idempotent on databases where the table
-- has already been removed by a manual repair. See ADR 0007.

DROP TABLE IF EXISTS users;
