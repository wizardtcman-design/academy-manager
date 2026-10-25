// ============================================================
// Stage 9: the common SMS-provider layer — contract tests against
// the REAL documented API shapes (SMS.ir REST v1) with fetch mocks,
// plus the manager settings endpoints and the channel-policy gates.
//
// No real SMS is ever sent: every panel HTTP call is answered by the
// mock below; the RyeSMS provider is honestly docs-pending and makes
// NO network call at all.
//
// Coverage map:
//   1 ✶ SMS.ir send contract: URL, X-API-KEY header, JSON body shape
//   2 ✶ SMS.ir error mapping: invalid key (10/401), insufficient
//       credit (102), invalid line (101), invalid mobile (104)
//   3 ✶ timeout/network failure → honest Persian error, never a crash
//   4 ✶ no key/secret ever appears in errors, logs or API responses
//   5 ✶ incomplete config → UNCONFIGURED, zero HTTP calls
//   6 ✶ phone normalization (۰۹/+98/0098/98) and invalid-number refusal
//   7 ✶ duplicate prevention: the ledger claim decides, one call only
//   8 ✶ channel policy: 'telegram' event → SMS never sent (SKIPPED row)
//   9 ✶ channel 'both' + configured panel → real send, outbox SENT
//  10 ✶ manual send via API: sms channel works, idempotent by clientMsgId
//  11 ✶ test-connection: FREE credit endpoint only, never sends
//  12 ✶ ryesms: honest docs-pending refusal, zero network calls
//  13 ✶ provider switch keeps the other panel's stored fields + history
//  14 ✶ tenant isolation: academy B never sees/uses academy A's panel
//  15 ✶ masked settings: only hasValue booleans leave the worker
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { dispatchDueReminders } from '../src/reminders';
import { sendSmsText, normalizeIranMobile, SMS_PROVIDERS } from '../src/sms';
import { epochToShamsi } from '../src/shamsi';
import { AcademyDatabase } from '../src/db';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, ACADEMY_B, API_KEY } from './helpers/fixtures';
import type { Env, InstallmentRecord } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);
const DAY = 86_400_000;
const TOKEN_A = '111111:AAA_stage9_sms_token_for_academy_a_1';
const STUDENT_CHAT = 930;

// ---------- SMS panel HTTP recorder (api.sms.ir) ----------
interface SmsHttpCall { url: string; method: string; apiKey?: string; body?: any }
let smsHttp: SmsHttpCall[] = [];
let smsPanelBehavior: 'ok' | 'invalid-key' | 'no-credit' | 'bad-line' | 'bad-mobile' | 'http500' | 'timeout' = 'ok';
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.sms.ir')) return realFetch(input as any, init as any);
        const headers = (init?.headers || {}) as Record<string, string>;
        const hdrKey = headers['X-API-KEY'] || headers['x-api-key'] || (typeof input === 'object' ? input?.headers?.get?.('X-API-KEY') : '');
        let parsedBody: any;
        try { parsedBody = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined; } catch { parsedBody = undefined; }
        smsHttp.push({ url, method: init?.method || 'GET', apiKey: hdrKey, body: parsedBody });
        if (smsPanelBehavior === 'timeout') throw new Error('The operation was aborted');
        if (smsPanelBehavior === 'http500') return new Response('boom', { status: 500 });
        if (url.endsWith('/credit')) {
            if (smsPanelBehavior === 'invalid-key') {
                return new Response(JSON.stringify({ data: null, status: 10, message: 'کلید وب سرویس نامعتبر است' }), { status: 401 });
            }
            return new Response(JSON.stringify({ status: 1, message: 'موفق', data: 165.3 }), { status: 200 });
        }
        const failBy = (status: number) =>
            new Response(JSON.stringify({ status, message: 'خطا', data: null }), { status: 400 });
        if (smsPanelBehavior === 'invalid-key') return failBy(10);
        if (smsPanelBehavior === 'no-credit') return failBy(102);
        if (smsPanelBehavior === 'bad-line') return failBy(101);
        if (smsPanelBehavior === 'bad-mobile') return failBy(104);
        return new Response(JSON.stringify({
            status: 1, message: 'موفق',
            data: { packId: '0c4b0e14-0000-0000-0000-000000000000', messageIds: [87654], cost: 2100 }
        }), { status: 200 });
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

