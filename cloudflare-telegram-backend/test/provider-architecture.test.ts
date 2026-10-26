// ============================================================
// Provider architecture (extensible SMS panels + payment gateways):
// contract-level tests for the REGISTRY, the STANDARD error mapping,
// the capability methods (template send, delivery status, payment
// status/cancel/refund), the reserved-key security on the app settings
// sync and the history preservation across provider switches.
//
// Everything runs against the REAL documented API shapes (SMS.ir REST
// v1, re-verified from https://sms.ir/rest-api/) with fetch mocks.
// No real SMS is ever sent; RyeSMS makes NO network call at all
// (docs-pending). The online gateway stays BLOCKED in every scenario
// that does not explicitly enable all three activation requirements.
//
// Coverage map:
//  1 ✶ standard error codes: the FULL official SMS.ir status table maps
//      onto the system vocabulary (no provider internals leak)
//  2 ✶ bulk send per official docs: messageIds[i]=0 → blacklist (REJECTED),
//      null → invalid number/too-long text (INVALID_MOBILE) — never a fake
//      SENT; positive id → SENT with THAT id; missing array → packId
//  3 ✶ template send contract: POST /v1/send/verify URL/header/body,
//      success parse, error mapping, ryesms capability refusal (zero calls)
//  4 ✶ delivery status contract: GET /v1/send/{id}, RAW deliveryState
//      (no invented legend), null → "not reported yet", ms conversion
//  5 ✶ adapter readiness validation (validateSettings, no network)
//  6 ✶ manager template-send API: idempotent by clientMsgId (one panel
//      call for a double click), ryesms → honest refusal, zero network
//  7 ✶ manager delivery API: ok / unknown-ledger / unsupported
//  8 ✶ reserved keys on /api/sync/settings: sms_provider, smsir_*,
//      rysesms_*, backup:* are refused all-or-nothing; gateway_* keys
//      keep working (stage-4 app path)
//  9 ✶ gateway registry: id normalization/validation, hasProviderFactory,
//      and NO accidental activation — full credentials + factory still
//      cannot resolve while the deploy flag is off; no cancel/refund/
//      status API route exists even fully activated
// 10 ✶ history preservation: switching/deactivating a provider never
//      deletes deliveries-ledger or sms_outbox records
// ============================================================

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { handleApiRequest } from '../src/handlers/api';
import { sendSmsText, sendSmsTemplate, querySmsDeliveryStatus, SMS_PROVIDERS, normalizeIranMobile } from '../src/sms';
import { resolveProvider, registerProviderFactory, hasProviderFactory, onlinePaymentsEnabled } from '../src/gateway';
import { AcademyDatabase } from '../src/db';
import { D1Shim } from './helpers/d1-shim';
import { makeEnv, ACADEMY_A, API_KEY } from './helpers/fixtures';
import type { Env } from '../src/types';

const BASE = 'https://worker.test';
const URL_ = (p: string) => new URL(`${BASE}${p}`);

// ---------- SMS.ir HTTP recorder ----------
interface SmsHttpCall { url: string; method: string; apiKey?: string; body?: any }
let smsHttp: SmsHttpCall[] = [];
let behavior: 'ok' | 'status' | 'verify' | 'delivery' | 'timeout' | 'http500' | 'bulk-no-ids' = 'ok';
let forcedStatus = 0;
const realFetch = globalThis.fetch;

