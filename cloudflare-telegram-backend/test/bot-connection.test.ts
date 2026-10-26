// ============================================================
// Stage 3: per-academy bot connection & data-sync tests.
//
// Proves, without any real bot/network/production database:
//   1. the app sync (students / installments / financials / reminders)
//      works with NO Telegram token anywhere — the historical 500 gate
//      that left students & installments empty is gone;
//   2. a batch whose rows cannot be stored is REPORTED (never a silent
//      200), including the migration-0008 unique-index failure mode of
//      the production database;
//   3. every Telegram send uses the token of the academy that owns the
//      rows (D1, migration 0004) — an academy without a bot is refused
//      with 409 and never borrows another tenant's bot;
//   4. /api/bot/connect stores the token in D1 only, returns no token,
//      refuses a bot owned by another academy (409) and requires the
//      bearer key (401);
//   5. the cron dispatches each academy's reminders through that
//      academy's own bot and skips academies without one;
//   6. the webhook of academy B cannot resolve a chat linked in
//      academy A (tenant isolation at the chat lookup);
//   7. files stored by the legacy latin1 build remain readable, and the
//      download header always carries the standard byte hash.
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { handleTelegramConnect } from '../src/handlers/telegram-connect';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleScheduledEvent } from '../src/handlers/cron';
import { handleContractsApi } from '../src/handlers/contracts-api';
import { handleSignRoute } from '../src/handlers/sign';
import { sha256Hex, sha256HexLegacyLatin1, sha256HexBytes } from '../src/crypto';
import { D1Shim } from './helpers/d1-shim';
import {
    makeEnv, makePdf, uploadRequest, sha256Bytes, bearer,
    loadMigrations, ACADEMY_A, ACADEMY_B, API_KEY
} from './helpers/fixtures';
import type { Env } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);

// ---------- Telegram API recorder (token-aware) ----------
interface TgCall { url: string; token: string; kind: string; chatId?: string; text?: string }
let tgCalls: TgCall[] = [];
let getMeResult: { ok: boolean; result?: any; description?: string; error_code?: number } = { ok: true, result: { id: 999, username: 'fresh_bot', first_name: 'Fresh' } };
let webhookRegistered: { url?: string; secret?: string } | null = null;
let telegramDown = false;
const realFetch = globalThis.fetch;

const TOKEN_A = '111111:AAA_test_token_for_academy_a_0001';
const TOKEN_B = '222222:BBB_test_token_for_academy_b_0002';

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) {
            return realFetch(input as any, init as any);
        }
        if (telegramDown) throw new Error('network down');
        // /bot<token>/method
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const token = m?.[1] || '';
        const kind = m?.[2] || '';
        const call: TgCall = { url, token, kind };
        if (kind === 'sendMessage') {
            const body = JSON.parse(init?.body || '{}');
            call.chatId = String(body.chat_id);
            call.text = body.text;
        }
        tgCalls.push(call);
        if (kind === 'getMe') {
            return new Response(JSON.stringify(getMeResult), { status: 200 });
        }
        if (kind === 'setWebhook') {
            const body = JSON.parse(init?.body || '{}');
            webhookRegistered = { url: body.url, secret: body.secret_token };
            return new Response(JSON.stringify({ ok: true, result: true }), { status: 200 });
        }
        if (kind === 'getWebhookInfo') {
            return new Response(JSON.stringify({ ok: true, result: { url: webhookRegistered?.url || '', pending_update_count: 0 } }), { status: 200 });
        }
        return new Response(JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }), { status: 200 });
    }) as any;
});

afterAll(() => {
    globalThis.fetch = realFetch;
});

// ---------- helpers ----------
function connectAcademyBot(db: D1Shim, academy: string, username: string, token: string) {
    db.run(
        `INSERT OR REPLACE INTO academies (id, name, bot_token, bot_username, bot_id, webhook_secret, bot_connected_at_epoch)
         VALUES (?,?,?,?,?,?,?)`,
        academy, 'Academy ' + academy, token, username, token.split(':')[0], 'sec-' + academy, Date.now()
    );
}

