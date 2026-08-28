-- ============================================================
-- Cloudflare D1 Relational Schema for Academy Telegram Backend
-- ============================================================

-- 1. Students Table
CREATE TABLE IF NOT EXISTS students (
    id TEXT PRIMARY KEY,
    student_code TEXT NOT NULL DEFAULT '',
    full_name TEXT NOT NULL,
    phone TEXT NOT NULL DEFAULT '',
    national_code TEXT NOT NULL DEFAULT '',
    telegram_chat_id TEXT NOT NULL DEFAULT '',
    telegram_username TEXT NOT NULL DEFAULT '',
    is_telegram_connected INTEGER NOT NULL DEFAULT 0,
    linked_at_epoch INTEGER NOT NULL DEFAULT 0,
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_students_chat_id ON students(telegram_chat_id);
CREATE INDEX IF NOT EXISTS idx_students_phone ON students(phone);

-- 2. Installments Table (For student menu queries & automated background reminders)
CREATE TABLE IF NOT EXISTS installments (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL,
    installment_number INTEGER NOT NULL DEFAULT 1,
    total_installments INTEGER NOT NULL DEFAULT 1,
    course_title TEXT NOT NULL DEFAULT '',
    amount INTEGER NOT NULL DEFAULT 0,
    paid_amount INTEGER NOT NULL DEFAULT 0,
    remaining_amount INTEGER NOT NULL DEFAULT 0,
    due_date_shamsi TEXT NOT NULL DEFAULT '',
    due_date_epoch INTEGER NOT NULL DEFAULT 0,
    is_paid INTEGER NOT NULL DEFAULT 0,
    paid_date_shamsi TEXT NOT NULL DEFAULT '',
    updated_at_epoch INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(student_id) REFERENCES students(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_installments_student_id ON installments(student_id);
CREATE INDEX IF NOT EXISTS idx_installments_due_date ON installments(due_date_epoch, is_paid);

-- 3. Financial Summary Table (For instant student financial balance queries)
CREATE TABLE IF NOT EXISTS financial_summaries (
    student_id TEXT PRIMARY KEY,
    total_tuition INTEGER NOT NULL DEFAULT 0,
    total_paid INTEGER NOT NULL DEFAULT 0,
    remaining_debt INTEGER NOT NULL DEFAULT 0,
    active_courses_count INTEGER NOT NULL DEFAULT 0,
    courses_summary TEXT NOT NULL DEFAULT '',
    settlement_status TEXT NOT NULL DEFAULT 'PENDING',
    updated_at_epoch INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(student_id) REFERENCES students(id) ON DELETE CASCADE
);

-- 4. Reminders Queue Table (For scheduled Telegram messages via Cron or API)
CREATE TABLE IF NOT EXISTS reminders (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL,
    student_name TEXT NOT NULL,
    chat_id TEXT NOT NULL,
    installment_id TEXT NOT NULL DEFAULT '',
    reminder_type TEXT NOT NULL DEFAULT 'REMINDER_DUE_DAY',
    message_text TEXT NOT NULL,
    scheduled_epoch INTEGER NOT NULL,
    scheduled_time_str TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, SENT, FAILED, CANCELLED
    error_message TEXT NOT NULL DEFAULT '',
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    sent_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_reminders_status_epoch ON reminders(status, scheduled_epoch);
CREATE INDEX IF NOT EXISTS idx_reminders_student ON reminders(student_id);

-- 5. Message Logs Table (For audit tracking and history)
CREATE TABLE IF NOT EXISTS message_logs (
    id TEXT PRIMARY KEY,
    student_id TEXT NOT NULL DEFAULT '',
    student_name TEXT NOT NULL DEFAULT '',
    chat_id TEXT NOT NULL,
    message_type TEXT NOT NULL DEFAULT 'DIRECT',
    content TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'SUCCESS', -- SUCCESS, FAILED
    error_message TEXT NOT NULL DEFAULT '',
    timestamp_epoch INTEGER NOT NULL,
    timestamp_shamsi TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON message_logs(timestamp_epoch DESC);

-- 6. Academy App Settings & Metadata
CREATE TABLE IF NOT EXISTS academy_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);
