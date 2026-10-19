-- ============================================================
-- Migration 0004: per-academy Telegram bot credentials
-- Each academy has at most ONE active bot; reconnecting replaces only its
-- own rows so a new bot never disturbs the other tenants.
-- ============================================================

ALTER TABLE academies ADD COLUMN bot_token TEXT NOT NULL DEFAULT '';
ALTER TABLE academies ADD COLUMN bot_username TEXT NOT NULL DEFAULT '';
ALTER TABLE academies ADD COLUMN bot_id TEXT NOT NULL DEFAULT '';
ALTER TABLE academies ADD COLUMN webhook_secret TEXT NOT NULL DEFAULT '';
ALTER TABLE academies ADD COLUMN bot_connected_at_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE academies ADD COLUMN last_update_id INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS pending_students (
    academy_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (academy_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_contracts_status ON contracts(academy_id, status, issued_at_epoch DESC);

-- One row per student+course, so a re-run of a sign/download event cannot
-- produce duplicate deliveries.
CREATE TABLE IF NOT EXISTS contract_deliveries (
    key TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    sent_at_epoch INTEGER NOT NULL DEFAULT 0
);