function auth(academy?: string): Record<string, string> {
    return academy
        ? { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': academy, 'Content-Type': 'application/json' }
        : { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };
}

async function api(env: Env, method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Response> {
    return handleApiRequest(
        new Request(`${BASE}${path}`, {
            method,
            headers: headers || auth(),
            body: body === undefined ? undefined : JSON.stringify(body)
        }),
        env, URL_(path)
    );
}

// ============================================================
// 1. Sync works with NO Telegram token at all
// ============================================================
describe('sync without any bot token (the empty-tables regression)', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { TELEGRAM_BOT_TOKEN: '' }));
    });

    it('stores a student batch with no token configured anywhere', async () => {
        const res = await api(env, 'POST', '/api/sync/students-batch', {
            students: [
                { id: 'stu-s1', fullName: 'هنرجو یک', studentCode: 'SC-1' },
                { id: 'stu-s2', fullName: 'هنرجو دو', studentCode: 'SC-2' }
            ]
        });
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.success).toBe(true);
        const rows = (db.get(`SELECT COUNT(*) AS n FROM students`) as any).n;
        expect(rows).toBe(2);
        const r1 = db.get(`SELECT academy_id, full_name FROM students WHERE id = 'stu-s1'`) as any;
        expect(r1.academy_id).toBe('default');
        expect(r1.full_name).toBe('هنرجو یک');
    });

    it('stores installments, financial summaries and reminders without a token', async () => {
        const inst = await api(env, 'POST', '/api/sync/installments', {
            installments: [{
                id: 'ins-1', student_id: 'stu-s1', installment_number: 1, total_installments: 2,
                course_title: 'ICDL', amount: 1000000, paid_amount: 0, remaining_amount: 1000000,
                due_date_shamsi: '۱۴۰۴/۰۱/۰۱', due_date_epoch: Date.now() + 86400000,
                is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now()
            }]
        });
        expect(inst.status).toBe(200);
        const instRow = db.get(`SELECT academy_id, amount FROM installments WHERE id = 'ins-1'`) as any;
        expect(instRow?.academy_id).toBe('default');

        const fin = await api(env, 'POST', '/api/sync/financials', {
            summaries: [{
                student_id: 'stu-s1', total_tuition: 1000000, total_paid: 0, remaining_debt: 1000000,
                active_courses_count: 1, courses_summary: 'ICDL', settlement_status: 'PENDING',
                updated_at_epoch: Date.now()
            }]
        });
        expect(fin.status).toBe(200);
        const finRow = db.get(`SELECT academy_id, remaining_debt FROM financial_summaries WHERE student_id = 'stu-s1'`) as any;
        expect(finRow?.academy_id).toBe('default');

        const rem = await api(env, 'POST', '/api/schedule-reminders', {
            reminders: [{
                id: 'rem-1', studentId: 'stu-s1', studentName: 'هنرجو یک', chatId: '555',
                installmentId: 'ins-1', reminderType: 'INSTALLMENT',
                message: 'یادآوری قسط', scheduledEpoch: Date.now() + 3600000
            }]
        });
        expect(rem.status).toBe(200);
        const remRow = db.get(`SELECT academy_id, status FROM reminders WHERE id = 'rem-1'`) as any;
        expect(remRow?.academy_id).toBe('default');
        expect(remRow?.status).toBe('SCHEDULED');
    });

    it('generate-link works from the D1 bot username without a global token', async () => {
        connectAcademyBot(db, 'default', 'default_bot', TOKEN_A);
        const res = await api(env, 'POST', '/api/generate-link', { studentId: 'stu-s1' });
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.link).toMatch(/^https:\/\/t\.me\/default_bot\?start=[A-Za-z0-9_-]{20,64}$/);
        expect(JSON.stringify(body)).not.toContain(TOKEN_A);
    });

    it('send-message is refused with 409 (bot not connected), not a 500 token gate', async () => {
        const { env: env2 } = makeEnv(undefined, { TELEGRAM_BOT_TOKEN: '' });
        const res = await api(env2, 'POST', '/api/send-message', { chatId: '555', message: 'سلام' });
        expect(res.status).toBe(409);
        const body = await res.json() as any;
        expect(body.message).toContain('متصل نیست');
    });

    it('unauthenticated sync is still 401', async () => {
        const res = await handleApiRequest(
            new Request(`${BASE}/api/sync/students-batch`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ students: [] })
            }), env, URL_('/api/sync/students-batch'));
        expect(res.status).toBe(401);
    });
});

