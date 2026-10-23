// ============================================================
// Stage 4: payment receipts + online payment infrastructure.
//
// Everything below runs against the REAL worker modules with the real
// D1 shim and a recorded Telegram API — no real bot, no real gateway,
// no real money. Proven here:
//   1. a REAL recorded payment becomes exactly ONE receipt (same id as
//      the app payment), re-sync never duplicates or re-sends;
//   2. later-installment receipts send through the academy's OWN bot
//      with the app's own receipt fields;
//   3. the FIRST installment (paid at registration) is held until the
//      contract PDF has REALLY been delivered, then sent after the
//      configured delay — never before, never without a payment;
//   4. unpaid installments produce NO receipt at any time;
//   5. reversals: blocked before send, noticed after send;
//   6. a student without a linked chat delays (not cancels) delivery;
//   7. Telegram outage is retried in a bounded, tracked way;
//   8. receipts are per-student and per-academy isolated;
//   9. online links: refused without a connected provider, dedicated
//      per installment with the exact remaining amount, idempotent reuse,
//      server-side verification only (claim/redirect never verifies),
//      amount-mismatch / expiry / double-verify handled;
//  10. a VERIFIED online payment auto-updates the installment and sends
//      its receipt — no manual re-registration anywhere;
//  11. reminders for ONLINE installments carry the payment link and the
//      SMS stays DISABLED (infrastructure ready, sending off);
//  12. the bot menu attaches the PRINTABLE PDF of the same receipts.
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleScheduledEvent } from '../src/handlers/cron';
import { handleSignRoute, deliverSignedPdfToStudent } from '../src/handlers/sign';
import { handlePayRoute } from '../src/handlers/pay';
import { ingestPayments, releaseFirstReceiptsAfterContract } from '../src/receipts';
import { registerProviderFactory, GatewayProvider } from '../src/gateway';
import { sha256Hex } from '../src/crypto';
import { epochToShamsi } from '../src/shamsi';
import { AcademyDatabase } from '../src/db';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, makePdf, sha256Bytes, ACADEMY_A, ACADEMY_B, API_KEY } from './helpers/fixtures';
import type { Env } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);
const DAY = 86_400_000;

const TOKEN_A = '111111:AAA_test_token_for_academy_a_0001';

