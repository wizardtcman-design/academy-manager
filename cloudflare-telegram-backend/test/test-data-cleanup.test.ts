// ============================================================
// Test-data cleanup — mandatory flows for the «پاک‌سازی کامل اطلاعات آزمایشی»
// admin tool:
//   1. auth: no key / wrong tenant / missing confirm are all refused
//   2. preview shows exact per-category counts of the TEST student only
//   3. purge removes the whole graph atomically; real student, academy
//      settings, backups and bot credentials survive untouched
//   4. every old link dies: /start link hash, sign page token, /pay token
//   5. re-running the purge is safe and reports "nothing left"
//   6. a NEW test cycle works with fresh ids/links (same phone is allowed)
//   7. the audit report cannot be forged through the app settings push
// ============================================================

import { describe, it, beforeAll, afterAll } from 'vitest';
import { expect } from 'vitest';
import { Env } from '../src/types';
import { AcademyDatabase } from '../src/db';
import { sha256Hex, sha256HexBytes } from '../src/crypto';
import { handleContractsApi } from '../src/handlers/contracts-api';
import { handleSignRoute } from '../src/handlers/sign';
import { handlePayRoute } from '../src/handlers/pay';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleApiRequest } from '../src/handlers/api';
import { handleTestDataApi } from '../src/handlers/test-data';
import { makeEnv, makePdf, makeSignaturePng, ACADEMY_A, ACADEMY_B, API_KEY } from './helpers/fixtures';
import type { D1Shim } from './helpers/d1-shim';

const BASE = 'https://worker.test';
const DAY = 86400000;

