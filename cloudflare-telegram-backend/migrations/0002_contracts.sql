-- ============================================================
-- Migration 0002: digital contracts (one per enrollment)
-- ============================================================

CREATE TABLE IF NOT EXISTS contracts (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    enrollment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    student_name TEXT NOT NULL DEFAULT '',
    course_title TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'ISSUED',   -- ISSUED | OPENED | SIGNED | EXPIRED | REVOKED
    token_hash TEXT NOT NULL,
    expires_at_epoch INTEGER NOT NULL DEFAULT 0,
    issued_at_epoch INTEGER NOT NULL DEFAULT 0,
    opened_at_epoch INTEGER NOT NULL DEFAULT 0,
    signed_at_epoch INTEGER NOT NULL DEFAULT 0,
    signed_at_shamsi TEXT NOT NULL DEFAULT '',
    signer_name TEXT NOT NULL DEFAULT '',
    pdf_key TEXT NOT NULL DEFAULT '',
    signed_pdf_key TEXT NOT NULL DEFAULT '',
    pdf_sha256 TEXT NOT NULL DEFAULT '',
    signed_pdf_sha256 TEXT NOT NULL DEFAULT '',
    enrollment_json TEXT NOT NULL DEFAULT '{}',
    phone_mask TEXT NOT NULL DEFAULT '',
    national_mask TEXT NOT NULL DEFAULT '',
    verify_hash TEXT NOT NULL DEFAULT '',
    rejected_attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_epoch INTEGER NOT NULL DEFAULT 0,
    last_ip TEXT NOT NULL DEFAULT '',
    last_user_agent TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);

-- One contract per enrollment, enforced by the database itself.
CREATE UNIQUE INDEX IF NOT EXISTS idx_contracts_enrollment ON contracts(academy_id, enrollment_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_contracts_token ON contracts(token_hash);
CREATE INDEX IF NOT EXISTS idx_contracts_student ON contracts(academy_id, student_id);

CREATE TABLE IF NOT EXISTS contract_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    academy_id TEXT NOT NULL,
    contract_id TEXT NOT NULL,
    type TEXT NOT NULL,          -- CREATED | LINK_ROTATED | OPENED | VERIFY_FAILED | SIGNED | REJECTED | REVOKED
    detail TEXT NOT NULL DEFAULT '',
    ip TEXT NOT NULL DEFAULT '',
    user_agent TEXT NOT NULL DEFAULT '',
    at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_contract_events ON contract_events(academy_id, contract_id, at_epoch);

-- Fallback when no R2 bucket is bound: the PDF bytes live in D1 so nothing is lost.
CREATE TABLE IF NOT EXISTS contract_files (
    key TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    content BLOB NOT NULL,
    sha256 TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);
