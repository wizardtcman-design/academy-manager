// ============================================================
// Mission phase-1: telegram connect link + auto contract sign-link flow
//   1. one link, two accounts  -> exactly one bind, honest "used" answer
//   2. the atomic claim itself (unit)  -> second claim can never win
//   3. auto sign-link after /start  -> same stable token, claimed once
//   4. re-issue clears the auto-send slot  -> a NEW link is auto-sent once
//   5. signed-PDF catch-up after connect  -> exactly one delivery
//   6. GET /api/contracts/:id  -> the REAL current pending signUrl
//   7. sealBakedInPdf  -> the final PDF shows the seal exactly once
//   8. bot menu «📄 قرارداد من»  -> sends the real sign link + button
// ============================================================

import { describe, it, beforeAll, afterAll } from 'vitest';
import { expect } from 'vitest';
import { Env } from '../src/types';
import { AcademyDatabase } from '../src/db';
import { sha256Hex, sha256HexBytes } from '../src/crypto';
import { handleContractsApi, readContractFile } from '../src/handlers/contracts-api';
import { handleSignRoute } from '../src/handlers/sign';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleApiRequest } from '../src/handlers/api';
import {
    makeEnv, makePdf, makeSignaturePng, loadMigrations, ACADEMY_A, API_KEY
} from './helpers/fixtures';
import type { D1Shim } from './helpers/d1-shim';

const BASE = 'https://worker.test';
const DAY = 86400000;

function URL_(path: string): URL {
    return new URL(`${BASE}${path}`);
}
function bearer(academy = ACADEMY_A): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': academy };
}
function tokenFromLink(link: string): string {
    return link.split('?start=')[1] || '';
}

interface TgCall {
    kind: string;
    token: string;
    chatId?: string;
    text?: string;
    replyMarkup?: any;
    fileName?: string;
}
let tgCalls: TgCall[] = [];
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) return realFetch(input as any, init as any);
        const m = /\/bot([^/]+)\/(\w+)/.exec(url);
        const call: TgCall = { kind: m?.[2] || '', token: m?.[1] || '' };
        if (call.kind === 'sendMessage' && init?.body) {
            const body = JSON.parse(init.body);
            call.chatId = String(body.chat_id);
            call.text = body.text;
            call.replyMarkup = body.reply_markup || undefined; // already an object
        }
        if (call.kind === 'sendDocument' && init?.body instanceof FormData) {
            const form = init.body as FormData;
            call.chatId = String(form.get('chat_id') ?? '');
            const doc = form.get('document');
            if (doc && typeof doc === 'object' && 'size' in doc) call.fileName = (doc as File).name;
        }
        tgCalls.push(call);
        if (call.kind === 'getMe') {
            return new Response(JSON.stringify({ ok: true, result: { id: 1, username: 'flow_bot' } }), { status: 200 });
        }
        return new Response(JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }), { status: 200 });
    }) as any;
});
afterAll(() => {
    globalThis.fetch = realFetch;
});

