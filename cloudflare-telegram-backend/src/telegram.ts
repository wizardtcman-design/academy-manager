// ============================================================
// Server-Side Telegram Bot API Client for Cloudflare Workers
// ============================================================

import { TelegramApiResponse, TelegramUser } from './types';

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

    /**
     * Standard Persian Student Interactive Reply Keyboard
     */
    static getStudentReplyKeyboard() {
        return {
            keyboard: [
                [{ text: '💰 وضعیت مالی' }, { text: '📅 اقساط من' }],
                [{ text: '🧾 رسیدهای من' }, { text: '📄 قرارداد من' }],
                [{ text: '📞 تماس با آموزشگاه' }, { text: '🔄 بروزرسانی اطلاعات' }]
            ],
            resize_keyboard: true,
            persistent: true,
            is_persistent: true
        };
    }
}
