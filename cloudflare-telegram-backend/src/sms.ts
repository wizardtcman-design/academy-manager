// ============================================================
// Stage 9 + provider architecture: the common SMS-provider layer.
//
// ONE formal contract (SmsProviderAdapter), many panels: reminders,
// receipts, claim acks/rejections, welcome messages and manager manual
// sends all go through sendSmsText()/sendSmsTemplate()/
// resolveSmsProvider() — business logic never talks to a panel
// directly. Adding a future panel touches ONLY SMS_PROVIDERS + its
// adapter here (see docs/PROVIDER_DEVELOPMENT.md).
//
// Every adapter maps provider-specific responses onto the STANDARD
// system error codes (SmsErrorCode) and produces key-free Persian
// messages for humans. Tracking id, status and outcome are recorded in
// the deliveries ledger + sms_outbox by the shared layer.
//
// Providers implemented (official docs only, nothing guessed — see
// docs/SMS_PANELS.md for sources; re-verified against the live official
// page https://sms.ir/rest-api/):
//   - smsir   : SMS.ir REST API v1
//               auth: X-API-KEY header;
//               text send: POST /v1/send/bulk {lineNumber, messageText,
//               mobiles[]} → data {packId, messageIds[], cost}; the docs
//               define messageIds[i]=0 → recipient blacklisted and null
//               → invalid number / text too long (both NOT delivered);
//               template send: POST /v1/send/verify {mobile, templateId,
//               parameters:[{name,value}]} → data {messageId, cost};
//               delivery report: GET /v1/send/{messageId} → data with
//               deliveryState (Nullable Byte — the official REST page
//               publishes NO value legend, so the raw value is reported
//               honestly, never interpreted);
//               credit: GET /v1/credit (FREE, used for connection test).
//   - ryesms  : رایس‌اس‌ام‌اس — the panel (panel6.ir) publishes NO public
//               web-service documentation (site + docs paths + search
//               engines re-probed; the site itself answers WAF 403). The
//               provider SLOT is registered so the settings UI can list
//               it and store its fields, but its adapter honestly
//               refuses everything with docs_pending until the owner
//               supplies the real API spec. NO endpoint or parameter is
//               invented anywhere.
//
// Security rules (enforced here):
//   - API keys live per academy in the namespaced D1 table
//     academy_settings (existing project mechanism, same as
//     gateway_api_key; scoped "<academyId>:<key>") and are NEVER
//     returned by any endpoint (only masked booleans), NEVER logged,
//     NEVER included in error texts. The app-side settings sync is
//     FORBIDDEN from writing these keys (reserved on /api/sync/settings).
//   - An optional Cloudflare Secret (SMSIR_API_KEY) overrides the
//     stored key at runtime — see docs/SMS_PANELS.md for the exact
//     `wrangler secret put` commands.
//   - testSmsConnection() uses ONLY free/no-cost official endpoints
//     (SMS.ir /v1/credit) — it can never send a billable SMS.
//   - sendSmsText()/sendSmsTemplate() claim the deliveries ledger
//     BEFORE calling the provider, so a retry or an overlapping run can
//     never double-send.
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

// ------------------------------------------------------------
// STANDARD system error codes — every adapter maps its provider's
// specific responses onto this vocabulary so the rest of the system
// (ledger, outbox, admin UI, tests) never sees provider internals.
// ------------------------------------------------------------
export type SmsErrorCode =
    | 'OK'
    | 'UNAUTHENTICATED'      // invalid / disabled / IP-restricted key, blocked account
    | 'NO_CREDIT'            // panel balance insufficient
    | 'INVALID_LINE'         // bad or inactive sender line
    | 'INVALID_MOBILE'       // recipient number rejected by the panel
    | 'RATE_LIMITED'         // panel-side throttling
    | 'REJECTED'             // content/recipient policy: blacklist, unapproved text, empty text…
    | 'PROVIDER_ERROR'       // panel-side failure the docs do not classify further
    | 'UNEXPECTED_RESPONSE'  // answer outside the documented contract
    | 'NETWORK'              // connection could not be established
    | 'TIMEOUT'              // the panel did not answer in time
    | 'NOT_CONFIGURED'       // local settings incomplete / no provider selected
    | 'DOCS_PENDING';        // provider slot exists but official docs are missing