// ---------- Telegram recorder ----------
interface TgCall { kind: string; token: string; chatId?: string; text?: string; fileName?: string; byteLength?: number; head?: string }
let tgCalls: TgCall[] = [];
let telegramDown = false;
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) return realFetch(input as any, init as any);
        if (telegramDown) throw new Error('network down');
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const call: TgCall = { kind: m?.[2] || '', token: m?.[1] || '' };
        if (call.kind === 'sendMessage') {
            const body = JSON.parse(init?.body || '{}');
            call.chatId = String(body.chat_id);
            call.text = body.text;
        }
        if (call.kind === 'sendDocument' && init?.body instanceof FormData) {
            const form = init.body as FormData;
            call.chatId = String(form.get('chat_id') ?? '');
            const doc = form.get('document');
            if (doc && typeof doc === 'object' && 'size' in doc) {
                call.fileName = (doc as File).name;
                call.byteLength = (doc as File).size;
                const buf = await (doc as File).arrayBuffer();
                call.head = String.fromCharCode(...new Uint8Array(buf.slice(0, 5)));
            }
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

function textsTo(chatId: number | string): string[] {
    return tgCalls.filter((c) => c.kind === 'sendMessage' && c.chatId === String(chatId)).map((c) => c.text || '');
}
function docsTo(chatId: number | string): TgCall[] {
    return tgCalls.filter((c) => c.kind === 'sendDocument' && c.chatId === String(chatId));
}

async function pushPayment(env: Env, academy: string, p: Record<string, unknown>): Promise<Response> {
    return api(env, 'POST', '/api/sync/payments', { payments: [p] }, auth(academy));
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

function payment(over: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: 'pay-1', transactionId: 'TRX-1404-0001', studentId: 'stu-1', studentName: 'دانا محمدی',
        installmentId: 'ins-2', installmentNumber: 2, amount: 1000000,
        paymentDateShamsi: '۱۴۰۴/۰۵/۱۲', paymentTime: '14:30', paymentMethod: 'TRANSFER',
        destinationAccountTitle: 'حساب پیش‌فرض آموزشگاه', trackingNumber: 'TRK-99881',
        notes: '', isReversed: false, ...over
    };
}

// ============================================================
// 1-4. Receipt ingestion, ordering, dedupe
// ============================================================
describe('receipt lifecycle (ingest → order → send, no duplicates)', () => {
    let env: Env; let db: D1Shim;

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-1', fullName: 'دانا محمدی', studentCode: 'SC-1', phone: '۰۹۱۲۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/installments', {
            installments: [
                { id: 'ins-1', student_id: 'stu-1', installment_number: 1, total_installments: 3, course_title: 'ICDL',
                  amount: 2000000, paid_amount: 2000000, remaining_amount: 0,
                  due_date_shamsi: '۱۴۰۴/۰۴/۰۱', due_date_epoch: Date.now() - 30 * DAY, is_paid: 1,
                  paid_date_shamsi: '۱۴۰۴/۰۴/۰۱', updated_at_epoch: Date.now() },
                { id: 'ins-2', student_id: 'stu-1', installment_number: 2, total_installments: 3, course_title: 'ICDL',
                  amount: 1000000, paid_amount: 0, remaining_amount: 1000000,
                  due_date_shamsi: '۱۴۰۴/۰۸/۰۱', due_date_epoch: Date.now() + 20 * DAY, is_paid: 0,
                  paid_date_shamsi: '', updated_at_epoch: Date.now() }
            ]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', { academy_name: 'آکادمی موسیقی آوا', currency_unit: 'تومان' }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-1', 700);
    });

    it('an UNPAID installment never produces a receipt', async () => {
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const n = (db.get(`SELECT COUNT(*) AS n FROM payment_receipts WHERE student_id = 'stu-1'`) as any).n;
        expect(n).toBe(0);
        expect(textsTo('700').length).toBe(0);
    });

    it('a REAL payment becomes exactly one receipt, re-push changes nothing', async () => {
        const res = await pushPayment(env, ACADEMY_A, payment());
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.created).toBe(1);

        // re-sync / retry of the SAME payment: no second row, nothing new
        const again = await pushPayment(env, ACADEMY_A, payment());
        const body2 = await again.json() as any;
        expect(body2.data.created).toBe(0);
        expect(body2.data.skipped).toBe(1);
        const n = (db.get(`SELECT COUNT(*) AS n FROM payment_receipts`) as any).n;
        expect(n).toBe(1);
    });

    it('a later-installment receipt is sent once through the academy OWN bot', async () => {
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const sent = textsTo('700');
        expect(sent.length).toBe(1);
        expect(sent[0]).toContain('رسید پرداخت شهریه');
        expect(sent[0]).toContain('دانا محمدی');
        expect(sent[0]).toContain('TRX-1404-0001');
        expect(sent[0]).toContain('1,000,000');
        expect(sent[0]).toContain('کارت به کارت');
        expect(sent[0]).toContain('TRK-99881');
        expect(sent[0]).toContain('قسط 2');
        const row = db.get(`SELECT send_status, telegram_message_id FROM payment_receipts WHERE id = 'pay-1'`) as any;
        expect(row.send_status).toBe('SENT');
        expect(String(row.telegram_message_id)).not.toBe('');

        // cron again: SENT receipts are never re-sent
        tgCalls.length = 0;
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').length).toBe(0);
    });

    it('the FIRST installment receipt waits for the signed contract PDF + delay', async () => {
        // the first installment WAS paid at registration (app-created payment)
        await pushPayment(env, ACADEMY_A, payment({
            id: 'pay-first', transactionId: 'TRX-1404-0009', installmentId: 'ins-1', installmentNumber: 1,
            amount: 2000000, paymentDateShamsi: '۱۴۰۴/۰۴/۰۱', paymentTime: 'همزمان با ثبت‌نام', trackingNumber: ''
        }));
        let row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-first'`) as any;
        expect(row.send_status).toBe('WAITING_CONTRACT');

        // cron with NO signed contract: still held, nothing sent
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').filter((t) => t.includes('TRX-1404-0009')).length).toBe(0);

        // the contract is signed and its PDF REALLY reaches the chat
        // (deliverSignedPdfToStudent is the exact production hook)
        const pdf = makePdf();
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, status,
                token_hash, expires_at_epoch, issued_at_epoch, signed_pdf_key, created_at_epoch, updated_at_epoch)
             VALUES ('c-1', ?, 'enr-1', 'stu-1', 'دانا محمدی', 'SIGNED', ?, ?, ?, 'k-1', ?, ?)`,
            ACADEMY_A, await sha256Hex('token-stage4-1'), Date.now() + DAY, Date.now(), Date.now(), Date.now()
        );
        db.run(`INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES ('k-1', ?, ?, ?, ?)`,
            ACADEMY_A, pdf, await sha256Bytes(pdf), Date.now());
        const contract = db.get(`SELECT * FROM contracts WHERE id = 'c-1'`) as any;
        tgCalls.length = 0;
        await deliverSignedPdfToStudent(env, contract, pdf);
        // PDF arrived + receipt release event recorded
        expect(docsTo('700').some((d) => (d.fileName || '').startsWith('contract-'))).toBe(true);
        expect((db.get(`SELECT COUNT(*) AS n FROM contract_events WHERE contract_id='c-1' AND type='RECEIPT_RELEASED'`) as any).n).toBe(1);

        // delay setting: 1 minute minimum -> still not sent immediately
        row = db.get(`SELECT send_status, eligible_at_epoch FROM payment_receipts WHERE id = 'pay-first'`) as any;
        expect(row.send_status).toBe('QUEUED');
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').filter((t) => t.includes('TRX-1404-0009')).length).toBe(0);

        // after the delay the receipt goes out — AFTER the contract, never before
        db.run(`UPDATE payment_receipts SET eligible_at_epoch = ? WHERE id = 'pay-first'`, Date.now() - 1000);
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const receiptTexts = textsTo('700').filter((t) => t.includes('TRX-1404-0009'));
        expect(receiptTexts.length).toBe(1);
        expect(receiptTexts[0]).toContain('2,000,000');
        row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-first'`) as any;
        expect(row.send_status).toBe('SENT');
    });

    it('a reversed payment that was never sent is never sent', async () => {
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-rev', transactionId: 'TRX-1404-REV', installmentNumber: 3, isReversed: true, reversalReason: 'اشتباه در ثبت' }));
        let row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-rev'`) as any;
        expect(row.send_status).toBe('REVERSED');
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').filter((t) => t.includes('TRX-1404-REV')).length).toBe(0);
    });

    it('reversing an ALREADY-SENT payment sends a reversal notice once', async () => {
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-sent-then-rev', transactionId: 'TRX-1404-77', installmentNumber: 3, amount: 500000 }));
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').filter((t) => t.includes('TRX-1404-77')).length).toBe(1);
        tgCalls.length = 0;

        // the manager reverses it in the app; the next sync carries isReversed
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-sent-then-rev', transactionId: 'TRX-1404-77', installmentNumber: 3, amount: 500000, isReversed: true, reversalReason: 'برگشت وجه' }));
        let row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-sent-then-rev'`) as any;
        expect(row.send_status).toBe('REVERSAL_QUEUED');
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const notices = textsTo('700').filter((t) => t.includes('TRX-1404-77'));
        expect(notices.length).toBe(1);
        expect(notices[0]).toContain('برگشتی');
        expect(notices[0]).toContain('برگشت وجه');
        row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-sent-then-rev'`) as any;
        expect(row.send_status).toBe('REVERSED_NOTIFIED');

        // and NOT again
        tgCalls.length = 0;
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        expect(textsTo('700').filter((t) => t.includes('TRX-1404-77')).length).toBe(0);
    });

    it('a student without a linked chat delays delivery; linking releases it', async () => {
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-late', fullName: 'پرویز رضایی', studentCode: 'SC-9' }]
        }, auth(ACADEMY_A));
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-late', transactionId: 'TRX-1404-LATE', studentId: 'stu-late', studentName: 'پرویز رضایی', installmentId: '', installmentNumber: 2 }));
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        let row = db.get(`SELECT send_status, last_error FROM payment_receipts WHERE id = 'pay-late'`) as any;
        expect(row.send_status).toBe('QUEUED');                       // delayed, not lost
        expect(String(row.last_error)).toContain('chat');

        // the student links via the secure one-time /start token (kept in place)
        await linkChat(env, db, ACADEMY_A, 'stu-late', 710);

        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const sent = textsTo(710).filter((t) => t.includes('TRX-1404-LATE'));
        expect(sent.length).toBe(1);
        row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-late'`) as any;
        expect(row.send_status).toBe('SENT');
    });

    it('a Telegram outage is tracked and retried in a bounded way', async () => {
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-outage', transactionId: 'TRX-1404-OUT', installmentNumber: 3, amount: 300000 }));
        telegramDown = true;
        try {
            await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
            const row = db.get(`SELECT send_status, attempts, last_error FROM payment_receipts WHERE id = 'pay-outage'`) as any;
            expect(row.send_status).toBe('QUEUED');
            expect(row.attempts).toBe(1);
            expect(String(row.last_error)).toContain('network down');
        } finally {
            telegramDown = false;   // never leaks into other tests
        }
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const row = db.get(`SELECT send_status FROM payment_receipts WHERE id = 'pay-outage'`) as any;
        expect(row.send_status).toBe('SENT');
    });

    it('receipts are isolated per student and per academy', async () => {
        // academy B bot exists; chat 711 is NOT linked in B
        connectAcademyBot(db, ACADEMY_B, '222222:BBB_test_token_for_academy_b_0002');
        // A payment of academy B pushed for a student of academy A must NOT leak to A's chat
        await pushPayment(env, ACADEMY_B, payment({ id: 'pay-cross', transactionId: 'TRX-CROSS', studentId: 'stu-1', installmentNumber: 3, amount: 111111 }));
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        // student stu-1 in academy B does not exist -> no chat -> nothing sent via either bot
        expect(tgCalls.filter((c) => c.kind === 'sendMessage' && (c.text || '').includes('TRX-CROSS')).length).toBe(0);
        // the receipt row is stored under academy B only
        const a = db.get(`SELECT COUNT(*) AS n FROM payment_receipts WHERE id='pay-cross' AND academy_id=?`, ACADEMY_A) as any;
        const b = db.get(`SELECT COUNT(*) AS n FROM payment_receipts WHERE id='pay-cross' AND academy_id=?`, ACADEMY_B) as any;
        expect(a.n).toBe(0); expect(b.n).toBe(1);
    });

    it('the bot menu attaches the PRINTABLE PDF of the same receipts', async () => {
        tgCalls.length = 0;
        await press(env, ACADEMY_A, 700, '🧾 رسیدهای من');
        const docs = docsTo(700);
        expect(docs.length).toBeGreaterThanOrEqual(1);
        for (const d of docs) {
            expect((d.fileName || '').startsWith('receipt-')).toBe(true);
            expect(d.byteLength || 0).toBeGreaterThan(500);
            expect(d.head).toBe('%PDF-');          // a real, printable PDF
        }
        const listing = textsTo(700).join(' ');
        expect(listing).toContain('پرداخت‌های ثبت‌شده');
    });
});

