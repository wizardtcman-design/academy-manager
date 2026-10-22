// ============================================================
// Stage 2 end-to-end flow tests (vitest, real SQLite + real migrations).
// Telegram Bot API is a stubbed global fetch: every sendMessage/sendDocument
// the worker performs is recorded here, so the whole
// register -> link -> sign -> deliver -> re-receive path is verified without
// any network, any real bot, or any real student data.
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleContractsApi } from '../src/handlers/contracts-api';
import { handleSignRoute, deliverSignedPdfToStudent } from '../src/handlers/sign';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { handleApiRequest } from '../src/handlers/api';
import { sha256Hex } from '../src/crypto';
import { D1Shim } from './helpers/d1-shim';
import {
    makeEnv, makePdf, makeSignaturePng, uploadRequest,
    sha256Bytes, bearer, ACADEMY_A, ACADEMY_B
} from './helpers/fixtures';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);

// ---------- Telegram API recorder ----------
interface TgCall { kind: string; chatId?: string; text?: string; fileName?: string }
let tgCalls: TgCall[] = [];
let telegramDown = false;
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) {
            return realFetch(input as any, init as any);
        }
        if (telegramDown) throw new Error('network down');
        const kind = url.includes('/sendMessage')
            ? 'sendMessage'
            : url.includes('/sendDocument') ? 'sendDocument' : 'other';
        const call: TgCall = { kind };
        try {
            if (kind === 'sendMessage') {
                const body = JSON.parse(init?.body || '{}');
                call.chatId = String(body.chat_id);
                call.text = body.text;
            } else if (kind === 'sendDocument') {
                const form = init?.body as FormData;
                call.chatId = String(form.get('chat_id'));
                const doc = form.get('document') as any;
                call.fileName = doc?.name || 'document';
            }
        } catch (_) {}
        tgCalls.push(call);
        return new Response(
            JSON.stringify({ ok: true, result: { message_id: tgCalls.length } }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
        );
    }) as any;
});

afterAll(() => {
    globalThis.fetch = realFetch;
});

// ---------- helpers ----------
function insertStudent(db: D1Shim, id: string, academy: string, chatId = '', phone = '09120000000') {
    db.run(
        `INSERT INTO students (id, academy_id, student_code, full_name, phone, telegram_chat_id, is_telegram_connected, created_at_epoch, updated_at_epoch)
         VALUES (?,?,?,?,?,?,?,?,?)`,
        id, academy, 'SC-' + id.slice(0, 6), 'هنرجو ' + id, phone, chatId, chatId ? 1 : 0, Date.now(), Date.now()
    );
}

function connectBot(db: D1Shim, academy: string, username: string, token: string) {
    db.run(
        `INSERT INTO academies (id, name, bot_token, bot_username, bot_connected_at_epoch)
         VALUES (?,?,?,?,?)`,
        academy, 'Academy ' + academy, token, username, Date.now()
    );
}

async function uploadContract(
    env: Env,
    opts: { studentId: string; enrollmentId: string; tokenHash: string; token?: string; pdf?: Uint8Array; phone?: string; national?: string; expiresAtEpoch?: number }
): Promise<Response> {
    const req = uploadRequest(BASE, {
        enrollmentId: opts.enrollmentId,
        studentId: opts.studentId,
        tokenHash: opts.tokenHash,
        publicToken: opts.token,
        phone: opts.phone ?? '09121234567',
        nationalCode: opts.national ?? '0079123456',
        expiresAtEpoch: opts.expiresAtEpoch
    }, opts.pdf ?? makePdf(), bearer(ACADEMY_A));
    return handleContractsApi(req, env, URL_('/api/contracts'));
}

