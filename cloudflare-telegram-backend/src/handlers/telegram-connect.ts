// ============================================================
// Telegram bot connection API (app side, authenticated with the API key)
//
// The manager pastes the bot token in the app; the app POSTs it here with the
// academy API key. This Worker keeps ONE bot per academy in D1 and points the
// Telegram webhook at /webhook/<academy>, so connecting or reconnecting a bot
// needs no redeploy and never touches another tenant's bot.
//
// The token is persisted (D1 column, not returned to any client) and only its
// masked tail is shown back. A bot id that already belongs to another academy
// is refused, so one bot can never be shared or hijacked.
// ============================================================

import { Env } from '../types';
import { TelegramBotClient } from '../telegram';
import { ContractStore, AcademyRow } from '../contracts-store';
import { timingSafeEqual } from '../crypto';

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
            'Access-Control-Allow-Origin': '*'
        }
    });
}

function authorized(request: Request, env: Env): boolean {
    const header = request.headers.get('Authorization') || '';
    if (!header.startsWith('Bearer ')) return false;
    const token = header.slice(7).trim();
    // Constant-time compare — the same guarantee the contracts API gives.
    return Boolean(env.API_SECRET_KEY) && timingSafeEqual(token, env.API_SECRET_KEY.trim());
}

function academyFor(request: Request, env: Env): string | null {
    const header = (request.headers.get('X-Academy-Id') || '').trim();
    const allowed = (env.ACADEMY_IDS || 'default').split(',').map((s) => s.trim()).filter(Boolean);
    const academy = header || allowed[0] || 'default';
    return allowed.includes(academy) ? academy : null;
}

function publicBase(request: Request, env: Env): string {
    return (env.PUBLIC_BASE_URL || new URL(request.url).origin).replace(/\/$/, '');
}

function webhookUrl(request: Request, env: Env, academy: string): string {
    return `${publicBase(request, env)}/webhook/${encodeURIComponent(academy)}`;
}

const SECRET_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

function randomSecret(len = 40): string {
    const bytes = new Uint8Array(len);
    crypto.getRandomValues(bytes);
    let out = '';
    for (const b of bytes) out += SECRET_ALPHABET[b % SECRET_ALPHABET.length];
    return out;
}

function mask(token: string): string {
    if (!token) return '';
    return token.length <= 8 ? '••••' : `••••${token.slice(-4)}`;
}

/** The stored token, or the legacy global secret when the academy has none. */
function tokenFor(env: Env, row: AcademyRow | null): string {
    return (row?.bot_token || env.TELEGRAM_BOT_TOKEN || '').trim();
}

async function describe(request: Request, env: Env, academy: string, row: AcademyRow | null) {
    const token = tokenFor(env, row);
    const expected = webhookUrl(request, env, academy);
    if (!token) {
        return {
            configured: false,
            ok: false,
            source: 'none',
            username: '',
            webhook: '',
            expectedWebhook: expected
        };
    }
    const bot = new TelegramBotClient(token);
    let me: any = null;
    let hook: any = null;
    try {
        const res = await bot.getMe();
        me = res.ok ? res.result : null;
    } catch (_) {}
    try {
        const info = await bot.getWebhookInfo();
        hook = info.ok ? info.result : null;
    } catch (_) {}
    return {
        configured: Boolean(me),
        ok: Boolean(me),
        source: row?.bot_token ? 'academy' : 'server',
        tokenMasked: mask(token),
        username: me?.username || row?.bot_username || '',
        botName: me?.first_name || '',
        webhook: hook?.url || '',
        expectedWebhook: expected,
        webhookConfirmed: (hook?.url || '') === expected,
        pendingUpdates: hook?.pending_update_count ?? null,
        lastError: me ? (hook?.last_error_message || '') : 'token rejected by Telegram'
    };
}

