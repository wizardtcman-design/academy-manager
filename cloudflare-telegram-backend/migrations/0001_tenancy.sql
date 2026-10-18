-- ============================================================
-- Migration 0001: multi-tenant foundation
-- Every row is scoped by academy_id (the tenant). The app sends it on every
-- request; D1 rows from one academy are never reachable with another's key.
-- ============================================================

ALTER TABLE students ADD COLUMN academy_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE installments ADD COLUMN academy_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE financial_summaries ADD COLUMN academy_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE reminders ADD COLUMN academy_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE message_logs ADD COLUMN academy_id TEXT NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS idx_students_academy ON students(academy_id, student_code);
CREATE INDEX IF NOT EXISTS idx_installments_academy ON installments(academy_id, student_id);
CREATE INDEX IF NOT EXISTS idx_reminders_academy ON reminders(academy_id, status, scheduled_epoch);
CREATE INDEX IF NOT EXISTS idx_logs_academy ON message_logs(academy_id, timestamp_epoch DESC);

-- Channels: Telegram and SMS must be tracked separately, so one channel failing
-- never blocks or deduplicates the other.
ALTER TABLE reminders ADD COLUMN sms_status TEXT NOT NULL DEFAULT 'NONE';
ALTER TABLE reminders ADD COLUMN sms_error TEXT NOT NULL DEFAULT '';
ALTER TABLE reminders ADD COLUMN sms_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reminders ADD COLUMN sms_sent_at_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE reminders ADD COLUMN student_phone TEXT NOT NULL DEFAULT '';

CREATE TABLE IF NOT EXISTS academies (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO academies (id, name, created_at_epoch)
VALUES ('default', 'academy', 0);

-- Idempotency for delivery: one row per (reminder, channel) and nothing else.
-- A re-run of cron or a re-sent request can therefore never send a duplicate.
CREATE TABLE IF NOT EXISTS deliveries (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    reminder_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    error TEXT NOT NULL DEFAULT '',
    provider_message_id TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0,
    sent_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_deliveries_unique ON deliveries(academy_id, reminder_id, channel);
CREATE INDEX IF NOT EXISTS idx_deliveries_status ON deliveries(academy_id, channel, status);

ALTER TABLE students ADD COLUMN telegram_link_hash TEXT NOT NULL DEFAULT '';
ALTER TABLE students ADD COLUMN telegram_link_expires INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS idx_students_link_hash ON students(telegram_link_hash);

CREATE TABLE IF NOT EXISTS sms_outbox (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    reminder_id TEXT NOT NULL,
    phone TEXT NOT NULL,
    body TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DISABLED',
    error TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);