// ============================================================
// 2. Failed rows are REPORTED (production parity of migration 0008)
// ============================================================
describe('students-batch failure reporting (missing migration 0008)', () => {
    it('a database whose 0008 was never applied reports the failure in Persian', async () => {
        // schema + 0001..0007 exactly like the audited production database
        const db = new D1Shim();
        db.applyMigrations(loadMigrations().slice(0, 8));
        const env: Env = {
            DB: db as unknown as Env['DB'],
            ACADEMY_IDS: `${ACADEMY_A},${ACADEMY_B}`,
            API_SECRET_KEY: API_KEY,
            TELEGRAM_BOT_TOKEN: '',
            PUBLIC_BASE_URL: BASE,
            ENVIRONMENT: 'test'
        };
        const res = await api(env, 'POST', '/api/sync/students-batch', {
            students: [
                { id: 'stu-old-1', fullName: 'اول' },
                { id: 'stu-old-2', fullName: 'دوم' }
            ]
        });
        expect(res.status).toBe(500);
        const body = await res.json() as any;
        expect(body.success).toBe(false);
        expect(body.message).toContain('0008');
        expect((db.get(`SELECT COUNT(*) AS n FROM students`) as any).n).toBe(1);
    });
});

// ============================================================
// 3. Per-academy token selection for sends
// ============================================================
describe('per-academy bot token for Telegram sends', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        connectAcademyBot(db, ACADEMY_B, 'bot_b', TOKEN_B);
        db.run(
            `INSERT INTO students (id, academy_id, full_name, telegram_chat_id, is_telegram_connected, created_at_epoch, updated_at_epoch)
             VALUES ('stu-a', ?, 'هنرجو A', '111', 1, ?, ?)`, ACADEMY_A, Date.now(), Date.now()
        );
        db.run(
            `INSERT INTO students (id, academy_id, full_name, telegram_chat_id, is_telegram_connected, created_at_epoch, updated_at_epoch)
             VALUES ('stu-b', ?, 'هنرجو B', '222', 1, ?, ?)`, ACADEMY_B, Date.now(), Date.now()
        );
    });

    it('send-message for academy A uses ONLY academy A token', async () => {
        tgCalls.length = 0;
        const res = await api(env, 'POST', '/api/send-message', { chatId: '111', message: 'سلام A' }, auth(ACADEMY_A));
        expect(res.status).toBe(200);
        expect(tgCalls.length).toBe(1);
        expect(tgCalls[0].token).toBe(TOKEN_A);
    });

    it('send-message for academy B uses ONLY academy B token', async () => {
        tgCalls.length = 0;
        const res = await api(env, 'POST', '/api/send-message', { chatId: '222', message: 'سلام B' }, auth(ACADEMY_B));
        expect(res.status).toBe(200);
        expect(tgCalls[0].token).toBe(TOKEN_B);
    });

    it('an academy whose bot row is empty is refused 409 and never borrows the other bot', async () => {
        const db3 = new D1Shim();
        const { env: env3 } = makeEnv(db3, { TELEGRAM_BOT_TOKEN: '' });
        connectAcademyBot(db3, ACADEMY_A, 'bot_a', TOKEN_A);
        tgCalls.length = 0;
        const res = await api(env3, 'POST', '/api/send-message', { chatId: '111', message: 'سلام' }, auth(ACADEMY_B));
        expect(res.status).toBe(409);
        expect(tgCalls.length).toBe(0);
    });

    it('test-connection uses the academy token and reports its username', async () => {
        tgCalls.length = 0;
        getMeResult = { ok: true, result: { id: 111, username: 'bot_a', first_name: 'A' } };
        const res = await api(env, 'POST', '/api/test-connection', {}, auth(ACADEMY_A));
        expect(res.status).toBe(200);
        expect(tgCalls[0].token).toBe(TOKEN_A);
        const body = await res.json() as any;
        expect(body.success).toBe(true);
        expect(body.message).toContain('@bot_a');
    });

    it('set-webhook registers the academy URL with the academy secret', async () => {
        webhookRegistered = null;
        const res = await api(env, 'POST', '/api/set-webhook', {}, auth(ACADEMY_A));
        expect(res.status).toBe(200);
        expect(webhookRegistered?.url).toBe(`${BASE}/webhook/${ACADEMY_A}`);
        expect(webhookRegistered?.secret).toBe('sec-academy-a');
    });

    it('telegram-links sync returns only THIS academy students', async () => {
        const resA = await api(env, 'GET', '/api/sync/telegram-links', undefined, auth(ACADEMY_A));
        const bodyA = await resA.json() as any;
        expect(bodyA.data.links.map((l: any) => l.id)).toEqual(['stu-a']);
        const resB = await api(env, 'GET', '/api/sync/telegram-links', undefined, auth(ACADEMY_B));
        const bodyB = await resB.json() as any;
        expect(bodyB.data.links.map((l: any) => l.id)).toEqual(['stu-b']);
    });

    it('an unknown academy id in the header is refused 403', async () => {
        const res = await api(env, 'GET', '/api/sync/telegram-links', undefined, auth('academy-evil'));
        expect(res.status).toBe(403);
    });
});

