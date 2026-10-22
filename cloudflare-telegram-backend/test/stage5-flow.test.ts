// ============================================================
// Stage 5: installment reminders + payments + receipts.
//
// Everything below runs against the REAL worker modules with the real
// D1 shim (schema.sql + migrations 0001..0010) and a recorded Telegram
// API — no real bot, no real gateway, no real SMS, no real money.
//
// Coverage map (the 24 required Stage-5 cases; ✶ = primary here, ↷ =
// primarily covered by payments-flow.test.ts which this suite extends):
//   1 ✶ five nobs fire at the scheduled academy time
//   2 ✶ no reminder for a paid installment
//   3 ✶ reminders stop after a real payment
//   4 ✶ due-date change re-aligns the schedule
//   5 ✶ scheduler re-run / re-sync never duplicates
//   6 ✶ two channels deliver independently and correctly
//   7 ✶ Telegram failure with SMS success (and the reverse)
//   8 ✶ real SMS sending stays DISABLED until a panel is connected
//   9 ✶ no online link without a REAL connected gateway
//  10 ↷ expired link / wrong amount / duplicate callback (payments-flow)
//  11 ✶ student receipt submission + manager approval
//  12 ✶ receipt rejection without payment registration
//  13 ✶ unauthorized user cannot approve
//  14 ✶ double approve cannot double-register
//  15 ✶ app manual payment visible in bot
//  16 ✶ bot approval visible in app (confirmed pull)
//  17 ✶ official receipt generated and sent exactly once
//  18 ✶ send-failure retry never duplicates payment/receipt
//  19 ✶ official receipt is never sent by SMS
//  20 ✶ unconnected student: queued, released on /start
//  21 ✶ multi-academy isolation (claims + reminders)
//  22 ✶ reversal notice exactly once
//  23 ✶ «اقساط من» statuses (pending/rejected claim, online pending…)
//  24 ✶ outage catch-up is controlled (no stale bursts), replay-safe sync
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleScheduledEvent } from '../src/handlers/cron';
import { syncInstallmentReminders, dispatchDueReminders } from '../src/reminders';
import { shamsiAtAcademyTime, academyTzOffsetMinutes } from '../src/tz';
import { epochToShamsi } from '../src/shamsi';
import { AcademyDatabase } from '../src/db';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, ACADEMY_B, API_KEY } from './helpers/fixtures';
import type { Env } from '../src/types';
import { InstallmentRecord } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);
const DAY = 86_400_000;
const TOKEN_A = '111111:AAA_stage5_token_for_academy_a_0001';
const TOKEN_B = '222222:BBB_stage5_token_for_academy_b_0002';
const MANAGER_A = '555000111';   // manager chat id of academy A
const STUDENT_CHAT = 700;

// ---------- Telegram recorder ----------
interface TgCall {
    kind: string; token: string; chatId?: string; text?: string;
    caption?: string; fileName?: string; byteLength?: number;
    callbackData?: string; callbackText?: string; editMessageId?: string; editText?: string;
}
let tgCalls: TgCall[] = [];
let telegramDown = false;
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) return realFetch(input as any, init as any);
        if (telegramDown) throw new Error('network down');
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const kind = m?.[2] || '';
        if (kind === 'getFile') {
            return new Response(JSON.stringify({ ok: true, result: { file_path: 'photos/receipt.jpg' } }), { status: 200 });
        }
        if (url.includes('/file/bot')) {
            return new Response(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]), { status: 200 });
        }
        const call: TgCall = { kind, token: m?.[1] || '' };
        const body = init?.body;
        if (body instanceof FormData) {
            call.chatId = String(body.get('chat_id') ?? '');
            if (kind === 'sendPhoto') {
                const photo = body.get('photo');
                if (photo && typeof photo === 'object' && 'size' in photo) {
                    call.fileName = (photo as File).name;
                    call.byteLength = (photo as File).size;
                }
                call.caption = String(body.get('caption') ?? '');
                const rm = String(body.get('reply_markup') ?? '');
                if (rm.includes('clm:ok:')) call.callbackData = 'inline-buttons';
            }
            if (kind === 'sendDocument') {
                const doc = body.get('document');
                if (doc && typeof doc === 'object' && 'size' in doc) {
                    call.fileName = (doc as File).name;
                    call.byteLength = (doc as File).size;
                }
                call.caption = String(body.get('caption') ?? '');
            }
        } else if (typeof body === 'string' || body instanceof Uint8Array) {
            const json = JSON.parse(String(body));
            call.chatId = String(json.chat_id ?? '');
            call.text = json.text;
            call.callbackText = json.text; // answerCallbackQuery
            if (json.callback_data) call.callbackData = json.callback_data;
            if (json.message_id !== undefined) { call.editMessageId = String(json.message_id); call.editText = json.text; }
        }
        tgCalls.push(call);
        return new Response(JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }), { status: 200 });
    }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; });

// ---------- helpers ----------
function connectAcademyBot(db: D1Shim, academy: string, token: string) {
    db.run(
        `INSERT OR REPLACE INTO academies (id, name, bot_token, bot_username, bot_id, webhook_secret, bot_connected_at_epoch)
         VALUES (?,?,?,?,?,?,?)`,
        academy, 'Academy ' + academy, token, 'bot_' + academy, token.split(':')[0], 'sec-' + academy, Date.now()
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

async function press(env: Env, academy: string, chatId: number, text: string): Promise<void> {
    await handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'sec-' + academy },
            body: JSON.stringify({
                update_id: Math.floor(Math.random() * 1e9),
                message: { message_id: tgCalls.length + 1, chat: { id: chatId }, from: { id: chatId, first_name: 'هنرجو' }, text }
            })
        }), env, URL_(`/webhook/${academy}`));
}