function auth(academy?: string, withKey: string = API_KEY): Record<string, string> {
    return {
        Authorization: `Bearer ${withKey}`,
        ...(academy ? { 'X-Academy-Id': academy } : {}),
        'Content-Type': 'application/json'
    };
}

async function api(env: Env, method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Response> {
    return handleApiRequest(
        new Request(`${BASE}${path}`, {
            method,
            headers: headers || auth(ACADEMY_A),
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

async function pushInstallment(env: Env, academy: string, list: InstallmentRecord[]): Promise<void> {
    const res = await api(env, 'POST', '/api/sync/installments', { installments: list }, auth(academy));
    expect(res.status).toBe(200);
}

async function seedWorld(over: Partial<Env> = {}) {
    const { env, db } = makeEnv(new D1Shim(), over);
    connectAcademyBot(db, ACADEMY_A, TOKEN_A);
    await api(env, 'POST', '/api/sync/students-batch', {
        students: [
            { id: 'stu-9', fullName: 'آرش موسیقی', studentCode: 'SC-9', phone: '۰۹۱۲۱۱۱۲۲۲۲' }
        ]
    }, auth(ACADEMY_A));
    const adb = new AcademyDatabase(env, ACADEMY_A);
    await adb.linkTelegramChat('stu-9', STUDENT_CHAT, 'arash');
    await api(env, 'POST', '/api/sync/settings', {
        academy_name: 'آکادمی مرحله ۹', currency_unit: 'تومان'
    }, auth(ACADEMY_A));
    return { env, db, adb };
}

async function configureSmsir(env: Env, academy = ACADEMY_A, apiKey = 'sk-live-SECRET-1234', line = '30001234567890') {
    const res = await api(env, 'PUT', '/api/messaging/sms/config', {
        provider: 'smsir',
        fields: { api_key: apiKey, line }
    }, auth(academy));
    expect(res.status).toBe(200);
    return res;
}

// ============================================================
describe('SMS.ir adapter contract (official REST v1 shapes)', () => {
    it('1. sends via POST /v1/send/bulk with X-API-KEY and the documented body', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'یادآوری قسط آزمونی', ledgerId: 'sms-test-1'
        });
        expect(out.status).toBe('SENT');
        expect(out.providerMessageId).toBe('87654'); // messageIds[0] per docs
        expect(smsHttp.length).toBe(1);
        expect(smsHttp[0].url).toBe('https://api.sms.ir/v1/send/bulk');
        expect(smsHttp[0].method).toBe('POST');
        expect(smsHttp[0].apiKey).toBe('sk-live-SECRET-1234');
        expect(smsHttp[0].body).toEqual({
            lineNumber: 30001234567890,
            messageText: 'یادآوری قسط آزمونی',
            mobiles: ['09121112222']
        });
    });

    it('2. maps the documented error codes to honest Persian messages', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        for (const [behavior, fragment] of [
            ['invalid-key', 'کلید وب‌سرویس نامعتبر'],
            ['no-credit', 'اعتبار پنل پیامکی کافی نیست'],
            ['bad-line', 'خط ارسال نامعتبر'],
            ['bad-mobile', 'موبایل گیرنده نامعتبر']
        ] as const) {
            smsPanelBehavior = behavior;
            const out = await sendSmsText(env, adb, ACADEMY_A, {
                to: '09121112222', text: 'x', ledgerId: `sms-err-${behavior}`
            });
            expect(out.status).toBe('FAILED');
            expect(out.error).toContain(fragment);
            // the API key must NEVER leak into an error text
            expect(out.error).not.toContain('sk-live');
        }
        smsPanelBehavior = 'http500';
        const out5 = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-err-500'
        });
        expect(out5.status).toBe('FAILED');
        expect(out5.error).toContain('پاسخ غیرمنتظره');
        smsPanelBehavior = 'timeout';
        const outT = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-err-timeout'
        });
        expect(outT.status).toBe('FAILED');
        expect(outT.error).toContain('مهلت اتصال');
    });

    it('6. normalizes Iranian mobile formats and refuses invalid ones BEFORE any call', async () => {
        expect(normalizeIranMobile('۰۹۱۲۱۱۱۲۲۲۲')).toBe('09121112222');
        expect(normalizeIranMobile('+98 912 111 2222')).toBe('09121112222');
        expect(normalizeIranMobile('00989121112222')).toBe('09121112222');
        expect(normalizeIranMobile('989121112222')).toBe('09121112222');
        expect(normalizeIranMobile('9121112222')).toBe('09121112222');
        expect(normalizeIranMobile('12345')).toBe('');
        expect(normalizeIranMobile('')).toBe('');
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '021-12345678', text: 'x', ledgerId: 'sms-bad-phone'
        });
        expect(out.status).toBe('FAILED');
        expect(out.error).toContain('شماره موبایل گیرنده معتبر نیست');
        expect(smsHttp.length).toBe(0); // nothing reached the panel
    });
});