// ============================================================
// 4. /api/bot/connect — the token lifecycle
// ============================================================
describe('bot connect / status lifecycle', () => {
    let env: Env; let db: D1Shim;
    const CONNECT_TOKEN = '777777:CCC_fresh_bot_token_abcdefghijklmnopqrst';

    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { TELEGRAM_BOT_TOKEN: '' }));
    });

    it('refuses connect without the bearer key (401)', async () => {
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/connect`, {
                method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Academy-Id': ACADEMY_A },
                body: JSON.stringify({ token: CONNECT_TOKEN })
            }), env, URL_('/api/bot/connect'));
        expect(res.status).toBe(401);
    });

    it('refuses an unknown academy (403)', async () => {
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/connect`, {
                method: 'POST', headers: { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': 'academy-evil', 'Content-Type': 'application/json' },
                body: JSON.stringify({ token: CONNECT_TOKEN })
            }), env, URL_('/api/bot/connect'));
        expect(res.status).toBe(403);
    });

    it('connects: getMe → setWebhook(academy url, academy secret) → token only in D1', async () => {
        getMeResult = { ok: true, result: { id: 777, username: 'fresh_bot', first_name: 'Fresh' } };
        webhookRegistered = null;
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/connect`, {
                method: 'POST', headers: auth(ACADEMY_A),
                body: JSON.stringify({ token: CONNECT_TOKEN })
            }), env, URL_('/api/bot/connect'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.success).toBe(true);
        expect(body.data.username).toBe('fresh_bot');
        expect(webhookRegistered?.url).toBe(`${BASE}/webhook/${ACADEMY_A}`);
        // the token NEVER appears in the response
        expect(JSON.stringify(body)).not.toContain(CONNECT_TOKEN);
        // ...but IS stored for this academy only
        const row = db.get(`SELECT bot_token, bot_username, bot_id, webhook_secret FROM academies WHERE id = '${ACADEMY_A}'`) as any;
        expect(row.bot_token).toBe(CONNECT_TOKEN);
        expect(row.bot_username).toBe('fresh_bot');
        expect(row.bot_id).toBe('777');
        expect(row.webhook_secret).toBeTruthy();
        // and the other academy stays untouched
        const other = db.get(`SELECT bot_token FROM academies WHERE id = '${ACADEMY_B}'`) as any;
        expect(other?.bot_token || '').toBe('');
    });

    it('the same bot cannot be connected to a second academy (409)', async () => {
        getMeResult = { ok: true, result: { id: 777, username: 'fresh_bot', first_name: 'Fresh' } };
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/connect`, {
                method: 'POST', headers: auth(ACADEMY_B),
                body: JSON.stringify({ token: CONNECT_TOKEN })
            }), env, URL_('/api/bot/connect'));
        expect(res.status).toBe(409);
    });

    it('status reports the connection without exposing the token', async () => {
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_A) }),
            env, URL_('/api/bot/status'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.configured).toBe(true);
        expect(body.data.username).toBe('fresh_bot');
        expect(body.data.webhookConfirmed).toBe(true);
        expect(JSON.stringify(body)).not.toContain(CONNECT_TOKEN);
        expect(JSON.stringify(body)).not.toContain(TOKEN_A);
    });

    it('status of an academy without bot says configured=false', async () => {
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.configured).toBe(false);
        expect(body.data.source).toBe('none');
    });
});