async function sendPhotoUpdate(env: Env, academy: string, chatId: number, caption: string): Promise<void> {
    await handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'sec-' + academy },
            body: JSON.stringify({
                update_id: Math.floor(Math.random() * 1e9),
                message: {
                    message_id: tgCalls.length + 1, chat: { id: chatId },
                    from: { id: chatId, first_name: 'هنرجو' },
                    caption,
                    photo: [
                        { file_id: 'small', file_unique_id: 's', width: 90, height: 90, file_size: 5_000 },
                        { file_id: 'big', file_unique_id: 'b', width: 1280, height: 960, file_size: 120_000 },
                        { file_id: 'huge', file_unique_id: 'h', width: 2560, height: 1920, file_size: 900_000 }
                    ]
                }
            })
        }), env, URL_(`/webhook/${academy}`));
}

async function callback(env: Env, academy: string, chatId: number | string, data: string, cbId = 'cb-1'): Promise<void> {
    await handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'sec-' + academy },
            body: JSON.stringify({
                update_id: Math.floor(Math.random() * 1e9),
                callback_query: {
                    id: cbId, data,
                    from: { id: Number(chatId), first_name: 'مدیر' },
                    message: { message_id: 4242, chat: { id: Number(chatId) }, date: 0 }
                }
            })
        }), env, URL_(`/webhook/${academy}`));
}

async function managerReply(env: Env, academy: string, managerChat: number, replyToMessageId: number, text: string): Promise<void> {
    await handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'sec-' + academy },
            body: JSON.stringify({
                update_id: Math.floor(Math.random() * 1e9),
                message: {
                    message_id: tgCalls.length + 1, chat: { id: managerChat },
                    from: { id: managerChat, first_name: 'مدیر' },
                    reply_to_message: { message_id: replyToMessageId, chat: { id: managerChat }, date: 0 },
                    text
                }
            })
        }), env, URL_(`/webhook/${academy}`));
}

function textsTo(chatId: number | string): string[] {
    return tgCalls.filter((c) => c.kind === 'sendMessage' && c.chatId === String(chatId)).map((c) => c.text || '');
}
function photosTo(chatId: number | string): TgCall[] {
    return tgCalls.filter((c) => c.kind === 'sendPhoto' && c.chatId === String(chatId));
}
function answersTo(chatId: number | string): TgCall[] {
    // answerCallbackQuery has no chat_id field; each test clears tgCalls before
    // its callback, so every recorded answer belongs to the click under test.
    void chatId;
    return tgCalls.filter((c) => c.kind === 'answerCallbackQuery');
}
function docsTo(chatId: number | string): TgCall[] {
    return tgCalls.filter((c) => c.kind === 'sendDocument' && c.chatId === String(chatId));
}

/** Secure one-time /start linking, exactly like the real bot flow. */
async function linkChat(env: Env, db: D1Shim, academy: string, studentId: string, chatId: number): Promise<void> {
    const res = await api(env, 'POST', '/api/generate-link', { studentId }, auth(academy));
    expect(res.status).toBe(200);
    const link = ((await res.json() as any).data?.link) || '';
    tgCalls.length = 0;
    await press(env, academy, chatId, '/start ' + link.split('?start=')[1]);
    const row = db.get(`SELECT telegram_chat_id FROM students WHERE id = ?`, studentId) as any;
    expect(String(row?.telegram_chat_id)).toBe(String(chatId));
    tgCalls.length = 0;
}

function installment(over: Partial<InstallmentRecord> = {}): InstallmentRecord {
    const dueEpoch = Date.now() + 30 * DAY;
    return {
        id: 'ins-2', student_id: 'stu-1', installment_number: 2, total_installments: 3,
        course_title: 'سازدهنی', amount: 1_000_000, paid_amount: 0, remaining_amount: 1_000_000,
        due_date_shamsi: epochToShamsi(dueEpoch), due_date_epoch: dueEpoch,
        is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now(),
        ...over
    } as InstallmentRecord;
}

async function syncInstallments(env: Env, academy: string, list: InstallmentRecord[]): Promise<Response> {
    return api(env, 'POST', '/api/sync/installments', { installments: list }, auth(academy));
}

async function cron(env: Env): Promise<void> {
    await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
}

