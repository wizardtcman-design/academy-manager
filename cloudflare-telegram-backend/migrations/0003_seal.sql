-- ============================================================
-- Migration 0003: academy seal / manager signature on contracts
-- ============================================================

ALTER TABLE contracts ADD COLUMN signer_title TEXT NOT NULL DEFAULT '';
ALTER TABLE contracts ADD COLUMN seal_key TEXT NOT NULL DEFAULT '';
ALTER TABLE contracts ADD COLUMN seal_sha256 TEXT NOT NULL DEFAULT '';
ALTER TABLE contracts ADD COLUMN seal_width INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contracts ADD COLUMN seal_height INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contracts ADD COLUMN sig_key TEXT NOT NULL DEFAULT '';
ALTER TABLE contracts ADD COLUMN sig_sha256 TEXT NOT NULL DEFAULT '';
ALTER TABLE contracts ADD COLUMN sig_width INTEGER NOT NULL DEFAULT 0;
ALTER TABLE contracts ADD COLUMN sig_height INTEGER NOT NULL DEFAULT 0;