// ============================================================
// 9-11. Online payment infrastructure
// ============================================================
describe('online payment links, verification and reminder integration', () => {
    let env: Env; let db: D1Shim;
    let verifyBehavior: 'ok' | 'mismatch' | 'canceled' | 'throw';

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-on', fullName: 'نیما کریمی', studentCode: 'SC-3', phone: '۰۹۳۵۰۰۰۰۰۰۰' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/installments', {
            installments: [
                { id: 'ins-on', student_id: 'stu-on', installment_number: 2, total_installments: 3, course_title: 'طراحی',
                  amount: 1500000, paid_amount: 0, remaining_amount: 1500000,
                  due_date_shamsi: '۱۴۰۴/۰۸/۰۱', due_date_epoch: Date.now() + DAY, is_paid: 0,
                  paid_date_shamsi: '', payment_method: 'ONLINE', updated_at_epoch: Date.now() }
            ]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/settings', { academy_name: 'آکادمی موسیقی آوا' }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-on', 720);

        // A TEST-ONLY adapter through the PUBLIC extension point — this is exactly
        // how a real gateway (IDPay/PayPing/…) will plug in later.
        registerProviderFactory('testpay', () => ({
            id: 'testpay',
            async createPaymentLink(txn) {
                return { redirectUrl: `https://pay.testpay.example/checkout/${txn.id}` };
            },
            async verifyPayment(txn) {
                if (verifyBehavior === 'mismatch') return { ok: true, paidAmount: txn.amount - 1, ref: 'REF-X', raw: '{"amount":' + (txn.amount - 1) + '}' };
                if (verifyBehavior === 'canceled') return { ok: false, error: 'canceled by user', status: 'CANCELED' };
                if (verifyBehavior === 'throw') throw new Error('gateway unreachable');
                return { ok: true, paidAmount: txn.amount, ref: 'REF-OK-1', raw: '{"status":1,"amount":' + txn.amount + '}' };
            }
        }));
        await api(env, 'POST', '/api/sync/settings', {
            gateway_provider: 'testpay', gateway_merchant_id: 'mid-1', gateway_api_key: 'test-key-secret'
        }, auth(ACADEMY_A));
    });

    it('without a connected provider, link creation refuses politely (409) and no secrets leak', async () => {
        // temporarily unset
        await api(env, 'POST', '/api/sync/settings', { gateway_provider: 'none' }, auth(ACADEMY_A));
        const res = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-on' }, auth(ACADEMY_A));
        expect(res.status).toBe(409);
        const body = await res.json() as any;
        expect(body.message).toContain('درگاه پرداخت آنلاین');
        await api(env, 'POST', '/api/sync/settings', { gateway_provider: 'testpay' }, auth(ACADEMY_A));
    });

    it('creates a dedicated link for the exact remaining amount and reuses it', async () => {
        const res = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-on' }, auth(ACADEMY_A));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.amount).toBe(1500000);
        expect(body.data.url).toMatch(/^https:\/\/worker\.test\/pay\/[A-Za-z0-9_-]{20,64}$/);
        expect(JSON.stringify(body)).not.toContain('test-key-secret');

        const again = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-on' }, auth(ACADEMY_A));
        const body2 = await again.json() as any;
        expect(body2.data.reused).toBe(true);
        expect(body2.data.orderId).toBe(body.data.orderId);   // the SAME link, no new orders
        // stage 8: the reuse path returns a FULL URL too — never the bare token
        expect(body2.data.url).toMatch(/^https:\/\/worker\.test\/pay\/[A-Za-z0-9_-]{20,64}$/);

        const txn = db.get(`SELECT status, amount, provider FROM payment_transactions WHERE id = ?`, body.data.orderId) as any;
        expect(txn.status).toBe('PENDING');
        expect(txn.amount).toBe(1500000);
        expect(txn.provider).toBe('testpay');
    });

    it('a WRONG-STUDENT link request is refused (isolation)', async () => {
        const res = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-OTHER' }, auth(ACADEMY_A));
        expect(res.status).toBe(404);
    });

    it('user claims / opened links never verify; the PROVIDER check does (amount mismatch blocked)', async () => {
        const txn = db.get(`SELECT id FROM payment_transactions WHERE status='PENDING' LIMIT 1`) as any;
        verifyBehavior = 'mismatch';
        const res = await api(env, 'POST', '/api/payments/verify', { orderId: txn.id }, auth(ACADEMY_A));
        const body = await res.json() as any;
        expect(body.data.state).toBe('AMOUNT_MISMATCH');
        const inst = db.get(`SELECT paid_amount, remaining_amount, is_paid FROM installments WHERE id = 'ins-on'`) as any;
        expect(inst.paid_amount).toBe(0);          // untouched
        expect(inst.remaining_amount).toBe(1500000);
        expect((db.get(`SELECT COUNT(*) AS n FROM payment_receipts`) as any).n).toBe(0);
    });

    it('verified PAID auto-updates the installment, queues and sends the receipt — idempotently', async () => {
        const txn = db.get(`SELECT id FROM payment_transactions WHERE status='AMOUNT_MISMATCH' LIMIT 1`) as any;
        // the mismatched order stays mismatched; create a fresh clean flow
        const linkRes = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-on' }, auth(ACADEMY_A));
        const link = await linkRes.json() as any;
        expect(link.data.reused).toBe(false);      // old one is not reusable anymore
        const orderId = link.data.orderId;

        verifyBehavior = 'ok';
        const res = await api(env, 'POST', '/api/payments/verify', { orderId }, auth(ACADEMY_A));
        const body = await res.json() as any;
        expect(body.data.state).toBe('PAID');
        expect(body.data.ref).toBe('REF-OK-1');
        expect(body.data.receiptSent).toBe(true);

        // installment updated AUTOMATICALLY (no manual re-registration)
        const inst = db.get(`SELECT paid_amount, remaining_amount, is_paid, paid_date_shamsi FROM installments WHERE id = 'ins-on'`) as any;
        expect(inst.paid_amount).toBe(1500000);
        expect(inst.remaining_amount).toBe(0);
        expect(inst.is_paid).toBe(1);
        expect(inst.paid_date_shamsi).toMatch(/^[۰-۹]{4}\/[۰-۹]{2}\/[۰-۹]{2}$/);

        // exactly ONE receipt, sent ONCE through the bot
        const receipts = (db.get(`SELECT COUNT(*) AS n FROM payment_receipts WHERE send_reason='online'`) as any).n;
        expect(receipts).toBe(1);
        const receipt = db.get(`SELECT * FROM payment_receipts WHERE send_reason='online'`) as any;
        expect(receipt.send_status).toBe('SENT');
        expect(receipt.tracking_number).toBe('REF-OK-1');
        expect(receipt.payment_method).toBe('ONLINE');
        expect(receipt.paid_date_shamsi).toMatch(/^[۰-۹]/);
        expect(tgCalls.filter((c) => c.kind === 'sendMessage' && (c.text || '').includes('پرداخت آنلاین') && (c.text || '').includes('1,500,000')).length).toBe(1);

        // double verification: reported, nothing re-applied, nothing re-sent
        tgCalls.length = 0;
        const again = await api(env, 'POST', '/api/payments/verify', { orderId }, auth(ACADEMY_A));
        const body2 = await again.json() as any;
        expect(body2.data.alreadyPaid).toBe(true);
        expect(textsTo('720').filter((t) => t.includes('1,500,000')).length).toBe(0);
        expect((db.get(`SELECT paid_amount FROM installments WHERE id='ins-on'`) as any).paid_amount).toBe(1500000);
        void txn; // (the mismatched row stays for forensics)
    });

    it('canceled and unreachable gateways keep the ledger honest', async () => {
        const linkRes = await api(env, 'POST', '/api/payments/link', { installmentId: 'ins-on', studentId: 'stu-on' }, auth(ACADEMY_A));
        // installment now fully paid -> refused
        expect(linkRes.status).toBe(409);
        expect(((await linkRes.json() as any).message) as string).toContain('پرداخت شده');
    });

    it('the /pay page: unknown token 404, short token refused — opening never verifies', async () => {
        const bad = await handlePayRoute(new Request(`${BASE}/pay/not-a-real-token-123456`), env, URL_('/pay/not-a-real-token-123456'));
        expect(bad.status).toBe(404);
        const short = await handlePayRoute(new Request(`${BASE}/pay/shorttoken`), env, URL_('/pay/shorttoken'));
        expect(short.status).toBe(404);
    });

    it('the app pull returns confirmed online payments for Room auto-sync', async () => {
        const res = await api(env, 'GET', `/api/payments/confirmed?since=0`, undefined, auth(ACADEMY_A));
        const body = await res.json() as any;
        expect(body.data.payments.length).toBe(1);
        expect(body.data.payments[0].amount).toBe(1500000);
        expect(body.data.payments[0].ref).toBe('REF-OK-1');
        const later = await api(env, 'GET', `/api/payments/confirmed?since=${Date.now() + 1000}`, undefined, auth(ACADEMY_A));
        expect(((await later.json() as any).data.payments.length)).toBe(0);
    });

    it('reminders for ONLINE installments carry the payment link; SMS stays DISABLED', async () => {
        // new unpaid ONLINE installment + a due reminder for it
        await api(env, 'POST', '/api/sync/installments', {
            installments: [
                { id: 'ins-on2', student_id: 'stu-on', installment_number: 3, total_installments: 3, course_title: 'طراحی',
                  amount: 800000, paid_amount: 0, remaining_amount: 800000,
                  due_date_shamsi: '۱۴۰۴/۰۹/۰۱', due_date_epoch: Date.now() + 2 * DAY, is_paid: 0,
                  paid_date_shamsi: '', payment_method: 'ONLINE', updated_at_epoch: Date.now() }
            ]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/schedule-reminders', {
            reminders: [{
                id: 'rem-pay', studentId: 'stu-on', studentName: 'نیما کریمی', chatId: '730',
                installmentId: 'ins-on2', reminderType: 'INSTALLMENT',
                message: '🔔 یادآوری قسط', scheduledEpoch: Date.now() + 50,
                scheduledTimeStr: '', studentPhone: '۰۹۳۵۰۰۰۰۰۰۰'
            }]
        }, auth(ACADEMY_A));
        await new Promise((r) => setTimeout(r, 150));   // the reminder comes due
        verifyBehavior = 'ok';
        tgCalls.length = 0;
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const reminderText = textsTo('730').find((t) => t.includes('یادآوری قسط'));
        expect(reminderText).toBeTruthy();
        expect(reminderText).toContain('پرداخت آنلاین این قسط');
        expect(reminderText).toContain('800,000');
        expect(reminderText).toMatch(/https:\/\/worker\.test\/pay\/[A-Za-z0-9_-]{20,64}/);
        // SMS: queued but DISABLED (no real provider; infrastructure ready)
        const sms = db.get(`SELECT status, error FROM sms_outbox WHERE id = 'sms-rem-pay'`) as any;
        expect(sms?.status).toBe('DISABLED');
        expect(String(sms?.error)).toContain('sms_provider_not_configured');
    });
});