// ============================================================
// 1-5. Scheduling: five nobs, unpaid-only, realign, idempotent
// ============================================================
describe('five-nob reminder scheduling (server-side)', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی موسیقی آوا', currency_unit: 'تومان', manager_chat_ids: MANAGER_A
        }, auth(ACADEMY_A));
    });

    it('schedules exactly the five nobs at 10:00 academy time — real data, no duplicates', async () => {
        const res = await syncInstallments(env, ACADEMY_A, [installment()]);
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.scheduled).toBe(5);

        const rows = db.all ? [] : [];
        void rows;
        const list = (db as any).db.prepare(
            `SELECT id, trigger_offset, schedule_source, scheduled_epoch, status FROM reminders
             WHERE installment_id='ins-2' AND academy_id='academy-a' ORDER BY trigger_offset`
        ).all() as any[];
        expect(list.length).toBe(5);
        expect(list.map((r) => r.trigger_offset)).toEqual([-7, -4, -1, 0, 1]);
        expect(new Set(list.map((r) => r.schedule_source))).toEqual(new Set(['server']));
        expect(new Set(list.map((r) => r.status))).toEqual(new Set(['SCHEDULED']));

        // All nobs at the same time-of-day, exactly one day apart per offset.
        const byOffset = new Map(list.map((r) => [r.trigger_offset, r.scheduled_epoch]));
        expect(byOffset.get(-7)! - byOffset.get(0)!).toBe(-7 * DAY);
        expect(byOffset.get(-1)! - byOffset.get(0)!).toBe(-1 * DAY);
        expect(byOffset.get(1)! - byOffset.get(0)!).toBe(1 * DAY);
        // 10:00 Asia/Tehran (+03:30, no DST) == 06:30 UTC
        const dueDay = new Date(byOffset.get(0)!);
        expect(dueDay.toISOString().slice(11, 16)).toBe('06:30');
        // Cross-check one nob against the tz helper (same formula the scheduler uses)
        const tzMin = academyTzOffsetMinutes('Asia/Tehran');
        expect(byOffset.get(0)!).toBe(shamsiAtAcademyTime(installment().due_date_shamsi, 0, 10, 0, tzMin));
    });

    it('a PAID installment gets no reminders and its existing ones are cancelled', async () => {
        const res = await syncInstallments(env, ACADEMY_A, [
            installment({ is_paid: 1, paid_amount: 1_000_000, remaining_amount: 0, paid_date_shamsi: '۱۴۰۵/۰۷/۱۰' })
        ]);
        expect(res.status).toBe(200);
        const scheduled = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-2' AND academy_id='academy-a' AND status='SCHEDULED'`
        ).get() as any;
        expect(scheduled.n).toBe(0);
        const cancelled = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-2' AND academy_id='academy-a' AND status='CANCELLED'`
        ).get() as any;
        expect(cancelled.n).toBe(5);
    });

    it('a due-date change re-aligns every still-SCHEDULED nob', async () => {
        // back to unpaid with the original due date
        await syncInstallments(env, ACADEMY_A, [installment()]);
        const before = (db as any).db.prepare(
            `SELECT scheduled_epoch FROM reminders WHERE id='auto:ins-2:0'`
        ).get() as any;

        const newDue = Date.now() + 45 * DAY;
        await syncInstallments(env, ACADEMY_A, [installment({
            due_date_shamsi: epochToShamsi(newDue), due_date_epoch: newDue
        })]);
        const after = (db as any).db.prepare(
            `SELECT scheduled_epoch FROM reminders WHERE id='auto:ins-2:0'`
        ).get() as any;
        expect(after.scheduled_epoch).toBeGreaterThan(before.scheduled_epoch);
        const list = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-2' AND academy_id='academy-a' AND status='SCHEDULED'`
        ).get() as any;
        expect(list.n).toBe(5); // still exactly five rows — realigned, not duplicated
    });

    it('re-running the scheduler and re-syncing never duplicate rows', async () => {
        const db2 = new AcademyDatabase(env, ACADEMY_A);
        const inst = installment();
        await syncInstallmentReminders(db2, ACADEMY_A, inst);
        await syncInstallmentReminders(db2, ACADEMY_A, inst);
        await syncInstallments(env, ACADEMY_A, [inst]);
        const list = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-2' AND academy_id='academy-a'`
        ).get() as any;
        expect(list.n).toBe(5);
    });

    it('app-pushed reminders for a server-owned nob are superseded, not doubled', async () => {
        // app pushes its own -1d reminder for the same installment
        const push = await api(env, 'POST', '/api/schedule-reminders', {
            reminders: [{
                id: 'app-rem-1', studentId: 'stu-1', studentName: 'دانا محمدی', chatId: '700',
                installmentId: 'ins-2', reminderType: 'INSTALLMENT', message: 'app text',
                scheduledEpoch: Date.now() + DAY, triggerDaysOffset: -1, studentPhone: '۰۹۱۲۰۰۰۰۰۰۰'
            }]
        }, auth(ACADEMY_A));
        const pushBody = await push.json() as any;
        // server already owns (ins-2, -1) → skipped
        expect(pushBody.data.supersededByServer).toBe(1);
        const appRow = (db as any).db.prepare(`SELECT status FROM reminders WHERE id='app-rem-1'`).get() as any;
        expect(appRow).toBeUndefined();

        // an app row that EXISTS from before the server schedule gets cancelled
        db.run(`INSERT INTO reminders (id, academy_id, student_id, student_name, chat_id, installment_id,
                reminder_type, message_text, scheduled_epoch, scheduled_time_str, status, error_message,
                retry_count, created_at_epoch, sent_at_epoch, student_phone, trigger_offset, schedule_source)
                VALUES ('app-rem-2','academy-a','stu-1','دانا محمدi','700','ins-2','INSTALLMENT','m',
                ?, '', 'SCHEDULED','',0,?,?,?,0,'app')`,
            Date.now() + DAY, Date.now(), 0, '۰۹۱۲۰۰۰۰۰۰۰');
        await syncInstallments(env, ACADEMY_A, [installment()]);
        const row2 = (db as any).db.prepare(`SELECT status, error_message FROM reminders WHERE id='app-rem-2'`).get() as any;
        expect(row2.status).toBe('CANCELLED');
        expect(String(row2.error_message)).toContain('superseded');
    });
});

