// ============================================================
// Stage 6: messaging configuration, per-event templates and the
// channel policy layer.
//
// ONE system, no parallel pipeline: every text the worker already
// sends (Stage 2 welcome, Stage 4 receipts, Stage 5 reminders,
// claim acks/rejections) keeps its exact current rendering as the
// DEFAULT template. Managers can override each template, toggle
// auto/manual sending per event type and pick the allowed channel
// (telegram | sms | both) per academy. Nothing here can register a
// payment, alter an installment or bypass the Stage-4/5 pipelines —
// templates only shape TEXT.
//
// Storage: the per-academy namespaced academy_settings KV (key
// `messaging_config`), so two academies can never see each other's
// config and NO new migration is required. Send time / timezone stay
// in the Stage-5 keys (reminder_send_hour / reminder_send_minute /
// reminder_timezone) — the same values the scheduler already reads.
//
// Channels: Telegram is the only REAL channel. SMS stays disabled
// end-to-end (no panel chosen in this stage): any SMS selection is
// reported honestly as `UNCONFIGURED / sms_provider_not_configured`
// and NO real SMS can ever leave the worker. One channel's failure
// never erases the other channel's success (per-channel ledger).
// ============================================================

import { AcademyDatabase } from './db';

// ------------------------------------------------------------
// Event registry
// ------------------------------------------------------------

export type MsgEventKey =
    | 'WELCOME'              // registration welcome + connect/signature request (contracts-api.welcomeMessageText)
    | 'CONNECT_WELCOME'      // /start one-time-link success greeting
    | 'INSTALLMENT_REMINDER' // Stage-5 nobs -7/-4/-1/0/+1
    | 'OVERDUE_DAILY'        // Stage-6 daily overdue continuation (day >= 2)
    | 'PAYMENT_RECEIPT'      // Stage-4 official receipt text
    | 'REVERSAL_NOTICE'      // Stage-4 reversal notice text
    | 'RECEIPT_REJECTED'     // manager rejected the student's claim (student notice)
    | 'CLAIM_ACK'            // student receipt-submission acknowledgement
    | 'CONTRACT_DELIVERED';  // signed-PDF follow-up text

export const MSG_EVENT_KEYS: MsgEventKey[] = [
    'WELCOME', 'CONNECT_WELCOME', 'INSTALLMENT_REMINDER', 'OVERDUE_DAILY',
    'PAYMENT_RECEIPT', 'REVERSAL_NOTICE', 'RECEIPT_REJECTED', 'CLAIM_ACK', 'CONTRACT_DELIVERED'
];

/** Persian titles shown in the settings UI. */
export const MSG_EVENT_TITLES: Record<MsgEventKey, string> = {
    WELCOME: 'خوشامدگویی پس از ثبت هنرجو (و دعوت به اتصال/امضا)',
    CONNECT_WELCOME: 'خوشامدگویی اتصال موفق تلگرام',
    INSTALLMENT_REMINDER: 'یادآوری اقساط (۷/۴/۱ روز قبل و روز سررسید)',
    OVERDUE_DAILY: 'یادآوری روزانه قسط معوق (از ۱ روز پس از سررسید)',
    PAYMENT_RECEIPT: 'تأیید ثبت پرداخت واقعی (رسید رسمی)',
    REVERSAL_NOTICE: 'اطلاع‌رسانی برگشت/ابطال پرداخت',
    RECEIPT_REJECTED: 'اطلاع‌رسانی رد مدرک پرداخت هنرجو',
    CLAIM_ACK: 'دریافت مدرک پرداخت هنرجو (در انتظار بررسی)',
    CONTRACT_DELIVERED: 'تحویل قرارداد امضاشده'
};

export type ChannelPolicy = 'telegram' | 'sms' | 'both';

export interface MsgEventConfig {
    auto: boolean;    // automatic sends of this event type are allowed
    manual: boolean;  // manager manual sends of this event type are allowed
    channel: ChannelPolicy;
    template?: string; // absent → default template
}

export interface OverdueDailyConfig {
    enabled: boolean;
    /** How many days ahead of "today" to pre-schedule the daily overdue chain. */
    forwardDays: number;
}

export interface MessagingConfig {
    version: 1;
    events: Record<MsgEventKey, MsgEventConfig>;
    overdueDaily: OverdueDailyConfig;
}

/** A template variable: `line` variables are pre-composed trusted HTML lines
 *  built server-side from validated data; all other values are HTML-escaped. */
