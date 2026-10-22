-- ============================================================
-- Migration 0010: Stage 5 — installment reminders + payment claims
-- ADDITIVE ONLY (same class as 0009): two new tables and two new
-- columns on reminders. No existing table is rebuilt.
--
-- NOTE: the ALTER TABLE statements are NOT idempotent (same class as
-- 0001/0003/0004/0009). They are applied exactly once through the
-- wrangler migration ledger; never run this file manually twice.
-- ============================================================

-- Server-side reminder scheduling (Stage 5). The worker derives the five
-- nobs (-7, -4, -1, 0, +1 days relative to the due date) from the synced
-- installment itself, so reminders keep working even when the app is off.
-- trigger_offset marks WHICH nob a row belongs to; schedule_source marks
-- who created it ('server' = the deterministic worker scheduler,
-- 'app' = a reminder pushed by the Android app). The scheduler keeps at
-- most one SCHEDULED row per (installment, offset) across both sources,
-- so the two paths can never double-send.
ALTER TABLE reminders ADD COLUMN trigger_offset INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reminders ADD COLUMN schedule_source TEXT NOT NULL DEFAULT 'app';

CREATE INDEX IF NOT EXISTS idx_reminders_installment_offset
    ON reminders(academy_id, installment_id, trigger_offset, status);

-- A payment receipt (bank-transfer screenshot) submitted by a STUDENT
-- through the bot. It is EVIDENCE for review only — it never marks the
-- installment paid by itself. The manager approves or rejects it from the
-- bot; only approval registers a REAL payment (deterministic id
-- 'clm-<claim id>', so a second click can never register twice).
CREATE TABLE IF NOT EXISTS payment_claims (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    student_name TEXT NOT NULL DEFAULT '',
    installment_id TEXT NOT NULL,
    installment_number INTEGER NOT NULL DEFAULT 0,
    course_title TEXT NOT NULL DEFAULT '',
    claimed_amount INTEGER NOT NULL DEFAULT 0,   -- server-side remaining at submission (never user-editable)
    note TEXT NOT NULL DEFAULT '',               -- student's caption / description
    photo_bytes BLOB,                            -- compressed Telegram photo (≤ 200 KB), evidence only
    photo_file_id TEXT NOT NULL DEFAULT '',      -- Telegram file id (re-downloadable)
    status TEXT NOT NULL DEFAULT 'PENDING',      -- PENDING | APPROVED | REJECTED
    decided_by TEXT NOT NULL DEFAULT '',         -- manager chat id (server-verified)
    decision_note TEXT NOT NULL DEFAULT '',      -- manager's rejection reason, if given
    decided_at_epoch INTEGER NOT NULL DEFAULT 0,
    payment_id TEXT NOT NULL DEFAULT '',         -- set on APPROVAL (clm-<id>)
    telegram_chat_id TEXT NOT NULL DEFAULT '',   -- student chat that submitted
    manager_message_id TEXT NOT NULL DEFAULT '', -- manager notification message (for reply-based reason)
    manager_chat_id TEXT NOT NULL DEFAULT '',    -- manager chat the notification was sent to
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_claims_pending
    ON payment_claims(academy_id, status, created_at_epoch);
CREATE INDEX IF NOT EXISTS idx_claims_installment
    ON payment_claims(academy_id, installment_id, status);

-- Payment lifecycle audit trail (register / approve / reject / reverse /
-- online-verify). Event ids are UUIDs; NO token, key or secret is ever
-- written here — details are plain financial facts only.
CREATE TABLE IF NOT EXISTS payment_events (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    student_id TEXT NOT NULL DEFAULT '',
    installment_id TEXT NOT NULL DEFAULT '',
    payment_id TEXT NOT NULL DEFAULT '',
    claim_id TEXT NOT NULL DEFAULT '',
    event_type TEXT NOT NULL,                    -- RECEIPT_SUBMITTED | RECEIPT_APPROVED | RECEIPT_REJECTED | PAYMENT_REGISTERED | PAYMENT_REVERSED | ONLINE_VERIFIED
    channel TEXT NOT NULL DEFAULT '',            -- telegram | app | worker | gateway
    actor TEXT NOT NULL DEFAULT '',              -- manager chat id / 'student' / 'system' / 'gateway'
    amount INTEGER NOT NULL DEFAULT 0,
    detail TEXT NOT NULL DEFAULT '',             -- short human-readable note (no secrets)
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_payment_events_academy
    ON payment_events(academy_id, created_at_epoch DESC);
CREATE INDEX IF NOT EXISTS idx_payment_events_installment
    ON payment_events(academy_id, installment_id, created_at_epoch);
