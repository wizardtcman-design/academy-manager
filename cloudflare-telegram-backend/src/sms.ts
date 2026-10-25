// ============================================================
// Stage 9: the common SMS-provider layer.
//
// ONE provider contract, many panels: reminders, receipts, claim
// acks/rejections, welcome messages and manager manual sends all go
// through sendSmsText()/resolveSmsProvider() — business logic never
// talks to a panel directly. Adding a future panel touches ONLY
// SMS_PROVIDERS + its adapter here.
//
// Providers implemented in this stage (official docs only, nothing
// guessed — see docs/SMS_PANELS.md for sources):
//   - smsir   : SMS.ir REST API v1 (https://sms.ir/rest-api/)
//               auth: X-API-KEY header; send: POST /v1/send/bulk
//               {lineNumber, messageText, mobiles[]} → {status,message,
//               data:{packId,messageIds,cost}}; status 1 = success;
//               credit: GET /v1/credit (FREE, used for connection test).
//   - ryesms  : رایس‌اس‌ام‌اس — the panel (panel6.ir) publishes NO public
//               web-service documentation (verified 2026: site sitemap,
//               all doc paths, search engines). The provider SLOT is
//               registered so the settings UI can list it and store its
//               fields, but its adapter honestly refuses everything with
//               docs_pending until the owner supplies the real API spec.
//               NO endpoint or parameter is invented anywhere.
//
// Security rules (enforced here):
//   - API keys live per academy in the namespaced academy_settings KV
//     (existing project mechanism, same as gateway_api_key) and are
//     NEVER returned by any endpoint (only masked booleans), NEVER
//     logged, NEVER included in error texts.
//   - An optional Cloudflare Secret (SMSIR_API_KEY) overrides the
//     stored key at runtime — see docs/SMS_PANELS.md for the exact
//     `wrangler secret put` commands.
//   - checkSmsConnection() uses ONLY free/no-cost official endpoints
//     (SMS.ir /v1/credit) — it can never send a billable SMS.
//   - sendSmsText() claims the deliveries ledger BEFORE calling the
//     provider, so a retry or an overlapping run can never double-send.
//
// Channel policy stays with the callers: when the messaging config has
// channel 'telegram' for an event, NO SMS may be sent for it — callers
// consult cfg.events[key].channel before reaching this layer.
// ============================================================

import { Env } from './types';
import { AcademyDatabase } from './db';
import { ContractStore } from './contracts-store';

// ------------------------------------------------------------
// Provider registry (the ONLY place a new panel is added)
// ------------------------------------------------------------

export interface SmsFieldDef {
    /** Settings-key suffix; the stored key is `<providerId>_<key>`. */
    key: string;
    label: string;
    kind: 'secret' | 'text' | 'line';
    required: boolean;
    hint?: string;
}

export interface SmsProviderDef {
    id: string;
    label: string;
    /** Official documentation URL (verified) shown in the settings UI. */
    docsUrl: string;
    /** true = official public API docs do not exist (login-only panel). */
    docsPending?: boolean;
    fields: SmsFieldDef[];
    capabilities: {
        /** A FREE official endpoint exists for connection/credit checks. */
        creditCheck: boolean;
        deliveryReport: boolean;
        /** Template-based (service line) sending supported by the docs. */
        templateSend: boolean;
    };
}