// ---------- local helpers ----------
function connectBot(db: D1Shim, academy: string, username: string, token: string) {
    // No webhook_secret column: the row falls back to env.WEBHOOK_SECRET,
    // which is exactly what the webhook() helper sends (mirrors stage2).
    db.run(
        `INSERT INTO academies (id, name, bot_token, bot_username, bot_connected_at_epoch)
         VALUES (?,?,?,?,?)`,
        academy, 'Academy ' + academy, token, username, Date.now()
    );
}
function insertStudent(db: D1Shim, id: string, academy: string) {
    db.run(
        `INSERT INTO students (id, academy_id, full_name, phone, national_code, created_at_epoch, updated_at_epoch)
         VALUES (?,?,'هنرجو جریان','09120000001','0079123456',?,?)`,
        id, academy, Date.now(), Date.now()
    );
}
async function webhook(env: Env, academy: string, text: string, chatId: number): Promise<Response> {
    return handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Telegram-Bot-Api-Secret-Token': 'test-webhook-secret' },
            body: JSON.stringify({
                update_id: tgCalls.length + 1,
                message: { message_id: 1, chat: { id: chatId }, from: { id: 42, first_name: 'سارا', username: 'sara' }, text }
            })
        }),
        env, URL_(`/webhook/${academy}`)
    );
}
async function mintLink(env: Env, studentId: string, academy = ACADEMY_A): Promise<string> {
    const res = await handleContractsApi(
        new Request(`${BASE}/api/students/${studentId}/telegram-link`, { method: 'POST', headers: bearer(academy) }),
        env, URL_(`/api/students/${studentId}/telegram-link`));
    expect(res.status).toBe(200);
    return tokenFromLink(((await res.json()) as any).data.botLink);
}
interface UploadOpts {
    studentId: string;
    enrollmentId: string;
    token: string;
    contractId?: string;
    phone?: string;
    national?: string;
    sealPng?: Uint8Array;
    sealBakedInPdf?: boolean;
}
async function uploadContract(env: Env, o: UploadOpts): Promise<Response> {
    const form = new FormData();
    form.append('meta', JSON.stringify({
        contractId: o.contractId || `c-${o.enrollmentId}`,
        enrollmentId: o.enrollmentId,
        studentId: o.studentId,
        tokenHash: await sha256Hex(o.token),
        publicToken: o.token,
        expiresAtEpoch: Date.now() + 30 * DAY,
        signerName: '', signerTitle: '',
        sealBakedInPdf: o.sealBakedInPdf === true,
        enrollment: {
            info: { contractNumber: 'CN-9', courseTitle: 'دوره جریان' },
            student: { fullName: 'هنرجو جریان', phone: o.phone ?? '09121234567', nationalCode: o.national ?? '0079123456' }
        }
    }));
    form.append('file', new Blob([makePdf(3) as BlobPart], { type: 'application/pdf' }), 'contract.pdf');
    if (o.sealPng) form.append('seal', new Blob([o.sealPng as BlobPart], { type: 'image/png' }), 'seal.png');
    return handleContractsApi(new Request(`${BASE}/api/contracts`, { method: 'POST', body: form, headers: bearer() }), env, URL_('/api/contracts'));
}
async function signContract(env: Env, token: string, phone = '09121234567', national = '0079123456'): Promise<Response> {
    const png = makeSignaturePng(6, 6);
    return handleSignRoute(
        new Request(`${BASE}/sign/${token}/sign`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                phone, nationalCode: national, accepted: true,
                signatureDataUrl: `data:image/png;base64,${Buffer.from(png).toString('base64')}`
            })
        }), env, URL_(`/sign/${token}/sign`));
}
function signLinkMessages(chatId: number): TgCall[] {
    return tgCalls.filter((c) => c.kind === 'sendMessage' && c.chatId === String(chatId) &&
        (c.text || '').includes('آمادهٔ امضاست'));
}