export interface MsgVarDef {
    name: string;
    title: string;
    kind: 'text' | 'money' | 'date' | 'line' | 'link' | 'number';
    required?: boolean;
}

interface MsgEventDef {
    key: MsgEventKey;
    title: string;
    template: string;
    vars: MsgVarDef[];
}

const D = '━━━━━━━━━━━━━━━━━━━';

const RECEIPT_VARS: MsgVarDef[] = [
    { name: 'headLine', title: 'تیتر پیام', kind: 'line', required: true },
    { name: 'academyName', title: 'نام آموزشگاه', kind: 'text', required: true },
    { name: 'transactionCode', title: 'کد تراکنش', kind: 'text', required: true },
    { name: 'dateLine', title: 'تاریخ پرداخت', kind: 'line', required: true },
    { name: 'studentName', title: 'نام هنرجو', kind: 'text', required: true },
    { name: 'installmentLine', title: 'قسط و دوره', kind: 'line', required: true },
    { name: 'amount', title: 'مبلغ (با جداکننده)', kind: 'money', required: true },
    { name: 'methodLabel', title: 'روش پرداخت', kind: 'text', required: true },
    { name: 'destinationLine', title: 'خط حساب مقصد (اختیاری)', kind: 'line' },
    { name: 'trackingLine', title: 'خط کد رهگیری (اختیاری)', kind: 'line' },
    { name: 'onlineLine', title: 'خط پرداخت آنلاین (اختیاری)', kind: 'line' },
    { name: 'reversalLine', title: 'خط دلیل برگشت (اختیاری)', kind: 'line' },
    { name: 'notesLine', title: 'خط توضیحات (اختیاری)', kind: 'line' },
    { name: 'footerLine', title: 'پایان پیام', kind: 'line', required: true }
];

const RECEIPT_TEMPLATE_BODY =
    `{headLine}\n${D}\n` +
    `🏫 <b>{academyName}</b>\n` +
    `🧾 کد تراکنش: <code>{transactionCode}</code>\n` +
    `🗓 تاریخ: {dateLine}\n` +
    `👤 هنرجو: <b>{studentName}</b>\n` +
    `📚 {installmentLine}\n` +
    `💵 مبلغ: <b>{amount}</b>\n` +
    `💳 روش پرداخت: {methodLabel}\n` +
    `{destinationLine}{trackingLine}{onlineLine}{reversalLine}{notesLine}` +
    `${D}\n{footerLine}`;

const REMINDER_VARS: MsgVarDef[] = [
    { name: 'nobHead', title: 'تیتر مرحله یادآوری', kind: 'line', required: true },
    { name: 'studentName', title: 'نام هنرجو', kind: 'text', required: true },
    { name: 'courseTitle', title: 'عنوان دوره', kind: 'text', required: true },
    { name: 'installmentLine', title: 'قسط n از N', kind: 'line', required: true },
    { name: 'nobLabel', title: 'برچسب مرحله (مثلاً ۱ روز قبل از سررسید)', kind: 'text', required: true },
    { name: 'amount', title: 'مبلغ (با جداکننده)', kind: 'money', required: true },
    { name: 'currency', title: 'واحد پول', kind: 'text', required: true },
    { name: 'dueDateShamsi', title: 'تاریخ سررسید (شمسی)', kind: 'date', required: true },
    { name: 'statusLine', title: 'وضعیت قسط', kind: 'text', required: true }
];

const REMINDER_TEMPLATE_BODY =
    `{nobHead}\n${D}\n` +
    `👤 هنرجو: <b>{studentName}</b>\n` +
    `📚 دوره: {courseTitle}\n` +
    `🔹 {installmentLine} ({nobLabel})\n` +
    `💵 مبلغ: <b>{amount} {currency}</b>\n` +
    `📆 سررسید: <b>{dueDateShamsi}</b>\n` +
    `وضعیت: <b>{statusLine}</b>\n` +
    `${D}\n` +
    `لطفاً در صورت پرداخت، رسید خود را از دکمه «ارسال رسید پرداخت» برای مدیریت ارسال کنید. 🙏`;

