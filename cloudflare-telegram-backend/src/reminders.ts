// ============================================================
// Stage 5: installment reminders — server-side scheduling and
// dual-channel dispatch.
//
// For every UNPAID installment the worker owns exactly five nobs
// relative to the due date:
//      -7 days, -4 days, -1 day, due day, +1 day (overdue notice)
// sent by default at 10:00 ACADEMY time (settings `reminder_send_hour`
// / `reminder_send_minute` / `reminder_timezone` — never device or
// server time).
//
// Guarantees (all covered by stage5-flow.test.ts):
//   - deterministic ids (`auto:<installment>:<offset>`): re-running the
//     scheduler or re-syncing the app can never create a second row;
//   - at most ONE SCHEDULED row per (installment, offset) across BOTH
//     sources — app-pushed duplicates are superseded, not duplicated;
//   - only UNPAID installments are scheduled; a paid / settled
//     installment has its pending reminders cancelled immediately;
//   - a due-date change re-aligns every still-SCHEDULED row;
//   - if the worker was down, catch-up is CONTROLLED: only the newest
//     due nob of each installment is sent, older nobs of the same
//     installment are marked superseded (never a burst of stale
//     messages), and anything older than the lateness window is
//     skipped, not spammed;
//   - every nob goes to BOTH channels (Telegram + SMS). Channel
//     statuses are tracked independently (deliveries ledger + reminders
//     sms_* columns + sms_outbox), one channel failing never blocks or
//     re-sends the other, and REAL SMS stays DISABLED until an SMS
//     panel is actually chosen and connected;
//   - before sending, the installment is RE-CHECKED: a payment
//     registered between scheduling and sending cancels the message.
// ============================================================

import { Env, InstallmentRecord, ReminderRecord, StudentRecord, PaymentEventRecord } from './types';
import { AcademyDatabase } from './db';
import { TelegramBotClient } from './telegram';
import { ContractStore } from './contracts-store';
import { shamsiAtAcademyTime, academyTzOffsetMinutes } from './tz';
import { createInstallmentPaymentLink } from './gateway';

export const DEFAULT_OFFSETS = [-7, -4, -1, 0, 1];
/** Fresh scheduling never lands deeper in the past than this. */
export const MAX_LATE_MS = 26 * 3600_000;
/** Dispatch backlog window: due reminders older than this are cancelled as missed, never sent. */
export const DISPATCH_MAX_LATE_MS = 72 * 3600_000;

export type ReminderOutcome = {
    created: number;
    rescheduled: number;
    cancelled: number;
    skippedLate: number;
};

function clampInt(raw: string | undefined, def: number, min: number, max: number): number {
    const n = Number(raw ?? '');
    if (!Number.isFinite(n)) return def;
    return Math.min(max, Math.max(min, Math.round(n)));
}

export function reminderOffsets(raw: string | undefined): number[] {
    if (!raw || !raw.trim()) return DEFAULT_OFFSETS;
    const parsed = raw.split(',').map((s) => Number(s.trim())).filter((n) => Number.isInteger(n) && n >= -60 && n <= 60);
    return parsed.length ? [...new Set(parsed)].sort((a, b) => a - b) : DEFAULT_OFFSETS;
}

/** Persian label of a nob, mirroring the app's own wording. */
export function offsetLabelFa(offset: number): string {
    switch (offset) {
        case -7: return '۷ روز قبل از سررسید';
        case -4: return '۴ روز قبل از سررسید';
        case -1: return '۱ روز قبل از سررسید';
        case 0: return 'روز سررسید';
        default: return `${offset} روز پس از سررسید (معوق)`;
    }
}

/** Due-relative status line, shared with the bot menu rendering. */
export function dueStatusFa(inst: InstallmentRecord, now: number): string {
    if (inst.is_paid === 1) return '✅ پرداخت شده';
    const day = 86_400_000;
    const due = inst.due_date_epoch || 0;
    if (due > 0 && due < now) {
        const days = Math.max(1, Math.floor((now - due) / day));
        return `🚨 معوق (${days} روز تاخیر)`;
    }
    if (due > 0 && now >= due - day && now <= due + 6 * 3600_000) return '⏰ سررسید امروز';
    if (due > 0 && due - now <= 3 * day) return '🔜 نزدیک سررسید';
    return '⏳ در انتظار پرداخت';
}