beforeAll(() => {
    globalThis.fetch = (async (input: any, init?: any) => {
        const url = String(typeof input === 'string' ? input : input?.url || '');
        if (!url.includes('api.sms.ir')) return realFetch(input as any, init as any);
        const headers = (init?.headers || {}) as Record<string, string>;
        const hdrKey = headers['X-API-KEY'] || headers['x-api-key'] || '';
        let parsedBody: any;
        try { parsedBody = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined; } catch { parsedBody = undefined; }
        smsHttp.push({ url, method: init?.method || 'GET', apiKey: hdrKey, body: parsedBody });
        if (behavior === 'timeout') throw new Error('The operation was aborted');
        if (behavior === 'http500') return new Response('boom', { status: 500 });
        if (behavior === 'status') {
            return new Response(JSON.stringify({ status: forcedStatus, message: 'خطا', data: null }), { status: 400 });
        }
        if (url.endsWith('/credit')) {
            return new Response(JSON.stringify({ status: 1, message: 'موفق', data: 100 }), { status: 200 });
        }
        if (url.endsWith('/send/bulk')) {
            const ids = behavior === 'bulk-no-ids' ? undefined : ((smsBulkIds as any[]).length ? smsBulkIds : [87654]);
            return new Response(JSON.stringify({
                status: 1, message: 'موفق',
                data: ids === undefined ? { packId: 'pack-1', cost: 1 } : { packId: 'pack-1', messageIds: ids, cost: 1 }
            }), { status: 200 });
        }
        if (url.endsWith('/send/verify')) {
            return new Response(JSON.stringify({ status: 1, message: 'موفق', data: { messageId: 99001, cost: 1 } }), { status: 200 });
        }
        if (/\/send\/\d+$/.test(url)) {
            const state = (behavior === 'delivery') ? forcedDeliveryState : null;
            return new Response(JSON.stringify({
                status: 1, message: 'موفق',
                data: { messageId: 87654, mobile: 9121112222, messageText: 'x', sendDateTime: 1628683626, lineNumber: 3000, cost: 1, deliveryState: state, deliveryDateTime: state === null ? null : 1628683629 }
            }), { status: 200 });
        }
        return new Response(JSON.stringify({ status: 1, message: 'موفق', data: null }), { status: 200 });
    }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; });

let smsBulkIds: (number | null)[] = [];
let forcedDeliveryState: number | null = 1;

// ---------- world helpers ----------
function auth(academy = ACADEMY_A): Record<string, string> {
    return { Authorization: `Bearer ${API_KEY}`, 'X-Academy-Id': academy, 'Content-Type': 'application/json' };
}

async function api(env: Env, method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<Response> {
    return handleApiRequest(
        new Request(`${BASE}${path}`, { method, headers: headers || auth(), body: body === undefined ? undefined : JSON.stringify(body) }),
        env, URL_(path)
    );
}

async function seedWorld(over: Partial<Env> = {}): Promise<{ env: Env; db: D1Shim; adb: AcademyDatabase }> {
    const { env, db } = makeEnv(new D1Shim(), over);
    db.run(
        `INSERT OR REPLACE INTO academies (id, name, bot_token, bot_username, bot_id, webhook_secret, bot_connected_at_epoch)
         VALUES (?,?,?,?,?,?,?)`,
        ACADEMY_A, 'A', '111111:AAA_arch_token_a_1', 'bot_a', '111111', 'sec-a', Date.now()
    );
    return { env, db, adb: new AcademyDatabase(env, ACADEMY_A) };
}

async function configureSmsir(env: Env, apiKey = 'sk-arch-SECRET-1', line = '30001234567890'): Promise<void> {
    const res = await api(env, 'PUT', '/api/messaging/sms/config', { provider: 'smsir', fields: { api_key: apiKey, line } });
    expect(res.status).toBe(200);
}

// ============================================================
describe('standard error-code mapping (official SMS.ir status table)', () => {
    it('1. maps every documented status onto the system vocabulary', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        const cases: Array<[number, string]> = [
            [10, 'UNAUTHENTICATED'], [11, 'UNAUTHENTICATED'], [12, 'UNAUTHENTICATED'],
            [13, 'UNAUTHENTICATED'], [14, 'UNAUTHENTICATED'],
            [20, 'RATE_LIMITED'],
            [101, 'INVALID_LINE'], [123, 'INVALID_LINE'],
            [102, 'NO_CREDIT'],
            [104, 'INVALID_MOBILE'],
            [0, 'REJECTED'], [103, 'REJECTED'], [105, 'REJECTED'], [115, 'REJECTED'], [117, 'REJECTED']
        ];
        for (const [status, code] of cases) {
            behavior = 'status';
            forcedStatus = status;
            const out = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: `arch-code-${status}` });
            expect(out.status, `status ${status}`).toBe('FAILED');
            expect(out.code, `status ${status}`).toBe(code);
            expect(out.error).not.toContain('sk-arch');
        }
        // undocumented code → PROVIDER_ERROR (honest, still key-free)
        behavior = 'status'; forcedStatus = 999;
        const unk = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-code-999' });
        expect(unk.code).toBe('PROVIDER_ERROR');
        // transport failures
        behavior = 'timeout';
        const t = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-timeout' });
        expect(t.code).toBe('TIMEOUT');
        behavior = 'http500';
        const h = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-500' });
        expect(h.code).toBe('UNEXPECTED_RESPONSE');
        behavior = 'ok';
    });

    it('2. bulk semantics per official docs: 0=blacklist, null=invalid, positive=SENT', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        behavior = 'ok';
        // positive id → SENT with THAT id (not the pack id)
        smsBulkIds = [87654];
        const ok1 = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-bulk-ok' });
        expect(ok1.status).toBe('SENT');
        expect(ok1.providerMessageId).toBe('87654');
        // 0 → the recipient is blacklisted: FAILED/REJECTED, NEVER a fake SENT
        smsBulkIds = [0];
        const bl = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-bulk-blacklist' });
        expect(bl.status).toBe('FAILED');
        expect(bl.code).toBe('REJECTED');
        expect(bl.error).toContain('لیست سیاه');
        // null → invalid number or text too long: FAILED/INVALID_MOBILE
        smsBulkIds = [null];
        const inv = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-bulk-null' });
        expect(inv.status).toBe('FAILED');
        expect(inv.code).toBe('INVALID_MOBILE');
        // documented array absent entirely → the packId is the tracking ref
        behavior = 'bulk-no-ids';
        const pk = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-bulk-pack' });
        expect(pk.status).toBe('SENT');
        expect(pk.providerMessageId).toBe('pack-1');
        smsBulkIds = [];
        behavior = 'ok';
    });
});

