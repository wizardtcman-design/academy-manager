// ============================================================
// Stage 3: all six Telegram student menu buttons, end to end.
//
// The data is pushed through the SAME authenticated API routes the
// Android app uses (students-batch / installments / financials /
// settings), the chat is linked through the secure ONE-TIME /start
// token (never a raw student id), and each button is then pressed as
// a real Telegram keyboard message. Outgoing bot traffic is recorded,
// so every assertion below is about what the STUDENT would actually
// receive:
//   1. 💰 وضعیت مالی      → the pushed financial summary (real numbers)
//   2. 📅 اقساط من        → every pushed installment with paid/pending/overdue
//   3. 🧾 رسیدهای من      → itemized receipts built ONLY from paid installments
//   4. 📄 قرارداد من      → the REAL stored signed PDF bytes, or honest guidance
//   5. 📞 تماس با آموزشگاه → the academy's OWN pushed contact info (per academy)
//   6. 🔄 بروزرسانی اطلاعات → refreshed identity + installment counts
// Negative cases: unlinked chats, cross-academy settings isolation and
// legacy (pre-scoping) setting rows keep working but never leak across.
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { handleTelegramWebhook } from '../src/handlers/webhook';
import { sha256Hex, sha256HexBytes } from '../src/crypto';
import { D1Shim } from './helpers/d1-shim';
import {
    makeEnv, makePdf, sha256Bytes,
    ACADEMY_A, ACADEMY_B, API_KEY
} from './helpers/fixtures';
import type { Env } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);
const DAY = 86_400_000;

const TOKEN_A = '111111:AAA_test_token_for_academy_a_0001';
const TOKEN_B = '222222:BBB_test_token_for_academy_b_0002';

