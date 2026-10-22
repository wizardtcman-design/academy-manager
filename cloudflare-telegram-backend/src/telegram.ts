// ============================================================
// Server-Side Telegram Bot API Client for Cloudflare Workers
// ============================================================

import { Env, TelegramApiResponse, TelegramUser } from './types';

export class TelegramBotClient {
    private botToken: string;
    private baseUrl: string;

    constructor(botToken: string) {
        this.botToken = botToken.trim();
        this.baseUrl = `https://api.telegram.org/bot${this.botToken}`;
    }

    /**
     * Verifies bot token validity using Telegram getMe API
     */
    async getMe(): Promise<TelegramApiResponse<TelegramUser>> {
        const res = await fetch(`${this.baseUrl}/getMe`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' }
        });
        return await res.json() as TelegramApiResponse<TelegramUser>;
    }

    /**
     * Registers Telegram Webhook to point to the Cloudflare Worker URL
     */
    async setWebhook(webhookUrl: string, secretToken?: string): Promise<TelegramApiResponse<boolean>> {
        const body: Record<string, any> = {
            url: webhookUrl,
            max_connections: 100,
            allowed_updates: ['message', 'callback_query']
        };
        if (secretToken && secretToken.trim().length > 0) {
            body.secret_token = secretToken.trim();
        }

        const res = await fetch(`${this.baseUrl}/setWebhook`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        return await res.json() as TelegramApiResponse<boolean>;
    }

    /**
     * Deletes active Telegram Webhook (allows polling getUpdates if needed)
     */
    async deleteWebhook(): Promise<TelegramApiResponse<boolean>> {
        const res = await fetch(`${this.baseUrl}/deleteWebhook`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ drop_pending_updates: false })
        });
        return await res.json() as TelegramApiResponse<boolean>;
    }

    /** Telegram's own view of the webhook (url, pending updates, last error). */
    async getWebhookInfo(): Promise<TelegramApiResponse<any>> {
        const res = await fetch(`${this.baseUrl}/getWebhookInfo`, {
            method: 'GET',
            headers: { 'Content-Type': 'application/json' }
        });
        return await res.json() as TelegramApiResponse<any>;
    }

    /**
     * Sends a text message to a specific Telegram chat with optional formatting and keyboard
     */
    async sendMessage(params: {
        chatId: string | number;
        text: string;
        parseMode?: string;
        replyMarkup?: any;
    }): Promise<TelegramApiResponse<any>> {
        const payload: Record<string, any> = {
            chat_id: params.chatId,
            text: params.text,
            parse_mode: params.parseMode || 'HTML'
        };

        if (params.replyMarkup) {
            payload.reply_markup = params.replyMarkup;
        }

        const res = await fetch(`${this.baseUrl}/sendMessage`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        return await res.json() as TelegramApiResponse<any>;
    }

    /** Sends a file (PDF) already rendered by us. */
    async sendDocument(params: {
        chatId: string | number;
        fileName: string;
        bytes: Uint8Array;
        caption?: string;
    }): Promise<TelegramApiResponse<any>> {
        const form = new FormData();
        form.append('chat_id', String(params.chatId));
        form.append('document', new Blob([params.bytes], { type: 'application/pdf' }), params.fileName);
        if (params.caption) form.append('caption', params.caption);
        const res = await fetch(`${this.baseUrl}/sendDocument`, { method: 'POST', body: form });
        return await res.json() as TelegramApiResponse<any>;
    }

    /**
     * Stage 5: sends a photo (e.g. the student's bank-transfer receipt) with
     * an optional inline keyboard (manager approve/reject buttons).
     * Stage 6: optional parseMode ('HTML') so captions with formatting tags
     * render correctly instead of showing the raw tags.
     */
    async sendPhoto(params: {
        chatId: string | number;
        bytes: Uint8Array;
        fileName?: string;
        caption?: string;
        parseMode?: string;
        replyMarkup?: any;
    }): Promise<TelegramApiResponse<any>> {
        const form = new FormData();
        form.append('chat_id', String(params.chatId));
        form.append('photo', new Blob([params.bytes], { type: 'image/jpeg' }), params.fileName || 'receipt.jpg');
        if (params.caption) form.append('caption', params.caption);
        if (params.parseMode) form.append('parse_mode', params.parseMode);
        if (params.replyMarkup) form.append('reply_markup', JSON.stringify(params.replyMarkup));
        const res = await fetch(`${this.baseUrl}/sendPhoto`, { method: 'POST', body: form });
        return await res.json() as TelegramApiResponse<any>;
    }

    /** Answers an inline-button callback (stops the client-side spinner). */
    async answerCallbackQuery(callbackQueryId: string, text?: string, showAlert = false): Promise<TelegramApiResponse<any>> {
        const payload: Record<string, any> = { callback_query_id: callbackQueryId };
        if (text) payload.text = text;
        if (showAlert) payload.show_alert = true;
        try {
            const res = await fetch(`${this.baseUrl}/answerCallbackQuery`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            return await res.json() as TelegramApiResponse<any>;
        } catch (e: any) {
            return { ok: false, description: `network: ${e?.message || e}` } as TelegramApiResponse<any>;
        }
    }

    /** Rewrites a sent message (used to settle the manager claim message after a decision). */
    async editMessageText(chatId: string | number, messageId: number | string, text: string): Promise<TelegramApiResponse<any>> {
        try {
            const res = await fetch(`${this.baseUrl}/editMessageText`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ chat_id: chatId, message_id: messageId, text, parse_mode: 'HTML' })
            });
            return await res.json() as TelegramApiResponse<any>;
        } catch (e: any) {
            return { ok: false, description: `network: ${e?.message || e}` } as TelegramApiResponse<any>;
        }
    }

    /**
     * Stage 5: downloads a file by id (getFile → file download endpoint).
     * Used for student-submitted receipt photos. Returns null on failure —
     * the caller must degrade gracefully (file id is kept for re-download).
     */
    async downloadFile(fileId: string): Promise<Uint8Array | null> {
        try {
            const meta = await fetch(`${this.baseUrl}/getFile?file_id=${encodeURIComponent(fileId)}`);
            const metaJson = await meta.json() as TelegramApiResponse<{ file_path?: string }>;
            const path = metaJson.result?.file_path;
            if (!metaJson.ok || !path) return null;
            const bin = await fetch(`https://api.telegram.org/file/bot${this.botToken}/${path}`);
            if (!bin.ok) return null;
            return new Uint8Array(await bin.arrayBuffer());
        } catch (_) {
            return null;
        }
    }

    /**
     * Standard Persian Student Interactive Reply Keyboard
     */
    static getStudentReplyKeyboard() {
        return {
            keyboard: [
                [{ text: '💰 وضعیت مالی' }, { text: '📅 اقساط من' }],
                [{ text: '🧾 رسیدهای من' }, { text: '📄 قرارداد من' }],
                [{ text: '📤 ارسال رسید پرداخت' }, { text: '📞 تماس با آموزشگاه' }],
                [{ text: '🔄 بروزرسانی اطلاعات' }]
            ],
            resize_keyboard: true,
            persistent: true,
            is_persistent: true
        };
    }

    /** Stage 5: manager inline buttons on a claim notification. */
    static getClaimDecisionKeyboard(claimId: string) {
        return {
            inline_keyboard: [[
                { text: '✅ ثبت و تأیید پرداخت', callback_data: `clm:ok:${claimId}` },
                { text: '❌ رد رسید', callback_data: `clm:no:${claimId}` }
            ]]
        };
    }
}