// ============================================================
describe('template send + delivery status (contract capabilities)', () => {
    it('3. sendTemplate hits POST /v1/send/verify with the documented body', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        behavior = 'ok';
        const out = await sendSmsTemplate(env, adb, ACADEMY_A, {
            to: '۰۹۱۲۱۱۱۲۲۲۲', // Persian digits must be normalized before the call
            templateId: '100500',
            parameters: [{ name: 'CODE', value: '12345' }],
            ledgerId: 'arch-tpl-1'
        });
        expect(out.status).toBe('SENT');
        expect(out.providerMessageId).toBe('99001');
        expect(smsHttp.length).toBe(1);
        expect(smsHttp[0].url).toBe('https://api.sms.ir/v1/send/verify');
        expect(smsHttp[0].method).toBe('POST');
        expect(smsHttp[0].apiKey).toBe('sk-arch-SECRET-1');
        expect(smsHttp[0].body).toEqual({ mobile: '09121112222', templateId: 100500, parameters: [{ name: 'CODE', value: '12345' }] });
        // duplicate protection works for template sends too
        const dup = await sendSmsTemplate(env, adb, ACADEMY_A, {
            to: '09121112222', templateId: '100500',
            parameters: [{ name: 'CODE', value: '12345' }], ledgerId: 'arch-tpl-1'
        });
        expect(dup.status).toBe('DUPLICATE');
        expect(smsHttp.length).toBe(1);
        // a provider error maps onto the standard codes
        behavior = 'status'; forcedStatus = 102;
        const nc = await sendSmsTemplate(env, adb, ACADEMY_A, {
            to: '09121112222', templateId: '100500',
            parameters: [{ name: 'CODE', value: '1' }], ledgerId: 'arch-tpl-2'
        });
        expect(nc.status).toBe('FAILED');
        expect(nc.code).toBe('NO_CREDIT');
        behavior = 'ok';
    });

    it('4. ryesms template send is honestly refused with ZERO network calls', async () => {
        const { env, adb } = await seedWorld();
        const put = await api(env, 'PUT', '/api/messaging/sms/config', { provider: 'rysesms', fields: { api_key: 'ryse-SECRET-1', line: '5000' } });
        expect(put.status).toBe(200);
        smsHttp.length = 0;
        const out = await sendSmsTemplate(env, adb, ACADEMY_A, {
            to: '09121112222', templateId: '1', parameters: [], ledgerId: 'arch-tpl-ryse'
        });
        expect(out.status).toBe('BLOCKED');
        expect(out.code).toBe('DOCS_PENDING');
        expect(smsHttp.length).toBe(0);
        expect(SMS_PROVIDERS['rysesms'].docsPending).toBe(true);
    });

    it('5. delivery status: raw state only (no invented legend), ms conversion, honest edges', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        behavior = 'ok';
        smsBulkIds = [87654];
        await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'arch-dlv-1' });
        // delivered: raw deliveryState reported verbatim, epoch→ms converted
        behavior = 'delivery'; forcedDeliveryState = 1;
        smsHttp.length = 0;
        const d1 = await querySmsDeliveryStatus(env, adb, ACADEMY_A, 'arch-dlv-1');
        expect(d1.kind).toBe('ok');
        if (d1.kind === 'ok') {
            expect(d1.deliveryState).toBe('1');
            expect(d1.deliveredAtEpoch).toBe(1628683629 * 1000);
            expect(smsHttp[0].url).toBe('https://api.sms.ir/v1/send/87654');
        }
        // null state (not yet reported) → honest empty state, no invention
        forcedDeliveryState = null;
        const d2 = await querySmsDeliveryStatus(env, adb, ACADEMY_A, 'arch-dlv-1');
        expect(d2.kind).toBe('ok');
        if (d2.kind === 'ok') {
            expect(d2.deliveryState).toBe('');
            expect(d2.deliveredAtEpoch).toBe(0);
            expect(d2.detail).toContain('گزارش نکرده');
        }
        // unknown ledger id
        const d3 = await querySmsDeliveryStatus(env, adb, ACADEMY_A, 'arch-dlv-never-sent');
        expect(d3.kind).toBe('unknown-ledger');
        behavior = 'ok';
    });

    it('6. registry metadata + readiness gates work locally (no network)', () => {
        // The registry is the public surface: its field defs drive the
        // settings UI, the completeness validation and the capabilities.
        const def = SMS_PROVIDERS['smsir'];
        const required = def.fields.filter((f) => f.required).map((f) => f.key);
        expect(required).toContain('api_key');
        expect(required).toContain('line');
        expect(def.capabilities.templateSend).toBe(true);
        expect(def.capabilities.deliveryReport).toBe(true);
        expect(def.capabilities.creditCheck).toBe(true);
        const rys = SMS_PROVIDERS['rysesms'];
        expect(rys.docsPending).toBe(true);
        expect(rys.capabilities.templateSend).toBe(false);
        expect(rys.capabilities.creditCheck).toBe(false);
        // normalizeIranMobile stays the single gate before any provider call
        expect(normalizeIranMobile('+989121112222')).toBe('09121112222');
        expect(normalizeIranMobile('12345')).toBe('');
    });
});