// ============================================================
describe('mission: one-time atomic /start link', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        connectBot(db, ACADEMY_A, 'flow_bot', '777:AA');
        insertStudent(db, 'stu-f1', ACADEMY_A);
    });

    it('two accounts, one link: the first binds, the second gets the used answer and binds nothing', async () => {
        const token = await mintLink(env, 'stu-f1');
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${token}`, 411000001);
        expect((db.get(`SELECT telegram_chat_id FROM students WHERE id = 'stu-f1'`) as any).telegram_chat_id).toBe('411000001');

        await webhook(env, ACADEMY_A, `/start ${token}`, 411000002);
        const row = db.get(`SELECT telegram_chat_id, telegram_link_used FROM students WHERE id = 'stu-f1'`) as any;
        expect(row.telegram_chat_id).toBe('411000001'); // original binding untouched
        expect(Number(row.telegram_link_used)).toBe(1);
        const last = tgCalls[tgCalls.length - 1];
        expect(last.text).toContain('قبلاً استفاده شده');
        const usedLog = db.get(
            `SELECT status, error_message FROM message_logs WHERE student_id = 'stu-f1' AND message_type = 'START_LINK_USED'`) as any;
        expect(usedLog?.status).toBe('FAILED');
    });

    it('the atomic claim itself: only one concurrent caller can win the same hash', async () => {
        insertStudent(db, 'stu-f1b', ACADEMY_A);
        const token = await mintLink(env, 'stu-f1b');
        const adb = new AcademyDatabase(env, ACADEMY_A);
        const hash = await sha256Hex(token);
        const first = await adb.claimTelegramLink('stu-f1b', '411000010', 'first', hash);
        const second = await adb.claimTelegramLink('stu-f1b', '411000011', 'second', hash);
        expect(first).toBe(true);
        expect(second).toBe(false);
        const row = db.get(`SELECT telegram_chat_id, telegram_link_used FROM students WHERE id = 'stu-f1b'`) as any;
        expect(row.telegram_chat_id).toBe('411000010');
        expect(Number(row.telegram_link_used)).toBe(1);
    });
});

// ============================================================
describe('mission: auto sign-link after connect (idempotent)', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        connectBot(db, ACADEMY_A, 'flow_bot', '777:AA');
        insertStudent(db, 'stu-f2', ACADEMY_A);
    });

    it('/start sends the CURRENT stable sign link exactly once with an inline button', async () => {
        const signToken = 'flow-sign-token-a1b2c3d4e5f6g7h8';
        const up = await uploadContract(env, { studentId: 'stu-f2', enrollmentId: 'enr-f2', token: signToken });
        expect(up.status).toBe(200);

        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f2')}`, 422000001);
        const msgs = signLinkMessages(422000001);
        expect(msgs.length).toBe(1);
        expect(msgs[0].replyMarkup?.inline_keyboard?.[0]?.[0]?.url).toBe(`${BASE}/sign?s=${signToken}`);
        const log = db.get(
            `SELECT status FROM message_logs WHERE student_id = 'stu-f2' AND message_type = 'SIGN_LINK_SENT'`) as any;
        expect(log?.status).toBe('SUCCESS');
        const slot = db.get(
            `SELECT sent_at_epoch FROM contract_deliveries WHERE contract_id = 'c-enr-f2' AND kind = 'SIGN_LINK_TELEGRAM'`) as any;
        expect(Number(slot?.sent_at_epoch)).toBeGreaterThan(0);
    });

    it('a reconnect (fresh manager link) never re-sends the sign link', async () => {
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f2')}`, 422000002);
        expect(signLinkMessages(422000002).length).toBe(0);
        const logs = db.all(
            `SELECT status FROM message_logs WHERE student_id = 'stu-f2' AND message_type = 'SIGN_LINK_SENT' AND status = 'SUCCESS'`);
        expect(logs.length).toBe(1);
    });

    it('re-issuing the contract link resets the slot: the NEW link is auto-sent once', async () => {
        const newToken = 'flow-sign-token-z9y8x7w6v5u4t3s2';
        const up = await uploadContract(env, { studentId: 'stu-f2', enrollmentId: 'enr-f2', token: newToken, contractId: 'c-enr-f2' });
        expect(up.status).toBe(200);
        const status = await (await handleContractsApi(
            new Request(`${BASE}/api/contracts/c-enr-f2`, { headers: bearer() }), env, URL_('/api/contracts/c-enr-f2'))).json() as any;
        expect(status.data.signUrl).toBe(`${BASE}/sign?s=${newToken}`);

        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f2')}`, 422000003);
        const msgs = signLinkMessages(422000003);
        expect(msgs.length).toBe(1);
        expect(msgs[0].replyMarkup?.inline_keyboard?.[0]?.[0]?.url).toBe(`${BASE}/sign?s=${newToken}`);
    });
});

// ============================================================
describe('mission: signed-PDF catch-up after connect', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        connectBot(db, ACADEMY_A, 'flow_bot', '777:AA');
        insertStudent(db, 'stu-f3', ACADEMY_A);
        // A SIGNED contract whose final PDF exists but was never delivered
        // (no chat was linked at signing time).
        const pdf = makePdf(9);
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title, status,
                token_hash, expires_at_epoch, issued_at_epoch, signed_pdf_key, signed_pdf_sha256, created_at_epoch, updated_at_epoch)
             VALUES ('c-f3', ?, 'enr-f3', 'stu-f3', 'هنرجو جریان', 'دوره جریان', 'SIGNED', ?, ?, ?, 'key-f3', ?, ?, ?)`,
            ACADEMY_A, await sha256Hex('flow-signed-token-12345678901'), Date.now() + DAY, Date.now(),
            await sha256HexBytes(pdf), Date.now(), Date.now());
        db.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES ('key-f3', ?, ?, ?, ?)`,
            ACADEMY_A, pdf, await sha256Bytes(pdf), Date.now());
    });

    it('the first /start delivers the final PDF once; reconnects never duplicate it', async () => {
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f3')}`, 433000001);
        expect(tgCalls.filter((c) => c.kind === 'sendDocument' && c.chatId === '433000001').length).toBe(1);
        const slot = db.get(
            `SELECT sent_at_epoch FROM contract_deliveries WHERE contract_id = 'c-f3' AND kind = 'SIGNED_PDF_TELEGRAM'`) as any;
        expect(Number(slot?.sent_at_epoch)).toBeGreaterThan(0);

        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f3')}`, 433000002);
        expect(tgCalls.filter((c) => c.kind === 'sendDocument').length).toBe(0);
    });
});