async function signContract(env: Env, token: string, phone = '09121234567', national = '0079123456'): Promise<Response> {
    const png = makeSignaturePng();
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

async function webhook(env: Env, academy: string, text: string, chatId: number): Promise<Response> {
    return handleTelegramWebhook(
        new Request(`${BASE}/webhook/${academy}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                update_id: tgCalls.length + 1,
                message: {
                    message_id: 1,
                    chat: { id: chatId },
                    from: { id: 42, first_name: 'سارا', username: 'sara' },
                    text
                }
            })
        }),
        env, URL_(`/webhook/${academy}`)
    );
}

function tokenFromLink(link: string): string {
    return link.split('?start=')[1] || '';
}

// ============================================================
// 1. Secure /start link token endpoint
// ============================================================
describe('secure telegram link token (Stage 2)', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv());
        connectBot(db, ACADEMY_A, 'academy_a_bot', '111:AA');
        insertStudent(db, 'stu-link', ACADEMY_A);
    });

    it('refuses the link endpoint without a bearer key', async () => {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-link/telegram-link`, { method: 'POST' }),
            env, URL_('/api/students/stu-link/telegram-link'));
        expect(res.status).toBe(401);
    });

    it('issues a one-time token link and stores ONLY its sha-256', async () => {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-link/telegram-link`, {
                method: 'POST', headers: bearer(ACADEMY_A)
            }),
            env, URL_('/api/students/stu-link/telegram-link'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        const link: string = body.data.botLink;
        expect(link).toMatch(/^https:\/\/t\.me\/academy_a_bot\?start=[A-Za-z0-9_-]{20,64}$/);

        const token = tokenFromLink(link);
        const row = db.get(
            'SELECT telegram_link_hash, telegram_link_expires FROM students WHERE id = ?', 'stu-link') as any;
        expect(row.telegram_link_hash).toBe(await sha256Hex(token));
        expect(row.telegram_link_expires).toBeGreaterThan(Date.now());
    });

    it('rotates the token: a second issue invalidates the first link', async () => {
        const r1 = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-link/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/stu-link/telegram-link'));
        const first = tokenFromLink(((await r1.json()) as any).data.botLink);
        const r2 = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-link/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/stu-link/telegram-link'));
        const second = tokenFromLink(((await r2.json()) as any).data.botLink);
        expect(second).not.toBe(first);
        const row = db.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-link') as any;
        expect(row.telegram_link_hash).toBe(await sha256Hex(second));
    });

    it('unknown student is a plain 404', async () => {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/nope/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/nope/telegram-link'));
        expect(res.status).toBe(404);
    });
});

// ============================================================
// 2. /start webhook security
// ============================================================
describe('/start secure identification (one-time token)', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv());
        connectBot(db, ACADEMY_A, 'academy_a_bot', '111:AA');
        connectBot(db, ACADEMY_B, 'academy_b_bot', '222:BB');
        insertStudent(db, 'stu-a1', ACADEMY_A);
        insertStudent(db, 'stu-a2', ACADEMY_A);
        insertStudent(db, 'stu-b1', ACADEMY_B);
    });

    async function mintLink(studentId: string): Promise<string> {
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/${studentId}/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_(`/api/students/${studentId}/telegram-link`));
        return tokenFromLink(((await res.json()) as any).data.botLink);
    }

    it('a raw student id (even an existing one) is refused and never linked', async () => {
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start stu-a1`, 111000001);
        const bound = db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-a1') as any;
        expect(bound.telegram_chat_id).toBe('');
        const last = tgCalls[tgCalls.length - 1];
        expect(last.kind).toBe('sendMessage');
        expect(last.text).toContain('لینک اختصاصی شخصی');
    });

    it('a valid token binds the chat, greets the student and is consumed', async () => {
        tgCalls = [];
        const token = await mintLink('stu-a1');
        const res = await webhook(env, ACADEMY_A, `/start ${token}`, 111000002);
        expect(res.status).toBe(200);
        const row = db.get('SELECT telegram_chat_id, telegram_link_hash FROM students WHERE id = ?', 'stu-a1') as any;
        expect(row.telegram_chat_id).toBe('111000002');
        expect(row.telegram_link_hash).toBe(''); // single-use: burned
        const doc = tgCalls.find((c) => c.kind === 'sendMessage' && c.chatId === '111000002');
        expect(doc?.text).toContain('stu-a1'.length ? 'اتصال حساب تلگرام' : '');
    });

    it('the same token cannot be reused (second /start is refused)', async () => {
        tgCalls = [];
        const token = await mintLink('stu-a2');
        await webhook(env, ACADEMY_A, `/start ${token}`, 111000003);
        expect((db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-a2') as any).telegram_chat_id)
            .toBe('111000003');
        // burn happened; replaying the same token binds nothing new
        await webhook(env, ACADEMY_A, `/start ${token}`, 111000099);
        const hijack = db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-a2') as any;
        expect(hijack.telegram_chat_id).toBe('111000003');
    });

    it("another academy's token is refused (tenant scoping)", async () => {
        tgCalls = [];
        const tokenB = await (async () => {
            const res = await handleContractsApi(
                new Request(`${BASE}/api/students/stu-b1/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_B) }),
                env, URL_('/api/students/stu-b1/telegram-link'));
            return tokenFromLink(((await res.json()) as any).data.botLink);
        })();
        await webhook(env, ACADEMY_A, `/start ${tokenB}`, 111000004);
        const row = db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-b1') as any;
        expect(row.telegram_chat_id).toBe('');
    });

    it('an expired token is refused with the expiry message', async () => {
        const token = await mintLink('stu-a2'); // fresh token for this student
        db.run('UPDATE students SET telegram_link_expires = ? WHERE id = ?', Date.now() - 1000, 'stu-a2');
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${token}`, 111000005);
        const last = tgCalls[tgCalls.length - 1];
        expect(last.kind).toBe('sendMessage');
        expect(last.text).toContain('مهلت این لینک اتصال به پایان رسیده');
        // and nothing was bound by it
        expect((db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-a2') as any).telegram_chat_id)
            .toBe('111000003');
    });

    it('a chat already bound to one student cannot grab another student', async () => {
        const token = await mintLink('stu-a2');
        tgCalls = [];
        await webhook(env, ACADEMY_A, `/start ${token}`, 111000002); // chat of stu-a1
        const last = tgCalls[tgCalls.length - 1];
        expect(last.text).toContain('متصل شده است');
        // stu-a2 keeps its original chat
        expect((db.get('SELECT telegram_chat_id FROM students WHERE id = ?', 'stu-a2') as any).telegram_chat_id)
            .toBe('111000003');
    });
});

// ============================================================
// 3. Welcome message + disabled SMS outbox
// ============================================================
describe('welcome path (SMS prepared, never sent)', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => {
        ({ env, db } = makeEnv());
        connectBot(db, ACADEMY_A, 'academy_a_bot', '111:AA');
        insertStudent(db, 'stu-w1', ACADEMY_A, '', '09121112233');
    });

    it('queues the welcome SMS as DISABLED with the personal link, sends nothing', async () => {
        tgCalls = [];
        const before = tgCalls.length;
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-w1/welcome`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/stu-w1/welcome'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.sms.status).toBe('DISABLED');
        expect(body.data.deduped).toBe(false);
        expect(body.data.botLink).toMatch(/^https:\/\/t\.me\/academy_a_bot\?start=/);
        expect(body.data.message).toContain(body.data.botLink);
        expect(tgCalls.length).toBe(before); // not a single telegram call

        const outbox = db.get('SELECT * FROM sms_outbox WHERE id = ?', 'welcome-stu-w1') as any;
        expect(outbox.status).toBe('DISABLED');
        expect(outbox.phone).toBe('09121112233');
        expect(outbox.body).toBe(body.data.message);
    });

    it('a second call dedupes and PRESERVES the queued link (no token rotation)', async () => {
        const firstBody = (db.get('SELECT body FROM sms_outbox WHERE id = ?', 'welcome-stu-w1') as any).body;
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-w1/welcome`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/stu-w1/welcome'));
        const body = await res.json() as any;
        expect(body.data.deduped).toBe(true);
        expect(body.data.message).toBe(firstBody);
        expect((db.get('SELECT COUNT(*) AS n FROM sms_outbox WHERE id = ?', 'welcome-stu-w1') as any).n).toBe(1);
    });

    it('without a connected bot the endpoint refuses instead of fabricating a link', async () => {
        const { env: env2, db: db2 } = makeEnv();
        insertStudent(db2, 'stu-nobot', ACADEMY_A);
        const hashBefore = (db2.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-nobot') as any)
            ?.telegram_link_hash ?? '';
        const res = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-nobot/welcome`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env2, URL_('/api/students/stu-nobot/welcome'));
        expect(res.status).toBe(409);
        const hashAfter = (db2.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-nobot') as any)
            ?.telegram_link_hash ?? '';
        expect(hashAfter).toBe(hashBefore); // no token burned for a dead end
    });
});

// ============================================================
// 4. Sign -> automatic Telegram delivery of the final PDF
// ============================================================
describe('signed PDF auto-delivery + bot re-receive', () => {
    let env: Env; let db: D1Shim;
    const SIGN_TOKEN = 'stage2-sign-token-aaaaaaaaaa'; // 43 chars url-safe
    let tokenHashMain = '';

    beforeAll(async () => {
        ({ env, db } = makeEnv());
        connectBot(db, ACADEMY_A, 'academy_a_bot', '111:AA');
        insertStudent(db, 'stu-d1', ACADEMY_A, '555000777'); // linked chat
        tokenHashMain = await sha256Hex(SIGN_TOKEN);
        await uploadContract(env, {
            studentId: 'stu-d1', enrollmentId: 'enr-d1', tokenHash: tokenHashMain, token: SIGN_TOKEN
        });
    });

    it('seal + manager signature uploads are stored with their standard digest', async () => {
        // upload again WITH seal/signature parts on a fresh enrollment
        const form = new FormData();
        const png = makeSignaturePng();
        form.append('meta', JSON.stringify({
            contractId: '', enrollmentId: 'enr-d2', studentId: 'stu-d1',
            tokenHash: await sha256Hex('stage2-seal-token-bbbbbbbbbb'), publicToken: '',
            expiresAtEpoch: Date.now() + 86400000, signerName: 'مدیر', signerTitle: 'مدیر آموزشگاه',
            enrollment: { info: {}, student: { fullName: 'هنرجو d1', phone: '09121234567', nationalCode: '0079123456' } }
        }));
        form.append('file', new Blob([makePdf(11) as BlobPart], { type: 'application/pdf' }), 'c.pdf');
        form.append('seal', new Blob([png as BlobPart], { type: 'image/png' }), 'seal.png');
        form.append('signature', new Blob([png as BlobPart], { type: 'image/png' }), 'sig.png');
        const res = await handleContractsApi(
            new Request(`${BASE}/api/contracts`, { method: 'POST', body: form, headers: bearer(ACADEMY_A) }),
            env, URL_('/api/contracts'));
        expect(res.status).toBe(200);
        const row = db.get('SELECT seal_key, seal_sha256, sig_key, sig_sha256 FROM contracts WHERE enrollment_id = ?', 'enr-d2') as any;
        expect(row.seal_key).toContain('/seal.png');
        expect(row.seal_sha256).toBe(await sha256Bytes(png));
        expect(row.sig_sha256).toBe(await sha256Bytes(png));
        const stored = db.get('SELECT sha256 FROM contract_files WHERE key = ?', row.seal_key) as any;
        expect(stored.sha256).toBe(await sha256Bytes(png));
    });

    it('signing delivers the final PDF to the student chat exactly once', async () => {
        tgCalls = [];
        const open = await handleSignRoute(
            new Request(`${BASE}/sign/${SIGN_TOKEN}/open`, { method: 'POST' }), env, URL_(`/sign/${SIGN_TOKEN}/open`));
        expect(open.status).toBe(200);

        const sign = await signContract(env, SIGN_TOKEN);
        const signBody = await sign.json() as any;
        expect(sign.status, JSON.stringify(signBody)).toBe(200);
        expect(signBody.data.status).toBe('SIGNED');

        const docs = tgCalls.filter((c) => c.kind === 'sendDocument');
        expect(docs.length).toBe(1);
        expect(docs[0].chatId).toBe('555000777');
        expect(docs[0].fileName).toMatch(/^contract-.+\.pdf$/);

        const row = db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-d1') as any;
        const delivery = db.get('SELECT sent_at_epoch FROM contract_deliveries WHERE key = ?', `SIGNED_PDF_TELEGRAM:${row.id}`) as any;
        expect(Number(delivery.sent_at_epoch)).toBeGreaterThan(0);
        const ev = db.get(
            "SELECT type, detail FROM contract_events WHERE contract_id = ? AND type = 'PDF_SENT'", row.id) as any;
        expect(ev.detail).toBe('telegram:auto-after-sign');
    });

    it('signing again is refused (409) and sends nothing more', async () => {
        tgCalls = [];
        const res = await signContract(env, SIGN_TOKEN);
        expect(res.status).toBe(409);
        expect(((await res.json()) as any).alreadySigned).toBe(true);
        expect(tgCalls.filter((c) => c.kind === 'sendDocument').length).toBe(0);
    });

    it('a duplicate delivery attempt claims nothing (idempotent)', async () => {
        const row = db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-d1') as any;
        tgCalls = [];
        const bytes = new Uint8Array(10);
        await deliverSignedPdfToStudent(env, {
            academy_id: ACADEMY_A, id: row.id, student_id: 'stu-d1', course_title: ''
        } as any, bytes);
        expect(tgCalls.filter((c) => c.kind === 'sendDocument').length).toBe(0);
    });

    it('telegram outage: signing still succeeds, failure is recorded, no crash', async () => {
        const T2 = 'stage2-down-token-cccccccccc';
        await uploadContract(env, {
            studentId: 'stu-d1', enrollmentId: 'enr-down',
            tokenHash: await sha256Hex(T2), token: T2
        });
        await handleSignRoute(new Request(`${BASE}/sign/${T2}/open`, { method: 'POST' }), env, URL_(`/sign/${T2}/open`));
        telegramDown = true;
        tgCalls = [];
        const res = await signContract(env, T2);
        telegramDown = false;
        expect(res.status).toBe(200);
        const row = db.get('SELECT id FROM contracts WHERE enrollment_id = ?', 'enr-down') as any;
        const failEv = db.get(
            "SELECT type FROM contract_events WHERE contract_id = ? AND type = 'DELIVERY_FAILED'", row.id) as any;
        expect(failEv).toBeTruthy();
        const dlv = db.get('SELECT sent_at_epoch FROM contract_deliveries WHERE key = ?', `SIGNED_PDF_TELEGRAM:${row.id}`) as any;
        expect(Number(dlv.sent_at_epoch)).toBe(-1); // permanently skipped; bot menu is the retry
    });

    it('bot menu "📄 قرارداد من" re-receives the same final PDF (user-initiated, not deduped)', async () => {
        tgCalls = [];
        await webhook(env, ACADEMY_A, '📄 قرارداد من', 555000777);
        const docs = tgCalls.filter((c) => c.kind === 'sendDocument');
        expect(docs.length).toBe(1);
        expect(docs[0].chatId).toBe('555000777');
        expect(docs[0].fileName).toMatch(/^contract-.+\.pdf$/);
        // menu event recorded (whichever signed contract the student's chat holds)
        const ev = db.get(
            "SELECT COUNT(*) AS n FROM contract_events WHERE academy_id = ? AND type = 'PDF_SENT' AND detail = 'telegram:bot-menu'", ACADEMY_A) as any;
        expect(ev.n).toBeGreaterThanOrEqual(1);
    });

    it('an unlinked student gets no auto-send; after /start the menu delivers', async () => {
        const T3 = 'stage2-late-token-dddddddddd';
        insertStudent(db, 'stu-d2', ACADEMY_A); // no chat yet
        await uploadContract(env, {
            studentId: 'stu-d2', enrollmentId: 'enr-late',
            tokenHash: await sha256Hex(T3), token: T3
        });
        await handleSignRoute(new Request(`${BASE}/sign/${T3}/open`, { method: 'POST' }), env, URL_(`/sign/${T3}/open`));
        tgCalls = [];
        const res = await signContract(env, T3);
        expect(res.status).toBe(200);
        expect(tgCalls.filter((c) => c.kind === 'sendDocument').length).toBe(0);

        // student links via the secure one-time link, then asks for the file
        const linkRes = await handleContractsApi(
            new Request(`${BASE}/api/students/stu-d2/telegram-link`, { method: 'POST', headers: bearer(ACADEMY_A) }),
            env, URL_('/api/students/stu-d2/telegram-link'));
        const token = tokenFromLink(((await linkRes.json()) as any).data.botLink);
        await webhook(env, ACADEMY_A, `/start ${token}`, 555000888);
        tgCalls = [];
        await webhook(env, ACADEMY_A, '📄 قرارداد من', 555000888);
        const docs = tgCalls.filter((c) => c.kind === 'sendDocument');
        expect(docs.length).toBe(1);
        expect(docs[0].chatId).toBe('555000888');
    });
});

// ============================================================
// 5. Creation / link dedupe + expiry (regression of req 1, 2, 11)
// ============================================================
describe('duplicate + expiry guarantees', () => {
    let env: Env; let db: D1Shim;
    beforeAll(() => { ({ env, db } = makeEnv()); });

    it('uploading the same enrollment twice keeps ONE contract with the same id', async () => {
        const h = await sha256Hex('dup-token-eeeeeeeeeeeeeeeeee');
        const r1 = await uploadContract(env, {
            studentId: 'stu-dup', enrollmentId: 'enr-dup', tokenHash: h, token: 'dup-token-eeeeeeeeeeeeeeeeee'
        });
        const r2 = await uploadContract(env, {
            studentId: 'stu-dup', enrollmentId: 'enr-dup', tokenHash: h, token: 'dup-token-eeeeeeeeeeeeeeeeee'
        });
        const b1 = await r1.json() as any;
        const b2 = await r2.json() as any;
        expect(b1.data.created).toBe(true);
        expect(b2.data.created).toBe(false);
        expect(b2.data.id).toBe(b1.data.id);
        expect((db.get('SELECT COUNT(*) AS n FROM contracts WHERE enrollment_id = ?', 'enr-dup') as any).n).toBe(1);
    });

    it('an expired contract link cannot be opened or signed (410)', async () => {
        const T = 'stage2-exp-token-ffffffffff';
        await uploadContract(env, {
            studentId: 'stu-exp', enrollmentId: 'enr-exp',
            tokenHash: await sha256Hex(T), token: T, expiresAtEpoch: Date.now() - 5000
        });
        const open = await handleSignRoute(
            new Request(`${BASE}/sign/${T}/open`, { method: 'POST' }), env, URL_(`/sign/${T}/open`));
        expect(open.status).toBe(410);
        const sign = await signContract(env, T);
        expect(sign.status).toBe(410);
    });

    it('sync stores the app token hash and preserves it when omitted later', async () => {
        const auth = { Authorization: 'Bearer test-key-123' };
        const hash = await sha256Hex('app-side-token-gggggggggg');
        const r1 = await handleApiRequest(
            new Request(`${BASE}/api/sync/student`, {
                method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    id: 'stu-sync', fullName: 'هنرجو سینک',
                    telegramLinkHash: hash, telegramLinkExpires: 0
                })
            }), env, URL_('/api/sync/student'));
        expect(r1.status).toBe(200);
        expect((db.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-sync') as any).telegram_link_hash)
            .toBe(hash);
        // a later sync WITHOUT the hash keeps the stored one
        await handleApiRequest(
            new Request(`${BASE}/api/sync/student`, {
                method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: 'stu-sync', fullName: 'هنرجو سینک' })
            }), env, URL_('/api/sync/student'));
        expect((db.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-sync') as any).telegram_link_hash)
            .toBe(hash);
    });

    it('legacy /api/generate-link now returns a one-time token link, never the raw id', async () => {
        insertStudent(db, 'stu-gen', 'default');
        const res = await handleApiRequest(
            new Request(`${BASE}/api/generate-link`, {
                method: 'POST', headers: { Authorization: 'Bearer test-key-123', 'Content-Type': 'application/json' },
                body: JSON.stringify({ studentId: 'stu-gen', botUsername: 'legacy_bot' })
            }), env, URL_('/api/generate-link'));
        expect(res.status).toBe(200);
        const body = await res.json() as any;
        expect(body.data.link).toMatch(/^https:\/\/t\.me\/legacy_bot\?start=[A-Za-z0-9_-]{20,64}$/);
        expect(body.data.link).not.toContain('stu-gen');
        const token = body.data.link.split('?start=')[1];
        expect((db.get('SELECT telegram_link_hash FROM students WHERE id = ?', 'stu-gen') as any).telegram_link_hash)
            .toBe(await sha256Hex(token));
    });
});