// ============================================================
describe('manager template-send + delivery APIs', () => {
    it('7. send-template API is real, idempotent by clientMsgId and secret-safe', async () => {
        const { env } = await seedWorld();
        await configureSmsir(env);
        smsHttp.length = 0;
        behavior = 'ok';
        const body = {
            to: '09121112222', templateId: '100500',
            parameters: [{ name: 'CODE', value: '12345' }], clientMsgId: 'tpl-msg-1'
        };
        const r1 = await api(env, 'POST', '/api/messaging/sms/send-template', body);
        expect(r1.status).toBe(200);
        const b1 = await r1.json() as any;
        expect(b1.data.status).toBe('SENT');
        expect(b1.data.providerMessageId).toBe('99001');
        expect(smsHttp.length).toBe(1);
        // double click → same clientMsgId → one panel call in total
        const r2 = await api(env, 'POST', '/api/messaging/sms/send-template', body);
        expect(r2.status).toBe(200);
        expect(smsHttp.length).toBe(1);
        // invalid template id refused BEFORE any panel call
        smsHttp.length = 0;
        const bad = await api(env, 'POST', '/api/messaging/sms/send-template', { ...body, templateId: 'bad id!', clientMsgId: 'tpl-msg-2' });
        expect(bad.status).toBe(400);
        expect(smsHttp.length).toBe(0);
        // missing clientMsgId refused
        const noc = await api(env, 'POST', '/api/messaging/sms/send-template', { to: '09121112222', templateId: '1', parameters: [] });
        expect(noc.status).toBe(400);
        expect(smsHttp.length).toBe(0);
        // the secret never appears in any response
        expect(JSON.stringify([b1, await bad.json(), await noc.json()])).not.toContain('sk-arch');
    });

    it('8. delivery API: ok / 404 unknown / 409 unsupported (rysesms)', async () => {
        const { env, adb } = await seedWorld();
        await configureSmsir(env);
        behavior = 'ok';
        await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'manual:api-dlv' });
        behavior = 'delivery'; forcedDeliveryState = 1;
        const okRes = await api(env, 'GET', '/api/messaging/sms/delivery?ledgerId=manual:api-dlv');
        expect(okRes.status).toBe(200);
        const okBody = await okRes.json() as any;
        expect(okBody.data.deliveryState).toBe('1');
        // unknown ledger → 404
        const unk = await api(env, 'GET', '/api/messaging/sms/delivery?ledgerId=manual:nope');
        expect(unk.status).toBe(404);
        behavior = 'ok';
        // ryesms active → unsupported (honest)
        await api(env, 'PUT', '/api/messaging/sms/config', { provider: 'rysesms', fields: { api_key: 'ryse-SECRET-1', line: '5000' } });
        const uns = await api(env, 'GET', '/api/messaging/sms/delivery?ledgerId=manual:api-dlv');
        expect(uns.status).toBe(409);
        const unsBody = await uns.json() as any;
        expect(unsBody.data.kind).toBe('unsupported');
        expect(JSON.stringify(unsBody)).not.toContain('ryse-SECRET');
    });
});