function msgEventDefs(): Record<MsgEventKey, MsgEventDef> {
    return {
        WELCOME: {
            key: 'WELCOME',
            title: MSG_EVENT_TITLES.WELCOME,
            template:
                `سلام {studentName} عزیز! 👋\n\n` +
                `به خانواده <b>{academyName}</b> خوش آمدید. 🌱\n` +
                `برای دریافت اعلان‌های اقساط، رسیدها و قرارداد آموزشی خود در تلگرام، روی لینک زیر بزنید و Start را بزنید:\n\n{botLink}\n\n` +
                `🔒 این لینک مخصوص شماست و فقط یک بار قابل استفاده است.`,
            vars: [
                { name: 'studentName', title: 'نام هنرجو', kind: 'text', required: true },
                { name: 'academyName', title: 'نام آموزشگاه', kind: 'text', required: true },
                { name: 'botLink', title: 'لینک اختصاصی اتصال', kind: 'link', required: true }
            ]
        },
        CONNECT_WELCOME: {
            key: 'CONNECT_WELCOME',
            title: MSG_EVENT_TITLES.CONNECT_WELCOME,
            template:
                `🎉 سلام <b>{studentName}</b> عزیز!\n\n` +
                `اتصال حساب تلگرام شما به سامانه هوشمند آموزشگاه با موفقیت برقرار شد. ✅\n\n` +
                `👤 <b>نام هنرجو:</b> {studentName}\n` +
                `{codeLine}` +
                `\nاز منوی زیر می‌توانید وضعیت مالی، اقساط، سوابق پرداختی و قراردادهای آموزشی خود را مشاهده نمایید 👇`,
            vars: [
                { name: 'studentName', title: 'نام هنرجو', kind: 'text', required: true },
                { name: 'codeLine', title: 'خط کد هنرجویی (اختیاری)', kind: 'line' }
            ]
        },
        INSTALLMENT_REMINDER: {
            key: 'INSTALLMENT_REMINDER',
            title: MSG_EVENT_TITLES.INSTALLMENT_REMINDER,
            template: REMINDER_TEMPLATE_BODY,
            vars: REMINDER_VARS
        },
        OVERDUE_DAILY: {
            key: 'OVERDUE_DAILY',
            title: MSG_EVENT_TITLES.OVERDUE_DAILY,
            template: REMINDER_TEMPLATE_BODY,
            vars: [
                ...REMINDER_VARS,
                { name: 'daysLate', title: 'تعداد روز تأخیر', kind: 'number', required: true }
            ]
        },
        PAYMENT_RECEIPT: {
            key: 'PAYMENT_RECEIPT',
            title: MSG_EVENT_TITLES.PAYMENT_RECEIPT,
            template: RECEIPT_TEMPLATE_BODY,
            vars: RECEIPT_VARS
        },
        REVERSAL_NOTICE: {
            key: 'REVERSAL_NOTICE',
            title: MSG_EVENT_TITLES.REVERSAL_NOTICE,
            template: RECEIPT_TEMPLATE_BODY,
            vars: RECEIPT_VARS
        },
        RECEIPT_REJECTED: {
            key: 'RECEIPT_REJECTED',
            title: MSG_EVENT_TITLES.RECEIPT_REJECTED,
            template:
                `❌ رسید ارسالی شما برای قسط {installmentNumber} تأیید نشد.{reasonLine}\n` +
                `در صورت نیاز با آموزشگاه تماس بگیرید یا رسید جدید ارسال کنید.`,
            vars: [
                { name: 'installmentNumber', title: 'شماره قسط', kind: 'number', required: true },
                { name: 'reasonLine', title: 'خط علت رد (اختیاری)', kind: 'line' }
            ]
        },
        CLAIM_ACK: {
            key: 'CLAIM_ACK',
            title: MSG_EVENT_TITLES.CLAIM_ACK,
            template:
                `✅ رسید شما برای <b>قسط {installmentNumber}</b> دریافت شد و برای بررسی مدیریت ارسال گردید.\n\n` +
                `⚠️ توجه: تا تأیید مدیریت، این قسط پرداخت‌شده محسوب نمی‌شود و رسید رسمی صادر نمی‌گردد. نتیجهٔ بررسی از همین ربات اطلاع‌رسانی می‌شود.`,
            vars: [
                { name: 'installmentNumber', title: 'شماره قسط', kind: 'number', required: true }
            ]
        },
        CONTRACT_DELIVERED: {
            key: 'CONTRACT_DELIVERED',
            title: MSG_EVENT_TITLES.CONTRACT_DELIVERED,
            template: `✅ این نسخهٔ نهایی و امضاشدهٔ قرارداد شماست.\nکد قرارداد: <code>{contractCode}</code>`,
            vars: [
                { name: 'contractCode', title: 'کد قرارداد', kind: 'text', required: true }
            ]
        }
    };
}