// ============================================================
// 4.b /api/bot/status problems diagnostics (machine-readable cause
//     codes so the app can show the exact Persian fix per state)
// ============================================================
describe('status problems diagnostics', () => {
    let env: Env; let db: D1Shim;
    const DIAG_TOKEN = '888888:DDD_diag_bot_token_abcdefghijklmnopqrstuv';

    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { TELEGRAM_BOT_TOKEN: '' }));
    });

    it('reports no_token for an academy that has no bot at all', async () => {
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.configured).toBe(false);
        expect(body.data.problems).toEqual(['no_token']);
    });

    it('reports token_rejected when Telegram refuses the token', async () => {
        getMeResult = { ok: false, description: 'Unauthorized', error_code: 401 };
        connectAcademyBot(db, ACADEMY_B, 'diag_bot', DIAG_TOKEN);
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.configured).toBe(false);
        expect(body.data.problems).toEqual(['token_rejected']);
        expect(body.data.lastError).toContain('token rejected');
    });

    it('reports webhook_not_set when the bot is valid but Telegram has no hook', async () => {
        getMeResult = { ok: true, result: { id: 888, username: 'diag_bot', first_name: 'Diag' } };
        webhookRegistered = null; // getWebhookInfo mock now returns an empty url
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.configured).toBe(true);
        expect(body.data.webhook).toBe('');
        expect(body.data.problems).toEqual(['webhook_not_set']);
    });

    it('reports webhook_mismatch when the hook points somewhere else', async () => {
        webhookRegistered = { url: 'https://someone-else.workers.dev/webhook/other', secret: 'x' };
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.configured).toBe(true);
        expect(body.data.webhookConfirmed).toBe(false);
        expect(body.data.problems).toEqual(['webhook_mismatch']);
    });

    it('a healthy academy reports an empty problems list', async () => {
        webhookRegistered = { url: `${BASE}/webhook/${ACADEMY_B}`, secret: 'x' };
        const res = await handleTelegramConnect(
            new Request(`${BASE}/api/bot/status`, { method: 'GET', headers: auth(ACADEMY_B) }),
            env, URL_('/api/bot/status'));
        const body = await res.json() as any;
        expect(body.data.webhookConfirmed).toBe(true);
        expect(body.data.problems).toEqual([]);
        expect(JSON.stringify(body)).not.toContain(DIAG_TOKEN);
    });
});

// ============================================================
// 5. Cron dispatches per academy with its own bot
// ============================================================
describe('cron per-academy reminders', () => {
    it('sends academy A reminders via token A; academy B (no bot) is skipped untouched', async () => {
        const db = new D1Shim();
        const { env } = makeEnv(db, { TELEGRAM_BOT_TOKEN: '' });
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        // academy B deliberately has NO bot
        const now = Date.now();
        db.run(
            `INSERT INTO reminders (id, academy_id, student_id, student_name, chat_id, installment_id, reminder_type, message_text, scheduled_epoch, scheduled_time_str, status, error_message, retry_count, created_at_epoch, sent_at_epoch)
             VALUES ('remA', ?, 'stu-a', 'هنرجو A', '111', '', 'INSTALLMENT', 'یادآوری A', ?, '', 'SCHEDULED', '', 0, ?, 0)`,
            ACADEMY_A, now - 1000, now
        );
        db.run(
            `INSERT INTO reminders (id, academy_id, student_id, student_name, chat_id, installment_id, reminder_type, message_text, scheduled_epoch, scheduled_time_str, status, error_message, retry_count, created_at_epoch, sent_at_epoch)
             VALUES ('remB', ?, 'stu-b', 'هنرجو B', '222', '', 'INSTALLMENT', 'یادآوری B', ?, '', 'SCHEDULED', '', 0, ?, 0)`,
            ACADEMY_B, now - 1000, now
        );
        tgCalls.length = 0;
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);

        const sent = tgCalls.filter(c => c.kind === 'sendMessage');
        expect(sent.length).toBe(1);
        expect(sent[0].token).toBe(TOKEN_A);
        expect(sent[0].text).toContain('یادآوری A');
        expect((db.get(`SELECT status FROM reminders WHERE id = 'remA'`) as any).status).toBe('SENT');
        // academy B reminder: untouched, still scheduled
        expect((db.get(`SELECT status FROM reminders WHERE id = 'remB'`) as any).status).toBe('SCHEDULED');
    });

    it('sends academy B reminders via token B on the next run', async () => {
        const db = new D1Shim();
        const { env } = makeEnv(db, { TELEGRAM_BOT_TOKEN: '' });
        connectAcademyBot(db, ACADEMY_B, 'bot_b', TOKEN_B);
        const now = Date.now();
        db.run(
            `INSERT INTO reminders (id, academy_id, student_id, student_name, chat_id, reminder_type, message_text, scheduled_epoch, scheduled_time_str, status, error_message, retry_count, created_at_epoch, sent_at_epoch)
             VALUES ('remB2', ?, 'stu-b', 'هنرجو B', '222', 'INSTALLMENT', 'یادآوری B', ?, '', 'SCHEDULED', '', 0, ?, 0)`,
            ACADEMY_B, now - 1000, now
        );
        tgCalls.length = 0;
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(tgCalls.filter(c => c.kind === 'sendMessage').length).toBe(1);
        expect(tgCalls[0].token).toBe(TOKEN_B);
    });
});

