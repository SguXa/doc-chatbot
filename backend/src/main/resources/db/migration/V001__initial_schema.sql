-- V001: Initial schema for AOS Documentation Chatbot
-- Tables: users, documents, chunks, images, config

-- Users table (for JWT auth)
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'user',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Documents table
CREATE TABLE documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size INTEGER,
    file_hash TEXT,
    chunk_count INTEGER DEFAULT 0,
    image_count INTEGER DEFAULT 0,
    indexed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Chunks table
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_type TEXT NOT NULL,
    page_number INTEGER,
    section_id TEXT,
    heading TEXT,
    embedding BLOB NOT NULL,
    image_refs TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- Images table
CREATE TABLE images (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    filename TEXT NOT NULL,
    path TEXT NOT NULL,
    page_number INTEGER,
    caption TEXT,
    description TEXT,
    embedding BLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- System configuration
CREATE TABLE config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_chunks_document ON chunks(document_id);
CREATE INDEX idx_chunks_content_type ON chunks(content_type);
CREATE INDEX idx_chunks_section ON chunks(section_id);
CREATE INDEX idx_images_document ON images(document_id);