export async function handleTelegramConnect(request: Request, env: Env, url: URL): Promise<Response> {
    if (!authorized(request, env)) return json({ success: false, message: 'دسترسی غیرمجاز.' }, 401);
    const academy = academyFor(request, env);
    if (!academy) return json({ success: false, message: 'شناسه آموزشگاه معتبر نیست.' }, 403);

    const store = new ContractStore(env);

    if (url.pathname === '/api/bot/status' && request.method === 'GET') {
        let row: AcademyRow | null = null;
        try {
            row = await store.getAcademy(academy);
        } catch (_) {
            return json({ success: false, message: 'جدول ربات‌ها آماده نیست (Migration 0004).' }, 500);
        }
        return json({ success: true, data: await describe(request, env, academy, row) });
    }

    if (url.pathname === '/api/bot/connect' && request.method === 'POST') {
        let body: any;
        try {
            body = await request.json();
        } catch {
            return json({ success: false, message: 'بدنهٔ درخواست معتبر نیست.' }, 400);
        }
        const token = String(body.token || '').trim();
        if (!/^\d{6,12}:[A-Za-z0-9_-]{30,}$/.test(token)) {
            return json({ success: false, message: 'توکن ربات معتبر نیست. توکن را از @BotFather کامل کپی کنید.' }, 400);
        }

        const bot = new TelegramBotClient(token);
        let me: any;
        try {
            const res = await bot.getMe();
            if (!res.ok || !res.result) {
                return json({ success: false, message: 'تلگرام این توکن را نپذیرفت: ' + (res.description || 'توکن نامعتبر') }, 400);
            }
            me = res.result;
        } catch (_) {
            return json({ success: false, message: 'ارتباط با تلگرام برقرار نشد. اتصال اینترنت سرور را بررسی کنید.' }, 502);
        }

        // A bot id belongs to exactly one academy.
        let owner: AcademyRow | null = null;
        try {
            owner = await store.findAcademyByBotId(String(me.id));
        } catch (_) {}
        if (owner && owner.id !== academy) {
            return json({ success: false, message: 'این ربات قبلاً به آموزشگاه دیگری متصل شده است.' }, 409);
        }

        const secret = randomSecret();
        const target = webhookUrl(request, env, academy);
        const hook = await bot.setWebhook(target, secret);
        if (!hook.ok) {
            return json({ success: false, message: 'ثبت وبهوک ناموفق بود: ' + (hook.description || 'خطای تلگرام') }, 502);
        }

        await store.saveAcademyBot({
            id: academy,
            botToken: token,
            botUsername: me.username || '',
            botId: String(me.id),
            webhookSecret: secret
        });

        // Confirm with a second round-trip: success is reported only when
        // Telegram itself shows our webhook for this bot.
        let live = false;
        try {
            const info = await bot.getWebhookInfo();
            live = Boolean(info.ok && info.result?.url === target);
        } catch (_) {}

        return json({
            success: live,
            message: live
                ? `ربات @${me.username || me.first_name} متصل شد و وبهوک ثبت گردید.`
                : 'توکن ذخیره شد، اما تلگرام هنوز وبهوک را تأیید نکرده است. دوباره تلاش کنید.',
            data: {
                username: me.username || '',
                botName: me.first_name || '',
                webhookUrl: target,
                webhookConfirmed: live
            }
        }, live ? 200 : 502);
    }

    if (url.pathname === '/api/bot/refresh-webhook' && request.method === 'POST') {
        const row = await store.getAcademy(academy);
        const token = tokenFor(env, row);
        if (!token) return json({ success: false, message: 'ابتدا ربات را متصل کنید.' }, 400);
        const target = webhookUrl(request, env, academy);
        const res = await new TelegramBotClient(token).setWebhook(target, row?.webhook_secret || env.WEBHOOK_SECRET);
        return json({
            success: res.ok,
            message: res.ok ? 'وبهوک به‌روز شد.' : 'ثبت وبهوک ناموفق بود: ' + (res.description || ''),
            data: { webhookUrl: target }
        }, res.ok ? 200 : 502);
    }

    if (url.pathname === '/api/bot/disconnect' && request.method === 'POST') {
        const row = await store.getAcademy(academy);
        const token = row?.bot_token || '';
        if (token) {
            try {
                await new TelegramBotClient(token).deleteWebhook();
            } catch (_) {}
        }
        await store.saveAcademyBot({ id: academy, botToken: '', botUsername: '', botId: '', webhookSecret: '' });
        return json({ success: true, message: 'ربات از این آموزشگاه جدا شد.' });
    }

    if (url.pathname === '/api/bot/updates' && request.method === 'GET') {
        // Diagnostics only: never returns message contents.
        const row = await store.getAcademy(academy);
        const token = tokenFor(env, row);
        if (!token) return json({ success: false, message: 'ربات متصل نیست.' }, 400);
        const info = await new TelegramBotClient(token).getWebhookInfo();
        return json({ success: info.ok, data: info.result || null });
    }

    return json({ success: false, message: 'مسیر نامعتبر است.' }, 404);
}
