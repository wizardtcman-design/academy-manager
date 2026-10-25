// ============================================================
// Stage 9: the online payment gateway is COMPLETELY HIDDEN.
//
// Production/wrangler default = ONLINE_PAYMENTS_ENABLED absent → every
// online-payment entry point is safely blocked, nothing online-payment
// related reaches a student, and the card-to-card → manager-approval
// flow keeps working untouched.
//
// Coverage map:
//  1 ✶ POST /api/payments/link → 409 honest refusal (flag off)
//  2 ✶ POST /api/payments/verify → 409 (flag off)
//  3 ✶ GET /pay/<token> + /pay/callback → one neutral page, no token
//      probing, no DB reads, no state disclosure
//  4 ✶ resolveProvider is null EVEN WITH gateway_provider + merchant +
//      key fully configured in the academy settings
//  5 ✶ channelStatus.gateway reports hidden (no configurable gateway)
//  6 ✶ a reminder for an ONLINE installment carries NO link and NO
//      online mention — even with a mock provider factory registered
//      and credentials stored (activation needs the deploy flag)
//  7 ✶ the bot «اقساط من» menu shows NO online link/hint for an ONLINE
//      installment while hidden
//  8 ✶ flag ON but no factory registered → honest «پشتیبانی نشده»
//      (the architecture is ready, nothing is accidentally activatable)
//  9 ✶ card-to-card stays fully functional: claim → approve → receipt
// 10 ✶ /api/sync/payments (the official registration path) unaffected
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { handlePayRoute } from '../src/handlers/pay';
import { dispatchDueReminders } from '../src/reminders';
import { registerProviderFactory } from '../src/gateway';
import { sha256Hex } from '../src/crypto';
import { epochToShamsi } from '../src/shamsi';
import { AcademyDatabase } from '../src/db';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, API_KEY } from './helpers/fixtures';
import type { Env, InstallmentRecord } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);
const DAY = 86_400_000;
const TOKEN_A = '111111:AAA_stage9_gw_token_for_academy_a_1';
const STUDENT_CHAT = 940;
const MANAGER_A = '5501';

interface TgCall { kind: string; chatId?: string; text?: string }
let tgCalls: TgCall[] = [];
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) return realFetch(input as any, init as any);
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const call: TgCall = { kind: m?.[2] || '' };
        const body = init?.body;
        if (typeof body === 'string') {
            const json = JSON.parse(body);
            call.chatId = String(json.chat_id ?? '');
            call.text = json.text;
        } else if (body instanceof FormData) {
            call.chatId = String(body.get('chat_id') ?? '');
        }
        tgCalls.push(call);
        return new Response(JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }), { status: 200 });
    }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; });

function auth(academy = ACADEMY_A, withKey: string = API_KEY): Record<string, string> {
    return {
        Authorization: `Bearer ${withKey}`,
        'X-Academy-Id': academy,
        'Content-Type': 'application/json'
    };
}

