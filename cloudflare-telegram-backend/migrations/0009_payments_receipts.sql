-- ============================================================
-- Migration 0009: payment receipts + online payment transactions
-- (Stage 4) ADDITIVE ONLY — creates two new tables and adds one
-- column to installments. No existing table is rebuilt (unlike 0007),
-- so a partial failure can never orphan contract storage.
--
-- NOTE: the ALTER TABLE at the end is NOT idempotent (same class as
-- 0001/0003/0004). It is applied exactly once through the wrangler
-- migration ledger; never run this file manually twice.
-- ============================================================

-- One row per REAL payment transaction (same id as the app's Payment).
-- The PRIMARY KEY is the app payment id itself, so re-syncing the same
-- payment can never create a duplicate receipt.
CREATE TABLE IF NOT EXISTS payment_receipts (
    id TEXT PRIMARY KEY,
    academy_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    installment_id TEXT NOT NULL DEFAULT '',
    installment_number INTEGER NOT NULL DEFAULT 0,
    course_title TEXT NOT NULL DEFAULT '',
    transaction_id TEXT NOT NULL DEFAULT '',
    tracking_number TEXT NOT NULL DEFAULT '',
    amount INTEGER NOT NULL DEFAULT 0,
    student_name TEXT NOT NULL DEFAULT '',
    paid_date_shamsi TEXT NOT NULL DEFAULT '',
    paid_time TEXT NOT NULL DEFAULT '',
    payment_method TEXT NOT NULL DEFAULT '',
    destination_account TEXT NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '',
    is_reversed INTEGER NOT NULL DEFAULT 0,
    reversal_reason TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'manual',       -- manual | online
    receipt_json TEXT NOT NULL DEFAULT '{}',     -- full app payload snapshot (printable render source)
    send_status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING | WAITING_CONTRACT | QUEUED | SENT | FAILED | REVERSED | REVERSAL_QUEUED | REVERSED_NOTIFIED
    send_reason TEXT NOT NULL DEFAULT 'payment', -- payment | after-contract | online
    eligible_at_epoch INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_attempt_epoch INTEGER NOT NULL DEFAULT 0,
    telegram_message_id TEXT NOT NULL DEFAULT '',
    last_error TEXT NOT NULL DEFAULT '',
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_receipts_dispatch
    ON payment_receipts(academy_id, send_status, eligible_at_epoch);
CREATE INDEX IF NOT EXISTS idx_receipts_student
    ON payment_receipts(academy_id, student_id, created_at_epoch);

-- One row per online payment ATTEMPT (dedicated link). The link token is
-- stored ONLY as a SHA-256; the raw token lives in the /pay/<token> URL.
CREATE TABLE IF NOT EXISTS payment_transactions (
    id TEXT PRIMARY KEY,                          -- unique order id
    academy_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    installment_id TEXT NOT NULL,
    amount INTEGER NOT NULL DEFAULT 0,            -- expected amount (exact remaining at creation)
    status TEXT NOT NULL DEFAULT 'PENDING',       -- PENDING | PAID | FAILED | CANCELED | EXPIRED | AMOUNT_MISMATCH
    provider TEXT NOT NULL DEFAULT 'none',
    provider_ref TEXT NOT NULL DEFAULT '',        -- gateway authority/ref id
    link_token_hash TEXT NOT NULL DEFAULT '',
    redirect_url TEXT NOT NULL DEFAULT '',        -- provider checkout URL (302 target of /pay/<token>)
    link_created_at_epoch INTEGER NOT NULL DEFAULT 0,
    expires_at_epoch INTEGER NOT NULL DEFAULT 0,
    paid_amount INTEGER NOT NULL DEFAULT 0,
    verified_payload TEXT NOT NULL DEFAULT '',    -- gateway verification response (audit)
    verified_at_epoch INTEGER NOT NULL DEFAULT 0,
    receipt_id TEXT NOT NULL DEFAULT '',          -- payment_receipts row created after PAID
    created_at_epoch INTEGER NOT NULL DEFAULT 0,
    updated_at_epoch INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_txn_token ON payment_transactions(link_token_hash);
CREATE INDEX IF NOT EXISTS idx_txn_installment
    ON payment_transactions(academy_id, installment_id, status);

-- Raw capability tokens for /pay/<token> URLs, kept ONLY so the reminder
-- flow can re-send the SAME link instead of minting new orders. The token
-- opens the payment page; it can never confirm a payment by itself.
CREATE TABLE IF NOT EXISTS payment_link_tokens (
    token TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL,
    academy_id TEXT NOT NULL,
    order_id TEXT NOT NULL,
    expires_at_epoch INTEGER NOT NULL DEFAULT 0,
    created_at_epoch INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_link_tokens_hash ON payment_link_tokens(token_hash);

-- Manager-chosen payment method per installment:
-- '' (unset) | ONLINE | TRANSFER | POS | CASH | BANK_DEPOSIT
ALTER TABLE installments ADD COLUMN payment_method TEXT NOT NULL DEFAULT '';
