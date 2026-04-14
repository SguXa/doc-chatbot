-- V002: Make chunks.embedding nullable for Phase 2 (embeddings deferred to Phase 3)
-- SQLite requires table rebuild to alter column constraints

CREATE TABLE chunks_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_type TEXT NOT NULL,
    page_number INTEGER,
    section_id TEXT,
    heading TEXT,
    embedding BLOB,
    image_refs TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

INSERT INTO chunks_new SELECT * FROM chunks;

DROP TABLE chunks;

ALTER TABLE chunks_new RENAME TO chunks;

-- Recreate indexes from V001
CREATE INDEX idx_chunks_document ON chunks(document_id);
CREATE INDEX idx_chunks_content_type ON chunks(content_type);
CREATE INDEX idx_chunks_section ON chunks(section_id);