/** The reminder body — real data only: student, course, nob, amount, Shamsi due date, status. */
export function renderReminderText(
    student: StudentRecord | null,
    inst: InstallmentRecord,
    offset: number,
    currency: string,
    statusLine?: string
): string {
    const amount = (inst.remaining_amount || 0) > 0 ? inst.remaining_amount : inst.amount;
    const numStr = inst.total_installments > 0
        ? `قسط ${inst.installment_number} از ${inst.total_installments}`
        : `قسط ${inst.installment_number}`;
    const head = offset < 0 ? '🔔 یادآوری قسط'
        : offset === 0 ? '⏰ سررسید قسط امروز'
        : '🚨 قسط معوق شده';
    return `${head}\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 هنرجو: <b>${student?.full_name || inst.student_id}</b>\n` +
        `📚 دوره: ${inst.course_title || '-'}\n` +
        `🔹 ${numStr} (${offsetLabelFa(offset)})\n` +
        `💵 مبلغ: <b>${amount.toLocaleString()} ${currency}</b>\n` +
        `📆 سررسید: <b>${inst.due_date_shamsi}</b>\n` +
        `وضعیت: <b>${statusLine || dueStatusFa(inst, Date.now())}</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `لطفاً در صورت پرداخت، رسید خود را از دکمه «ارسال رسید پرداخت» برای مدیریت ارسال کنید. 🙏`;
}

/**
 * Ensures the five nobs exist for one installment, aligned with its CURRENT
 * due date and payment state. Idempotent — safe to run on every sync.
 */
export async function syncInstallmentReminders(
    db: AcademyDatabase,
    academyId: string,
    inst: InstallmentRecord,
    now = Date.now()
): Promise<ReminderOutcome> {
    const out: ReminderOutcome = { created: 0, rescheduled: 0, cancelled: 0, skippedLate: 0 };
    if (!inst || !inst.id || !inst.student_id) return out;

    // A paid / fully settled / zero-amount installment must never keep (or regain) reminders.
    if (inst.is_paid === 1 || (inst.remaining_amount || 0) <= 0 || (inst.amount || 0) <= 0) {
        out.cancelled = await db.cancelPendingRemindersForInstallment(inst.id);
        return out;
    }

    const [hour, minute, tz, offsetsRaw] = await Promise.all([
        db.getSetting('reminder_send_hour', '10'),
        db.getSetting('reminder_send_minute', '0'),
        db.getSetting('reminder_timezone', 'Asia/Tehran'),
        db.getSetting('reminder_offsets', '')
    ]);
    const offsets = reminderOffsets(offsetsRaw);
    const tzMin = academyTzOffsetMinutes(tz);
    const hh = clampInt(hour, 10, 0, 23);
    const mm = clampInt(minute, 0, 0, 59);

    const student = await db.getStudentById(inst.student_id, academyId);
    const currency = await db.getSetting('currency_unit', 'تومان');

    for (const offset of offsets) {
        const epoch = shamsiAtAcademyTime(inst.due_date_shamsi, offset, hh, mm, tzMin);
        const existing = await db.findScheduledReminderForOffset(inst.id, offset, academyId);
        const message = renderReminderText(student, inst, offset, currency);

        // Unparsable due date (should not happen — the app pushes Shamsi) or a
        // nob whose window is long gone: never schedule into the deep past.
        if (epoch <= 0 || epoch < now - MAX_LATE_MS) {
            if (existing) {
                await db.updateReminderStatus(existing.id, 'CANCELLED', 'missed schedule window');
                out.cancelled++;
            }
            out.skippedLate++;
            continue;
        }

        const timeStr = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')} ${inst.due_date_shamsi}`;
        const id = `auto:${inst.id}:${offset}`;

        if (existing) {
            // Re-align an already-SCHEDULED nob (due date changed / hour changed).
            if (existing.scheduled_epoch !== epoch || existing.message_text !== message) {
                await db.rescheduleReminder(existing.id, epoch, timeStr, message);
                out.rescheduled++;
            }
            continue;
        }

        // A deterministic nob that was cancelled/failed without ever delivering
        // (paid → unpaid reversal, past realignment) is REVIVED, never
        // re-inserted — the INSERT below would otherwise collide with its id
        // and silently vanish. SENT nobs are never revived (no double send).
        const deadId = `auto:${inst.id}:${offset}`;
        const dead = await db.getReminderById(deadId);
        if (dead && dead.status !== 'SENT' && dead.id === deadId) {
            await db.reviveReminder(deadId, epoch, timeStr, message);
            out.rescheduled++;
            continue;
        }

        await db.addReminder({
            id,
            student_id: inst.student_id,
            student_name: student?.full_name || '',
            chat_id: student?.telegram_chat_id || '',
            installment_id: inst.id,
            reminder_type: offset < 0 ? 'INSTALLMENT_UPCOMING' : offset === 0 ? 'INSTALLMENT_DUE' : 'INSTALLMENT_OVERDUE',
            message_text: message,
            scheduled_epoch: epoch,
            scheduled_time_str: timeStr,
            status: 'SCHEDULED',
            error_message: '',
            retry_count: 0,
            created_at_epoch: now,
            sent_at_epoch: 0,
            student_phone: student?.phone || '',
            trigger_offset: offset,
            schedule_source: 'server'
        } as ReminderRecord);
        out.created++;
    }

    // Supersede app-pushed SCHEDULED rows whose (installment, offset) nob the
    // scheduler now owns — otherwise the same nob could fire twice.
    const rows = await db.listRemindersForInstallment(inst.id, academyId);
    const serverIds = new Set(offsets.map((o) => `auto:${inst.id}:${o}`));
    for (const r of rows) {
        if (r.status !== 'SCHEDULED') continue;
        if (r.id.startsWith('auto:')) continue;
        const off = Number((r as any).trigger_offset ?? 0);
        const hasServer = serverIds.has(`auto:${inst.id}:${off}`);
        const offsetKnown = offsets.includes(off);
        if (offsetKnown && hasServer) {
            await db.updateReminderStatus(r.id, 'CANCELLED', 'superseded by server schedule');
            out.cancelled++;
        }
    }
    return out;
}