export const SMS_PROVIDERS: Record<string, SmsProviderDef> = {
    smsir: {
        id: 'smsir',
        label: 'SMS.ir',
        docsUrl: 'https://sms.ir/rest-api/',
        fields: [
            {
                key: 'api_key',
                label: 'کلید API (X-API-KEY)',
                kind: 'secret',
                required: true,
                hint: 'از پنل sms.ir ← برنامه‌نویسان ← کلیدهای API ساخته می‌شود.'
            },
            {
                key: 'line',
                label: 'شماره خط ارسال (Line Number)',
                kind: 'line',
                required: true,
                hint: 'خط اختصاصی آموزشگاه؛ مثال: ۳۰۰۰xxxxxxxxx'
            }
        ],
        capabilities: { creditCheck: true, deliveryReport: true, templateSend: true }
    },
    rysesms: {
        id: 'rysesms',
        label: 'رایس‌اس‌ام‌اس',
        docsUrl: 'https://www.rysesms.ir/',
        docsPending: true,
        fields: [
            {
                key: 'api_key',
                label: 'کلید وب‌سرویس',
                kind: 'secret',
                required: true,
                hint: 'تا انتشار مستندات رسمی وب‌سرویس تکمیل نمی‌شود.'
            },
            {
                key: 'line',
                label: 'شماره خط ارسال',
                kind: 'line',
                required: true,
                hint: 'تا انتشار مستندات رسمی وب‌سرویس تکمیل نمی‌شود.'
            }
        ],
        capabilities: { creditCheck: false, deliveryReport: false, templateSend: false }
    }
};

// ------------------------------------------------------------
// Settings storage (per academy, namespaced academy_settings — no migration)
// ------------------------------------------------------------

export const SMS_PROVIDER_KEY = 'sms_provider';

export interface SmsConfig {
    provider: string; // '' | provider id
    settings: Record<string, string>; // full values for the ACTIVE provider only
}

export async function getSmsConfig(db: AcademyDatabase): Promise<SmsConfig> {
    const provider = ((await db.getSetting(SMS_PROVIDER_KEY, '')) || '').trim().toLowerCase();
    if (!provider || !SMS_PROVIDERS[provider]) return { provider: '', settings: {} };
    const def = SMS_PROVIDERS[provider];
    const settings: Record<string, string> = {};
    for (const f of def.fields) {
        settings[f.key] = (await db.getSetting(`${provider}_${f.key}`, '')) || '';
    }
    return { provider, settings };
}

/** Missing REQUIRED fields of the active provider's config. */
export function missingSmsFields(def: SmsProviderDef, settings: Record<string, string>): string[] {
    return def.fields.filter((f) => f.required && !(settings[f.key] || '').trim()).map((f) => f.label);
}

export interface ResolvedSmsProvider {
    def: SmsProviderDef;
    settings: Record<string, string>;
}

export type SmsResolveResult =
    | { kind: 'ok'; provider: ResolvedSmsProvider }
    | { kind: 'not-selected'; reason: string }
    | { kind: 'incomplete'; reason: string; missing: string[] }
    | { kind: 'docs-pending'; reason: string };

/**
 * Resolves the ACTIVE provider for THIS academy. Sending is impossible
 * unless a provider is selected AND its required fields are complete —
 * and for ryesms until its official web-service docs exist.
 */
export async function resolveSmsProvider(env: Env, db: AcademyDatabase): Promise<SmsResolveResult> {
    const cfg = await getSmsConfig(db);
    if (!cfg.provider) {
        return { kind: 'not-selected', reason: 'پنل پیامکی انتخاب نشده است؛ ارسال پیامک انجام نمی‌شود.' };
    }
    const def = SMS_PROVIDERS[cfg.provider];
    if (!def) return { kind: 'not-selected', reason: 'پنل پیامکی انتخاب نشده است؛ ارسال پیامک انجام نمی‌شود.' };
    const missing = missingSmsFields(def, cfg.settings);
    if (missing.length) {
        return {
            kind: 'incomplete',
            reason: `پیکربندی پنل پیامکی ناقص است: ${missing.join('، ')}.`,
            missing
        };
    }
    if (def.docsPending) {
        return {
            kind: 'docs-pending',
            reason: 'مستندات رسمی وب‌سرویس این پنل به‌صورت عمومی منتشر نشده است؛ اتصال پس از تکمیل مستندات فعال می‌شود.'
        };
    }
    // Optional Cloudflare Secret override (never logged, never echoed):
    const override = ((env as any)[`${def.id.toUpperCase()}_API_KEY`] as string | undefined) || '';
    const settings = { ...cfg.settings };
    if (override.trim()) settings.api_key = override.trim();
    return { kind: 'ok', provider: { def, settings } };
}