/** Immutable registry — imported by render, validation and the config API. */
export const MSG_EVENTS = msgEventDefs();

// ------------------------------------------------------------
// Rendering + validation
// ------------------------------------------------------------

const TOKEN_RE = /\{([A-Za-z_][A-Za-z0-9_]*)\}/g;
const ALLOWED_TAGS = ['b', 'i', 'u', 's', 'code', 'pre'];
const ANY_TAG_RE = /<[^>]+>/g;

export function escapeHtml(s: string): string {
    return (s || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

export interface RenderResult {
    ok: boolean;
    text: string;
    errors: string[];
}

/** Extracts template errors: unknown variables and disallowed HTML tags. */
export function validateTemplateText(key: MsgEventKey, text: string): string[] {
    const errors: string[] = [];
    const def = MSG_EVENTS[key];
    if (!def) return ['نوع پیام ناشناخته است.'];
    const known = new Set(def.vars.map((v) => v.name));
    for (const m of (text || '').matchAll(TOKEN_RE)) {
        if (!known.has(m[1])) errors.push(`متغیر ناشناخته: {${m[1]}}`);
    }
    for (const m of (text || '').matchAll(ANY_TAG_RE)) {
        // Only a small allow-list of Telegram-safe formatting tags is accepted;
        // anything else (script, style, links with arbitrary hrefs, …) is refused.
        const name = /^<\/?([a-zA-Z][a-zA-Z0-9]*)/.exec(m[0])?.[1]?.toLowerCase() || '';
        if (!ALLOWED_TAGS.includes(name)) {
            errors.push(`برچسب HTML مجاز نیست: ${m[0].slice(0, 40)}`);
        }
    }
    // A lone '<' (or a tag-like fragment ANY_TAG_RE cannot match) passes the
    // allow-list above but Telegram's HTML parser rejects it — every auto
    // send of this event would fail until the template is fixed. Refuse at
    // save time instead.
    const remainder = (text || '').replace(ANY_TAG_RE, '');
    if (remainder.includes('<')) {
        errors.push('نویسه «<» بدون برچسب کامل مجاز نیست؛ آن را حذف کنید یا از &lt; استفاده کنید.');
    }
    if ((text || '').length > 3800) errors.push('متن پیام بیش از حد مجاز طولانی است (حداکثر ۳۸۰۰ نویسه).');
    return [...new Set(errors)];
}

/**
 * Renders a template with real values. Unknown tokens and missing REQUIRED
 * values make the render INVALID (ok:false) — callers must never send an
 * invalid render; automatic pipelines fall back to the default template.
 */
export function renderMsgTemplate(
    key: MsgEventKey,
    template: string,
    vars: Record<string, string | number>
): RenderResult {
    const def = MSG_EVENTS[key];
    if (!def) return { ok: false, text: '', errors: ['نوع پیام ناشناخته است.'] };
    const errors: string[] = [];
    const known = new Map(def.vars.map((v) => [v.name, v]));
    const text = (template || '').replace(TOKEN_RE, (_m, name: string) => {
        const v = known.get(name);
        if (!v) {
            errors.push(`متغیر ناشناخته: {${name}}`);
            return _m;
        }
        const raw = vars[name];
        if (raw === undefined || raw === null || String(raw) === '') {
            if (v.required) errors.push(`مقدار ضروری خالی است: {${name}}`);
            return v.required ? _m : '';
        }
        const s = String(raw);
        return v.kind === 'line' ? s : escapeHtml(s);
    });
    // Any token that survived = unknown or missing-required → invalid render.
    // (matchAll clones the regex, so the shared TOKEN_RE state is untouched.)
    for (const m of text.matchAll(TOKEN_RE)) errors.push(`متغیر ناشناخته یا خالی: {${m[1]}}`);
    return { ok: errors.length === 0, text, errors: [...new Set(errors)] };
}

/** Plain-text copy of an HTML message (SMS outbox / preview). */
export function htmlToPlain(html: string): string {
    return (html || '')
        .replace(/<[^>]+>/g, '')
        .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
        .trim();
}

/**
 * Config-aware render used by every automatic pipeline. A manager-customized
 * template that fails to render (unknown var / missing required value) is
 * NEVER sent — the pipeline falls back to the shipped default template, and
 * only if even that fails does the caller's legacy fallback text get used.
 */
export function renderEvent(
    cfg: MessagingConfig,
    key: MsgEventKey,
    vars: Record<string, string | number>,
    fallback: string
): { text: string; fromOverride: boolean; errors: string[] } {
    const override = cfg.events[key].template;
    if (override) {
        const r = renderMsgTemplate(key, override, vars);
        if (r.ok) return { text: r.text, fromOverride: true, errors: [] };
        console.warn(`[Messaging] override template for ${key} invalid (${r.errors.join('; ')}) — using default`);
    }
    const d = renderMsgTemplate(key, MSG_EVENTS[key].template, vars);
    if (d.ok) return { text: d.text, fromOverride: false, errors: [] };
    return { text: fallback, fromOverride: false, errors: d.errors };
}

const SAMPLE_LINE_VARS: Record<string, string> = {
    nobHead: '🔔 یادآوری قسط',
    installmentLine: 'قسط ۲ از ۶',
    destinationLine: '🏦 حساب مقصد: حساب نمونه آموزشگاه\n',
    trackingLine: '🔎 کد رهگیری: ۱۲۳۴۵۶\n',
    onlineLine: '🌐 نوع: پرداخت آنلاین\n',
    reversalLine: '',
    notesLine: '📝 توضیحات: پرداخت قسط دوم\n',
    dateLine: '۱۴۰۴/۰۷/۱۵ — ۱۰:۳۰',
    headLine: '🧾 <b>رسید پرداخت شهریه</b>',
    footerLine: 'با تشکر از پرداخت شما 🌸',
    codeLine: '🆔 <b>کد هنرجویی:</b> ۱۴۰۴-۰۰۱\n',
    reasonLine: '\nعلت: نمونه علت رد رسید'
};

const SAMPLE_TEXT_VARS: Record<string, string> = {
    studentName: 'هنرجو نمونه',
    academyName: 'آموزشگاه نمونه',
    courseTitle: 'دوره نمونه',
    nobLabel: '۷ روز قبل از سررسید',
    statusLine: '⏳ در انتظار پرداخت',
    transactionCode: 'TRX-SAMPLE-01',
    methodLabel: 'کارت به کارت',
    currency: 'تومان'
};

/** Fake, clearly-sample values for the preview endpoint — never real data. */
export function sampleVars(key: MsgEventKey): Record<string, string> {
    const out: Record<string, string> = {};
    for (const v of MSG_EVENTS[key].vars) {
        if (SAMPLE_LINE_VARS[v.name] !== undefined) { out[v.name] = SAMPLE_LINE_VARS[v.name]; continue; }
        if (SAMPLE_TEXT_VARS[v.name] !== undefined) { out[v.name] = SAMPLE_TEXT_VARS[v.name]; continue; }
        switch (v.kind) {
            case 'money': out[v.name] = '2,500,000'; break;
            case 'date': out[v.name] = '۱۴۰۴/۰۷/۱۵'; break;
            case 'number': out[v.name] = '۲'; break;
            case 'link': out[v.name] = 'https://t.me/sample_bot?start=sample-token'; break;
            default: out[v.name] = 'نمونه';
        }
    }
    return out;
}

// ------------------------------------------------------------
// Per-academy config (namespaced academy_settings, NO migration)
// ------------------------------------------------------------

export const MESSAGING_CONFIG_KEY = 'messaging_config';
export const REMINDER_STOPS_KEY = 'reminder_stops';

function defaultEventConfig(): Record<MsgEventKey, MsgEventConfig> {
    const out = {} as Record<MsgEventKey, MsgEventConfig>;
    for (const k of MSG_EVENT_KEYS) {
        out[k] = { auto: true, manual: true, channel: 'telegram' };
    }
    return out;
}

export function defaultMessagingConfig(): MessagingConfig {
    return {
        version: 1,
        events: defaultEventConfig(),
        overdueDaily: { enabled: true, forwardDays: 3 }
    };
}

function clampBool(v: unknown, def: boolean): boolean {
    return typeof v === 'boolean' ? v : def;
}

function clampChannel(v: unknown, def: ChannelPolicy): ChannelPolicy {
    return v === 'telegram' || v === 'sms' || v === 'both' ? v : def;
}

/**
 * Loads THIS academy's messaging config and merges it over the defaults.
 * Unknown/invalid fields are dropped, so a hand-edited or older row can
 * never break the dispatcher.
 */
export async function getMessagingConfig(db: AcademyDatabase): Promise<MessagingConfig> {
    const cfg = defaultMessagingConfig();
    try {
        const raw = await db.getSetting(MESSAGING_CONFIG_KEY, '');
        if (!raw) return cfg;
        const parsed = JSON.parse(raw) as Partial<MessagingConfig>;
        if (parsed && typeof parsed === 'object') {
            if (parsed.events && typeof parsed.events === 'object') {
                for (const k of MSG_EVENT_KEYS) {
                    const e = (parsed.events as Record<string, Partial<MsgEventConfig>>)[k];
                    if (!e) continue;
                    cfg.events[k] = {
                        auto: clampBool(e.auto, cfg.events[k].auto),
                        manual: clampBool(e.manual, cfg.events[k].manual),
                        channel: clampChannel(e.channel, cfg.events[k].channel),
                        template: typeof e.template === 'string' && e.template.trim() ? e.template : undefined
                    };
                }
            }
            if (parsed.overdueDaily && typeof parsed.overdueDaily === 'object') {
                const od = parsed.overdueDaily as Partial<OverdueDailyConfig>;
                cfg.overdueDaily = {
                    enabled: clampBool(od.enabled, cfg.overdueDaily.enabled),
                    forwardDays: Math.min(7, Math.max(0, Math.round(Number(od.forwardDays ?? cfg.overdueDaily.forwardDays)) || 0))
                };
            }
        }
    } catch (e) {
        console.warn('[Messaging] config parse failed, using defaults:', e);
    }
    return cfg;
}

export interface PutConfigInput {
    events?: Partial<Record<MsgEventKey, Partial<MsgEventConfig>>>;
    overdueDaily?: Partial<OverdueDailyConfig>;
    sendHour?: number;
    sendMinute?: number;
    timezone?: string;
}

export interface PutConfigResult {
    ok: boolean;
    config?: MessagingConfig;
    errors: string[];
}

/** Validates + saves the config. Template errors REFUSE the save. */
export async function putMessagingConfig(db: AcademyDatabase, input: PutConfigInput): Promise<PutConfigResult> {
    const errors: string[] = [];
    const current = await getMessagingConfig(db);
    const next: MessagingConfig = JSON.parse(JSON.stringify(current)) as MessagingConfig;

    if (input.events && typeof input.events === 'object') {
        for (const [key, patch] of Object.entries(input.events)) {
            if (!MSG_EVENT_KEYS.includes(key as MsgEventKey)) {
                errors.push(`نوع پیام ناشناخته: ${key}`);
                continue;
            }
            const k = key as MsgEventKey;
            const p = (patch || {}) as Partial<MsgEventConfig>;
            const ev = next.events[k];
            if ('auto' in p) ev.auto = clampBool(p.auto, ev.auto);
            if ('manual' in p) ev.manual = clampBool(p.manual, ev.manual);
            if ('channel' in p) ev.channel = clampChannel(p.channel, ev.channel);
            if ('template' in p) {
                const t = typeof p.template === 'string' ? p.template.trim() : '';
                if (!t || t === MSG_EVENTS[k].template) {
                    ev.template = undefined; // reset to default
                } else {
                    const tErrors = validateTemplateText(k, t);
                    if (tErrors.length) errors.push(`قالب «${MSG_EVENT_TITLES[k]}»: ${tErrors.join('؛ ')}`);
                    else ev.template = t;
                }
            }
        }
    }
    if (input.overdueDaily && typeof input.overdueDaily === 'object') {
        next.overdueDaily = {
            enabled: clampBool(input.overdueDaily.enabled, current.overdueDaily.enabled),
            forwardDays: Math.min(7, Math.max(0, Math.round(Number(input.overdueDaily.forwardDays ?? current.overdueDaily.forwardDays)) || 0))
        };
    }

    if (errors.length) return { ok: false, errors };

    await db.setSetting(MESSAGING_CONFIG_KEY, JSON.stringify(next));

    // Send hour / minute / timezone write the EXISTING Stage-5 scheduler keys —
    // one source of truth for the academy's send time, never a parallel one.
    const h = Number(input.sendHour);
    const m = Number(input.sendMinute);
    if (Number.isFinite(h)) await db.setSetting('reminder_send_hour', String(Math.min(23, Math.max(0, Math.round(h)))));
    if (Number.isFinite(m)) await db.setSetting('reminder_send_minute', String(Math.min(59, Math.max(0, Math.round(m)))));
    if (typeof input.timezone === 'string' && input.timezone.trim()) {
        await db.setSetting('reminder_timezone', input.timezone.trim().slice(0, 40));
    }
    return { ok: true, config: next, errors: [] };
}

/** Removes one (or all) template override(s) — back to the shipped defaults. */
export async function resetTemplate(db: AcademyDatabase, key?: MsgEventKey): Promise<MessagingConfig> {
    const cfg = await getMessagingConfig(db);
    for (const k of MSG_EVENT_KEYS) {
        if (!key || k === key) cfg.events[k].template = undefined;
    }
    await db.setSetting(MESSAGING_CONFIG_KEY, JSON.stringify(cfg));
    return cfg;
}

/** Effective template text for an event (override or shipped default). */
export function effectiveTemplate(cfg: MessagingConfig, key: MsgEventKey): string {
    return cfg.events[key].template || MSG_EVENTS[key].template;
}

// ------------------------------------------------------------
// Channel status (honest, real configuration only)
// ------------------------------------------------------------

export interface ChannelStatus {
    telegram: { configured: boolean; detail: string };
    sms: { configured: boolean; detail: string };
    gateway: { configured: boolean; detail: string };
}

export async function channelStatus(db: AcademyDatabase, botToken: string): Promise<ChannelStatus> {
    const gatewayProvider = (await db.getSetting('gateway_provider', '') || '').trim();
    return {
        telegram: {
            configured: Boolean(botToken),
            detail: botToken ? 'ربات تلگرام آموزشگاه متصل است.' : 'ربات تلگرام آموزشگاه متصل نیست؛ پیام‌های تلگرام ارسال نمی‌شوند.'
        },
        // No SMS panel is chosen or activatable in this stage — the worker can
        // never send a real SMS and the UI must never claim it can.
        sms: {
            configured: false,
            detail: 'پنل پیامکی متصل نیست؛ ارسال پیامک غیرفعال است.'
        },
        gateway: {
            configured: Boolean(gatewayProvider),
            detail: gatewayProvider ? `درگاه پرداخت پیکربندی شده (${gatewayProvider}).` : 'درگاه پرداخت آنلاین متصل نیست؛ لینک پرداخت صادر نمی‌شود.'
        }
    };
}

// ------------------------------------------------------------
// Reminder stop list (manager-controlled, per installment)
// ------------------------------------------------------------

export interface ReminderStop {
    reason: string;
    actor: string;
    at: number;
}

export async function getReminderStops(db: AcademyDatabase): Promise<Record<string, ReminderStop>> {
    try {
        const raw = await db.getSetting(REMINDER_STOPS_KEY, '');
        if (!raw) return {};
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed as Record<string, ReminderStop> : {};
    } catch {
        return {};
    }
}

export async function setReminderStop(db: AcademyDatabase, installmentId: string, stop: ReminderStop | null): Promise<void> {
    const stops = await getReminderStops(db);
    if (stop) stops[installmentId] = stop;
    else delete stops[installmentId];
    // Bounded: drop the oldest entries beyond 500 so the KV row can never grow unbounded.
    const ids = Object.keys(stops);
    if (ids.length > 500) {
        ids.sort((a, b) => (stops[a].at || 0) - (stops[b].at || 0));
        for (const id of ids.slice(0, ids.length - 500)) delete stops[id];
    }
    await db.setSetting(REMINDER_STOPS_KEY, JSON.stringify(stops));
}

// ------------------------------------------------------------
// Event audit (reuses the project's payment_events ledger)
// ------------------------------------------------------------

export async function logMessagingEvent(
    db: AcademyDatabase,
    academyId: string,
    eventType: string,
    opts: { studentId?: string; installmentId?: string; channel?: string; actor?: string; detail?: string } = {}
): Promise<void> {
    try {
        await db.insertPaymentEvent({
            id: crypto.randomUUID(),
            academy_id: academyId,
            student_id: opts.studentId || '',
            installment_id: opts.installmentId || '',
            payment_id: '',
            claim_id: '',
            event_type: eventType,
            channel: opts.channel || 'worker',
            actor: opts.actor || 'manager-api',
            amount: 0,
            detail: (opts.detail || '').slice(0, 300),
            created_at_epoch: Date.now()
        });
    } catch (e) {
        console.warn('[Messaging] event log failed (non-blocking):', e);
    }
}