export const SMS_ERROR_LABELS: Record<SmsErrorCode, string> = {
    OK: 'موفق',
    UNAUTHENTICATED: 'احراز هویت',
    NO_CREDIT: 'اعتبار',
    INVALID_LINE: 'خط ارسال',
    INVALID_MOBILE: 'شماره گیرنده',
    RATE_LIMITED: 'محدودیت تعداد',
    REJECTED: 'رد شد',
    PROVIDER_ERROR: 'خطای پنل',
    UNEXPECTED_RESPONSE: 'پاسخ غیرمنتظره',
    NETWORK: 'شبکه',
    TIMEOUT: 'مهلت',
    NOT_CONFIGURED: 'پیکربندی نشده',
    DOCS_PENDING: 'در انتظار مستندات'
};

/** Provider-independent delivery information (capability-gated). */
export interface SmsDeliveryInfo {
    ok: boolean;
    code: SmsErrorCode;
    /** Persian human message (never contains credentials). */
    detail: string;
    /** Raw provider delivery state (NOT interpreted — no official legend). */
    deliveryState: string;
    /** Unix ms of the delivery event when the provider reports one; else 0. */
    deliveredAtEpoch: number;
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
    /** Standard system error code (see SmsErrorCode). */
    code: SmsErrorCode;
    /** Provider tracking id (messageId / packId) when the API returns one. */
    messageId: string;
    /** Human-readable Persian error; NEVER contains credentials. */
    error: string;
    /** Sanitized short response echo for the ledger (no keys, no full text). */
    raw: string;
}

export interface SmsConnectionCheckResult {
    ok: boolean;
    code: SmsErrorCode;
    detail: string;
    credit?: string;
}

/**
 * THE provider contract (formal). A new panel implements this interface
 * and is registered in SMS_ADAPTERS — nothing else in the system changes
 * (docs/PROVIDER_DEVELOPMENT.md). All methods MUST be key-free in their
 * outputs and MUST honour the 12s timeout via fetchWithTimeout.
 */
export interface SmsProviderAdapter {
    readonly id: string;
    /** Local readiness check of THIS provider's settings shape (no network). */
    validateSettings(settings: Record<string, string>): { ok: boolean; missing: string[] };
    /** MUST be a free/no-cost endpoint only (credit/balance); never sends. */
    checkConnection(settings: Record<string, string>): Promise<SmsConnectionCheckResult>;
    /** Sends ONE text to ONE normalized mobile. */
    sendText(settings: Record<string, string>, to: string, text: string): Promise<SmsAdapterCallResult>;
    /** Template (service-line) send — required only when capabilities.templateSend. */
    sendTemplate?(settings: Record<string, string>, to: string, templateId: string, parameters: Array<{ name: string; value: string }>): Promise<SmsAdapterCallResult>;
    /** Delivery status of ONE message — required only when capabilities.deliveryReport. */
    queryDelivery?(settings: Record<string, string>, messageId: string): Promise<SmsDeliveryInfo>;
}

/** Internal alias — the formal contract every registered adapter implements. */
type SmsAdapter = SmsProviderAdapter;