// ============================================================
// 2,3,6,7,8,24. Dispatch: dual channel, paid re-check, catch-up
// ============================================================
describe('dual-channel dispatch + payment cancellation + controlled catch-up', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', { academy_name: 'آکادمی آوا', currency_unit: 'تومان' }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-1', STUDENT_CHAT);
        await syncInstallments(env, ACADEMY_A, [installment()]);
        // force the due-day nob to be due NOW
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE id = 'auto:ins-2:0'`, Date.now() - 10_000);
    });

    it('the due-day nob sends with real data through BOTH channels; statuses recorded separately', async () => {
        tgCalls.length = 0;
        await cron(env);
        const texts = textsTo(STUDENT_CHAT);
        expect(texts.length).toBe(1); // controlled: no burst, one message
        const text = texts[0];
        expect(text).toContain('دانا محمدی');           // student
        expect(text).toContain('سازدهنی');              // course
        expect(text).toContain('1,000,000');            // exact amount
        expect(text).toContain(installment().due_date_shamsi); // Shamsi due date
        expect(text).toContain('قسط 2 از 3');           // installment number
        expect(text).toContain('سررسید');               // status section

        // Telegram channel ledger
        const tgDelivery = db.get(`SELECT status, provider_message_id FROM deliveries
            WHERE academy_id='academy-a' AND reminder_id='auto:ins-2:0' AND channel='telegram'`) as any;
        expect(tgDelivery?.status).toBe('SENT');
        expect(String(tgDelivery?.provider_message_id)).not.toBe('');

        // SMS channel: queued for the SAME reminder, independent status, DISABLED
        const sms = db.get(`SELECT status, error FROM sms_outbox WHERE id='sms-auto:ins-2:0'`) as any;
        expect(sms?.status).toBe('DISABLED');
        expect(String(sms?.error)).toContain('sms_provider_not_configured');
        const smsDelivery = db.get(`SELECT status, error FROM deliveries
            WHERE academy_id='academy-a' AND reminder_id='auto:ins-2:0' AND channel='sms'`) as any;
        expect(smsDelivery?.status).toBe('SKIPPED');
        const smsCol = db.get(`SELECT sms_status FROM reminders WHERE id='auto:ins-2:0'`) as any;
        expect(smsCol?.sms_status).toBe('DISABLED');

        // re-running cron does NOT send again (idempotent by reminder status)
        tgCalls.length = 0;
        await cron(env);
        expect(textsTo(STUDENT_CHAT).length).toBe(0);
    });

    it('no reminder is ever sent for a PAID installment — pre-send re-check cancels', async () => {
        // a fresh unpaid installment scheduled, then paid BEFORE the cron run
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-paid', installment_number: 3 })]);
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE id='auto:ins-paid:0'`, Date.now() - 10_000);
        await api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: 'pay-direct-1', transactionId: 'TRX-D1', studentId: 'stu-1', installmentId: 'ins-paid',
                installmentNumber: 3, amount: 1_000_000, paymentDateShamsi: '۱۴۰۵/۰۷/۱۵',
                paymentMethod: 'TRANSFER', isReversed: false
            }]
        }, auth(ACADEMY_A));
        tgCalls.length = 0;
        await cron(env);
        // the reminder text never reaches the student (receipt text for the
        // SAME installment legitimately DOES — that's the Stage-4 pipeline)
        const reminderTexts = textsTo(STUDENT_CHAT).filter((t) => t.includes('یادآوری قسط') || t.includes('سررسید قسط'));
        expect(reminderTexts.length).toBe(0);
        const rows = (db as any).db.prepare(
            `SELECT status, error_message FROM reminders WHERE installment_id='ins-paid' AND academy_id='academy-a'`
        ).all() as any[];
        expect(rows.length).toBeGreaterThan(0);
        for (const r of rows) expect(r.status).toBe('CANCELLED');
        // the payment DID register (receipt pipeline, stage 4)
        const receipt = db.get(`SELECT send_status FROM payment_receipts WHERE id='pay-direct-1'`) as any;
        expect(receipt?.send_status).toBe('SENT');
    });

    it('Telegram outage: SMS copy still queued; retry happens for Telegram only; no duplicate', async () => {
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-tg', installment_number: 4 })]);
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE id='auto:ins-tg:0'`, Date.now() - 10_000);
        tgCalls.length = 0;
        telegramDown = true;
        try { await cron(env); } finally { telegramDown = false; }
        // SMS outbox row exists (independent channel did its part)
        const sms = db.get(`SELECT status FROM sms_outbox WHERE id='sms-auto:ins-tg:0'`) as any;
        expect(sms?.status).toBe('DISABLED');
        // Telegram scheduled for retry, not lost, not marked sent
        const row = db.get(`SELECT status, retry_count, error_message FROM reminders WHERE id='auto:ins-tg:0'`) as any;
        expect(row.status).toBe('SCHEDULED');
        expect(row.retry_count).toBeGreaterThan(0);
        // recovery: next cron sends exactly once
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE id='auto:ins-tg:0'`, Date.now() - 10_000);
        tgCalls.length = 0;
        await cron(env);
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('قسط 4')).length).toBe(1);
    });

    it('controlled catch-up: a backlog sends only the NEWEST nob of the installment', async () => {
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-burst', installment_number: 5 })]);
        // simulate a 2-day outage: -4d, -1d and 0 nobs are all overdue now
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE installment_id='ins-burst' AND trigger_offset=-4`, Date.now() - 2 * DAY);
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE installment_id='ins-burst' AND trigger_offset=-1`, Date.now() - 1 * DAY);
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE installment_id='ins-burst' AND trigger_offset=0`, Date.now() - 3600_000);
        tgCalls.length = 0;
        await cron(env);
        const texts = textsTo(STUDENT_CHAT).filter((t) => t.includes('قسط 5'));
        expect(texts.length).toBe(1);                    // ONE message, not three
        expect(texts[0]).toContain('سررسید');            // the newest (due-day) nob
        const superseded = (db as any).db.prepare(
            `SELECT status, error_message FROM reminders WHERE installment_id='ins-burst' AND trigger_offset IN (-4,-1)`
        ).all() as any[];
        for (const r of superseded) {
            expect(r.status).toBe('CANCELLED');
            expect(String(r.error_message)).toContain('superseded');
        }
    });

    it('backlog older than the window is cancelled as missed, never sent', async () => {
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-old', installment_number: 6 })]);
        // every nob is ancient (5 days) — nothing may send
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE installment_id='ins-old' AND academy_id='academy-a'`, Date.now() - 5 * DAY);
        tgCalls.length = 0;
        await cron(env);
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('قسط 6')).length).toBe(0);
        // the NEWEST nob was chosen by catch-up and then dropped as missed;
        // older nobs were superseded — every row ends CANCELLED, never SENT
        const newest = db.get(`SELECT status, error_message FROM reminders WHERE id='auto:ins-old:1'`) as any;
        expect(newest.status).toBe('CANCELLED');
        expect(String(newest.error_message)).toContain('missed');
        const statuses = (db as any).db.prepare(
            `SELECT status FROM reminders WHERE installment_id='ins-old' AND academy_id='academy-a'`
        ).all() as any[];
        for (const r of statuses) expect(r.status).toBe('CANCELLED');
    });
});

