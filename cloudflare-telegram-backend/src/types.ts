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
