-- ============================================================
-- Migration 0007: contract_files.content becomes nullable
-- The chunked storage path (0005) writes the metadata row with
-- content = NULL and keeps the bytes in contract_file_chunks.
-- The original NOT NULL made that INSERT fail on a fresh database,
-- so chunked D1 storage could never persist a file. SQLite cannot
-- drop a NOT NULL in place: rebuild the table and copy the rows
-- (metadata rows are tiny; the file blobs of legacy rows are kept).
-- ============================================================

CREATE TABLE contract_files_nullable (
    key TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    content BLOB,
    sha256 TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);

INSERT INTO contract_files_nullable (key, academy_id, content, sha256, created_at_epoch)
    SELECT key, academy_id, content, sha256, created_at_epoch FROM contract_files;

DROP TABLE contract_files;

ALTER TABLE contract_files_nullable RENAME TO contract_files;