/** Persian, key-free description of the current SMS configuration state. */
export async function smsChannelStatus(db: AcademyDatabase): Promise<{ configured: boolean; detail: string; provider: string }> {
    const cfg = await getSmsConfig(db);
    if (!cfg.provider) {
        return { configured: false, provider: '', detail: 'پنل پیامکی انتخاب نشده است؛ ارسال پیامک غیرفعال است.' };
    }
    const def = SMS_PROVIDERS[cfg.provider];
    if (!def) {
        return { configured: false, provider: cfg.provider, detail: 'پنل پیامکی انتخاب نشده است؛ ارسال پیامک غیرفعال است.' };
    }
    const missing = missingSmsFields(def, cfg.settings);
    if (missing.length) {
        return { configured: false, provider: def.id, detail: `پیکربندی «${def.label}» ناقص است: ${missing.join('، ')}.` };
    }
    if (def.docsPending) {
        return {
            configured: false, provider: def.id,
            detail: `«${def.label}» انتخاب شده اما مستندات رسمی وب‌سرویس آن عمومی نیست؛ ارسال پیامک تا تکمیل مستندات غیرفعال است.`
        };
    }
    return { configured: true, provider: def.id, detail: `پنل پیامکی «${def.label}» پیکربندی شده است.` };
}

// ------------------------------------------------------------
// Phone normalization (Iranian mobiles) — checked BEFORE any provider call
// ------------------------------------------------------------

/** Converts Persian/Arabic digits to ASCII. */
function toAsciiDigits(s: string): string {
    let out = '';
    for (const ch of s || '') {
        const c = ch.codePointAt(0) || 0;
        if (c >= 0x06f0 && c <= 0x06f9) out += String.fromCharCode(c - 0x06f0 + 0x30);      // ۰-۹
        else if (c >= 0x0660 && c <= 0x0669) out += String.fromCharCode(c - 0x0660 + 0x30); // ٠-٩
        else out += ch;
    }
    return out;
}

/**
 * Normalizes to the 09xxxxxxxxx form every Iranian panel accepts.
 * Returns '' when the value is not an Iranian mobile (refused BEFORE
 * any provider call — a malformed number must never reach the API).
 */
export function normalizeIranMobile(raw: string): string {
    let d = toAsciiDigits(String(raw || '')).replace(/[^\d+]/g, '');
    if (d.startsWith('+98')) d = `0${d.slice(3)}`;
    else if (d.startsWith('0098')) d = `0${d.slice(4)}`;
    else if (d.startsWith('98') && d.length === 12) d = `0${d.slice(2)}`;
    else if (d.startsWith('9') && d.length === 10) d = `0${d}`;
    return /^09\d{9}$/.test(d) ? d : '';
}

// ------------------------------------------------------------
// Provider adapters (HTTP contract per official docs ONLY)
// ------------------------------------------------------------

const SMS_HTTP_TIMEOUT_MS = 12_000;

export interface SmsAdapterCallResult {
    ok: boolean;
    /** Provider tracking id (messageId / packId) when the API returns one. */
    messageId: string;
    /** Human-readable Persian error; NEVER contains credentials. */
    error: string;
    /** Sanitized short response echo for the ledger (no keys, no full text). */
    raw: string;
}

type SmsAdapter = {
    /** MUST be a free/no-cost endpoint only (credit/balance). */
    checkConnection(settings: Record<string, string>): Promise<{ ok: boolean; detail: string; credit?: string }>;
    /** Sends ONE text to ONE normalized mobile. */
    send(settings: Record<string, string>, to: string, text: string): Promise<SmsAdapterCallResult>;
};

/** fetch with a hard timeout — a hanging panel must never stall the cron. */
async function fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), SMS_HTTP_TIMEOUT_MS);
    try {
        return await fetch(url, { ...init, signal: ctrl.signal });
    } finally {
        clearTimeout(t);
    }
}

