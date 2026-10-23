-- ============================================================
-- Migration 0011: server-side daily backups (Stage 7)
--
-- Every academy gets one gzip-compressed JSON snapshot per day,
-- stored back inside the same D1 database in <= 768 KB chunks
-- (D1 caps a single bound parameter at 1 MB, the same reason
-- contract files use contract_file_chunks).
--
-- WHY INSIDE D1 (and not R2 / another service):
--   * R2 is not enabled on this Cloudflare account (API 10042) and
--     activating a new paid/paid-capable service is forbidden for
--     this stage. D1 free storage is 500 MB PER DATABASE (5 GB per
--     ACCOUNT) per the official limits page (developers.cloudflare.com/
--     d1/platform/limits/, verified 2026-10-23); a 7-day retention of
--     the current ~21 MB database needs well under 200 MB.
--   * Disaster recovery for a TOTAL D1 loss stays Cloudflare's own
--     Time Travel (free plan: 7-day point-in-time restore; 30 days on
--     Paid). A backup stored inside the same D1 is NOT an independent
--     copy against total database loss — it protects against LOGICAL
--     damage: accidental deletes, bad writes, corrupted rows.
--
-- SAFETY RULES (enforced in src/backup.ts):
--   * A new snapshot is fully verified (sha256 + gunzip + JSON parse
--     + row counts + financial sums + file hashes) BEFORE any older
--     copy may be pruned.
--   * If the new snapshot fails verification it is marked CORRUPT
--     and every previous HEALTHY snapshot is kept untouched.
--   * A backup row never leaves the database it backs up unless the
--     owner explicitly runs the documented restore runbook.
-- ============================================================

-- One row per finished backup attempt (healthy or not).
CREATE TABLE IF NOT EXISTS backups (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'AUTO',            -- AUTO (cron) | MANUAL (retry)
    status TEXT NOT NULL DEFAULT 'PENDING',       -- PENDING | HEALTHY | CORRUPT
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    payload_sha256 TEXT NOT NULL DEFAULT '',      -- sha256 of the gzip payload
    total_bytes INTEGER NOT NULL DEFAULT 0,       -- uncompressed JSON size
    stored_bytes INTEGER NOT NULL DEFAULT 0,      -- compressed payload size
    chunk_count INTEGER NOT NULL DEFAULT 0,
    table_counts TEXT NOT NULL DEFAULT '{}',      -- JSON { table: rows }
    financial_checks TEXT NOT NULL DEFAULT '{}',  -- JSON sums verified pre-store
    files_included INTEGER NOT NULL DEFAULT 1,    -- 0 = payload too large, metadata only
    error TEXT NOT NULL DEFAULT '',
    duration_ms INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_backups_academy ON backups(academy_id, created_at_epoch DESC);

-- The gzip payload, split into ordered binary parts.
CREATE TABLE IF NOT EXISTS backup_chunks (
    backup_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    content BLOB NOT NULL,
    PRIMARY KEY (backup_id, seq),
    FOREIGN KEY(backup_id) REFERENCES backups(id) ON DELETE CASCADE
);

-- Audit trail of every attempt (RUNNING/FAILED runs keep no backup row,
-- but the manager must still see when and why a backup failed).
CREATE TABLE IF NOT EXISTS backup_runs (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    trigger TEXT NOT NULL DEFAULT 'AUTO',         -- AUTO | MANUAL
    outcome TEXT NOT NULL DEFAULT 'RUNNING',      -- RUNNING | SUCCESS | FAILED
    backup_id TEXT NOT NULL DEFAULT '',
    error TEXT NOT NULL DEFAULT '',
    started_at_epoch INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_backup_runs_academy ON backup_runs(academy_id, started_at_epoch DESC);