// ---------- Telegram API recorder ----------
interface TgCall {
    kind: string;
    token: string;
    chatId?: string;
    text?: string;
    fileName?: string;
    byteLength?: number;
}
let tgCalls: TgCall[] = [];
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.telegram.org')) {
            return realFetch(input as any, init as any);
        }
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
            }
        }
        tgCalls.push(call);
        if (call.kind === 'getMe') {
            return new Response(JSON.stringify({ ok: true, result: { id: 999, username: 'menu_bot', first_name: 'Menu' } }), { status: 200 });
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

/** Simulates one Telegram keyboard button press (or /start) from a chat. */
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

/** The last text message the bot sent to this chat. */
function lastTextTo(chatId: number | string): string {
    const hits = tgCalls.filter((c) => c.kind === 'sendMessage' && c.chatId === String(chatId));
    return hits.length ? (hits[hits.length - 1].text || '') : '';
}

function docsTo(chatId: number | string): TgCall[] {
    return tgCalls.filter((c) => c.kind === 'sendDocument' && c.chatId === String(chatId));
}

/**
 * The same flow the real app performs: push the student batch, then
 * generate a one-time link and bind the chat by messaging /start <token>.
 */
async function linkChat(env: Env, db: D1Shim, academy: string, studentId: string, chatId: number): Promise<void> {
    const res = await api(env, 'POST', '/api/generate-link', { studentId }, auth(academy));
    expect(res.status).toBe(200);
    const link = ((await res.json() as any).data?.link) || '';
    expect(link).toMatch(/^https:\/\/t\.me\/.+?\?start=[A-Za-z0-9_-]{20,64}$/);
    tgCalls.length = 0;
    await press(env, academy, chatId, '/start ' + link.split('?start=')[1]);
    expect(lastTextTo(chatId)).toContain('اتصال حساب تلگرام شما');
    const row = db.get(`SELECT telegram_chat_id FROM students WHERE id = ?`, studentId) as any;
    expect(String(row?.telegram_chat_id)).toBe(String(chatId));
    tgCalls.length = 0;
}

// ============================================================
// 1. The happy path: every button answers with the academy's pushed data
// ============================================================
describe('six menu buttons answer with real pushed data (academy A)', () => {
    let env: Env; let db: D1Shim;
    const PDF = makePdf();

    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        connectAcademyBot(db, ACADEMY_B, 'bot_b', TOKEN_B);

        // --- push through the same API routes the Android app calls ---
        expect((await api(env, 'POST', '/api/sync/students-batch', {
            students: [
                { id: 'stu-menu', fullName: 'دانا محمدی', studentCode: 'SC-901', phone: '۰۹۱۲۰۰۰۰۰۰۰' },
                { id: 'stu-issued', fullName: 'سپیده اکبری', studentCode: 'SC-902' },
                { id: 'stu-nodata', fullName: 'نیما کریمی', studentCode: 'SC-903' },
                { id: 'stu-b', fullName: 'پرویز رضایی', studentCode: 'SC-904' }
            ]
        }, auth(ACADEMY_A))).status).toBe(200);

        expect((await api(env, 'POST', '/api/sync/installments', {
            installments: [
                { id: 'ins-1', student_id: 'stu-menu', installment_number: 1, total_installments: 3, course_title: 'ICDL',
                  amount: 2000000, paid_amount: 2000000, remaining_amount: 0,
                  due_date_shamsi: '۱۴۰۴/۰۴/۰۱', due_date_epoch: Date.now() - 10 * DAY,
                  is_paid: 1, paid_date_shamsi: '۱۴۰۴/۰۵/۱۲', updated_at_epoch: Date.now() },
                { id: 'ins-2', student_id: 'stu-menu', installment_number: 2, total_installments: 3, course_title: 'ICDL',
                  amount: 1000000, paid_amount: 0, remaining_amount: 1000000,
                  due_date_shamsi: '۱۴۰۴/۰۸/۰۱', due_date_epoch: Date.now() + 20 * DAY,
                  is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now() },
                { id: 'ins-3', student_id: 'stu-menu', installment_number: 3, total_installments: 3, course_title: 'طراحی',
                  amount: 1000000, paid_amount: 0, remaining_amount: 1000000,
                  due_date_shamsi: '۱۴۰۴/۰۶/۱۰', due_date_epoch: Date.now() - 3 * DAY,
                  is_paid: 0, paid_date_shamsi: '', updated_at_epoch: Date.now() }
            ]
        }, auth(ACADEMY_A))).status).toBe(200);

        expect((await api(env, 'POST', '/api/sync/financials', {
            summaries: [{
                student_id: 'stu-menu', total_tuition: 4000000, total_paid: 2000000, remaining_debt: 2000000,
                active_courses_count: 2, courses_summary: 'ICDL، طراحی', settlement_status: 'PENDING',
                updated_at_epoch: Date.now()
            }]
        }, auth(ACADEMY_A))).status).toBe(200);

        expect((await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی موسیقی آوا', phone: '۰۲۱-۱۲۳۴۵۶۷۸',
            address: 'تهران، خیابان آوا، پلاک ۷', currency_unit: 'تومان'
        }, auth(ACADEMY_A))).status).toBe(200);

        // --- a SIGNED contract with a REAL stored PDF for stu-menu ---
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title, status,
                token_hash, expires_at_epoch, issued_at_epoch, signed_pdf_key, signed_pdf_sha256,
                created_at_epoch, updated_at_epoch)
             VALUES ('c-signed', ?, 'enr-1', 'stu-menu', 'دانا محمدی', 'ICDL', 'SIGNED', ?, ?, ?, 'key-signed-1', ?, ?, ?)`,
            ACADEMY_A, await sha256Hex('token-signed-unique-1'), Date.now() + DAY, Date.now(),
            await sha256HexBytes(PDF), Date.now(), Date.now()
        );
        db.run(
            `INSERT INTO contract_files (key, academy_id, content, sha256, created_at_epoch) VALUES ('key-signed-1', ?, ?, ?, ?)`,
            ACADEMY_A, PDF, await sha256Bytes(PDF), Date.now()
        );
        // --- an ISSUED-only contract for stu-issued ---
        db.run(
            `INSERT INTO contracts (id, academy_id, enrollment_id, student_id, student_name, course_title, status,
                token_hash, expires_at_epoch, issued_at_epoch, created_at_epoch, updated_at_epoch)
             VALUES ('c-issued', ?, 'enr-2', 'stu-issued', 'سپیده اکبری', 'ICDL', 'ISSUED', ?, ?, ?, ?, ?)`,
            ACADEMY_A, await sha256Hex('token-issued-unique-2'), Date.now() + DAY, Date.now(), Date.now(), Date.now()
        );

        await linkChat(env, db, ACADEMY_A, 'stu-menu', 701);
        await linkChat(env, db, ACADEMY_A, 'stu-issued', 704);
        await linkChat(env, db, ACADEMY_A, 'stu-nodata', 703);
    });

    it('💰 وضعیت مالی shows the pushed summary, course list and currency', async () => {
        await press(env, ACADEMY_A, 701, '💰 وضعیت مالی');
        const text = lastTextTo(701);
        expect(text).toContain('وضعیت مالی');
        expect(text).toContain('دانا محمدی');
        expect(text).toContain('SC-901');
        expect(text).toContain('ICDL، طراحی');
        expect(text).toContain('4,000,000'); // tuition
        expect(text).toContain('2,000,000'); // paid == remaining
        expect(text).toContain('تومان');
    });

    it('📅 اقساط من lists every installment with paid / pending / overdue status', async () => {
        await press(env, ACADEMY_A, 701, '📅 اقساط من');
        const text = lastTextTo(701);
        expect(text).toContain('کل اقساط: 3');
        expect(text).toContain('1 پرداخت‌شده | 2 مانده');
        expect(text).toContain('✅ پرداخت شده');          // ins-1
        expect(text).toContain('در انتظار پرداخت');       // ins-2
        expect(text).toContain('سررسید گذشته');           // ins-3
        expect(text).toContain('۱۴۰۴/۰۶/۱۰');             // overdue due date
        expect(text).toContain('قسط شماره 2 از 3');
    });

    it('🧾 رسیدهای من itemizes ONLY the paid installments with paid dates', async () => {
        await press(env, ACADEMY_A, 701, '🧾 رسیدهای من');
        const text = lastTextTo(701);
        expect(text).toContain('پرداخت‌های ثبت‌شده (1 مورد)');
        expect(text).toContain('قسط 1 از 3');
        expect(text).toContain('2,000,000');
        expect(text).toContain('۱۴۰۴/۰۵/۱۲'); // paid date
        // unpaid installment amounts must NOT be presented as receipts
        expect(text).not.toContain('1,000,000');
    });

    it('📄 قرارداد من sends the REAL stored signed PDF and logs PDF_SENT', async () => {
        await press(env, ACADEMY_A, 701, '📄 قرارداد من');
        const docs = docsTo(701);
        expect(docs.length).toBe(1);
        expect(docs[0].token).toBe(TOKEN_A);          // sent through the academy's OWN bot
        expect(docs[0].fileName).toBe('contract-c-signed.pdf');
        expect(docs[0].byteLength).toBe(PDF.length);  // the actual stored bytes
        expect(lastTextTo(701)).toContain('کد قرارداد');
        const ev = db.get(`SELECT type, detail FROM contract_events WHERE contract_id = 'c-signed' AND type = 'PDF_SENT'`) as any;
        expect(ev?.detail).toBe('telegram:bot-menu');
    });

    it('📄 قرارداد من for an unsigned (ISSUED) contract explains the next step', async () => {
        await press(env, ACADEMY_A, 704, '📄 قرارداد من');
        expect(lastTextTo(704)).toContain('هنوز امضا نشده است');
        expect(docsTo(704).length).toBe(0);           // nothing signed to send
    });

    it('📄 قرارداد من for a student without any contract gives honest guidance', async () => {
        await press(env, ACADEMY_A, 703, '📄 قرارداد من');
        expect(lastTextTo(703)).toContain('دفتر آموزشگاه');
        expect(docsTo(703).length).toBe(0);
    });

    it('📞 تماس با آموزشگاه shows the academy’s OWN pushed info, not hardcoded defaults', async () => {
        await press(env, ACADEMY_A, 701, '📞 تماس با آموزشگاه');
        const text = lastTextTo(701);
        expect(text).toContain('آکادمی موسیقی آوا');
        expect(text).toContain('۰۲۱-۱۲۳۴۵۶۷۸');
        expect(text).toContain('تهران، خیابان آوا، پلاک ۷');
        expect(text).not.toContain('پیشگامان');
    });

    it('🔄 بروزرسانی اطلاعات re-reads identity and installment counts', async () => {
        await press(env, ACADEMY_A, 701, '🔄 بروزرسانی اطلاعات');
        const text = lastTextTo(701);
        expect(text).toContain('به‌روزرسانی شد');
        expect(text).toContain('دانا محمدی');
        expect(text).toContain('SC-901');
        expect(text).toContain('2,000,000');
        expect(text).toContain('3 ثبت‌شده (1 پرداخت‌شده | 2 مانده)');
    });
});

// ============================================================
// 2. Receipts empty state
// ============================================================
describe('🧾 رسیدهای من with nothing paid yet', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-empty', fullName: 'کنارک صادقی', studentCode: 'SC-70' }]
        }, auth(ACADEMY_A));
        await linkChat(env, db, ACADEMY_A, 'stu-empty', 705);
    });

    it('says honestly that no payment is recorded yet', async () => {
        await press(env, ACADEMY_A, 705, '🧾 رسیدهای من');
        expect(lastTextTo(705)).toContain('هنوز پرداختی در سامانه برای شما ثبت نشده است');
    });
});

// ============================================================
// 3. Every button refuses an unlinked chat
// ============================================================
describe('an unlinked chat gets the linking warning for every button', () => {
    let env: Env;
    beforeAll(async () => {
        const made = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' });
        env = made.env;
        connectAcademyBot(made.db, ACADEMY_A, 'bot_a', TOKEN_A);
    });

    it('never answers a menu press with financial data', async () => {
        const buttons = ['💰 وضعیت مالی', '📅 اقساط من', '🧾 رسیدهای من', '📄 قرارداد من', '📞 تماس با آموزشگاه', '🔄 بروزرسانی اطلاعات'];
        for (const b of buttons) {
            await press(env, ACADEMY_A, 999, b);
            expect(lastTextTo(999)).toContain('متصل نشده است');
        }
        // none of the replies may contain any money formatting
        const replies = tgCalls.filter((c) => c.kind === 'sendMessage' && c.chatId === '999');
        for (const r of replies) {
            expect(r.text || '').not.toMatch(/\d{1,3}(,\d{3})+/);
        }
        expect(docsTo(999).length).toBe(0);
    });
});

// ============================================================
// 4. Settings are per academy (namespaced keys, legacy fallback)
// ============================================================
describe('academy settings isolation for the contact button', () => {
    let env: Env; let db: D1Shim;
    beforeAll(async () => {
        ({ env, db } = makeEnv(new D1Shim(), { TELEGRAM_BOT_TOKEN: '' }));
        connectAcademyBot(db, ACADEMY_A, 'bot_a', TOKEN_A);
        connectAcademyBot(db, ACADEMY_B, 'bot_b', TOKEN_B);
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-a2', fullName: 'آرش نوروزی', studentCode: 'SC-11' }]
        }, auth(ACADEMY_A));
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-b2', fullName: 'بابک حیدری', studentCode: 'SC-12' }]
        }, auth(ACADEMY_B));
        await linkChat(env, db, ACADEMY_A, 'stu-a2', 711);
        await linkChat(env, db, ACADEMY_B, 'stu-b2', 712);
        // a settings row written before per-academy scoping (single-tenant era)
        db.run(`INSERT INTO academy_settings (key, value, updated_at_epoch) VALUES ('academy_name', 'آکادمی قدیمی', ?)`, Date.now());
    });

    it('a legacy unprefixed settings row is still read (compatibility)', async () => {
        await press(env, ACADEMY_B, 712, '📞 تماس با آموزشگاه');
        expect(lastTextTo(712)).toContain('آکادمی قدیمی');
    });

    it('academy A’s pushed settings never leak into academy B’s bot', async () => {
        await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی موسیقی آوا', phone: '۰۲۱-۱۲۳۴۵۶۷۸'
        }, auth(ACADEMY_A));
        await press(env, ACADEMY_A, 711, '📞 تماس با آموزشگاه');
        expect(lastTextTo(711)).toContain('آکادمی موسیقی آوا');
        // B has no pushed settings: it keeps its own (legacy) value
        await press(env, ACADEMY_B, 712, '📞 تماس با آموزشگاه');
        expect(lastTextTo(712)).toContain('آکادمی قدیمی');
        expect(lastTextTo(712)).not.toContain('آکادمی موسیقی آوا');
    });

    it('academy B’s own push overwrites only B and never A', async () => {
        await api(env, 'POST', '/api/sync/settings', {
            academy_name: 'آکادمی بهار', currency_unit: 'ریال'
        }, auth(ACADEMY_B));
        await press(env, ACADEMY_B, 712, '📞 تماس با آموزشگاه');
        expect(lastTextTo(712)).toContain('آکادمی بهار');
        await press(env, ACADEMY_A, 711, '📞 تماس با آموزشگاه');
        expect(lastTextTo(711)).toContain('آکادمی موسیقی آوا'); // unchanged
        expect(lastTextTo(711)).not.toContain('آکادمی بهار');

        // storage shape: namespaced rows + untouched legacy row
        const a = db.get(`SELECT value FROM academy_settings WHERE key = 'academy-a:academy_name'`) as any;
        const b = db.get(`SELECT value FROM academy_settings WHERE key = 'academy-b:academy_name'`) as any;
        const legacy = db.get(`SELECT value FROM academy_settings WHERE key = 'academy_name'`) as any;
        expect(a?.value).toBe('آکادمی موسیقی آوا');
        expect(b?.value).toBe('آکادمی بهار');
        expect(legacy?.value).toBe('آکادمی قدیمی');
    });

    it('the pushed currency unit is what money displays use, per academy', async () => {
        await press(env, ACADEMY_B, 712, '💰 وضعیت مالی');
        expect(lastTextTo(712)).toContain('ریال');
        await press(env, ACADEMY_A, 711, '💰 وضعیت مالی');
        expect(lastTextTo(711)).toContain('تومان'); // A still default/pushed تومان
    });
});
