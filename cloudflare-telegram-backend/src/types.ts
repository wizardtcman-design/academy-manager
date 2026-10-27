// ============================================================
// TypeScript Type Definitions for Cloudflare Telegram Backend
// ============================================================

export interface Env {
    // Cloudflare Bindings (Optional - can be attached via Dashboard later)
    DB?: D1Database;
    STUDENT_KV?: KVNamespace;
    /** R2 bucket for contract PDFs (original + signed). Falls back to D1 when absent. */
    CONTRACTS?: R2Bucket;

    /** Comma separated tenant ids this deployment accepts (default: "default"). */
    ACADEMY_IDS?: string;
    /** Public origin used to build signing links (default: the Worker origin). */
    PUBLIC_BASE_URL?: string;
    /** Set once a real SMS provider is chosen; sending stays disabled while empty. */
    SMS_PROVIDER?: string;

    // Stage 9 — SMS panel + online-gateway kill-switch:
    /**
     * THE online-payment master switch. Defaults to DISABLED: without this
     * deploy-time flag every /pay route, /api/payments/link,
     * /api/payments/verify and all student-facing online hints are blocked
     * (the gateway is completely hidden). Activation later = this flag +
     * a registered provider factory + the manager's stored credentials.
     */
    ONLINE_PAYMENTS_ENABLED?: string;
    /**
     * Optional Cloudflare Secret override for the SMS.ir API key (takes
     * precedence over the per-academy setting). Set with:
     *   wrangler secret put SMSIR_API_KEY
     * Never logged, never echoed by any endpoint.
     */
    SMSIR_API_KEY?: string;

    // Stage 7 backup knobs (all optional, all free-tier safe):
    /** How many verified healthy snapshots to keep per academy (default 7). */
    BACKUP_RETENTION_DAYS?: string;
    /** Above this compressed size, file bytes are summarized, not stored (default 25 MB). */
    BACKUP_MAX_BYTES?: string;
    /** Set to "false" to exclude contract file bytes entirely (default: included). */
    BACKUP_INCLUDE_FILES?: string;
    /**
     * Production must never answer from process memory: the isolate can be
     * recycled between requests and the user would see a fake success.
     * Defaults to "true" in production (ENVIRONMENT !== 'development').
     */
    STRICT_STORAGE?: string;

    // Secrets (configured via `wrangler secret put <KEY>` or Cloudflare Dashboard)
    TELEGRAM_BOT_TOKEN: string; // Server-side secret Telegram Bot Token
    API_SECRET_KEY: string;      // Secret bearer key for Android App HTTPS API
    WEBHOOK_SECRET?: string;    // Secret for Telegram X-Telegram-Bot-Api-Secret-Token
    ADMIN_CHAT_ID?: string;     // Telegram chat id of the academy manager (admin commands)

    // Non-sensitive environment variables
    ENVIRONMENT?: string;
    BOT_USERNAME?: string;
}

// Telegram Bot API Models
export interface TelegramUser {
    id: number;
    is_bot: boolean;
    first_name: string;
    last_name?: string;
    username?: string;
    language_code?: string;
}

export interface TelegramChat {
    id: number;
    type: string;
    title?: string;
    username?: string;
    first_name?: string;
    last_name?: string;
}

export interface TelegramMessage {
    message_id: number;
    from?: TelegramUser;
    chat: TelegramChat;
    date: number;
    text?: string;
    caption?: string;
    photo?: Array<{ file_id: string; file_unique_id: string; width: number; height: number; file_size?: number }>;
    document?: { file_id: string; file_unique_id: string; file_name?: string; mime_type?: string; file_size?: number };
    /** Stage 5: set when the user replies to another message (claim rejection reason). */
    reply_to_message?: TelegramMessage;
}

export interface TelegramCallbackQuery {
    id: string;
    from: TelegramUser;
    message?: TelegramMessage;
    data?: string;
}

export interface TelegramUpdate {
    update_id: number;
    message?: TelegramMessage;
    edited_message?: TelegramMessage;
    callback_query?: TelegramCallbackQuery;
}

export interface TelegramApiResponse<T = any> {
    ok: boolean;
    result?: T;
    description?: string;
    error_code?: number;
}

// Database Models
export interface StudentRecord {
    id: string;
    student_code: string;
    full_name: string;
    phone: string;
    national_code: string;
    telegram_chat_id: string;
    telegram_username: string;
    is_telegram_connected: number; // 0 or 1
    linked_at_epoch: number;
    created_at_epoch: number;
    updated_at_epoch: number;
    /** Added by migration 0012 — 1 once the /start link has been consumed. */
    telegram_link_used?: number;
}

export interface InstallmentRecord {
    id: string;
    student_id: string;
    installment_number: number;
    total_installments: number;
    course_title: string;
    amount: number;
    paid_amount: number;
    remaining_amount: number;
    due_date_shamsi: string;
    due_date_epoch: number;
    is_paid: number;
    paid_date_shamsi: string;
    updated_at_epoch: number;
}

export interface FinancialSummaryRecord {
    student_id: string;
    total_tuition: number;
    total_paid: number;
    remaining_debt: number;
    active_courses_count: number;
    courses_summary: string;
    settlement_status: string;
    updated_at_epoch: number;
}