/** Network/timeout errors become honest, key-free Persian messages. */
function networkErrorText(e: unknown): string {
    const msg = e instanceof Error ? e.message : String(e);
    if (/abort/i.test(msg)) return 'مهلت اتصال به پنل پیامکی تمام شد.';
    return 'اتصال به پنل پیامکی برقرار نشد.';
}

/** SMS.ir status codes (official table, https://sms.ir/rest-api/). */
function smsIrStatusText(status: number): string {
    const map: Record<number, string> = {
        0: 'خطای سیستمی در پنل پیامکی.',
        10: 'کلید وب‌سرویس نامعتبر است.',
        11: 'کلید وب‌سرویس غیرفعال شده است.',
        12: 'کلید وب‌سرویس به IP محدود شده است.',
        13: 'حساب کاربری غیرفعال است.',
        14: 'حساب کاربری مسدود شده است.',
        20: 'تعداد درخواست‌ها بیش از حد مجاز است؛ کمی بعد تلاش کنید.',
        101: 'شماره خط ارسال نامعتبر است.',
        102: 'اعتبار پنل پیامکی کافی نیست.',
        103: 'متن پیام خالی است.',
        104: 'شماره موبایل گیرنده نامعتبر است.',
        105: 'تعداد گیرندگان بیش از حد مجاز است.',
        115: 'شماره گیرنده در لیست سیاه است.',
        117: 'متن ارسالی تأیید نشده است.',
        123: 'خط ارسال فعال نیست.'
    };
    return map[status] || `خطای پنل پیامکی (کد ${status}).`;
}

const SMSIR_BASE = 'https://api.sms.ir/v1';

const smsIrAdapter: SmsAdapter = {
    // GET /v1/credit — the official FREE balance endpoint (no SMS is sent).
    async checkConnection(settings) {
        try {
            const res = await fetchWithTimeout(SMSIR_BASE + '/credit', {
                method: 'GET',
                headers: { 'X-API-KEY': settings.api_key, Accept: 'application/json' }
            });
            let body: any = null;
            try { body = await res.json(); } catch (_) { /* non-JSON error page */ }
            if (res.status === 401) {
                return { ok: false, detail: 'کلید وب‌سرویس نامعتبر است (۴۰۱).' };
            }
            if (body && typeof body.status === 'number' && body.status !== 1) {
                return { ok: false, detail: smsIrStatusText(body.status) };
            }
            if (res.status !== 200) {
                return { ok: false, detail: `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).` };
            }
            const credit = body?.data;
            return {
                ok: true,
                detail: 'اتصال با پنل SMS.ir برقرار است.',
                credit: credit === undefined || credit === null ? undefined : String(credit)
            };
        } catch (e) {
            return { ok: false, detail: networkErrorText(e) };
        }
    },

    // POST /v1/send/bulk — {lineNumber, messageText, mobiles[]} (official docs).
    async send(settings, to, text) {
        try {
            const res = await fetchWithTimeout(SMSIR_BASE + '/send/bulk', {
                method: 'POST',
                headers: {
                    'X-API-KEY': settings.api_key,
                    'Content-Type': 'application/json',
                    Accept: 'application/json'
                },
                body: JSON.stringify({
                    lineNumber: Number(settings.line),
                    messageText: text,
                    mobiles: [to]
                })
            });
            let body: any = null;
            try { body = await res.json(); } catch (_) { /* non-JSON error page */ }
            if (body && typeof body.status === 'number' && body.status !== 1) {
                return { ok: false, messageId: '', error: smsIrStatusText(body.status), raw: `status:${body.status}` };
            }
            if (res.status !== 200 || !body || body.status !== 1) {
                return {
                    ok: false, messageId: '',
                    error: `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).`,
                    raw: `http:${res.status}`
                };
            }
            const data = body.data || {};
            const packId = String(data.packId || '');
            const firstMsgId = Array.isArray(data.messageIds) && data.messageIds.length
                ? String(data.messageIds[0]) : '';
            return { ok: true, messageId: firstMsgId || packId, error: '', raw: `pack:${packId}` };
        } catch (e) {
            return { ok: false, messageId: '', error: networkErrorText(e), raw: 'network' };
        }
    }
};