function okResult(messageId: string, raw: string): SmsAdapterCallResult {
    return { ok: true, code: 'OK', messageId, error: '', raw };
}
function failResult(code: SmsErrorCode, error: string, raw: string): SmsAdapterCallResult {
    return { ok: false, code, messageId: '', error, raw };
}

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
function networkError(e: unknown): { text: string; code: SmsErrorCode } {
    const msg = e instanceof Error ? e.message : String(e);
    if (/abort/i.test(msg)) return { text: networkErrorText(e), code: 'TIMEOUT' };
    return { text: networkErrorText(e), code: 'NETWORK' };
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

/** Official status table → STANDARD system error codes. */
function smsIrStatusCode(status: number): SmsErrorCode {
    if (status === 1) return 'OK';
    if ([10, 11, 12, 13, 14].includes(status)) return 'UNAUTHENTICATED';
    if (status === 20) return 'RATE_LIMITED';
    if (status === 101 || status === 123) return 'INVALID_LINE';
    if (status === 102) return 'NO_CREDIT';
    if (status === 104) return 'INVALID_MOBILE';
    if ([0, 103, 105, 115, 117].includes(status)) return 'REJECTED';
    return 'PROVIDER_ERROR';
}

const SMSIR_BASE = 'https://api.sms.ir/v1';

const smsIrAdapter: SmsAdapter = {
    id: 'smsir',

    // Local readiness — no network, no cost.
    validateSettings(settings) {
        const missing = ['api_key', 'line'].filter((k) => !(settings[k] || '').trim());
        return { ok: missing.length === 0, missing };
    },

    // GET /v1/credit — the official FREE balance endpoint (no SMS is sent).
    async checkConnection(settings): Promise<SmsConnectionCheckResult> {
        try {
            const res = await fetchWithTimeout(SMSIR_BASE + '/credit', {
                method: 'GET',
                headers: { 'X-API-KEY': settings.api_key, Accept: 'application/json' }
            });
            let body: any = null;
            try { body = await res.json(); } catch (_) { /* non-JSON error page */ }
            if (res.status === 401) {
                return { ok: false, code: 'UNAUTHENTICATED', detail: 'کلید وب‌سرویس نامعتبر است (۴۰۱).' };
            }
            if (body && typeof body.status === 'number' && body.status !== 1) {
                return { ok: false, code: smsIrStatusCode(body.status), detail: smsIrStatusText(body.status) };
            }
            if (res.status !== 200) {
                return { ok: false, code: 'UNEXPECTED_RESPONSE', detail: `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).` };
            }
            const credit = body?.data;
            return {
                ok: true,
                code: 'OK',
                detail: 'اتصال با پنل SMS.ir برقرار است.',
                credit: credit === undefined || credit === null ? undefined : String(credit)
            };
        } catch (e) {
            const n = networkError(e);
            return { ok: false, code: n.code, detail: n.text };
        }
    },

    // POST /v1/send/bulk — {lineNumber, messageText, mobiles[]} (official docs).
    // Per the official page: messageIds[i] = 0 → that recipient is
    // BLACKLISTED; null → invalid number or text too long — neither is a
    // delivery, so the adapter reports them as failures, never a fake SENT.
    async sendText(settings, to, text): Promise<SmsAdapterCallResult> {
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
                return failResult(smsIrStatusCode(body.status), smsIrStatusText(body.status), `status:${body.status}`);
            }
            if (res.status !== 200 || !body || body.status !== 1) {
                return failResult('UNEXPECTED_RESPONSE', `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).`, `http:${res.status}`);
            }
            const data = body.data || {};
            const packId = String(data.packId || '');
            // ONE recipient was sent: its own messageId decides the outcome
            // (docs — 0 = blacklist, null = invalid number / text too long).
            if (Array.isArray(data.messageIds) && data.messageIds.length) {
                const first = data.messageIds[0];
                if (first === 0 || first === '0') {
                    return failResult('REJECTED', smsIrStatusText(115), `pack:${packId};msg:0`);
                }
                if (first === null || first === undefined) {
                    return failResult('INVALID_MOBILE', 'شماره گیرنده نامعتبر است یا متن بیش از حد مجاز بوده است.', `pack:${packId};msg:null`);
                }
                return okResult(String(first), `pack:${packId}`);
            }
            if (packId) {
                // Array absent (outside the documented shape): the pack id is
                // the only tracking reference the panel gave us.
                return okResult(packId, `pack:${packId}`);
            }
            return failResult('UNEXPECTED_RESPONSE', 'پاسخ غیرمنتظره پنل پیامکی: شناسه پیامک برگردانده نشد.', 'no-id');
        } catch (e) {
            const n = networkError(e);
            return failResult(n.code, n.text, 'network');
        }
    },

    // POST /v1/send/verify — {mobile, templateId, parameters:[{name,value}]}
    // (official docs; service-line template send, data:{messageId, cost}).
    async sendTemplate(settings, to, templateId, parameters): Promise<SmsAdapterCallResult> {
        try {
            const res = await fetchWithTimeout(SMSIR_BASE + '/send/verify', {
                method: 'POST',
                headers: {
                    'X-API-KEY': settings.api_key,
                    'Content-Type': 'application/json',
                    Accept: 'application/json'
                },
                body: JSON.stringify({
                    mobile: to,
                    templateId: Number(templateId),
                    parameters: parameters.map((p) => ({ name: String(p.name), value: String(p.value) }))
                })
            });
            let body: any = null;
            try { body = await res.json(); } catch (_) { /* non-JSON error page */ }
            if (body && typeof body.status === 'number' && body.status !== 1) {
                return failResult(smsIrStatusCode(body.status), smsIrStatusText(body.status), `status:${body.status}`);
            }
            if (res.status !== 200 || !body || body.status !== 1) {
                return failResult('UNEXPECTED_RESPONSE', `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).`, `http:${res.status}`);
            }
            const messageId = String(body.data?.messageId || '');
            if (!messageId || messageId === '0') {
                return failResult('UNEXPECTED_RESPONSE', 'پاسخ غیرمنتظره پنل پیامکی: شناسه پیامک برگردانده نشد.', 'no-id');
            }
            return okResult(messageId, 'verify');
        } catch (e) {
            const n = networkError(e);
            return failResult(n.code, n.text, 'network');
        }
    },

    // GET /v1/send/{messageId} — official delivery report. The official REST
    // page publishes NO legend for deliveryState values (Nullable Byte), so
    // the RAW value is reported honestly and never interpreted.
    async queryDelivery(settings, messageId): Promise<SmsDeliveryInfo> {
        try {
            const res = await fetchWithTimeout(`${SMSIR_BASE}/send/${encodeURIComponent(messageId)}`, {
                method: 'GET',
                headers: { 'X-API-KEY': settings.api_key, Accept: 'application/json' }
            });
            let body: any = null;
            try { body = await res.json(); } catch (_) { /* non-JSON error page */ }
            if (res.status === 401) {
                return { ok: false, code: 'UNAUTHENTICATED', detail: 'کلید وب‌سرویس نامعتبر است (۴۰۱).', deliveryState: '', deliveredAtEpoch: 0 };
            }
            if (body && typeof body.status === 'number' && body.status !== 1) {
                return { ok: false, code: smsIrStatusCode(body.status), detail: smsIrStatusText(body.status), deliveryState: '', deliveredAtEpoch: 0 };
            }
            if (res.status !== 200 || !body || body.status !== 1) {
                return { ok: false, code: 'UNEXPECTED_RESPONSE', detail: `پاسخ غیرمنتظره پنل پیامکی (کد HTTP ${res.status}).`, deliveryState: '', deliveredAtEpoch: 0 };
            }
            const data = body.data || {};
            const state = data.deliveryState === null || data.deliveryState === undefined ? '' : String(data.deliveryState);
            const at = Number(data.deliveryDateTime || 0);
            return {
                ok: true,
                code: 'OK',
                detail: state
                    ? `وضعیت دلیوری پنل: ${state}${at > 0 ? ` (زمان دلیوری: ${at})` : ''}`
                    : 'پنل هنوز وضعیت دلیوری را گزارش نکرده است.',
                deliveryState: state,
                deliveredAtEpoch: at > 0 ? at * 1000 : 0
            };
        } catch (e) {
            const n = networkError(e);
            return { ok: false, code: n.code, detail: n.text, deliveryState: '', deliveredAtEpoch: 0 };
        }
    }
};