describe('configuration, security and tenant isolation', () => {
    it('4+5+15. incomplete config → UNCONFIGURED with zero calls; secrets are masked in responses', async () => {
        const { env } = await seedWorld();
        // select the provider but save ONLY the line (no key) → refused save
        const refused = await api(env, 'PUT', '/api/messaging/sms/config', {
            provider: 'smsir', fields: { line: '30001234567890' }
        });
        expect(refused.status).toBe(400);
        const refusedBody = await refused.json() as any;
        expect(refusedBody.message).toContain('الزامی');

        // nothing configured at all → honest UNCONFIGURED, no HTTP call
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const adb = new AcademyDatabase(env, ACADEMY_A);
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-unconf'
        });
        expect(out.status).toBe('UNCONFIGURED');
        expect(smsHttp.length).toBe(0);

        // full config now; then the GET must NEVER echo the key
        await configureSmsir(env);
        const cfg = await (await api(env, 'GET', '/api/messaging/sms/config')).json() as any;
        const smsir = cfg.data.providers.find((p: any) => p.id === 'smsir');
        expect(smsir.active).toBe(true);
        expect(JSON.stringify(cfg)).not.toContain('sk-live-SECRET-1234');
        expect(smsir.settings.api_key).toEqual({ hasValue: true });
        expect(smsir.settings.line).toBe('30001234567890'); // non-secret, readable
    });

    it('13. switching providers keeps the other panel\u2019s stored fields; deactivation keeps everything', async () => {
        const { env } = await seedWorld();
        await configureSmsir(env);
        // switch to ryesms with its own fields
        const put = await api(env, 'PUT', '/api/messaging/sms/config', {
            provider: 'rysesms',
            fields: { api_key: 'ryse-SECRET-987', line: '50001234' }
        });
        expect(put.status).toBe(200);
        const cfg = await (await api(env, 'GET', '/api/messaging/sms/config')).json() as any;
        expect(cfg.data.activeProvider).toBe('rysesms');
        const rys = cfg.data.providers.find((p: any) => p.id === 'rysesms');
        expect(rys.settings.api_key.hasValue).toBe(true);
        // switching back to smsir: its stored key still works WITHOUT re-entry
        const back = await api(env, 'PUT', '/api/messaging/sms/config', {
            provider: 'smsir', fields: {} // blank secret = keep stored
        });
        expect(back.status).toBe(200);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const adb = new AcademyDatabase(env, ACADEMY_A);
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-switchback'
        });
        expect(out.status).toBe('SENT'); // stored key was kept and worked
        // deactivate → everything stays stored, sending stops
        const off = await api(env, 'PUT', '/api/messaging/sms/config', { provider: '' });
        expect(off.status).toBe(200);
        const out2 = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-deactivated'
        });
        expect(out2.status).toBe('UNCONFIGURED');
    });

    it('14. tenant isolation: academy B never sees or uses academy A\u2019s panel config', async () => {
        const { env, db } = await seedWorld();
        connectAcademyBot(db, ACADEMY_B, '222222:BBB_stage9_sms_token_b_00002');
        await api(env, 'POST', '/api/sync/students-batch', {
            students: [{ id: 'stu-b9', fullName: 'هنرجوی آکادمی ب', studentCode: 'SC-B9', phone: '۰۹۳۵۳۳۳۴۴۴۴' }]
        }, auth(ACADEMY_B));
        await configureSmsir(env, ACADEMY_A);
        const cfgB = await (await api(env, 'GET', '/api/messaging/sms/config', undefined, auth(ACADEMY_B))).json() as any;
        expect(cfgB.data.activeProvider).toBe('');
        expect(JSON.stringify(cfgB)).not.toContain('sk-live-SECRET-1234');
        // academy B cannot send through A's panel either
        const adbB = new AcademyDatabase(env, ACADEMY_B);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const out = await sendSmsText(env, adbB, ACADEMY_B, {
            to: '09353334444', text: 'x', ledgerId: 'sms-B-isolated'
        });
        expect(out.status).toBe('UNCONFIGURED');
        expect(smsHttp.length).toBe(0);
    });
});

