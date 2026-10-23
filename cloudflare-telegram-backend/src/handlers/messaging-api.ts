// ============================================================
// Stage 6: messaging settings, templates, preview and manual send.
//
// All routes are bearer-authenticated (the academy's API key) and
// tenant-scoped (X-Academy-Id) by the caller (handlers/api.ts) — the
// same server-side auth every other admin route uses; hiding a button
// in the UI would never be enough.
//
// Endpoints (all under /api/messaging/*):
//   GET  /api/messaging/config          effective config + REAL channel status
//   PUT  /api/messaging/config          validate + save (per academy, no migration)
//   POST /api/messaging/preview         render with FAKE sample data — never sends
//   POST /api/messaging/templates/reset back to the shipped default template(s)
//   POST /api/messaging/send            manager manual send (idempotent, policy-gated)
//   GET  /api/messaging/reminders       per-installment reminder status for the manager
//   POST /api/messaging/reminders/stop  stop ONE installment's reminders (scoped)
//   POST /api/messaging/reminders/resume undo the stop
//
// Hard rules implemented here:
//   - manual sends NEVER register a payment, NEVER create an official
//     receipt and NEVER cancel/bypass an automatic reminder;
//   - amounts/dates/status always come from the SERVER installment row,
//     never from the client payload;
//   - every manual send carries a clientMsgId: a double click, a retry
//     or a concurrent request can never send the message twice
//     (deliveries ledger, one row per (clientMsgId, channel));
//   - SMS is reported honestly as UNCONFIGURED and can never really
//     send in this stage; a Telegram failure never erases an SMS
//     outcome and vice versa;
//   - no token, key or secret is ever logged or echoed.
// ============================================================

import { Env } from '../types';
import { AcademyDatabase } from '../db';
import { TelegramBotClient } from '../telegram';
import { ContractStore } from '../contracts-store';
import {
    MSG_EVENTS, MSG_EVENT_KEYS, MsgEventKey, MessagingConfig,
    getMessagingConfig, putMessagingConfig, resetTemplate, effectiveTemplate,
    channelStatus, sampleVars, renderMsgTemplate, renderEvent, htmlToPlain,
    getReminderStops, setReminderStop, logMessagingEvent, PutConfigInput
} from '../messaging';

function json(data: unknown, status = 200): Response {
    return new Response(JSON.stringify(data), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }
    });
}

function ok(message: string, data?: unknown): Response {
    return json({ success: true, message, data: data ?? null });
}

function fail(message: string, status = 400, extra: Record<string, unknown> = {}): Response {
    return json({ success: false, message, errorCode: status, ...extra }, status);
}

async function readBody(request: Request): Promise<Record<string, any> | null> {
    try {
        const b = await request.json();
        return b && typeof b === 'object' ? b as Record<string, any> : null;
    } catch {
        return null;
    }
}

/** Event types a MANAGER may send manually from the student/installment UI. */
const MANUAL_ALLOWED_EVENTS: MsgEventKey[] = ['INSTALLMENT_REMINDER', 'OVERDUE_DAILY'];

/**
 * Entry from handlers/api.ts — returns null when `path` is not a
 * messaging route so the legacy router continues.
 */
