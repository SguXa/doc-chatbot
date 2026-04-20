-- V003: Add UNIQUE index on documents.file_hash for race-defense deduplication
-- See ADR 0002: UNIQUE without NOT NULL — each NULL is treated as distinct by SQLite

CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_file_hash_unique ON documents(file_hash);