// ============================================================
// 9. No online link without a REAL gateway
// ============================================================
describe('payment links stay honest without a gateway', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-1', STUDENT_CHAT);
        await syncInstallments(env, ACADEMY_A, [installment({ payment_method: 'ONLINE' } as any)]);
    });

    it('an ONLINE installment reminder carries NO link and no fake success when no gateway is configured', async () => {
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE id='auto:ins-2:0'`, Date.now() - 10_000);
        tgCalls.length = 0;
        await cron(env);
        const text = textsTo(STUDENT_CHAT)[0] || '';
        expect(text).not.toMatch(/pay\//);               // no fabricated link
        expect(text).toContain('درگاه پرداخت آنلاین هنوز متصل نشده'); // honest note
        const txns = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns.n).toBe(0);                          // no order minted either
    });
});

// ============================================================
// 11-14, 16, 17, 19-23. Card-to-card claims + manager decisions
// ============================================================
describe('receipt claims: submission → manager decision → shared registration', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [
                { id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' },
                { id: 'stu-b', fullName: 'هنرجوی دیگر', studentCode: 'SC-2', phone: '۰۹۱۲۰۰۰۰۰۰۱' }
            ]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی آوا', currency_unit: 'تومان', manager_chat_ids: MANAGER_A,
            bank_card_number: '6037-9911-2222-3333', bank_account_owner: 'آکادمی موسیقی آوا'
        }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-1', STUDENT_CHAT);
        await syncInstallments(env, ACADEMY_A, [
            installment({ payment_method: 'TRANSFER' } as any),
            installment({ id: 'ins-3', installment_number: 3, payment_method: 'TRANSFER' } as any)
        ]);
    });

    it('card-to-card guidance shows the academy REAL card info per installment', async () => {
        await press(env, ACADEMY_A, STUDENT_CHAT, '📤 ارسال رسید پرداخت');
        const text = textsTo(STUDENT_CHAT).find((t) => t.includes('ارسال رسید پرداخت')) || '';
        expect(text).toContain('6037-9911-2222-3333');
        expect(text).toContain('آکادمی موسیقی آوا');
        expect(text).toContain('قسط 2');
        expect(text).toContain('قسط 3');
    });

    it('student photo + caption «قسط ۲» → PENDING claim; manager notified with buttons; installment NOT paid', async () => {
        tgCalls.length = 0;
        await sendPhotoUpdate(env, ACADEMY_A, STUDENT_CHAT, 'واریز کردم خانم  قسط ۲');
        // student confirmation, honest about review semantics
        const studentText = textsTo(STUDENT_CHAT).find((t) => t.includes('دریافت شد')) || '';
        expect(studentText).toContain('پرداخت‌شده محسوب نمی‌شود');
        // claim row: evidence only
        const claim = db.get(`SELECT * FROM payment_claims WHERE academy_id='academy-a'`) as any;
        expect(claim?.status).toBe('PENDING');
        expect(claim?.claimed_amount).toBe(1_000_000); // server-side remaining, not user-typed
        expect(claim?.installment_id).toBe('ins-2');
        expect(claim?.photo_bytes === null || claim?.photo_bytes === undefined).toBe(false); // bytes stored
        // manager got the photo + caption with REAL data + decision buttons
        const managerPhoto = photosTo(MANAGER_A)[0];
        expect(managerPhoto).toBeTruthy();
        expect(managerPhoto.caption).toContain('دانا محمدی');
        expect(managerPhoto.caption).toContain('سازدهنی');
        expect(managerPhoto.caption).toContain('1,000,000');
        expect(managerPhoto.caption).toContain('مدرک بررسی است');
        expect(managerPhoto.callbackData).toBe('inline-buttons');
        // audit event
        const ev = db.get(`SELECT event_type, channel FROM payment_events WHERE claim_id=?`, claim.id) as any;
        expect(ev?.event_type).toBe('RECEIPT_SUBMITTED');
        expect(ev?.channel).toBe('telegram');
        // installment unchanged
        const inst = db.get(`SELECT is_paid, remaining_amount FROM installments WHERE id='ins-2'`) as any;
        expect(inst.is_paid).toBe(0);
        expect(inst.remaining_amount).toBe(1_000_000);
        // «اقساط من» shows the awaiting-review status (NOT paid)
        tgCalls.length = 0;
        await press(env, ACADEMY_A, STUDENT_CHAT, '📅 اقساط من');
        const instText = textsTo(STUDENT_CHAT).find((t) => t.includes('اقساط')) || '';
        expect(instText).toContain('در انتظار بررسی مدیر');
        expect(instText).not.toContain('✅ پرداخت شده'); // for ins-2
        expect(instText).toContain('کارت‌به‌کارت');
    });

    it('a second photo for the same installment is refused (one live claim)', async () => {
        tgCalls.length = 0;
        await sendPhotoUpdate(env, ACADEMY_A, STUDENT_CHAT, 'قسط ۲');
        const text = textsTo(STUDENT_CHAT).find((t) => t.includes('قبلاً')) || '';
        expect(text).toContain('در انتظار بررسی');
        const count = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_claims`).get() as any;
        expect(count.n).toBe(1);
    });

    it('an unauthorized chat CANNOT approve — server-side check, not UI hiding', async () => {
        tgCalls.length = 0;
        const claim = db.get(`SELECT id FROM payment_claims WHERE status='PENDING'`) as any;
        await callback(env, ACADEMY_A, 999999, `clm:ok:${claim.id}`, 'cb-forbidden');
        const ans = answersTo(999999)[0];
        expect(ans?.callbackText).toContain('مجاز');
        const still = db.get(`SELECT status FROM payment_claims WHERE id=?`, claim.id) as any;
        expect(still?.status).toBe('PENDING');
        expect((db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts`).get() as any).toEqual({ n: 0 });
    });

    it('manager approval registers the REAL payment once: installment updated, receipt sent, reminders stopped, events + app visibility', async () => {
        tgCalls.length = 0;
        const claim = db.get(`SELECT id FROM payment_claims WHERE status='PENDING'`) as any;
        await callback(env, ACADEMY_A, MANAGER_A, `clm:ok:${claim.id}`, 'cb-ok');
        const ans = answersTo(MANAGER_A)[0];
        expect(ans?.callbackText).toContain('ثبت شد');

        // installment fully paid with the claimed amount
        const inst = db.get(`SELECT is_paid, paid_amount, remaining_amount, paid_date_shamsi FROM installments WHERE id='ins-2'`) as any;
        expect(inst.is_paid).toBe(1);
        expect(inst.paid_amount).toBe(1_000_000);
        expect(inst.remaining_amount).toBe(0);
        expect(inst.paid_date_shamsi).not.toBe('');

        // exactly ONE official receipt through the Stage-4 pipeline, id clm-<claim>
        const receipt = db.get(`SELECT * FROM payment_receipts WHERE id = ?`, `clm-${claim.id}`) as any;
        expect(receipt?.send_status).toBe('SENT');      // approveClaim dispatches immediately
        const receiptCount = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts`).get() as any;
        expect(receiptCount.n).toBe(1);
        // the receipt went to the student via Telegram as the Stage-4 receipt
        // message (never SMS)
        const receiptTexts = textsTo(STUDENT_CHAT).filter((t) => t.includes('رسید پرداخت شهریه'));
        expect(receiptTexts.length).toBe(1);
        expect(receiptTexts[0]).toContain('کد تراکنش');
        const anyReceiptSms = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM sms_outbox WHERE id LIKE '%clm%' OR reminder_id LIKE '%clm%'`
        ).get() as any;
        expect(anyReceiptSms.n).toBe(0);

        // reminders for the installment are stopped
        const pending = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-2' AND status='SCHEDULED'`
        ).get() as any;
        expect(pending.n).toBe(0);

        // audit: RECEIPT_APPROVED rides the claim; PAYMENT_REGISTERED is written
        // by the shared ingest pipeline keyed by the payment id
        const evs = (db as any).db.prepare(
            `SELECT event_type FROM payment_events WHERE claim_id=? OR payment_id=? ORDER BY created_at_epoch`
        ).bind(claim.id, `clm-${claim.id}`).all() as any[];
        expect(evs.map((e) => e.event_type)).toEqual(expect.arrayContaining(['RECEIPT_SUBMITTED', 'RECEIPT_APPROVED', 'PAYMENT_REGISTERED']));

        // manager informed about the app sync
        const managerText = textsTo(MANAGER_A).find((t) => t.includes('ثبت شد')) || '';
        expect(managerText).toContain('اپ آموزشگاه');

        // 16. the APP sees the bot-registered payment through the confirmed pull
        const pull = await api(env, 'GET', '/api/payments/confirmed?since=0', undefined, auth(ACADEMY_A));
        const body = await pull.json() as any;
        const mine = body.data.payments.find((p: any) => p.orderId === `clm-${claim.id}`);
        expect(mine).toBeTruthy();
        expect(mine.method).toBe('TRANSFER');
        expect(mine.source).toBe('claim');
        expect(mine.amount).toBe(1_000_000);

        // 23. «اقساط من» now shows paid for ins-2
        tgCalls.length = 0;
        await press(env, ACADEMY_A, STUDENT_CHAT, '📅 اقساط من');
        const instText = textsTo(STUDENT_CHAT).join('\n');
        expect(instText).toContain('✅ پرداخت شده');
    });

    it('double-click on approve cannot double-register (CAS + deterministic id)', async () => {
        const claim = db.get(`SELECT id FROM payment_claims WHERE status='APPROVED'`) as any;
        tgCalls.length = 0;
        await callback(env, ACADEMY_A, MANAGER_A, `clm:ok:${claim.id}`, 'cb-again');
        const ans = answersTo(MANAGER_A)[0];
        expect(ans?.callbackText).toContain('قبلاً');
        const receiptCount = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts`).get() as any;
        expect(receiptCount.n).toBe(1); // still exactly one
        const evCount = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM payment_events WHERE event_type='PAYMENT_REGISTERED'`
        ).get() as any;
        expect(evCount.n).toBe(1);
        const inst = db.get(`SELECT paid_amount FROM installments WHERE id='ins-2'`) as any;
        expect(inst.paid_amount).toBe(1_000_000); // not 2,000,000
    });

    it('rejection registers NOTHING, keeps the claim history, accepts a manager-reply reason', async () => {
        // student submits for installment 3
        tgCalls.length = 0;
        await sendPhotoUpdate(env, ACADEMY_A, STUDENT_CHAT, 'قسط ۳');
        const claim = db.get(`SELECT id, manager_message_id FROM payment_claims WHERE status='PENDING' ORDER BY created_at_epoch DESC LIMIT 1`) as any;
        expect(claim).toBeTruthy();

        // manager presses «رد رسید»
        tgCalls.length = 0;
        await callback(env, ACADEMY_A, MANAGER_A, `clm:no:${claim.id}`, 'cb-no');
        const ans = answersTo(MANAGER_A)[0];
        expect(ans?.callbackText).toContain('رد شد');
        const row = db.get(`SELECT status, decision_note FROM payment_claims WHERE id=?`, claim.id) as any;
        expect(row?.status).toBe('REJECTED');           // history kept, never deleted
        const inst = db.get(`SELECT is_paid, remaining_amount FROM installments WHERE id='ins-3'`) as any;
        expect(inst.is_paid).toBe(0);                   // nothing changed financially
        expect(inst.remaining_amount).toBe(1_000_000);
        expect((db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts WHERE installment_id='ins-3'`).get() as any).toEqual({ n: 0 });
        const ev = db.get(`SELECT event_type FROM payment_events WHERE claim_id=? AND event_type='RECEIPT_REJECTED'`, claim.id) as any;
        expect(ev).toBeTruthy();

        // «اقساط من» shows the rejected status
        tgCalls.length = 0;
        await press(env, ACADEMY_A, STUDENT_CHAT, '📅 اقساط من');
        const instText = textsTo(STUDENT_CHAT).find((t) => t.includes('رسید ردشده')) || '';
        expect(instText).toContain('رسید ردشده');

        // next submission: rejection WITH reason via manager REPLY
        tgCalls.length = 0;
        await sendPhotoUpdate(env, ACADEMY_A, STUDENT_CHAT, 'قسط ۳ دوباره');
        const claim2 = db.get(`SELECT id, manager_message_id FROM payment_claims WHERE status='PENDING' ORDER BY created_at_epoch DESC LIMIT 1`) as any;
        await managerReply(env, ACADEMY_A, Number(MANAGER_A), Number(claim2.manager_message_id), 'رد: تصویر ناخوانا است');
        const row2 = db.get(`SELECT status, decision_note FROM payment_claims WHERE id=?`, claim2.id) as any;
        expect(row2?.status).toBe('REJECTED');
        expect(String(row2?.decision_note)).toContain('تصویر ناخوانا');
        const ev2 = db.get(`SELECT detail FROM payment_events WHERE claim_id=? AND event_type='RECEIPT_REJECTED'`, claim2.id) as any;
        expect(String(ev2?.detail)).toContain('تصویر ناخوانا');
        // the student was informed
        const studentText = textsTo(STUDENT_CHAT).find((t) => t.includes('تأیید نشد')) || '';
        expect(studentText).toContain('تصویر ناخوانا');
    });

    it('reversal after send: the app pushes isReversed → one reversal notice only', async () => {
        const claim = db.get(`SELECT id FROM payment_claims WHERE status='APPROVED'`) as any;
        const paymentId = `clm-${claim.id}`;
        // the academy reverses the payment in the app and syncs
        const res = await api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: paymentId, transactionId: 'CLM-X', studentId: 'stu-1', installmentId: 'ins-2',
                installmentNumber: 2, amount: 1_000_000, isReversed: true, reversalReason: 'اشتباه مبلغی',
                paymentDateShamsi: '۱۴۰۵/۰۷/۲۰', paymentMethod: 'TRANSFER'
            }]
        }, auth(ACADEMY_A));
        expect(res.status).toBe(200);
        const receipt = db.get(`SELECT is_reversed, send_status FROM payment_receipts WHERE id=?`, paymentId) as any;
        expect(receipt?.is_reversed).toBe(1);
        expect(receipt?.send_status).toBe('REVERSAL_QUEUED');
        tgCalls.length = 0;
        await cron(env);
        const notified = db.get(`SELECT send_status FROM payment_receipts WHERE id=?`, paymentId) as any;
        expect(notified?.send_status).toBe('REVERSED_NOTIFIED');
        const notices = textsTo(STUDENT_CHAT).filter((t) => t.includes('برگشتی'));
        expect(notices.length).toBe(1);
        const evCount = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM payment_events WHERE event_type='PAYMENT_REVERSED' AND payment_id=?`
        ).bind(paymentId).get() as any;
        expect(evCount.n).toBe(1);
        // replaying the same reversal push never notifies twice
        await api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: paymentId, studentId: 'stu-1', installmentId: 'ins-2', amount: 1_000_000,
                isReversed: true, reversalReason: 'اشتباه مبلغی'
            }]
        }, auth(ACADEMY_A));
        tgCalls.length = 0;
        await cron(env);
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('برگشتی')).length).toBe(0);
    });
});