// ============================================================
describe('settings-sync reserved keys + history preservation', () => {
    it('9. /api/sync/settings refuses provider/backup keys all-or-nothing', async () => {
        const { env, adb } = await seedWorld();
        await adb.setSetting('academy_name', 'نام اصلی');
        for (const key of ['sms_provider', 'smsir_api_key', 'rysesms_line', 'backup:running']) {
            const res = await api(env, 'POST', '/api/sync/settings', { academy_name: 'هک‌شده', [key]: 'hijack' });
            expect(res.status, key).toBe(400);
            // ALL-OR-NOTHING: the benign key in the same payload is NOT saved
            expect(await adb.getSetting('academy_name')).toBe('نام اصلی');
            expect(await adb.getSetting(key)).not.toBe('hijack');
        }
        // gateway keys stay writable (stage-4 app path; cannot activate
        // anything alone — the deploy flag + a code factory are also needed)
        const gw = await api(env, 'POST', '/api/sync/settings', { gateway_provider: 'none' });
        expect(gw.status).toBe(200);
    });

    it('10. switching/deactivating providers never deletes send history', async () => {
        const { env, adb, db } = await seedWorld();
        await configureSmsir(env);
        behavior = 'ok';
        const out = await sendSmsText(env, adb, ACADEMY_A, {
            to: '09121112222', text: 'یادآوری', ledgerId: 'hist-1',
            // real callers (reminders/receipts/manual send) record the outbox
            // row through this callback — mirror that exactly
            onOutbox: (r) => {
                void adb.insertSmsOutbox('hist-1', ACADEMY_A, 'hist-1', '09121112222', 'یادآوری',
                    r.status === 'SENT' ? 'SENT' : 'FAILED', r.error, Date.now());
            }
        });
        expect(out.status).toBe('SENT');
        await new Promise((r) => setTimeout(r, 25)); // fire-and-forget ledger writes settle
        // switch to ryesms, then deactivate completely
        await api(env, 'PUT', '/api/messaging/sms/config', { provider: 'rysesms', fields: { api_key: 'ryse-SECRET-1', line: '5000' } });
        await api(env, 'PUT', '/api/messaging/sms/config', { provider: '' });
        const ledger = db.get(`SELECT status, provider_message_id FROM deliveries WHERE academy_id = ? AND reminder_id = 'hist-1' AND channel = 'sms'`, ACADEMY_A) as any;
        expect(ledger?.status).toBe('SENT');
        expect(String(ledger?.provider_message_id || '')).toBe('87654');
        const outbox = db.get(`SELECT status FROM sms_outbox WHERE id = 'hist-1'`) as any;
        expect(outbox?.status).toBe('SENT');
        // the stored smsir credentials also survive the round trip
        await api(env, 'PUT', '/api/messaging/sms/config', { provider: 'smsir', fields: {} });
        smsHttp.length = 0;
        const again = await sendSmsText(env, adb, ACADEMY_A, { to: '09121112222', text: 'x', ledgerId: 'hist-2' });
        expect(again.status).toBe('SENT'); // stored key worked without re-entry
        expect(smsHttp[0].apiKey).toBe('sk-arch-SECRET-1');
    });
});

