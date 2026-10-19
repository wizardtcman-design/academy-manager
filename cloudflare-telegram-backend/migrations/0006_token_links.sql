-- ============================================================
-- Migration 0006: resendable signing links + tenant-scoped bot token
-- The app builds its link offline, so the server keeps the raw token ONLY for
-- an unsigned, unexpired contract; it is deleted as soon as the contract is
-- signed or revoked and never returned to anybody but the academy API.
-- ============================================================

CREATE TABLE IF NOT EXISTS contract_pending_links (
    contract_id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    token TEXT NOT NULL,
    expires_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_pending_links_academy ON contract_pending_links(academy_id);
