-- ============================================================
-- Migration 0005: chunked contract files inside D1
-- R2 is not enabled on this account yet. D1 caps a row at 2 MB, so each file
-- is stored as ordered parts; `contract_files` keeps the metadata (+ sha256)
-- and legacy rows still hold the whole blob and stay readable.
-- ============================================================

CREATE TABLE IF NOT EXISTS contract_file_chunks (
    file_key TEXT NOT NULL,
    part INTEGER NOT NULL,
    content BLOB NOT NULL,
    PRIMARY KEY (file_key, part)
);

CREATE INDEX IF NOT EXISTS idx_chunks_file ON contract_file_chunks(file_key);