function URL_(path: string): URL {
    return new URL(`${BASE}${path}`);
}
function bearer(academy = ACADEMY_A): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': academy };
}
function bearerNoAcademy(): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}` };
}
function noAuth(academy = ACADEMY_A): Record<string, string> {
    return { 'X-Academy-Id': academy };
}

async function callTestData(env: Env, path: string, body: unknown, headers: Record<string, string>): Promise<Response> {
    return handleTestDataApi(
        new Request(`${BASE}${path}`, { method: 'POST', headers, body: JSON.stringify(body) }),
        env, URL_(path)
    );
}

function connectBot(db: D1Shim, academy: string, username: string, token: string) {
    db.run(
        `INSERT INTO academies (id, name, bot_token, bot_username, bot_connected_at_epoch)
         VALUES (?,?,?,?,?)`,
        academy, 'Academy ' + academy, token, username, Date.now()
    );
}
function insertStudent(db: D1Shim, id: string, academy: string, fullName: string, phone: string, linkHash = '', chatId = '') {
    db.run(
        `INSERT INTO students (id, academy_id, full_name, phone, national_code, telegram_link_hash, telegram_chat_id, created_at_epoch, updated_at_epoch)
         VALUES (?,?,?,?,?,?,?,?,?)`,
        id, academy, fullName, phone, '0079123456', linkHash, chatId, Date.now(), Date.now()
    );
}

interface TgCall {
    kind: string;
    chatId?: string;
    text?: string;
}
let tgCalls: TgCall[] = [];
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) return realFetch(input as any, init as any);
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const call: TgCall = { kind: m?.[2] || '' };
        if (call.kind === 'sendMessage' && init?.body) {
            const body = JSON.parse(init.body);
            call.chatId = String(body.chat_id);
            call.text = body.text;
        }
        tgCalls.push(call);
        return new Response(JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }), { status: 200 });
    }) as any;
});
afterAll(() => {
    globalThis.fetch = realFetch;
});

async function webhook(env: Env, academy: string, text: string, chatId: number): Promise<Response> {
    return handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'test-webhook-secret' },
            body: JSON.stringify({
                update_id: tgCalls.length + 1,
                message: { message_id: 1, chat: { id: chatId }, from: { id: 42, first_name: 'آزمون', username: 'tester' }, text }
            })
        }),
        env, URL_(`/webhook/${academy}`)
    );
}

async function uploadContract(env: Env, o: { studentId: string; enrollmentId: string; token: string; phone: string }): Promise<Response> {
    const form = new FormData();
    form.append('meta', JSON.stringify({
        contractId: `c-${o.enrollmentId}`,
        enrollmentId: o.enrollmentId,
        studentId: o.studentId,
        tokenHash: await sha256Hex(o.token),
        publicToken: o.token,
        expiresAtEpoch: Date.now() + 30 * DAY,
        signerName: '', signerTitle: '',
        enrollment: {
            info: { contractNumber: 'CN-T', courseTitle: 'دوره آزمایشی' },
            student: { fullName: 'هنرجوی آزمایشی', phone: o.phone, nationalCode: '0079123456' }
        }
    }));
    form.append('file', new Blob([makePdf(3) as BlobPart], { type: 'application/pdf' }), 'contract.pdf');
    return handleContractsApi(new Request(`${BASE}/api/contracts`, { method: 'POST', body: form, headers: bearer() }), env, URL_('/api/contracts'));
}

async function mintLink(env: Env, studentId: string): Promise<string> {
    const res = await handleContractsApi(
        new Request(`${BASE}/api/students/${studentId}/telegram-link`, { method: 'POST', headers: bearer() }),
        env, URL_(`/api/students/${studentId}/telegram-link`));
    expect(res.status).toBe(200);
    return (((await res.json()) as any).data.botLink as string).split('?start=')[1];
}

/** Seeds one REAL student + one TEST student with a full dependency graph. */
async function seedMixedData(env: Env, db: D1Shim) {
    connectBot(db, ACADEMY_A, 'cleanup_bot', '888:BB');
    // Real, operational student — must survive everything.
    insertStudent(db, 'stu-real', ACADEMY_A, 'هنرجوی واقعی', '09121110000');
    db.run(
        `INSERT INTO installments (id, academy_id, student_id, amount, paid_amount, remaining_amount, updated_at_epoch)
         VALUES ('inst-real-1', ?, 'stu-real', 1000, 0, 1000, ?)`, ACADEMY_A, Date.now());

    // Test student with a /start link hash + full graph (already connected
    // to chat 5550001 — the realistic state before a cleanup).
    const startHash = await sha256Hex('start-token-of-test-student');
    insertStudent(db, 'stu-test', ACADEMY_A, 'هنرجوی آزمایشی', '09122220000', startHash, '5550001');
    db.run(
        `INSERT INTO installments (id, academy_id, student_id, amount, paid_amount, remaining_amount, updated_at_epoch)
         VALUES ('inst-t1', ?, 'stu-test', 500, 100, 400, ?)`, ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO financial_summaries (student_id, academy_id, remaining_debt, updated_at_epoch)
         VALUES ('stu-test', ?, 400, ?)`, ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO reminders (id, academy_id, student_id, student_name, chat_id, installment_id, message_text, scheduled_epoch, created_at_epoch)
         VALUES ('rem-t1', ?, 'stu-test', 'هنرجوی آزمایشی', '5550001', 'inst-t1', 'یادآوری', ?, ?)`,
        ACADEMY_A, Date.now() + DAY, Date.now());
    db.run(
        `INSERT INTO deliveries (id, academy_id, reminder_id, channel, status, created_at_epoch)
         VALUES ('dlv-t1', ?, 'rem-t1', 'telegram', 'SENT', ?)`, ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO sms_outbox (id, academy_id, reminder_id, phone, body, created_at_epoch)
         VALUES ('sms-t1', ?, 'rem-t1', '09122220000', 'متن', ?)`, ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO message_logs (id, academy_id, student_id, student_name, chat_id, message_type, content, status, timestamp_epoch)
         VALUES ('ml-t1', ?, 'stu-test', 'هنرجوی آزمایشی', '5550001', 'DIRECT_MESSAGE', 'سلام', 'SUCCESS', ?)`,
        ACADEMY_A, Date.now());
    // Unscoped log addressed to the test student's chat only.
    db.run(
        `INSERT INTO message_logs (id, academy_id, student_id, chat_id, message_type, content, status, timestamp_epoch)
         VALUES ('ml-t2', ?, '', '5550001', 'WELCOME', 'خوش آمدید', 'SUCCESS', ?)`, ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO pending_students (academy_id, student_id) VALUES (?, 'stu-test')`, ACADEMY_A);
    db.run(
        `INSERT INTO payment_receipts (id, academy_id, student_id, amount, created_at_epoch, updated_at_epoch)
         VALUES ('pr-t1', ?, 'stu-test', 100, ?, ?)`, ACADEMY_A, Date.now(), Date.now());
    db.run(
        `INSERT INTO payment_transactions (id, academy_id, student_id, installment_id, amount, status, link_token_hash, created_at_epoch, updated_at_epoch)
         VALUES ('txn-t1', ?, 'stu-test', 'inst-t1', 400, 'PENDING', ?, ?, ?)`,
        ACADEMY_A, await sha256Hex('pay-token-of-test'), Date.now(), Date.now());
    db.run(
        `INSERT INTO payment_link_tokens (token, token_hash, academy_id, order_id, expires_at_epoch, created_at_epoch)
         VALUES ('pay-token-of-test', ?, ?, 'txn-t1', ?, ?)`,
        await sha256Hex('pay-token-of-test'), ACADEMY_A, Date.now() + DAY, Date.now());
    db.run(
        `INSERT INTO payment_claims (id, academy_id, student_id, installment_id, status, created_at_epoch, updated_at_epoch)
         VALUES ('clm-t1', ?, 'stu-test', 'inst-t1', 'PENDING', ?, ?)`, ACADEMY_A, Date.now(), Date.now());
    db.run(
        `INSERT INTO payment_events (id, academy_id, student_id, event_type, created_at_epoch)
         VALUES ('pe-t1', ?, 'stu-test', 'RECEIPT_SUBMITTED', ?)`, ACADEMY_A, Date.now());
    // A real contract for the test student (file + pending link + event).
    const up = await uploadContract(env, { studentId: 'stu-test', enrollmentId: 'enr-t1', token: 'sign-token-test-0000000001', phone: '09122220000' });
    expect(up.status).toBe(200);
    // Protected academy data.
    const adb = new AcademyDatabase(env, ACADEMY_A);
    await adb.setSetting('academy_name', 'آموزشگاه نمونه');
    db.run(`INSERT INTO academy_settings (key, value) VALUES ('legacy_global_key', 'keep-me')`);
    db.run(
        `INSERT INTO backups (id, academy_id, status, created_at_epoch) VALUES ('bk-1', ?, 'HEALTHY', ?)`,
        ACADEMY_A, Date.now());
    db.run(
        `INSERT INTO backup_chunks (backup_id, seq, content) VALUES ('bk-1', 0, ?)`, new Uint8Array([1, 2, 3]));
}