// ============================================================
// Dual-channel dispatch (cron-driven, per academy, own bot only)
// ============================================================

export interface ReminderDispatchResult { sent: number; retried: number; failed: number; cancelledPaid: number; superseded: number }

/**
 * Sends every due reminder through BOTH channels with independent status:
 *  - Telegram: through THIS academy's own bot (retry bounded, tracked);
 *  - SMS: queued in sms_outbox with status DISABLED until a real panel is
 *    connected (the infrastructure + integration point are ready; sending
 *    real SMS without a panel is impossible by construction).
 * A channel failure never duplicates or blocks the other channel.
 */
export async function dispatchDueReminders(
    env: Env,
    db: AcademyDatabase,
    bot: TelegramBotClient,
    academyId: string,
    now = Date.now(),
    limit = 50
): Promise<ReminderDispatchResult> {
    const res: ReminderDispatchResult = { sent: 0, retried: 0, failed: 0, cancelledPaid: 0, superseded: 0 };
    const due = await db.getDueReminders(now, limit * 3, academyId);
    if (due.length === 0) return res;
    const store = new ContractStore(env);
    const currency = await db.getSetting('currency_unit', 'تومان');

    // Controlled catch-up: one nob per (student, installment) per tick.
    // getDueReminders returns rows oldest-first; walking BACKWARDS keeps the
    // NEWEST due nob of each installment and supersedes older stale ones —
    // a worker outage can therefore never burst several old messages at once.
    const seen = new Set<string>();
    const chosen: ReminderRecord[] = [];
    for (let i = due.length - 1; i >= 0; i--) {
        const r = due[i];
        const key = `${r.student_id}|${r.installment_id || r.id}`;
        if (seen.has(key)) {
            await db.updateReminderStatus(r.id, 'CANCELLED', 'superseded by newer reminder of the same installment');
            res.superseded++;
            continue;
        }
        seen.add(key);
        chosen.push(r);
        if (chosen.length >= limit) break;
    }

    for (const reminder of chosen) {
        // Controlled catch-up: a backlog row older than the dispatch window is
        // cancelled ("missed"), never spammed — the installment's CURRENT state
        // is always visible in «اقساط من» instead.
        if (reminder.scheduled_epoch < now - DISPATCH_MAX_LATE_MS) {
            await db.updateReminderStatus(reminder.id, 'CANCELLED', 'missed schedule window');
            res.superseded++;
            continue;
        }

        // Re-check the installment RIGHT BEFORE sending: a payment registered
        // after scheduling must cancel the message, not send it.
        if (reminder.installment_id) {
            const inst = await db.getInstallmentById(reminder.installment_id, academyId);
            if (!inst) {
                await db.updateReminderStatus(reminder.id, 'CANCELLED', 'installment no longer exists');
                continue;
            }
            if (inst.is_paid === 1 || (inst.remaining_amount <= 0 && inst.paid_amount > 0)) {
                await db.updateReminderStatus(reminder.id, 'CANCELLED', 'Installment already paid');
                res.cancelledPaid++;
                await recordEvent(db, academyId, inst, 'REMINDER_CANCELLED_PAID', 'system');
                continue;
            }
        }

        // ---- Telegram channel (independent status) ----
        let tgOk = false;
        let tgErr = '';
        let tgMessageId = '';
        let sendText = reminder.message_text;
        if (reminder.chat_id) {
            // ONLINE installment → attach THIS installment's dedicated link
            // (same student + exact remaining). Without a REAL connected
            // gateway no link is produced and nothing appears "active".
            try {
                if (reminder.installment_id) {
                    const inst = await db.getInstallmentById(reminder.installment_id, academyId);
                    const method = ((inst as any)?.payment_method || '').toUpperCase();
                    if (inst && inst.is_paid !== 1 && method === 'ONLINE' && (inst.remaining_amount || 0) > 0) {
                        const link = await createInstallmentPaymentLink(
                            env, db, academyId, reminder.student_id, reminder.installment_id,
                            env.PUBLIC_BASE_URL || ''
                        );
                        if (link.ok) {
                            sendText += `\n\n💳 پرداخت آنلاین این قسط (مبلغ ${link.amount.toLocaleString()} ${currency}):\n${link.url}`;
                        } else if (link.reason) {
                            sendText += `\n\nℹ️ ${link.reason}`;
                        }
                    }
                }
            } catch (e) {
                console.error(`[Reminders] link enrichment failed for ${reminder.id}:`, e);
            }

            let sendRes: Awaited<ReturnType<TelegramBotClient['sendMessage']>>;
            try {
                sendRes = await bot.sendMessage({
                    chatId: reminder.chat_id,
                    text: sendText,
                    parseMode: 'HTML',
                    replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                });
            } catch (e: any) {
                sendRes = { ok: false, description: `network: ${e?.message || e}` } as any;
            }
            if (sendRes.ok) {
                tgOk = true;
                tgMessageId = String((sendRes.result as any)?.message_id || '');
            } else {
                tgErr = (sendRes as any).description || 'send failed';
            }
        } else {
            // Unconnected student: Telegram cannot deliver (no retry spam —
            // the bot menu + receipts cover them once they connect), but the
            // SMS copy below still reaches the student's phone.
            tgErr = 'بدون اتصال تلگرام — پیامک یادآوری در صف قرار گرفت';
        }

        // Claim the delivery slots FIRST so a re-run can never double-send.
        await store.claimDelivery(academyId, reminder.id, 'telegram');

        if (tgOk) {
            await db.updateReminderStatus(reminder.id, 'SENT');
            await store.finishDelivery(academyId, reminder.id, 'telegram', 'SENT', '', tgMessageId);
            await db.logMessage({
                id: crypto.randomUUID(),
                student_id: reminder.student_id,
                student_name: reminder.student_name,
                chat_id: reminder.chat_id,
                message_type: `AUTO_${reminder.reminder_type}`,
                content: reminder.message_text,
                status: 'SUCCESS',
                error_message: '',
                timestamp_epoch: Date.now(),
                timestamp_shamsi: ''
            });
            res.sent++;
        } else {
            const attempts = (reminder.retry_count || 0) + 1;
            const noChat = !reminder.chat_id;
            if (noChat) {
                // No chat: retrying Telegram is pointless (nothing will link
                // within minutes) — settle the row honestly instead.
                await db.updateReminderStatus(reminder.id, 'FAILED', tgErr);
                await store.finishDelivery(academyId, reminder.id, 'telegram', 'SKIPPED', tgErr, '');
                res.failed++;
            } else if (attempts < 3) {
                await db.retryReminder(reminder.id, now + 5 * 60_000, `${tgErr} (attempt ${attempts})`);
                await store.finishDelivery(academyId, reminder.id, 'telegram', 'FAILED', tgErr, '');
                res.retried++;
            } else {
                await db.updateReminderStatus(reminder.id, 'FAILED', tgErr);
                await store.finishDelivery(academyId, reminder.id, 'telegram', 'FAILED', tgErr, '');
                await db.logMessage({
                    id: crypto.randomUUID(),
                    student_id: reminder.student_id,
                    student_name: reminder.student_name,
                    chat_id: reminder.chat_id,
                    message_type: `AUTO_${reminder.reminder_type}`,
                    content: reminder.message_text,
                    status: 'FAILED',
                    error_message: tgErr,
                    timestamp_epoch: Date.now(),
                    timestamp_shamsi: ''
                });
                res.failed++;
            }
        }

        // ---- SMS channel (independent status; sending DISABLED by design) ----
        // The outbox row is the integration point for the future panel: its
        // status/error are tracked separately from Telegram (own deliveries
        // ledger row + reminders.sms_status + sms_outbox), and a Telegram
        // failure neither blocks it nor re-queues it.
        if (reminder.student_phone) {
            await queueSmsCopy(store, db, academyId, reminder, now);
        }
    }
    return res;
}