// ============================================================
// 6. Webhook tenant isolation at the chat lookup
// ============================================================
describe('webhook chat isolation between academies', () => {
    it('a chat linked in academy A is unknown to academy B bot (no data leak)', async () => {
        const db = new D1Shim();
        const { env } = makeEnv(db, { TELEGRAM_BOT_TOKEN: '' });
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        connectAcademyBot(db, ACADEMY_B, 'bot_b', TOKEN_B);
        db.run(
            `INSERT INTO students (id, academy_id, full_name, telegram_chat_id, is_telegram_connected, created_at_epoch, updated_at_epoch)
             VALUES ('stu-a', ?, 'هنرجو A', '333', 1, ?, ?)`, ACADEMY_A, Date.now(), Date.now()
        );
        db.run(
            `INSERT INTO financial_summaries (student_id, academy_id, total_tuition, total_paid, remaining_debt, active_courses_count, courses_summary, settlement_status, updated_at_epoch)
             VALUES ('stu-a', ?, 9000000, 0, 9000000, 1, 'دوره A', 'PENDING', ?)`, ACADEMY_A, Date.now()
        );
        tgCalls.length = 0;
        // the SAME chat id writes to academy B's bot
        await handleTelegramWebhook(
            new Request(`${BASE}/webhook/${ACADEMY_B}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'sec-academy-b' },
                body: JSON.stringify({
                    update_id: 1,
                    message: { message_id: 1, chat: { id: 333 }, from: { id: 50, first_name: 'سارا' }, text: '💰 وضعیت مالی' }
                })
            }), env, URL_(`/webhook/${ACADEMY_B}`));
        const sent = tgCalls.filter(c => c.kind === 'sendMessage');
        expect(sent.length).toBe(1);
        expect(sent[0].token).toBe(TOKEN_B);
        // the reply must be the "not linked" warning, NEVER academy A's finances
        expect(sent[0].text).toContain('متصل نشده است');
        expect(sent[0].text).not.toContain('۹٬۰۰۰٬۰۰۰');
        expect(sent[0].text).not.toContain('9,000,000');
        expect(sent[0].text).not.toContain('هنرجو A');
    });
});

// ============================================================
// 7. Legacy latin1-stored files stay readable (production data parity)
// ============================================================
describe('legacy latin1 stored hash readability', () => {
    it('a file whose stored hash came from the old latin1 build still downloads', async () => {
        const db = new D1Shim();
        const { env } = makeEnv(db, { TELEGRAM_BOT_TOKEN: '' });
        const pdf = makePdf();
        // store the file the way the DEPLOYED production build does:
        // sha256 over the latin1-decoded string, NOT the raw bytes
        const legacyHash = await sha256HexLegacyLatin1(pdf);
        expect(legacyHash).not.toBe(await sha256Bytes(pdf));
        const tokenHash = await sha256Hex('legacy-read-token-1234567890');
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, status, token_hash, expires_at_epoch, issued_at_epoch, pdf_key, pdf_sha256, created_at_epoch, updated_at_epoch)
             VALUES ('c-legacy', ?, 'enr-legacy', 'stu-legacy', 'هنرجو قدیمی', 'ISSUED', ?, ?, ?, 'legacy-key', ?, ?, ?)`,
            ACADEMY_A, tokenHash, Date.now() + 86400000, Date.now(), legacyHash, Date.now(), Date.now()
        );
        db.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES ('legacy-key', ?, ?, ?, ?)`,
            ACADEMY_A, pdf, legacyHash, Date.now()
        );
        const openRes = await handleSignRoute(
            new Request(`${BASE}/sign/legacy-read-token-1234567890/open`, { method: 'POST' }),
            env, URL_('/sign/legacy-read-token-1234567890/open'));
        expect(openRes.status).toBe(200);
        const dl = await handleSignRoute(
            new Request(`${BASE}/sign/legacy-read-token-1234567890/pdf`),
            env, URL_('/sign/legacy-read-token-1234567890/pdf'));
        expect(dl.status).toBe(200);
        const bytes = new Uint8Array(await (await dl.arrayBuffer()));
        expect(bytes.length).toBe(pdf.length);
        // the delivery header carries the STANDARD hash the app verifies
        expect(dl.headers.get('X-Content-SHA256')).toBe(await sha256Bytes(pdf));
    });
});