// ============================================================
describe('gateway registry + no accidental activation', () => {
    it('9b. factory ids are normalized/validated and introspectable', () => {
        registerProviderFactory('ArchPay ', () => ({ id: 'archpay', async createPaymentLink() { return { redirectUrl: 'u' }; }, async verifyPayment() { return { ok: true, paidAmount: 1, ref: 'r', raw: '{}' }; } }));
        expect(hasProviderFactory('archpay')).toBe(true);
        expect(hasProviderFactory('ARCHPAY')).toBe(true);
        expect(() => registerProviderFactory('x', (() => ({})) as any)).toThrow();
        expect(() => registerProviderFactory('', (() => ({})) as any)).toThrow();
        expect(hasProviderFactory('x')).toBe(false);
    });

    it('9c. even flag+factory+credentials cannot resolve while the deploy switch is off', async () => {
        const { env, adb } = await seedWorld();
        registerProviderFactory('archpay', () => ({ id: 'archpay', async createPaymentLink() { return { redirectUrl: 'u' }; }, async verifyPayment() { return { ok: true, paidAmount: 1, ref: 'r', raw: '{}' }; } }));
        await adb.setSetting('gateway_provider', 'archpay');
        await adb.setSetting('gateway_merchant_id', 'M-ARCH');
        await adb.setSetting('gateway_api_key', 'GK-ARCH-SECRET');
        expect(onlinePaymentsEnabled(env)).toBe(false);
        const r1 = await resolveProvider(env, adb);
        expect(r1.provider).toBe(null);
        // WITH the flag the very same configuration resolves — activation is
        // fully explicit, never accidental (this is architecture proof; the
        // activated flows themselves are covered by payments-flow tests).
        const envOn = { ...env, ONLINE_PAYMENTS_ENABLED: 'true' } as Env;
        const r2 = await resolveProvider(envOn, adb);
        expect(r2.provider?.id).toBe('archpay');
        // ...and no cancel/refund/status endpoint exists at all (the contract
        // methods are for FUTURE adapters, no route can invoke them today)
        for (const p of ['/api/payments/cancel', '/api/payments/refund', '/api/payments/status']) {
            const res = await api(envOn, 'POST', p, { orderId: 'x' });
            expect(res.status, p).toBe(404);
        }
    });
});