/** Idempotent SMS outbox row for one reminder (real sending stays disabled). */
export async function queueSmsCopy(
    store: ContractStore,
    db: AcademyDatabase,
    academyId: string,
    reminder: ReminderRecord,
    now = Date.now()
): Promise<void> {
    try {
        const body = (reminder.message_text || '').replace(/<[^>]+>/g, '');
        await db.insertSmsOutbox(
            `sms-${reminder.id}`, academyId, reminder.id, reminder.student_phone || '', body,
            'DISABLED', 'sms_provider_not_configured', now
        );
        await db.updateSmsStatus(academyId, reminder.id, 'DISABLED', 'sms_provider_not_configured');
        // Uniform per-channel ledger row: one row per (reminder, channel).
        await store.claimDelivery(academyId, reminder.id, 'sms');
        await store.finishDelivery(academyId, reminder.id, 'sms', 'SKIPPED', 'sms_provider_not_configured');
    } catch (e) {
        console.warn(`[Reminders] SMS queue failed for ${reminder.id} (non-blocking):`, e);
    }
}

async function recordEvent(
    db: AcademyDatabase,
    academyId: string,
    inst: InstallmentRecord,
    type: string,
    actor: string
): Promise<void> {
    try {
        const ev: PaymentEventRecord = {
            id: crypto.randomUUID(),
            academy_id: academyId,
            student_id: inst.student_id,
            installment_id: inst.id,
            payment_id: '',
            claim_id: '',
            event_type: type,
            channel: 'worker',
            actor,
            amount: 0,
            detail: '',
            created_at_epoch: Date.now()
        };
        await db.insertPaymentEvent(ev);
    } catch (_) { /* auditing must never break the dispatch */ }
}