describe('channel policy + duplicate prevention + pipelines', () => {
    it('7. duplicate prevention: a second dispatch never calls the panel again', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const first = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'یادآوری', ledgerId: 'sms-dup-1'
        });
        expect(first.status).toBe('SENT');
        const second = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'یادآوری', ledgerId: 'sms-dup-1'
        });
        expect(second.status).toBe('DUPLICATE');
        expect(smsHttp.length).toBe(1); // exactly ONE panel call
    });

    it('8+9. event channel policy decides: telegram-only never sends; both delivers', async () => {
        const { env, adb, db } = await seedWorld();
        await configureSmsir(env);
        await pushInstallment(env, ACADEMY_A, [installment({
            id: 'ins-9a', student_id: 'stu-9',
            due_date_epoch: Date.now() + DAY, due_date_shamsi: epochToShamsi(Date.now() + DAY)
        })]);
        // default channel 'telegram' → the reminder goes to Telegram, SMS row SKIPPED
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        await dispatchDueReminders(env, adb, null as any, ACADEMY_A, Date.now() + 26 * 3600_000);
        expect(smsHttp.length).toBe(0);
        // the newest DUE nob of ins-9a at now+26h is the -1d one (…:-1)
        const skipped = db.get(`SELECT status, error FROM sms_outbox WHERE id = 'sms-auto:ins-9a:-1'`) as any;
        expect(skipped?.status).toBe('SKIPPED');
        expect(String(skipped?.error)).toContain('sms_channel_disabled_by_settings');

        // policy 'both' → the SAME reminder id now really delivers via the panel
        await api(env, 'PUT', '/api/messaging/config', {
            events: { INSTALLMENT_REMINDER: { channel: 'both' } }
        });
        // a fresh due nob (different installment so the ledger is fresh)
        await pushInstallment(env, ACADEMY_A, [installment({
            id: 'ins-9b', student_id: 'stu-9', installment_number: 2,
            due_date_epoch: Date.now() + 2 * DAY, due_date_shamsi: epochToShamsi(Date.now() + 2 * DAY)
        })]);
        smsHttp.length = 0;
        await dispatchDueReminders(env, adb, null as any, ACADEMY_A, Date.now() + 50 * 3600_000);
        // At +50h the catch-up keeps the NEWEST due nob per installment:
        // ins-9a → its due-day nob (INSTALLMENT_REMINDER), ins-9b → its -1d
        // nob — both legitimately deliver via the panel under 'both'.
        expect(smsHttp.length).toBe(2);
        const sentA = db.get(`SELECT status FROM sms_outbox WHERE id = 'sms-auto:ins-9a:0'`) as any;
        expect(sentA?.status).toBe('SENT');
        const sent = db.get(`SELECT status FROM sms_outbox WHERE id = 'sms-auto:ins-9b:-1'`) as any;
        expect(sent?.status).toBe('SENT');
        const ledger = db.get(
            `SELECT provider_message_id FROM deliveries WHERE academy_id = ? AND reminder_id = 'sms-auto:ins-9b:-1' AND channel = 'sms'`
        , ACADEMY_A) as any;
        expect(String(ledger?.provider_message_id || '')).toContain('87654');
    });

    it('10. manager manual send over the SMS channel is real, policy-gated and idempotent', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        await pushInstallment(env, ACADEMY_A, [installment({
            id: 'ins-9m', student_id: 'stu-9', due_date_epoch: Date.now() - DAY,
            due_date_shamsi: epochToShamsi(Date.now() - DAY)
        })]);
        await api(env, 'PUT', '/api/messaging/config', {
            events: { OVERDUE_DAILY: { channel: 'both' } }
        });
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const res = await api(env, 'POST', '/api/messaging/send', {
            studentId: 'stu-9', installmentId: 'ins-9m', eventType: 'OVERDUE_DAILY',
            channel: 'sms', clientMsgId: 'msg-sms-9-1'
        });
        expect(res.status).toBe(200);
        const b = await res.json() as any;
        expect(b.data.channels.sms.status).toBe('SENT');
        expect(b.data.status).toBe('SENT');
        expect(smsHttp.length).toBe(1);
        // same clientMsgId again → provider called once in total (idempotent)
        const res2 = await api(env, 'POST', '/api/messaging/send', {
            studentId: 'stu-9', installmentId: 'ins-9m', eventType: 'OVERDUE_DAILY',
            channel: 'sms', clientMsgId: 'msg-sms-9-1'
        });
        expect(res2.status).toBe(200);
        const b2 = await res2.json() as any;
        expect(b2.data.channels.sms.status).toBe('SENT');
        expect(b2.data.idempotent || b2.data.channels.sms.reason || '').toBeTruthy();
        expect(smsHttp.length).toBe(1);
        // policy 'telegram' refuses the sms channel server-side
        await api(env, 'PUT', '/api/messaging/config', {
            events: { OVERDUE_DAILY: { channel: 'telegram' } }
        });
        const res3 = await api(env, 'POST', '/api/messaging/send', {
            studentId: 'stu-9', installmentId: 'ins-9m', eventType: 'OVERDUE_DAILY',
            channel: 'sms', clientMsgId: 'msg-sms-9-2'
        });
        expect(res3.status).toBe(403);
    });

    it('11. test-connection uses ONLY the free credit endpoint and never sends', async () => {
        const { env } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        smsPanelBehavior = 'ok';
        const res = await api(env, 'POST', '/api/messaging/sms/test-connection');
        expect(res.status).toBe(200);
        const b = await res.json() as any;
        expect(b.success).toBe(true);
        expect(b.data.credit).toBe('165.3');
        expect(smsHttp.length).toBe(1);
        expect(smsHttp[0].url).toBe('https://api.sms.ir/v1/credit');
        expect(smsHttp[0].method).toBe('GET');
        // invalid key → honest failure, still no send
        smsPanelBehavior = 'invalid-key';
        const bad = await api(env, 'POST', '/api/messaging/sms/test-connection');
        expect(bad.status).toBe(409);
        const bb = await bad.json() as any;
        expect(bb.message).toContain('نامعتبر');
        expect(JSON.stringify(bb)).not.toContain('sk-live');
    });

    it('12. ryesms: honest docs-pending refusal everywhere, zero network calls', async () => {
        const { env, adb } = await seedWorld();
        const put = await api(env, 'PUT', '/api/messaging/sms/config', {
            provider: 'rysesms', fields: { api_key: 'ryse-SECRET-987', line: '50001234' }
        });
        expect(put.status).toBe(200);
        smsHttp.length = 0;
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'x', ledgerId: 'sms-ryse-1'
        });
        expect(out.status).toBe('BLOCKED');
        expect(out.error).toContain('مستندات رسمی وب‌سرویس');
        expect(smsHttp.length).toBe(0);
        const t = await api(env, 'POST', '/api/messaging/sms/test-connection');
        expect(t.status).toBe(409);
        const tb = await t.json() as any;
        expect(tb.message).toContain('عمومی منتشر نشده');
        expect(smsHttp.length).toBe(0);
        // the registry advertises the provider honestly
        expect(SMS_PROVIDERS['rysesms'].docsPending).toBe(true);
    });
});