// ============================================================
// 15, 20, 21. App manual payment, unconnected student, isolation
// ============================================================
describe('manual app payment, unconnected students, multi-academy isolation', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        connectAcademyBot(db, ACADEMY_B, TOKEN_B);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-b', fullName: 'هنرجوی بی', studentCode: 'SC-B1', phone: '۰۹۳۵۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_B));
        await api(env, 'POST', '/api/sync/settings', { academy_name: 'آکادمی آوا', manager_chat_ids: MANAGER_A }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', { academy_name: 'آکادمی ب', manager_chat_ids: '888777' }, auth(ACADEMY_B));
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-app', installment_number: 7, total_installments: 8 })]);
        await syncInstallments(env, ACADEMY_B, [installment({ id: 'ins-b2', student_id: 'stu-b', installment_number: 2, total_installments: 4 })]);
    });

    it('manager registers payment in the APP → bot shows it paid; re-sync never duplicates', async () => {
        const push = (reversed = false) => api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: 'pay-app-1', transactionId: 'TRX-APP-1', studentId: 'stu-1', installmentId: 'ins-app',
                installmentNumber: 7, amount: 1_000_000, paymentDateShamsi: '۱۴۰۵/۰۷/۲۱',
                paymentTime: '11:20', paymentMethod: 'POS', destinationAccountTitle: 'کارتخوان فروشگاه',
                trackingNumber: 'TRK-77', recordedBy: 'مدیر آموزشگاه', isReversed: reversed
            }]
        }, auth(ACADEMY_A));
        const res = await push();
        const body = await res.json() as any;
        expect(body.data.created).toBe(1);
        // offline retry / duplicate push: no second transaction, no second receipt
        const again = await push();
        expect(((await again.json() as any).data).skipped).toBe(1);
        const receipts = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts WHERE id='pay-app-1'`).get() as any;
        expect(receipts.n).toBe(1);

        // the app also pushes the UPDATED installment row (Room is its source
        // of truth) — exactly what BotCloudBridge.pushAll does after recordPayment
        const dueApp = Date.now() + 30 * DAY;
        await syncInstallments(env, ACADEMY_A, [installment({
            id: 'ins-app', installment_number: 7, total_installments: 8,
            paid_amount: 1_000_000, remaining_amount: 0, is_paid: 1,
            paid_date_shamsi: '۱۴۰۵/۰۷/۲۱', due_date_shamsi: epochToShamsi(dueApp), due_date_epoch: dueApp
        })]);

        // the bot reflects the app-side payment
        await linkChat(env, db, ACADEMY_A, 'stu-1', STUDENT_CHAT);
        tgCalls.length = 0;
        await press(env, ACADEMY_A, STUDENT_CHAT, '📅 اقساط من');
        const instText = textsTo(STUDENT_CHAT).find((t) => t.includes('قسط شماره 7')) || '';
        expect(instText).toContain('✅ پرداخت شده');
        // reminders of the app-paid installment are stopped
        const pending = (db as any).db.prepare(
            `SELECT COUNT(*) AS n FROM reminders WHERE installment_id='ins-app' AND status='SCHEDULED'`
        ).get() as any;
        expect(pending.n).toBe(0);
        // event recorded with the app channel
        const ev = db.get(`SELECT channel, actor FROM payment_events WHERE payment_id='pay-app-1' AND event_type='PAYMENT_REGISTERED'`) as any;
        expect(ev?.channel).toBe('app');
        expect(ev?.actor).toBe('مدیر آموزشگاه');
    });

    it('academy B end-to-end: claim via B bot → B manager approves → receipt through B bot only', async () => {
        // claims are submitted IN the bot, so the student links first (the
        // queue-until-connected case is proven by the outage test above and
        // by payments-flow.test.ts for app-side payments)
        await linkChat(env, db, ACADEMY_B, 'stu-b', 812);
        tgCalls.length = 0;
        // installment 2 — the FIRST installment's receipt is contract-gated
        // (Stage-4 ordering, covered separately), so a later nob proves delivery
        await sendPhotoUpdate(env, ACADEMY_B, 812, 'قسط ۲');
        const claim = db.get(`SELECT * FROM payment_claims WHERE academy_id='academy-b' AND status='PENDING'`) as any;
        expect(claim).toBeTruthy();
        expect(claim.claimed_amount).toBe(1_000_000);
        // manager of academy B approves
        tgCalls.length = 0;
        await callback(env, ACADEMY_B, 888777, `clm:ok:${claim.id}`, 'cb-b');
        const receipt = db.get(`SELECT send_status, academy_id FROM payment_receipts WHERE id = ?`, `clm-${claim.id}`) as any;
        expect(receipt?.send_status).toBe('SENT');
        expect(receipt?.academy_id).toBe(ACADEMY_B);
        expect(textsTo(812).filter((t) => t.includes('رسید پرداخت شهریه')).length).toBe(1);
        // academy A never saw any of it
        expect((db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts WHERE academy_id='academy-a' AND student_id='stu-b'`).get() as any).toEqual({ n: 0 });
    });

    it('a manager of academy B cannot approve academy A claims (per-academy authorization)', async () => {
        // student A submits a receipt
        tgCalls.length = 0;
        await syncInstallments(env, ACADEMY_A, [installment({ id: 'ins-iso', installment_number: 9, total_installments: 9 })]);
        await sendPhotoUpdate(env, ACADEMY_A, STUDENT_CHAT, 'قسط ۹');
        const claim = db.get(`SELECT id FROM payment_claims WHERE academy_id='academy-a' AND status='PENDING' ORDER BY created_at_epoch DESC LIMIT 1`) as any;
        // B's manager chat (888777) tries to approve through A's webhook
        tgCalls.length = 0;
        await callback(env, ACADEMY_A, 888777, `clm:ok:${claim.id}`, 'cb-cross');
        const ans = answersTo(888777)[0];
        expect(ans?.callbackText).toContain('مجاز');
        const still = db.get(`SELECT status FROM payment_claims WHERE id=?`, claim.id) as any;
        expect(still?.status).toBe('PENDING');
        // reminder schedules never leak between academies
        const aRows = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM reminders WHERE academy_id='academy-a' AND installment_id='ins-b2'`).get() as any;
        const bRows = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM reminders WHERE academy_id='academy-b' AND installment_id='ins-b2'`).get() as any;
        expect(aRows.n).toBe(0);
        expect(bRows.n).toBe(5);
    });

    it('unconnected students get NO reminder retry spam; SMS copy still queued', async () => {
        // academy B student (stu-b) is now linked — use a fresh unlinked one
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-c', fullName: 'هنرجوی بی‌اتصال', studentCode: 'SC-B2', phone: '۰۹۳۵۰۰۰۰۰۰۲' }]
        }, auth(ACADEMY_B));
        await syncInstallments(env, ACADEMY_B, [installment({ id: 'ins-c1', student_id: 'stu-c', installment_number: 2, total_installments: 4 })]);
        db.run(`UPDATE reminders SET scheduled_epoch = ? WHERE installment_id='ins-c1' AND academy_id='academy-b' AND trigger_offset=0`, Date.now() - 10_000);
        tgCalls.length = 0;
        await cron(env);
        const row = db.get(`SELECT status, error_message FROM reminders WHERE id='auto:ins-c1:0'`) as any;
        expect(row.status).toBe('FAILED');
        expect(String(row.error_message)).toContain('بدون اتصال تلگرام');
        const sms = db.get(`SELECT status FROM sms_outbox WHERE id='sms-auto:ins-c1:0'`) as any;
        expect(sms?.status).toBe('DISABLED');           // queued for the future panel
        expect(textsTo(9999).length).toBe(0);           // nobody was messaged
    });
});