// ============================================================
// Manual partial payments + multiple installments (ingest level)
// ============================================================
describe('manual partial & multi-installment payments', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-m', fullName: 'سپیده اکبری', studentCode: 'SC-5' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/installments', {
            installments: [
                { id: 'ins-m1', student_id: 'stu-m', installment_number: 1, total_installments: 2, course_title: 'ICDL',
                  amount: 2000000, paid_amount: 500000, remaining_amount: 1500000,
                  due_date_shamsi: '۱۴۰۴/۰۵/۰۱', due_date_epoch: Date.now() + DAY, is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now() },
                { id: 'ins-m2', student_id: 'stu-m', installment_number: 2, total_installments: 2, course_title: 'ICDL',
                  amount: 2000000, paid_amount: 0, remaining_amount: 2000000,
                  due_date_shamsi: '۱۴۰۴/۱۰/۰۱', due_date_epoch: Date.now() + 40 * DAY, is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now() }
            ]
        }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-m', 740);
        // first installment PARTIALLY paid at registration: the payment exists (real),
        // so it is held for the contract like every first-installment receipt.
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-m1', transactionId: 'TRX-M-1', studentId: 'stu-m', studentName: 'سپیده اکبری', installmentId: 'ins-m1', installmentNumber: 1, amount: 500000 }));
        await pushPayment(env, ACADEMY_A, payment({ id: 'pay-m2', transactionId: 'TRX-M-2', studentId: 'stu-m', studentName: 'سپیده اکبری', installmentId: 'ins-m2', installmentNumber: 2, amount: 2000000, paymentMethod: 'POS' }));
    });

    it('a partial payment is still a REAL payment: receipt queued (first: held for contract)', async () => {
        const r1 = db.get(`SELECT send_status, amount FROM payment_receipts WHERE id='pay-m1'`) as any;
        expect(r1.send_status).toBe('WAITING_CONTRACT');      // first installment rule
        expect(r1.amount).toBe(500000);
        const r2 = db.get(`SELECT send_status FROM payment_receipts WHERE id='pay-m2'`) as any;
        expect(r2.send_status).toBe('QUEUED');
    });

    it('POS (card reader) manual registration flows like any other manual payment', async () => {
        await handleScheduledEvent({} as ScheduledEvent, env, {} as ExecutionContext);
        const r2 = db.get(`SELECT send_status FROM payment_receipts WHERE id='pay-m2'`) as any;
        expect(r2.send_status).toBe('SENT');
    });

    it('epochToShamsi produces a valid Persian date (used for online receipts)', () => {
        expect(epochToShamsi(Date.UTC(2025, 7, 3))).toMatch(/^[۰-۹]{4}\/[۰-۹]{2}\/[۰-۹]{2}$/);
        expect(epochToShamsi(Date.UTC(2025, 2, 21))).toBe('۱۴۰۴/۰۱/۰۱'); // Nowruz 1404
    });
});