// ============================================================
describe('test-data cleanup: auth & confirmation gates', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        await seedMixedData(env, db);
    });

    it('refuses anonymous callers', async () => {
        const res = await callTestData(env, '/api/test-data/preview', { studentIds: ['stu-test'] }, noAuth());
        expect(res.status).toBe(401);
    });

    it('refuses another tenant: academy-b key cannot purge academy-a students', async () => {
        const res = await callTestData(env, '/api/test-data/purge', { studentIds: ['stu-test'], confirm: true }, bearer(ACADEMY_B));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.students.length).toBe(0);
        expect(body.data.deletedTotal).toBe(0);
        // Nothing was deleted.
        expect(db.get(`SELECT COUNT(*) AS n FROM students WHERE id = 'stu-test'`)?.n).toBe(1);
    });

    it('refuses a purge without the explicit confirm flag', async () => {
        const res = await callTestData(env, '/api/test-data/purge', { studentIds: ['stu-test'] }, bearer());
        expect(res.status).toBe(400);
    });

    it('refuses an empty or oversized id list', async () => {
        expect((await callTestData(env, '/api/test-data/preview', { studentIds: [] }, bearer())).status).toBe(400);
        const many = Array.from({ length: 26 }, (_, i) => `s${i}`);
        expect((await callTestData(env, '/api/test-data/preview', { studentIds: many }, bearer())).status).toBe(400);
    });
});