export interface ReminderRecord {
    id: string;
    student_id: string;
    student_name: string;
    chat_id: string;
    installment_id: string;
    reminder_type: string;
    message_text: string;
    scheduled_epoch: number;
    scheduled_time_str: string;
    status: 'SCHEDULED' | 'SENT' | 'FAILED' | 'CANCELLED';
    error_message: string;
    retry_count: number;
    created_at_epoch: number;
    sent_at_epoch: number;
    /** Set by migration 0001; carried for SMS queuing. */
    student_phone?: string;
    /** Stage 5 (migration 0010): which nob (-7/-4/-1/0/+1) this row serves. */
    trigger_offset?: number;
    /** Stage 5: who created the row — the worker scheduler or the app push. */
    schedule_source?: string;
}

export interface MessageLogRecord {
    id: string;
    student_id: string;
    student_name: string;
    chat_id: string;
    message_type: string;
    content: string;
    status: string;
    error_message: string;
    timestamp_epoch: number;
    timestamp_shamsi: string;
}

// Android <-> Backend API Payload Models
export interface ApiResponse<T = any> {
    success: boolean;
    message: string;
    data?: T;
    error?: string;
    errorCode?: number;
}

export interface SendMessageRequest {
    chatId: string;
    studentId?: string;
    studentName?: string;
    message: string;
    parseMode?: 'HTML' | 'Markdown' | 'MarkdownV2';
    withMenu?: boolean;
}

export interface ScheduleReminderRequest {
    id?: string;
    studentId: string;
    studentName: string;
    chatId: string;
    installmentId?: string;
    reminderType: string;
    message: string;
    scheduledEpoch: number;
    scheduledTimeStr?: string;
    /** Carried so cron can queue the SMS copy (migration 0001 column). */
    studentPhone?: string;
}

export interface SyncStudentRequest {
    id: string;
    studentCode?: string;
    fullName: string;
    phone?: string;
    nationalCode?: string;
    telegramChatId?: string;
    telegramUsername?: string;
    isTelegramConnected?: boolean;
    /**
     * SHA-256 of the app-generated /start link token (Stage 2 secure linking).
     * The raw token never leaves the app; the server keeps only this hash plus
     * an expiry. Omitted / empty keeps the value already stored server-side.
     */
    telegramLinkHash?: string;
    telegramLinkExpires?: number;
}

// ============================================================
// Stage 4: payment receipts & online payment transactions
// (migration 0009; every row is scoped by academy_id)
// ============================================================

export interface PaymentReceiptRecord {
    id: string;
    academy_id: string;
    student_id: string;
    installment_id: string;
    installment_number: number;
    course_title: string;
    transaction_id: string;
    tracking_number: string;
    amount: number;
    student_name: string;
    paid_date_shamsi: string;
    paid_time: string;
    payment_method: string;
    destination_account: string;
    notes: string;
    is_reversed: number;
    reversal_reason: string;
    source: string;              // manual | online
    receipt_json: string;        // verbatim app payload snapshot
    send_status: string;         // PENDING | WAITING_CONTRACT | QUEUED | SENT | FAILED | REVERSED | REVERSAL_QUEUED | REVERSED_NOTIFIED
    send_reason: string;         // payment | after-contract | online
    eligible_at_epoch: number;
    attempts: number;
    last_attempt_epoch: number;
    telegram_message_id: string;
    last_error: string;
    created_at_epoch: number;
    updated_at_epoch: number;
    /** Joined for rendering (n of N); not a stored column. */
    total_installments_hint?: number;
}

export interface PaymentTxnRecord {
    id: string;
    academy_id: string;
    student_id: string;
    installment_id: string;
    amount: number;
    status: string;              // PENDING | PAID | FAILED | CANCELED | EXPIRED | AMOUNT_MISMATCH
    provider: string;
    provider_ref: string;
    link_token_hash: string;
    redirect_url: string;
    link_created_at_epoch: number;
    expires_at_epoch: number;
    paid_amount: number;
    verified_payload: string;
    verified_at_epoch: number;
    receipt_id: string;
    created_at_epoch: number;
    updated_at_epoch: number;
}

export interface PaymentLinkTokenRecord {
    token: string;               // raw capability token (needed to re-send the SAME link)
    token_hash: string;
    academy_id: string;
    order_id: string;
    expires_at_epoch: number;
    created_at_epoch: number;
}

// ============================================================
// Stage 5 (migration 0010): student receipt claims, payment events
// ============================================================

/** A bank-transfer screenshot submitted by the student through the bot. */
export interface PaymentClaimRecord {
    id: string;
    academy_id: string;
    student_id: string;
    student_name: string;
    installment_id: string;
    installment_number: number;
    course_title: string;
    claimed_amount: number;      // server-side remaining at submission (never user-editable)
    note: string;
    photo_bytes: Uint8Array | null;
    photo_file_id: string;
    status: string;              // PENDING | APPROVED | REJECTED
    decided_by: string;          // manager chat id (server-verified)
    decision_note: string;
    decided_at_epoch: number;
    payment_id: string;          // set on APPROVAL (clm-<id>)
    telegram_chat_id: string;
    manager_message_id: string;
    manager_chat_id: string;
    created_at_epoch: number;
    updated_at_epoch: number;
}

/** Payment lifecycle audit row — financial facts only, never secrets. */
export interface PaymentEventRecord {
    id: string;
    academy_id: string;
    student_id: string;
    installment_id: string;
    payment_id: string;
    claim_id: string;
    event_type: string;          // RECEIPT_SUBMITTED | RECEIPT_APPROVED | RECEIPT_REJECTED | PAYMENT_REGISTERED | PAYMENT_REVERSED | ONLINE_VERIFIED
    channel: string;             // telegram | app | worker | gateway
    actor: string;               // manager chat id / 'student' / 'system' / 'gateway'
    amount: number;
    detail: string;
    created_at_epoch: number;
}