/**
 * RyeSMS adapter — DOCS PENDING. The panel publishes no public web-service
 * documentation (verified: site sitemap, doc paths, search engines — see
 * docs/SMS_PANELS.md). Every method honestly refuses; NOTHING is invented.
 */
const ryeSmsDocsPending: SmsConnectionCheckResult = {
    ok: false,
    code: 'DOCS_PENDING',
    detail: 'مستندات رسمی وب‌سرویس رایس‌اس‌ام‌اس به‌صورت عمومی منتشر نشده است؛ آزمایش اتصال پس از دسترسی به مستندات فعال می‌شود.'
};

const ryeSmsAdapter: SmsAdapter = {
    id: 'rysesms',
    validateSettings(settings) {
        const missing = ['api_key', 'line'].filter((k) => !(settings[k] || '').trim());
        return { ok: missing.length === 0, missing };
    },
    async checkConnection() {
        return ryeSmsDocsPending;
    },
    async sendText() {
        return failResult('DOCS_PENDING',
            'ارسال از رایس‌اس‌ام‌اس هنوز فعال نیست: مستندات رسمی وب‌سرویس در دسترس نیست.',
            'docs_pending');
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
    /** Standard system error code of a failed attempt ('' when not applicable). */
    code: SmsErrorCode | '';
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
 * Shared duplicate-protected send path (text + template). The deliveries
 * ledger slot (channel 'sms') is claimed BEFORE the HTTP call — a retry, a
 * concurrent cron run or a crash+resume can never double-send the message.
 */
async function sendViaProvider(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    input: SmsSendInput,
    invoke: (adapter: SmsAdapter, settings: Record<string, string>, to: string) => Promise<SmsAdapterCallResult>
): Promise<SmsSendOutcome> {
    const store = new ContractStore(env);
    const cfg = await getSmsConfig(db);
    const resolved = await resolveSmsProvider(env, db);
    if (resolved.kind !== 'ok') {
        return {
            status: resolved.kind === 'docs-pending' ? 'BLOCKED' : 'UNCONFIGURED',
            code: resolved.kind === 'docs-pending' ? 'DOCS_PENDING' : 'NOT_CONFIGURED',
            providerMessageId: '',
            error: resolved.reason,
            provider: cfg.provider
        };
    }
    const provider = resolved.provider;
    const to = normalizeIranMobile(input.to);
    if (!to) {
        return {
            status: 'FAILED', code: 'INVALID_MOBILE', providerMessageId: '',
            error: 'شماره موبایل گیرنده معتبر نیست؛ پیامک ارسال نشد.', provider: provider.def.id
        };
    }

    // Claim the ledger slot BEFORE the provider call (duplicate protection).
    const claimed = await store.claimDelivery(academyId, input.ledgerId, 'sms');
    if (!claimed) {
        const prev = await store.getDeliveryRow(academyId, input.ledgerId, 'sms');
        const prevStatus = prev?.status || '';
        const prevAge = Date.now() - (prev?.updated_at_epoch || 0);
        if (prevStatus === 'SENT') return { status: 'DUPLICATE', code: '', providerMessageId: '', error: 'این پیامک قبلاً ارسال شده بود.', provider: provider.def.id };
        if (prevStatus === 'SKIPPED') return { status: 'DUPLICATE', code: '', providerMessageId: '', error: 'این پیامک قبلاً رد شده بود.', provider: provider.def.id };
        const reclaimable = prevStatus === 'PENDING' ? prevAge > 10 * 60_000 : true;
        const reClaimed = reclaimable ? await store.reclaimDelivery(academyId, input.ledgerId, 'sms') : false;
        if (!reClaimed) {
            return { status: 'IN_FLIGHT', code: '', providerMessageId: '', error: 'ارسال این پیامک در جریان است.', provider: provider.def.id };
        }
    }

    const adapter = SMS_ADAPTERS[provider.def.id];
    if (!adapter) {
        await store.finishDelivery(academyId, input.ledgerId, 'sms', 'SKIPPED', 'provider adapter missing');
        return { status: 'UNCONFIGURED', code: 'NOT_CONFIGURED', providerMessageId: '', error: 'آداپتور پنل پیامکی یافت نشد.', provider: provider.def.id };
    }

    let res: SmsAdapterCallResult;
    try {
        res = await invoke(adapter, provider.settings, to);
    } catch (e) {
        const n = networkError(e);
        res = { ok: false, code: n.code, messageId: '', error: n.text, raw: 'network' };
    }

    if (res.ok) {
        await store.finishDelivery(academyId, input.ledgerId, 'sms', 'SENT', '', res.messageId);
        input.onOutbox?.({ status: 'SENT', error: '', providerMessageId: res.messageId });
        return { status: 'SENT', code: '', providerMessageId: res.messageId, error: '', provider: provider.def.id };
    }
    await store.finishDelivery(academyId, input.ledgerId, 'sms', 'FAILED', res.error, '');
    input.onOutbox?.({ status: 'FAILED', error: res.error, providerMessageId: '' });
    return { status: 'FAILED', code: res.code, providerMessageId: '', error: res.error, provider: provider.def.id };
}

/**
 * Sends ONE text SMS through the active provider (duplicate-protected).
 */
export async function sendSmsText(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    input: SmsSendInput
): Promise<SmsSendOutcome> {
    return sendViaProvider(env, db, academyId, input, (adapter, settings, to) => adapter.sendText(settings, to, input.text));
}

// ------------------------------------------------------------
// Template (service-line) send — contract capability, duplicate-protected
// ------------------------------------------------------------

export interface SmsTemplateSendInput {
    to: string;
    /** The provider's own template id (SMS.ir: numeric قالب خدماتی id). */
    templateId: string;
    parameters: Array<{ name: string; value: string }>;
    /** Unique ledger id, e.g. `manual-tpl:<clientMsgId>`. */
    ledgerId: string;
    onOutbox?: (row: { status: string; error: string; providerMessageId: string }) => void;
}

/** Template sends reuse the text path's envelope (ledger claim, outbox, outcome). */
function templateAsSendInput(input: SmsTemplateSendInput): SmsSendInput {
    return {
        to: input.to,
        text: '', // the template + parameters define the body panel-side
        ledgerId: input.ledgerId,
        onOutbox: input.onOutbox
    };
}

/**
 * Sends ONE template SMS through the active provider when — and only when —
 * the provider's official API documents a template send and the adapter
 * implements it. The SAME duplicate protection as sendSmsText applies.
 */
export async function sendSmsTemplate(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    input: SmsTemplateSendInput
): Promise<SmsSendOutcome> {
    const cfg = await getSmsConfig(db);
    const def = cfg.provider ? SMS_PROVIDERS[cfg.provider] : null;
    if (!def || !def.capabilities.templateSend) {
        return {
            status: 'BLOCKED', code: 'DOCS_PENDING', providerMessageId: '',
            error: 'این پنل ارسال قالبی مستندشده ندارد؛ ارسال انجام نشد.', provider: cfg.provider
        };
    }
    return sendViaProvider(env, db, academyId, templateAsSendInput(input), (adapter, settings, to) => {
        if (!adapter.sendTemplate) {
            return Promise.resolve(failResult('DOCS_PENDING', 'ارسال قالبی برای این پنل پیاده‌سازی نشده است.', 'no-template'));
        }
        return adapter.sendTemplate(settings, to, input.templateId, input.parameters);
    });
}

// ------------------------------------------------------------
// Delivery status (contract capability — raw values, never interpreted)
// ------------------------------------------------------------

export type SmsDeliveryLookup =
    | { kind: 'ok'; provider: string; deliveryState: string; deliveredAtEpoch: number; detail: string }
    | { kind: 'unsupported'; provider: string; detail: string }
    | { kind: 'unknown-ledger'; detail: string }
    | { kind: 'error'; provider: string; code: SmsErrorCode; detail: string };

/**
 * Queries the panel's delivery report for ONE previously sent message.
 * The ledger id is the SAME id the send used (e.g. `sms-<reminderId>` or
 * `manual:<clientMsgId>`); the provider tracking id recorded at send time
 * is what the panel is asked about. Raw delivery state only — the official
 * REST docs publish no value legend, so nothing is interpreted here.
 */
export async function querySmsDeliveryStatus(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    ledgerId: string
): Promise<SmsDeliveryLookup> {
    const cfg = await getSmsConfig(db);
    const def = cfg.provider ? SMS_PROVIDERS[cfg.provider] : null;
    if (!def || !def.capabilities.deliveryReport) {
        return { kind: 'unsupported', provider: def?.id || '', detail: 'این پنل گزارش دلیوری مستندشده ندارد.' };
    }
    const store = new ContractStore(env);
    const row = await store.getDeliveryRow(academyId, ledgerId, 'sms');
    const providerMessageId = String(row?.provider_message_id || '');
    if (!row || !providerMessageId) {
        return { kind: 'unknown-ledger', detail: 'ارسالی با این شناسه در دفتر ثبت تحویل پیدا نشد.' };
    }
    if (row.status !== 'SENT') {
        return { kind: 'unknown-ledger', detail: 'این پیامک موفق ثبت نشده است؛ گزارش دلیوری فقط برای ارسال‌های موفق معنا دارد.' };
    }
    const resolved = await resolveSmsProvider(env, db);
    if (resolved.kind !== 'ok') {
        return { kind: 'error', provider: def.id, code: resolved.kind === 'docs-pending' ? 'DOCS_PENDING' : 'NOT_CONFIGURED', detail: resolved.reason };
    }
    const adapter = SMS_ADAPTERS[def.id];
    if (!adapter?.queryDelivery) {
        return { kind: 'unsupported', provider: def.id, detail: 'این پنل گزارش دلیوری مستندشده ندارد.' };
    }
    const info = await adapter.queryDelivery(resolved.provider.settings, providerMessageId);
    if (!info.ok) {
        return { kind: 'error', provider: def.id, code: info.code, detail: info.detail };
    }
    return { kind: 'ok', provider: def.id, deliveryState: info.deliveryState, deliveredAtEpoch: info.deliveredAtEpoch, detail: info.detail };
}