export async function handleMessagingApi(
    request: Request,
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    botToken: string,
    path: string,
    url: URL
): Promise<Response | null> {
    if (!path.startsWith('/api/messaging/')) return null;
    const method = request.method;
    const store = new ContractStore(env);

    // ------------------------------------------------------------
    // GET /api/messaging/config
    // ------------------------------------------------------------
    if (path === '/api/messaging/config' && method === 'GET') {
        const cfg = await getMessagingConfig(db);
        const [status, hour, minute, tz, currency, stops] = await Promise.all([
            channelStatus(db, botToken),
            db.getSetting('reminder_send_hour', '10'),
            db.getSetting('reminder_send_minute', '0'),
            db.getSetting('reminder_timezone', 'Asia/Tehran'),
            db.getSetting('currency_unit', 'تومان'),
            getReminderStops(db)
        ]);
        const events: Record<string, unknown> = {};
        for (const k of MSG_EVENT_KEYS) {
            events[k] = {
                title: MSG_EVENTS[k].title,
                variables: MSG_EVENTS[k].vars.map((v) => ({ name: v.name, title: v.title, kind: v.kind, required: !!v.required })),
                defaultTemplate: MSG_EVENTS[k].template,
                effectiveTemplate: effectiveTemplate(cfg, k),
                isOverridden: Boolean(cfg.events[k].template),
                ...cfg.events[k]
            };
        }
        return ok('تنظیمات پیام‌رسانی دریافت شد.', {
            version: cfg.version,
            events,
            overdueDaily: cfg.overdueDaily,
            sendTime: { hour: Number(hour) || 10, minute: Number(minute) || 0, timezone: tz },
            currency,
            channelStatus: status,
            stoppedInstallments: Object.keys(stops)
        });
    }

    // ------------------------------------------------------------
    // PUT /api/messaging/config
    // ------------------------------------------------------------
    if (path === '/api/messaging/config' && method === 'PUT') {
        const body = await readBody(request);
        if (!body) return fail('بدنهٔ درخواست معتبر نیست.', 400);
        const input: PutConfigInput = {
            events: body.events,
            overdueDaily: body.overdueDaily,
            sendHour: body.sendHour,
            sendMinute: body.sendMinute,
            timezone: body.timezone
        };
        const res = await putMessagingConfig(db, input);
        if (!res.ok || !res.config) {
            return json({ success: false, message: 'تنظیمات ذخیره نشد؛ خطاهای اعتبارسنجی را برطرف کنید.', errors: res.errors, errorCode: 400 }, 400);
        }
        await logMessagingEvent(db, academyId, 'MSG_CONFIG_UPDATED', {
            actor: 'manager-api',
            detail: `events: ${Object.keys(body.events || {}).join(',') || '-'}`
                + `; overdueDaily: ${res.config.overdueDaily.enabled ? 'on' : 'off'}`
        });
        return ok('تنظیمات پیام‌رسانی با موفقیت ذخیره شد.', { config: res.config });
    }

    // ------------------------------------------------------------
    // POST /api/messaging/preview — SAMPLE data only, never sends.
    // ------------------------------------------------------------
    if (path === '/api/messaging/preview' && method === 'POST') {
        const body = await readBody(request);
        const key = String(body?.eventType || '') as MsgEventKey;
        if (!MSG_EVENT_KEYS.includes(key)) return fail('نوع پیام ناشناخته است.', 400);
        const cfg = await getMessagingConfig(db);
        // A draft template can be previewed BEFORE saving (body.template);
        // without it the effective (saved or default) template is shown.
        const template = typeof body?.template === 'string' && body.template.trim()
            ? body.template
            : effectiveTemplate(cfg, key);
        const vars = sampleVars(key);
        const rendered = renderMsgTemplate(key, template, vars);
        return ok('پیش‌نمایش با اطلاعات نمونه ساخته شد (هیچ پیامی ارسال نشد).', {
            eventType: key,
            valid: rendered.ok,
            errors: rendered.errors,
            telegramHtml: rendered.ok ? rendered.text : '',
            smsText: rendered.ok ? htmlToPlain(rendered.text) : '',
            variables: MSG_EVENTS[key].vars.map((v) => ({ name: v.name, title: v.title, kind: v.kind, required: !!v.required }))
        });
    }

    // ------------------------------------------------------------
    // POST /api/messaging/templates/reset
    // ------------------------------------------------------------
    if (path === '/api/messaging/templates/reset' && method === 'POST') {
        const body = await readBody(request);
        let key: MsgEventKey | undefined;
        if (body?.eventType) {
            key = String(body.eventType) as MsgEventKey;
            if (!MSG_EVENT_KEYS.includes(key)) return fail('نوع پیام ناشناخته است.', 400);
        }
        const cfg = await resetTemplate(db, key);
        await logMessagingEvent(db, academyId, 'TEMPLATE_RESET', {
            actor: 'manager-api',
            detail: key || 'all'
        });
        return ok(key ? 'قالب به حالت پیش‌فرض بازگردانده شد.' : 'همهٔ قالب‌ها به حالت پیش‌فرض بازگردانده شدند.', { config: cfg });
    }

    // ------------------------------------------------------------
    // POST /api/messaging/send — manager manual send
    // ------------------------------------------------------------
    if (path === '/api/messaging/send' && method === 'POST') {
        const body = await readBody(request);
        if (!body) return fail('بدنهٔ درخواست معتبر نیست.', 400);

        const eventType = String(body.eventType || '') as MsgEventKey;
        if (!MANUAL_ALLOWED_EVENTS.includes(eventType)) {
            return fail('این نوع پیام از مسیر ارسال دستی قابل ارسال نیست.', 400);
        }
        const clientMsgId = String(body.clientMsgId || '').trim();
        if (!clientMsgId || clientMsgId.length > 80) {
            return fail('کلید یکتای پیام (clientMsgId) الزامی است.', 400);
        }

        const cfg = await getMessagingConfig(db);
        // Server-side policy: a hidden button is never the authorization.
        if (!cfg.events[eventType].manual) {
            return json({
                success: false,
                message: 'ارسال دستی این نوع پیام در تنظیمات پیام‌رسانی مجاز نیست.',
                errorCode: 403,
                status: 'BLOCKED',
                channels: { telegram: { status: 'BLOCKED', reason: 'manual_send_disabled_by_settings' } }
            }, 403);
        }

        // Real student of THIS academy (404 otherwise — no cross-tenant leak).
        const studentId = String(body.studentId || '').trim();
        if (!studentId) return fail('انتخاب هنرجو الزامی است.', 400);
        const student = await db.getStudentById(studentId, academyId);
        if (!student) return fail('هنرجو در این آموزشگاه پیدا نشد.', 404);

        // The installment must exist AND belong to that student; the amount
        // and due date ALWAYS come from this server row, never the client.
        const installmentId = String(body.installmentId || '').trim();
        if (!installmentId) return fail('انتخاب قسط الزامی است.', 400);
        const inst = await db.getInstallmentById(installmentId, academyId);
        if (!inst || inst.student_id !== student.id) {
            return fail('قسط موردنظر برای این هنرجو معتبر نیست.', 400);
        }

        // Channel policy: requested ∩ allowed-for-this-event ∩ REAL.
        const allowed: string[] = cfg.events[eventType].channel === 'both'
            ? ['telegram', 'sms', 'both']
            : [cfg.events[eventType].channel, 'both'];
        const requested = String(body.channel || cfg.events[eventType].channel || 'telegram');
        if (!allowed.includes(requested)) {
            return json({
                success: false,
                message: 'این کانال برای این نوع پیام مجاز نیست.',
                errorCode: 403,
                status: 'BLOCKED',
                channels: { telegram: { status: 'BLOCKED', reason: 'channel_not_allowed_by_settings' } }
            }, 403);
        }

        const currency = await db.getSetting('currency_unit', 'تومان');

        // Text: manager-edited (plain text, HTML-escaped → no injection) or
        // rendered from the server template with REAL values.
        let text: string;
        if (typeof body.text === 'string' && body.text.trim()) {
            const raw = body.text.trim().slice(0, 3800);
            // {variables} in a manual text are resolved server-side with REAL
            // data; an unknown token refuses the send (never sent invalid).
            if (/\{[A-Za-z_][A-Za-z0-9_]*\}/.test(raw)) {
                const rendered = renderMsgTemplate(eventType, raw, {
                    studentName: student.full_name,
                    courseTitle: inst.course_title || '-',
                    installmentLine: inst.total_installments > 0 ? `قسط ${inst.installment_number} از ${inst.total_installments}` : `قسط ${inst.installment_number}`,
                    nobLabel: eventType === 'OVERDUE_DAILY' ? 'پس از سررسید (معوق)' : 'یادآوری قسط',
                    amount: ((inst.remaining_amount || 0) > 0 ? inst.remaining_amount : inst.amount).toLocaleString('en-US'),
                    currency,
                    dueDateShamsi: inst.due_date_shamsi,
                    // {daysLate} in a manager-written text resolves to the REAL
                    // overdue days of THIS installment (empty only for events
                    // where the variable is not required).
                    daysLate: eventType === 'OVERDUE_DAILY'
                        ? String(Math.max(1, Math.floor((Date.now() - (inst.due_date_epoch || 0)) / 86_400_000)))
                        : ''
                });
                if (!rendered.ok) {
                    return fail(`متن پیام معتبر نیست: ${rendered.errors.join('؛ ')}`, 400, { errors: rendered.errors });
                }
                text = htmlToPlain(rendered.text);
            } else {
                text = raw;
            }
            text = escapeText(text);
        } else {
            const { renderReminderViaConfig } = await import('../reminders');
            text = escapeText(htmlToPlain(renderReminderViaConfig(
                cfg, eventType, student, inst,
                eventType === 'OVERDUE_DAILY' ? Math.max(1, Math.floor((Date.now() - (inst.due_date_epoch || 0)) / 86_400_000)) : -1,
                currency
            )));
        }

        const channels: Record<string, { status: string; reason?: string; messageId?: string }> = {};
        const wantTelegram = requested === 'telegram' || requested === 'both';
        const wantSms = requested === 'sms' || requested === 'both';

        // ---- Telegram channel (idempotent via the deliveries ledger) ----
        let overall: 'SENT' | 'FAILED' | 'BLOCKED' = 'BLOCKED';
        if (wantTelegram) {
            const ledgerId = `manual:${clientMsgId}`;
            const claimed = await store.claimDelivery(academyId, ledgerId, 'telegram');
            if (!claimed) {
                // A previous attempt exists: a retry must never duplicate it.
                const statuses = await store.deliveryStatuses(academyId, [ledgerId]);
                const prev = statuses[ledgerId]?.telegram?.status;
                if (prev === 'SENT' || prev === 'SKIPPED') {
                    channels.telegram = { status: 'SENT', reason: 'قبلاً ارسال شده بود (ارسال تکراری مسدود شد).' };
                    return ok('این پیام قبلاً ارسال شده است؛ ارسال تکراری انجام نشد.', {
                        status: 'SENT', idempotent: true, channels
                    });
                }
                if (prev === 'PENDING') {
                    return ok('ارسال این پیام در جریان است.', { status: 'IN_FLIGHT', channels: { telegram: { status: 'PENDING' } } });
                }
                // FAILED → bounded retry below (one more attempt).
            }
            if (!botToken) {
                channels.telegram = { status: 'BLOCKED', reason: 'ربات تلگرام آموزشگاه متصل نیست.' };
                await store.finishDelivery(academyId, ledgerId, 'telegram', 'SKIPPED', 'bot not connected');
            } else if (!student.telegram_chat_id) {
                channels.telegram = { status: 'FAILED', reason: 'این هنرجو به تلگرام متصل نیست.' };
                await store.finishDelivery(academyId, ledgerId, 'telegram', 'FAILED', 'student not linked');
            } else {
                let sendRes: Awaited<ReturnType<TelegramBotClient['sendMessage']>>;
                try {
                    sendRes = await new TelegramBotClient(botToken).sendMessage({
                        chatId: student.telegram_chat_id,
                        text,
                        parseMode: 'HTML',
                        replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                    });
                } catch (e: any) {
                    sendRes = { ok: false, description: `network: ${e?.message || e}` } as any;
                }
                if (sendRes.ok) {
                    channels.telegram = { status: 'SENT', messageId: String((sendRes.result as any)?.message_id || '') };
                    await store.finishDelivery(academyId, ledgerId, 'telegram', 'SENT', '', channels.telegram.messageId);
                    overall = 'SENT';
                } else {
                    channels.telegram = { status: 'FAILED', reason: (sendRes as any).description || 'ارسال ناموفق بود.' };
                    await store.finishDelivery(academyId, ledgerId, 'telegram', 'FAILED', channels.telegram.reason || '');
                    overall = 'FAILED';
                }
            }
        }

        // ---- SMS channel (honest: never configured in this stage) ----
        if (wantSms) {
            channels.sms = { status: 'UNCONFIGURED', reason: 'پنل پیامکی متصل نیست؛ ارسال پیامک انجام نشد.' };
        }

        // ---- logging (no credentials, no sensitive payload) ----
        const logId = crypto.randomUUID();
        const tgStatus = channels.telegram?.status || 'SKIPPED';
        await db.logMessage({
            id: logId,
            student_id: student.id,
            student_name: student.full_name,
            chat_id: student.telegram_chat_id || '',
            message_type: `MANUAL_${eventType}`,
            content: text,
            status: tgStatus === 'SENT' ? 'SUCCESS' : 'FAILED',
            error_message: tgStatus === 'SENT' ? '' : (channels.telegram?.reason || channels.sms?.reason || ''),
            timestamp_epoch: Date.now(),
            timestamp_shamsi: ''
        });
        await logMessagingEvent(db, academyId, tgStatus === 'SENT' ? 'MANUAL_MESSAGE_SENT' : 'MANUAL_MESSAGE_FAILED', {
            studentId: student.id,
            installmentId: inst.id,
            channel: tgStatus === 'SENT' ? 'telegram' : 'worker',
            actor: 'manager-api',
            detail: `${eventType} client=${clientMsgId}`
        });

        if (overall === 'BLOCKED' && channels.telegram?.status === 'BLOCKED') {
            return json({ success: false, message: channels.telegram.reason || 'ارسال انجام نشد.', status: 'BLOCKED', channels, errorCode: 409 }, 409);
        }
        return json({
            success: true,
            message: overall === 'SENT'
                ? (channels.sms ? 'پیام تلگرام ارسال شد (پیامک پیکربندی نشده است).' : 'پیام با موفقیت ارسال شد.')
                : 'ارسال ناموفق بود؛ جزئیات در وضعیت کانال‌ها آمده است.',
            data: { status: overall, channels, logId }
        });
    }

    // ------------------------------------------------------------
    // GET /api/messaging/reminders — per-installment status view
    // ------------------------------------------------------------
    if (path === '/api/messaging/reminders' && method === 'GET') {
        const studentId = (url.searchParams.get('studentId') || '').trim();
        const installmentId = (url.searchParams.get('installmentId') || '').trim();
        const rows = await db.listRemindersForView(academyId, {
            studentId: studentId || undefined,
            installmentId: installmentId || undefined,
            limit: 200
        });
        const ids = [...new Set(rows.map((r) => r.id))];
        const deliveries = await store.deliveryStatuses(academyId, ids);
        const stops = await getReminderStops(db);
        const cfg = await getMessagingConfig(db);
        return ok('وضعیت یادآوری‌ها دریافت شد.', {
            reminders: rows.map((r) => ({
                id: r.id,
                installmentId: r.installment_id,
                studentId: r.student_id,
                studentName: r.student_name,
                type: r.reminder_type,
                status: r.status,
                scheduledEpoch: r.scheduled_epoch,
                scheduledTimeStr: r.scheduled_time_str,
                sentAtEpoch: r.sent_at_epoch,
                error: r.error_message,
                retryCount: r.retry_count,
                smsStatus: (r as any).sms_status,
                smsError: (r as any).sms_error,
                triggerOffset: (r as any).trigger_offset,
                scheduleSource: (r as any).schedule_source,
                deliveries: deliveries[r.id] || {}
            })),
            stoppedInstallments: Object.fromEntries(Object.entries(stops).filter(([k]) => {
                if (installmentId) return k === installmentId;
                if (studentId) {
                    return rows.some((r) => r.installment_id === k && r.student_id === studentId);
                }
                return true;
            })),
            autoSending: {
                INSTALLMENT_REMINDER: cfg.events.INSTALLMENT_REMINDER.auto,
                OVERDUE_DAILY: cfg.events.OVERDUE_DAILY.auto
            }
        });
    }

    // ------------------------------------------------------------
    // POST /api/messaging/reminders/stop — scoped to ONE installment
    // ------------------------------------------------------------
    if (path === '/api/messaging/reminders/stop' && method === 'POST') {
        const body = await readBody(request);
        const installmentId = String(body?.installmentId || '').trim();
        if (!installmentId) return fail('installmentId الزامی است.', 400);
        const inst = await db.getInstallmentById(installmentId, academyId);
        if (!inst) return fail('قسط در این آموزشگاه پیدا نشد.', 404);
        const reason = String(body?.reason || '').slice(0, 200);
        await setReminderStop(db, installmentId, { reason, actor: 'manager-api', at: Date.now() });
        const cancelled = await db.cancelPendingRemindersForInstallment(installmentId);
        await logMessagingEvent(db, academyId, 'REMINDER_STOPPED', {
            studentId: inst.student_id,
            installmentId,
            actor: 'manager-api',
            detail: `scope: this installment only; cancelled=${cancelled}${reason ? `; reason: ${reason}` : ''}`
        });
        return ok(`یادآوری این قسط متوقف شد (${cancelled} پیام زمان‌بندی‌شده لغو شد). دامنه توقف فقط همین قسط است؛ اقساط دیگر ادامه دارند.`, {
            installmentId, cancelled, scope: 'installment'
        });
    }

    // ------------------------------------------------------------
    // POST /api/messaging/reminders/resume
    // ------------------------------------------------------------
    if (path === '/api/messaging/reminders/resume' && method === 'POST') {
        const body = await readBody(request);
        const installmentId = String(body?.installmentId || '').trim();
        if (!installmentId) return fail('installmentId الزامی است.', 400);
        const inst = await db.getInstallmentById(installmentId, academyId);
        await setReminderStop(db, installmentId, null);
        let reScheduled = 0;
        if (inst) {
            const { syncInstallmentReminders } = await import('../reminders');
            const out = await syncInstallmentReminders(db, academyId, inst, Date.now());
            reScheduled = out.created + out.rescheduled;
        }
        await logMessagingEvent(db, academyId, 'REMINDER_RESUMED', {
            installmentId,
            actor: 'manager-api',
            detail: `re-scheduled=${reScheduled}`
        });
        return ok('یادآوری این قسط از سر گرفته شد و زمان‌بندی دوباره ساخته شد.', { installmentId, reScheduled });
    }

    return fail('مسیر پیام‌رسانی یافت نشد.', 404);
}

/** Manual sends are plain text: escaping guarantees no HTML injection. */
function escapeText(s: string): string {
    return (s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Exported for tests. */
export type { MessagingConfig };