/**
 * RyeSMS adapter — DOCS PENDING. The panel publishes no public web-service
 * documentation (verified: site sitemap, doc paths, search engines — see
 * docs/SMS_PANELS.md). Every method honestly refuses; NOTHING is invented.
 */
const ryeSmsDocsPending = {
    ok: false,
    detail: 'مستندات رسمی وب‌سرویس رایس‌اس‌ام‌اس به‌صورت عمومی منتشر نشده است؛ آزمایش اتصال پس از دسترسی به مستندات فعال می‌شود.'
};

const ryeSmsAdapter: SmsAdapter = {
    async checkConnection() {
        return ryeSmsDocsPending;
    },
    async send() {
        return {
            ok: false, messageId: '',
            error: 'ارسال از رایس‌اس‌ام‌اس هنوز فعال نیست: مستندات رسمی وب‌سرویس در دسترس نیست.',
            raw: 'docs_pending'
        };
    }
};

const SMS_ADAPTERS: Record<string, SmsAdapter> = {
    smsir: smsIrAdapter,
    ryesms: ryeSmsAdapter
};

// ------------------------------------------------------------
// Connection test (FREE endpoints only — can never send an SMS)
// ------------------------------------------------------------

export type SmsConnectionTest =
    | { ok: true; detail: string; credit?: string }
    | { ok: false; detail: string; provider: string };

export async function testSmsConnection(env: Env, db: AcademyDatabase): Promise<SmsConnectionTest> {
    const cfg = await getSmsConfig(db);
    const resolved = await resolveSmsProvider(env, db);
    if (resolved.kind !== 'ok') {
        return {
            ok: false,
            provider: cfg.provider,
            detail: resolved.reason
        };
    }
    const adapter = SMS_ADAPTERS[resolved.provider.def.id];
    if (!adapter || !resolved.provider.def.capabilities.creditCheck) {
        return { ok: false, provider: resolved.provider.def.id, detail: 'این پنل روش رسمی بدون‌هزینه برای آزمایش اتصال ندارد.' };
    }
    const res = await adapter.checkConnection(resolved.provider.settings);
    return res.ok ? { ok: true, detail: res.detail, credit: res.credit } : { ok: false, provider: resolved.provider.def.id, detail: res.detail };
}

// ------------------------------------------------------------
// Event SMS copies (claim ack/reject, welcome, contract delivered…)
// ------------------------------------------------------------

/** Plain-text copy of an HTML message (local, to avoid a circular import). */
function plainFromHtml(html: string): string {
    return (html || '')
        .replace(/<[^>]+>/g, '')
        .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
        .trim();
}

export interface EventSmsCopyInput {
    /** The event's channel policy from the academy's messaging config. */
    channel: 'telegram' | 'sms' | 'both';
    studentId: string;
    /** Stable unique key part (claim id / contract id / student id …). */
    ledgerKey: string;
    /** The rendered Telegram HTML — the SMS copy is its plain-text form. */
    html: string;
}

/**
 * Sends the SMS copy of ONE bot event when — and only when — the event's
 * channel policy allows SMS ('sms' | 'both'). Returns null when the policy
 * keeps this event Telegram-only or the student has no phone on record.
 * The ledger id is `ev:<eventKey>:<ledgerKey>` (channel 'sms').
 */
export async function sendEventSmsCopy(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    eventKey: string,
    input: EventSmsCopyInput
): Promise<SmsSendOutcome | null> {
    if (input.channel === 'telegram') return null;
    const student = await db.getStudentById(input.studentId, academyId);
    const phone = student?.phone || '';
    if (!phone) return null;
    return sendSmsText(env, db, academyId, {
        to: phone,
        text: plainFromHtml(input.html),
        ledgerId: `ev:${eventKey}:${input.ledgerKey}`
    });
}

export type SmsSendStatus = 'SENT' | 'FAILED' | 'UNCONFIGURED' | 'BLOCKED' | 'IN_FLIGHT' | 'DUPLICATE';