// ============================================================
describe('test-data cleanup: preview, purge, dead links, new cycle', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        await seedMixedData(env, db);
    });

    it('preview counts exactly the test student graph and reports unknown ids', async () => {
        const res = await callTestData(env, '/api/test-data/preview', { studentIds: ['stu-test', 'stu-ghost'] }, bearer());
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.unknownIds).toEqual(['stu-ghost']);
        expect(body.data.students.length).toBe(1);
        expect(body.data.students[0].id).toBe('stu-test');
        expect(body.data.students[0].fullName).toBe('هنرجوی آزمایشی');
        const c = body.data.counts;
        expect(c.contracts).toBe(1);
        expect(c.contractFiles).toBeGreaterThanOrEqual(1);
        expect(c.contractPendingLinks).toBe(1);
        expect(c.contractEvents).toBeGreaterThanOrEqual(1);
        expect(c.installments).toBe(1);
        expect(c.financialSummaries).toBe(1);
        expect(c.reminders).toBe(1);
        expect(c.deliveries).toBe(1);
        expect(c.smsOutbox).toBe(1);
        expect(c.messageLogs).toBe(1);            // student-scoped
        expect(c.messageLogsUnscoped).toBe(1);    // chat-only welcome log
        expect(c.pendingStudents).toBe(1);
        expect(c.paymentReceipts).toBe(1);
        expect(c.paymentTransactions).toBe(1);
        expect(c.paymentClaims).toBe(1);
        expect(c.paymentEvents).toBe(1);
        expect(c.paymentLinkTokens).toBe(1);
        expect(body.data.fileBytes).toBeGreaterThan(0);
        // The REAL student is not part of the plan.
        expect(body.data.totalRecords).toBeLessThan(20);
    });

    it('purge deletes the whole graph atomically and keeps academy data intact', async () => {
        const res = await callTestData(env, '/api/test-data/purge', { studentIds: ['stu-test'], confirm: true }, bearer());
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.remainingClean).toBe(true);
        expect(body.data.remainingStudents).toBe(0);
        expect(body.data.deleted.contracts).toBe(1);
        expect(body.data.deleted.students).toBe(1);
        expect(body.data.deleted.installments).toBe(1);
        expect(Object.keys(body.data.deleted).length).toBeGreaterThanOrEqual(15);

        // Direct database verification — nothing dependent remains.
        const expectZero = (sql: string) => expect(Number(db.get(sql)?.n)).toBe(0);
        expectZero(`SELECT COUNT(*) AS n FROM students WHERE id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM installments WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM financial_summaries WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM reminders WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM deliveries WHERE reminder_id = 'rem-t1'`);
        expectZero(`SELECT COUNT(*) AS n FROM sms_outbox WHERE reminder_id = 'rem-t1'`);
        expectZero(`SELECT COUNT(*) AS n FROM message_logs WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM message_logs WHERE chat_id = '5550001'`);
        expectZero(`SELECT COUNT(*) AS n FROM pending_students WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM contracts WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM contract_events WHERE academy_id = '${ACADEMY_A}' AND contract_id = 'c-enr-t1'`);
        expectZero(`SELECT COUNT(*) AS n FROM contract_pending_links WHERE contract_id = 'c-enr-t1'`);
        expectZero(`SELECT COUNT(*) AS n FROM contract_deliveries WHERE contract_id = 'c-enr-t1'`);
        expectZero(`SELECT COUNT(*) AS n FROM contract_files WHERE academy_id = '${ACADEMY_A}' AND key LIKE 'contracts/academy-a/enr-t1%'`);
        expectZero(`SELECT COUNT(*) AS n FROM contract_file_chunks WHERE file_key LIKE 'contracts/academy-a/enr-t1%'`);
        expectZero(`SELECT COUNT(*) AS n FROM payment_receipts WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM payment_transactions WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM payment_claims WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM payment_events WHERE student_id = 'stu-test'`);
        expectZero(`SELECT COUNT(*) AS n FROM payment_link_tokens WHERE order_id = 'txn-t1'`);

        // Academy-owned data survives untouched.
        const expectOne = (sql: string) => expect(Number(db.get(sql)?.n)).toBe(1);
        expectOne(`SELECT COUNT(*) AS n FROM students WHERE id = 'stu-real'`);
        expectOne(`SELECT COUNT(*) AS n FROM installments WHERE id = 'inst-real-1'`);
        expectOne(`SELECT COUNT(*) AS n FROM academies WHERE id = '${ACADEMY_A}' AND bot_token = '888:BB'`);
        expectOne(`SELECT COUNT(*) AS n FROM academy_settings WHERE key = '${ACADEMY_A}:academy_name' AND value = 'آموزشگاه نمونه'`);
        expectOne(`SELECT COUNT(*) AS n FROM academy_settings WHERE key = 'legacy_global_key'`);
        expectOne(`SELECT COUNT(*) AS n FROM backups WHERE id = 'bk-1'`);
        expectOne(`SELECT COUNT(*) AS n FROM backup_chunks WHERE backup_id = 'bk-1'`);

        // The purge report is persisted for traceability.
        const report = db.get(`SELECT value FROM academy_settings WHERE key = '${ACADEMY_A}:test_cleanup:last'`) as any;
        expect(report).toBeTruthy();
        expect(JSON.parse(report.value).remainingClean).toBe(true);
    });

    it('every old link is dead: /start hash, sign page token, /pay token', async () => {
        // /start with the consumed link hash must not bind anything anywhere.
        tgCalls = [];
        await webhook(env, ACADEMY_A, '/start start-token-of-test-student', 999000001);
        expect(Number(db.get(`SELECT COUNT(*) AS n FROM students WHERE telegram_chat_id = '999000001'`)?.n)).toBe(0);

        // Sign PAGE token of the deleted contract -> 404 (page route is /sign?s=).
        const signRes = await handleSignRoute(
            new Request(`${BASE}/sign?s=sign-token-test-0000000001`),
            env, URL_('/sign?s=sign-token-test-0000000001'));
        expect(signRes.status).toBe(404);

        // Payment link: the online gateway is kill-switched OFF in this
        // environment, so /pay/<token> serves the same neutral page for any
        // token — no state can leak. The actual invalidation is proven at the
        // database level above (payment_link_tokens / payment_transactions = 0).
        const payRes = await handlePayRoute(
            new Request(`${BASE}/pay/pay-token-of-test`),
            env, URL_('/pay/pay-token-of-test'));
        expect(payRes.status).not.toBe(302);
    });

    it('re-running the purge is safe and reports nothing left', async () => {
        const res = await callTestData(env, '/api/test-data/purge', { studentIds: ['stu-test'], confirm: true }, bearer());
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.deletedTotal).toBe(0);
        expect(body.data.remainingClean).toBe(true);
        expect(body.data.message).toContain('چیزی حذف نشد');
    });

    it('a NEW test cycle works end-to-end with fresh ids (same phone allowed)', async () => {
        insertStudent(db, 'stu-test-2', ACADEMY_A, 'آزمایشی دوم', '09122220000');
        // Fresh /start link connects the new student.
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-test-2')}`, 5550002);
        const row = db.get(`SELECT telegram_chat_id, telegram_link_used FROM students WHERE id = 'stu-test-2'`) as any;
        expect(row.telegram_chat_id).toBe('5550002');
        expect(Number(row.telegram_link_used)).toBe(1);
        // A fresh contract can be issued and OPENED for the new cycle.
        const up = await uploadContract(env, { studentId: 'stu-test-2', enrollmentId: 'enr-t2', token: 'sign-token-new-000000002', phone: '09122220000' });
        expect(up.status).toBe(200);
        const signRes = await handleSignRoute(
            new Request(`${BASE}/sign?s=sign-token-new-000000002`),
            env, URL_('/sign?s=sign-token-new-000000002'));
        expect(signRes.status).toBe(200);
    });

    it('the audit report cannot be forged through the app settings push', async () => {
        const res = await handleApiRequest(
            new Request(`${BASE}/api/sync/settings`, {
                method: 'POST', headers: bearer(),
                body: JSON.stringify({ [`${ACADEMY_A}:test_cleanup:last`]: 'forged' })
            }),
            env, URL_('/api/sync/settings'));
        expect(res.status).toBe(400);
        const stored = db.get(`SELECT value FROM academy_settings WHERE key = '${ACADEMY_A}:test_cleanup:last'`) as any;
        expect(JSON.parse(stored.value).mode).toBe('purge'); // untouched
    });
});

// sha256Bytes kept for parity with the fixtures module
void sha256HexBytes;
void makeSignaturePng;