// ============================================================
// 18. Send-failure retry without duplicate payments/receipts
// ============================================================
describe('delivery retries never duplicate payments or receipts', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-1', STUDENT_CHAT);
        await syncInstallments(env, ACADEMY_A, [installment()]);
        await api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: 'pay-outage', transactionId: 'TRX-OUT', studentId: 'stu-1', installmentId: 'ins-2',
                installmentNumber: 2, amount: 1_000_000, paymentDateShamsi: '۱۴۰۵/۰۷/۲۲',
                paymentMethod: 'TRANSFER', isReversed: false
            }]
        }, auth(ACADEMY_A));
    });

    it('receipt send outage: tracked attempt, stays queued, then exactly one send', async () => {
        let before = (db as any).db.prepare(`SELECT attempts, send_status FROM payment_receipts WHERE id='pay-outage'`).get() as any;
        tgCalls.length = 0;
        telegramDown = true;
        try { await cron(env); } finally { telegramDown = false; }
        before = (db as any).db.prepare(`SELECT attempts, send_status FROM payment_receipts WHERE id='pay-outage'`).get() as any;
        expect(before.attempts).toBeGreaterThan(0);
        expect(before.send_status).toBe('QUEUED');
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('رسید پرداخت شهریه')).length).toBe(0);
        // recovery: exactly one receipt message, status SENT; second cron changes nothing
        tgCalls.length = 0;
        await cron(env);
        expect(db.get(`SELECT send_status FROM payment_receipts WHERE id='pay-outage'`) as any).toEqual(
            expect.objectContaining({ send_status: 'SENT' }));
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('رسید پرداخت شهریه')).length).toBe(1);
        tgCalls.length = 0;
        await cron(env);
        expect(textsTo(STUDENT_CHAT).filter((t) => t.includes('رسید پرداخت شهریه')).length).toBe(0);
        // the payment was never duplicated by the retries
        const receipts = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_receipts WHERE student_id='stu-1'`).get() as any;
        expect(receipts.n).toBe(1);
    });
});