// ============================================================
// Stage 2 helpers: per-academy bot credentials
// ============================================================

/**
 * The academy's own bot token when it has one (D1, migration 0004); the
 * deployment secret otherwise. A bot token therefore never has to be copied
 * into the app and one tenant can never send through another tenant's bot.
 */
export async function academyBotToken(env: Env, academyId: string): Promise<string> {
    if (env.DB) {
        try {
            const row = await env.DB
                .prepare('SELECT bot_token FROM academies WHERE id = ?')
                .bind(academyId)
                .first<{ bot_token: string }>();
            if (row?.bot_token) return row.bot_token;
        } catch (_) {}
    }
    return (env.TELEGRAM_BOT_TOKEN || '').trim();
}

/**
 * Bot username used to build https://t.me/<username>?start=<token> links.
 * Priority: the connected bot of THIS academy, then the deployment var,
 * then the app-provided fallback. Returns '' when nothing is known — callers
 * must NOT fabricate a link in that case.
 */
export async function academyBotUsername(env: Env, academyId: string, fallback = ''): Promise<string> {
    let username = '';
    if (env.DB) {
        try {
            const row = await env.DB
                .prepare('SELECT bot_username FROM academies WHERE id = ?')
                .bind(academyId)
                .first<{ bot_username: string }>();
            username = (row?.bot_username || '').replace('@', '').trim();
        } catch (_) {}
    }
    if (!username) username = (env.BOT_USERNAME || '').replace('@', '').trim();
    if (!username) username = (fallback || '').replace('@', '').trim();
    return username;
}

/**
 * The webhook secret that Telegram echoes in X-Telegram-Bot-Api-Secret-Token
 * for THIS academy: its own stored secret (set at /api/bot/connect), or the
 * deployment-wide fallback. Callers must keep the legacy global behavior
 * intact — a connected academy never shares a secret with another tenant.
 */
export async function academyWebhookSecret(env: Env, academyId: string): Promise<string> {
    if (env.DB) {
        try {
            const row = await env.DB
                .prepare('SELECT webhook_secret FROM academies WHERE id = ?')
                .bind(academyId)
                .first<{ webhook_secret: string }>();
            if (row?.webhook_secret) return row.webhook_secret;
        } catch (_) {}
    }
    return (env.WEBHOOK_SECRET || '').trim();
}

/** Tenants allowed by the deployment (ACADEMY_IDS, default: 'default'). */
export function allowedAcademies(env: Env): string[] {
    return (env.ACADEMY_IDS || 'default')
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean);
}

/**
 * The academy from the request, validated against ACADEMY_IDS. Returns null
 * when the header names a tenant this deployment does not serve.
 */
export function academyFromRequest(request: Request, env: Env): string | null {
    const header = (request.headers.get('X-Academy-Id') || '').trim();
    const allowed = allowedAcademies(env);
    if (!allowed.length) allowed.push('default');
    const academy = header || allowed[0];
    return allowed.includes(academy) ? academy : null;
}

export function telegramDeepLink(botUsername: string, startParam: string): string {
    const user = (botUsername || '').replace('@', '').trim();
    if (!user || !startParam) return '';
    return `https://t.me/${user}?start=${encodeURIComponent(startParam)}`;
}