// ============================================================
describe('mission: status endpoint signUrl + seal dedup + bot menu link', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv(undefined, { PUBLIC_BASE_URL: BASE }));
        connectBot(db, ACADEMY_A, 'flow_bot', '777:AA');
        insertStudent(db, 'stu-f4', ACADEMY_A);
        insertStudent(db, 'stu-f5', ACADEMY_A);
        insertStudent(db, 'stu-f6', ACADEMY_A);
    });

    it('GET /api/contracts/:id carries the real pending signUrl; empty after signing', async () => {
        const token = 'flow-status-token-aabbccddeeffggh';
        await uploadContract(env, { studentId: 'stu-f4', enrollmentId: 'enr-f4a', token });
        let status = await (await handleContractsApi(
            new Request(`${BASE}/api/contracts/c-enr-f4a`, { headers: bearer() }), env, URL_('/api/contracts/c-enr-f4a'))).json() as any;
        expect(status.data.signUrl).toBe(`${BASE}/sign?s=${token}`);

        const signed = await signContract(env, token);
        expect((await signed.json() as any).success).toBe(true);
        status = await (await handleContractsApi(
            new Request(`${BASE}/api/contracts/c-enr-f4a`, { headers: bearer() }), env, URL_('/api/contracts/c-enr-f4a'))).json() as any;
        expect(status.data.status).toBe('SIGNED');
        expect(status.data.signUrl).toBe('');
    });

    it('sealBakedInPdf: the final PDF contains the seal exactly once (not twice)', async () => {
        const seal = makeSignaturePng(10, 10);
        const bakedToken = 'flow-seal-token-baked00000000001';
        const drawnToken = 'flow-seal-token-drawn00000000001';
        await uploadContract(env, {
            studentId: 'stu-f5', enrollmentId: 'enr-f5', token: bakedToken,
            sealPng: seal, sealBakedInPdf: true, phone: '09121234561', national: '0079123451'
        });
        await uploadContract(env, {
            studentId: 'stu-f6', enrollmentId: 'enr-f6', token: drawnToken,
            sealPng: seal, sealBakedInPdf: false, phone: '09121234562', national: '0079123452'
        });

        await signContract(env, bakedToken, '09121234561', '0079123451');
        await signContract(env, drawnToken, '09121234562', '0079123452');

        const baked = await readContractFile(env, 'contracts/academy-a/enr-f5/signed.pdf');
        const drawn = await readContractFile(env, 'contracts/academy-a/enr-f6/signed.pdf');
        expect(baked).toBeTruthy();
        expect(drawn).toBeTruthy();
        const needle = new TextEncoder().encode('/Seal');
        const count = (b: Uint8Array) => {
            let n = 0;
            for (let i = 0; i <= b.length - needle.length; i++) {
                if (b[i] === needle[0] && b[i + 1] === needle[1] && b[i + 2] === needle[2] && b[i + 3] === needle[3] && b[i + 4] === needle[4]) n++;
            }
            return n;
        };
        expect(count(drawn!)).toBeGreaterThan(0);  // seal drawn by the worker
        expect(count(baked!)).toBe(0);             // app already baked it — no second draw
    });

    it('«📄 قرارداد من» sends the real sign link with an inline button for an ISSUED contract', async () => {
        const token = 'flow-menu-token-abc123def456ghij';
        await uploadContract(env, { studentId: 'stu-f5', enrollmentId: 'enr-f5b', token, phone: '09121234561', national: '0079123451' });
        // link chat via the secure connect flow
        await webhook(env, ACADEMY_A, `/start ${await mintLink(env, 'stu-f5')}`, 444000001);
        tgCalls = [];
        await webhook(env, ACADEMY_A, '📄 قرارداد من', 444000001);
        console.log('M3-DEBUG', JSON.stringify(tgCalls.map(c => ({ k: c.kind, chat: c.chatId, t: (c.text || '').slice(0, 60), hasBtn: !!c.replyMarkup?.inline_keyboard }))));
        const msg = tgCalls.find((c) => c.kind === 'sendMessage' && (c.text || '').includes('هنوز امضا نشده است'));
        expect(msg?.replyMarkup?.inline_keyboard?.[0]?.[0]?.url).toBe(`${BASE}/sign?s=${token}`);
        const evs = db.all(
            `SELECT detail FROM contract_events WHERE contract_id = 'c-enr-f5b' AND type = 'SIGN_LINK_SENT'`) as any[];
        // the connect auto-send fires first; the menu adds its own event on top
        expect(evs.map((e) => e.detail)).toContain('telegram:bot-menu');
    });
});

// sha256Bytes (same formula the fixtures use)
async function sha256Bytes(bytes: Uint8Array): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as BufferSource);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
// keep loadMigrations referenced for readability of the setup contract
void loadMigrations;