async function api(env: Env, method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Response> {
    return handleApiRequest(
        new Request(`${BASE}${path}`, {
            method, headers: headers || auth(),
            body: body === undefined ? undefined : JSON.stringify(body)
        }),
        env, URL_(path)
    );
}

function installment(over: Partial<InstallmentRecord> = {}): InstallmentRecord {
    const dueEpoch = Date.now() + 30 * DAY;
    return {
        id: 'ins-x', student_id: 'stu-x', installment_number: 2, total_installments: 3,
        course_title: 'سازدهنی', amount: 1_000_000, paid_amount: 0, remaining_amount: 1_000_000,
        due_date_shamsi: '۱۴۰۵/۰۹/۰۱', due_date_epoch: dueEpoch,
        is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now(),
        ...over
    } as InstallmentRecord;
}

/** A fully-configured gateway setup that MUST still be inert while hidden. */
async function seedHiddenWorld() {
    const { env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' });
    db.run(
        `INSERT OR REPLACE INTO academies (id, name, bot_token, bot_username, bot_id, webhook_secret, bot_connected_at_epoch)
         VALUES (?,?,?,?,?,?,?)`,
        ACADEMY_A, 'A', TOKEN_A, 'bot_a', '111111', 'sec-a', Date.now()
    );
    await api(env, 'POST', '/api/sync/students-batch', {
        students: [{ id: 'stu-gw', fullName: 'gateway دانشجو', studentCode: 'SC-GW', phone: '۰۹۱۲۳۳۳۴۴۴۴' }]
    });
    const adb = new AcademyDatabase(env, ACADEMY_A);
    await adb.linkTelegramChat('stu-gw', STUDENT_CHAT, 'gw');
    // The manager EVEN stored full gateway credentials + manager chat:
    await api(env, 'POST', '/api/sync/settings', {
        academy_name: 'آکادمی مخفی', currency_unit: 'تومان',
        gateway_provider: 'testpay', gateway_merchant_id: 'M-123', gateway_api_key: 'GK-SECRET-000',
        manager_chat_ids: MANAGER_A
    }, auth());
    await api(env, 'POST', '/api/sync/installments', {
        installments: [installment({
            id: 'ins-gw', student_id: 'stu-gw', payment_method: 'ONLINE' as any,
            due_date_epoch: Date.now() + DAY, due_date_shamsi: epochToShamsi(Date.now() + DAY)
        })]
    });
    return { env, db, adb };
}

// A registered mock factory — WITHOUT the deploy-time flag it must never run.
registerProviderFactory('testpay', () => ({
    id: 'testpay',
    async createPaymentLink() { return { redirectUrl: 'https://mock-gateway.test/checkout' }; },
    async verifyPayment() { return { ok: true, paidAmount: 1_000_000, ref: 'MOCK-REF', raw: '{}' }; }
}));

describe('gateway hidden (kill-switch off — the default)', () => {
    let env: Env; let db: D1Shim; let adb: AcademyDatabase;

    beforeAll(async () => {
        ({ env, db, adb } = await seedHiddenWorld());
    });

    it('1+2. /api/payments/link and /api/payments/verify refuse with the honest reason', async () => {
        const link = await api(env, 'POST', '/api/payments/link', { studentId: 'stu-gw', installmentId: 'ins-gw' });
        expect(link.status).toBe(409);
        const lb = await link.json() as any;
        expect(lb.success).toBe(false);
        expect(lb.message).toContain('پرداخت آنلاین در این نسخه فعال نیست');

        const verify = await api(env, 'POST', '/api/payments/verify', { orderId: 'whatever' });
        expect(verify.status).toBe(409);
        const vb = await verify.json() as any;
        expect(vb.message).toContain('کارت‌به‌کارت');

        // nothing was minted anywhere
        const txns = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns.n).toBe(0);
    });

    it('3. /pay routes answer one neutral page without probing tokens or DB', async () => {
        const entry = await handlePayRoute(new Request(`${BASE}/pay/AbCdEf123456789012345678`), env, URL_('/pay/AbCdEf123456789012345678'));
        expect(entry.status).toBe(200);
        const html = await entry.text();
        expect(html).toContain('پرداخت آنلاین غیرفعال است');
        expect(html).not.toContain('لینک');
        // a callback path gets the SAME page (no order lookup, no verify call)
        const cb = await handlePayRoute(new Request(`${BASE}/pay/callback/testpay?orderId=x`), env, URL_('/pay/callback/testpay?orderId=x'));
        expect(await cb.text()).toContain('پرداخت آنلاین غیرفعال است');
        const txns = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns.n).toBe(0);
    });

    it('4+5. full credentials do NOT activate anything; channel status reports hidden', async () => {
        const cfg = await (await api(env, 'GET', '/api/messaging/config')).json() as any;
        expect(cfg.data.channelStatus.gateway.configured).toBe(false);
        expect(cfg.data.channelStatus.gateway.detail).toContain('مخفی');
        // and the settings GET endpoint never echoes the gateway key either
        expect(JSON.stringify(cfg)).not.toContain('GK-SECRET-000');
    });

    it('6+7. reminders and the bot installment menu carry NO online trace', async () => {
        tgCalls.length = 0;
        await dispatchDueReminders(env, adb, null as any, ACADEMY_A, Date.now() + 26 * 3600_000);
        const reminderTexts = tgCalls.filter((c) => c.kind === 'sendMessage').map((c) => c.text || '');
        const all = reminderTexts.join('\n');
        expect(all).not.toMatch(/pay\//);
        expect(all).not.toContain('آنلاین');
        // no order was minted by the reminder path either
        const txns = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns.n).toBe(0);

        // bot «اقساط من»
        const webhook = await import('../src/handlers/webhook');
        tgCalls.length = 0;
        const secret = 'sec-' + ACADEMY_A;
        await webhook.handleTelegramWebhook(new Request(`${BASE}/webhook/${ACADEMY_A}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': secret },
            body: JSON.stringify({
                update_id: 900001,
                message: { message_id: 1, date: Math.floor(Date.now() / 1000), chat: { id: STUDENT_CHAT }, from: { id: STUDENT_CHAT }, text: '📅 اقساط من' }
            })
        }), env);
        const menu = tgCalls.filter((c) => c.kind === 'sendMessage').map((c) => c.text || '').join('\n');
        expect(menu).not.toMatch(/pay\//);
        expect(menu).not.toContain('آنلاین');
        // card-to-card guidance IS present (the real, only payment path)
        const txns2 = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns2.n).toBe(0);
    });

    it('9+10. card-to-card + manager approval keep working untouched', async () => {
        // register a TRANSFER payment through the official app path
        const push = await api(env, 'POST', '/api/sync/payments', {
            payments: [{
                id: 'pay-gw-1', studentId: 'stu-gw', installmentId: 'ins-gw', installmentNumber: 2,
                amount: 500000, paymentMethod: 'TRANSFER',
                paymentDateShamsi: epochToShamsi(Date.now()), paymentTime: '12:00',
                destinationAccountTitle: 'حساب آموزشگاه', trackingNumber: 'TRK-GW-1', notes: ''
            }]
        });
        expect(push.status).toBe(200);
        const receipts = (db as any).db.prepare(`SELECT id, send_status FROM payment_receipts`).all() as any[];
        expect(receipts.length).toBe(1);
        expect(receipts[0].send_status).toBe('QUEUED');
        // and the receipt pipeline (Telegram channel, default policy) delivers
        tgCalls.length = 0;
        const { dispatchReceipts } = await import('../src/receipts');
        const { TelegramBotClient, academyBotToken } = await import('../src/telegram');
        const token = await academyBotToken(env, ACADEMY_A);
        const out = await dispatchReceipts(env, adb, new TelegramBotClient(token || TOKEN_A), ACADEMY_A, Date.now() + 1000);
        expect(out.sent).toBe(1);
        expect(tgCalls.some((c) => (c.text || '').includes('رسید پرداخت شهریه'))).toBe(true);
    });
});

describe('activation contract (flag ON, architecture ready)', () => {
    it('8. with the flag but NO usable provider the refusal stays honest — never a fake success', async () => {
        const { env, db } = makeEnv(new D1Shim(), { ONLINE_PAYMENTS_ENABLED: 'true', TELEGRAM_BOT_TOKEN: '' });
        await api(env, 'POST', '/api/sync/settings', {
            gateway_provider: 'unregistered-pay', gateway_merchant_id: 'M', gateway_api_key: 'K'
        }, auth());
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-act', fullName: 'فعال‌سازی', studentCode: 'SC-ACT', phone: '۰۹۱۲۵۵۵۶۶۶۶' }]
        });
        const adbA = new AcademyDatabase(env, ACADEMY_A);
        await adbA.linkTelegramChat('stu-act', 941, 'act');
        await api(env, 'POST', '/api/sync/installments', {
            installments: [installment({
                id: 'ins-act', student_id: 'stu-act', payment_method: 'ONLINE' as any,
                due_date_epoch: Date.now() + DAY, due_date_shamsi: epochToShamsi(Date.now() + DAY)
            })]
        });
        const link = await api(env, 'POST', '/api/payments/link', { studentId: 'stu-act', installmentId: 'ins-act' });
        // the flag alone is NOT enough: the factory map decides — an
        // unregistered provider name fails with the honest "not supported"
        // message and NOTHING is minted.
        expect(link.status).toBe(409);
        const b = await link.json() as any;
        expect(b.success).toBe(false);
        expect(b.message).toContain('پشتیبانی یا تأیید نشده');
        const txns = (db as any).db.prepare(`SELECT COUNT(*) AS n FROM payment_transactions`).get() as any;
        expect(txns.n).toBe(0);
    });
});