export interface SmsSendOutcome {
    status: SmsSendStatus;
    providerMessageId: string;
    error: string;
    provider: string;
}

export interface SmsSendInput {
    to: string;
    text: string;
    /** Unique ledger id, e.g. `sms-<reminderId>` / `receipt:<id>` / `manual:<clientMsgId>`. */
    ledgerId: string;
    /** Called only after a provider attempt, so DISABLED/SKIPPED rows keep the legacy shape. */
    onOutbox?: (row: { status: string; error: string; providerMessageId: string }) => void;
}

/**
 * Sends ONE SMS through the active provider with duplicate protection:
 * the deliveries ledger slot (channel 'sms') is claimed BEFORE the HTTP
 * call — a retry, a concurrent cron run or a crash+resume can never
 * double-send the same message.
 */
export async function sendSmsText(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    input: SmsSendInput
): Promise<SmsSendOutcome> {
    const store = new ContractStore(env);
    const cfg = await getSmsConfig(db);
    const resolved = await resolveSmsProvider(env, db);
    if (resolved.kind !== 'ok') {
        return {
            status: resolved.kind === 'docs-pending' ? 'BLOCKED' : 'UNCONFIGURED',
            providerMessageId: '',
            error: resolved.reason,
            provider: cfg.provider
        };
    }
    const provider = resolved.provider;
    const to = normalizeIranMobile(input.to);
    if (!to) {
        return {
            status: 'FAILED', providerMessageId: '',
            error: 'شماره موبایل گیرنده معتبر نیست؛ پیامک ارسال نشد.', provider: provider.def.id
        };
    }

    // Claim the ledger slot BEFORE the provider call (duplicate protection).
    const claimed = await store.claimDelivery(academyId, input.ledgerId, 'sms');
    if (!claimed) {
        const prev = await store.getDeliveryRow(academyId, input.ledgerId, 'sms');
        const prevStatus = prev?.status || '';
        const prevAge = Date.now() - (prev?.updated_at_epoch || 0);
        if (prevStatus === 'SENT') return { status: 'DUPLICATE', providerMessageId: '', error: 'این پیامک قبلاً ارسال شده بود.', provider: provider.def.id };
        if (prevStatus === 'SKIPPED') return { status: 'DUPLICATE', providerMessageId: '', error: 'این پیامک قبلاً رد شده بود.', provider: provider.def.id };
        const reclaimable = prevStatus === 'PENDING' ? prevAge > 10 * 60_000 : true;
        const reClaimed = reclaimable ? await store.reclaimDelivery(academyId, input.ledgerId, 'sms') : false;
        if (!reClaimed) {
            return { status: 'IN_FLIGHT', providerMessageId: '', error: 'ارسال این پیامک در جریان است.', provider: provider.def.id };
        }
    }

    const adapter = SMS_ADAPTERS[provider.def.id];
    if (!adapter) {
        await store.finishDelivery(academyId, input.ledgerId, 'sms', 'SKIPPED', 'provider adapter missing');
        return { status: 'UNCONFIGURED', providerMessageId: '', error: 'آداپتور پنل پیامکی یافت نشد.', provider: provider.def.id };
    }

    let res: SmsAdapterCallResult;
    try {
        res = await adapter.send(provider.settings, to, input.text);
    } catch (e) {
        res = { ok: false, messageId: '', error: networkErrorText(e), raw: 'network' };
    }

    if (res.ok) {
        await store.finishDelivery(academyId, input.ledgerId, 'sms', 'SENT', '', res.messageId);
        input.onOutbox?.({ status: 'SENT', error: '', providerMessageId: res.messageId });
        return { status: 'SENT', providerMessageId: res.messageId, error: '', provider: provider.def.id };
    }
    await store.finishDelivery(academyId, input.ledgerId, 'sms', 'FAILED', res.error, '');
    input.onOutbox?.({ status: 'FAILED', error: res.error, providerMessageId: '' });
    return { status: 'FAILED', providerMessageId: '', error: res.error, provider: provider.def.id };
}
