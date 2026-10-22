// ============================================================
// Cloudflare Scheduled Cron Handler for Automated Background Reminders
// Runs every minute (see wrangler.toml) so reminder times are respected
// even when the Android app is closed or the phone is offline.
//
// Multi-tenant: every ALLOWED academy is processed with ITS OWN bot token
// (D1, migration 0004) and ITS OWN reminder rows. An academy without a
// connected bot is skipped — its reminders stay SCHEDULED and are never
// sent through another tenant's bot.
// ============================================================

import { Env } from '../types';
import { TelegramBotClient, academyBotToken, allowedAcademies } from '../telegram';
import { AcademyDatabase } from '../db';
import { dispatchReceipts } from '../receipts';
import { createInstallmentPaymentLink, resolveProvider } from '../gateway';

const MAX_ATTEMPTS = 3;
const RETRY_DELAY_MS = 5 * 60 * 1000;
const STALE_GRACE_DAYS = 2;

export async function handleScheduledEvent(
    event: ScheduledEvent,
    env: Env,
    ctx: ExecutionContext
): Promise<void> {
    const academies = allowedAcademies(env);
    if (academies.length === 0) academies.push('default');

    for (const academyId of academies) {
        try {
            await processAcademy(env, academyId);
        } catch (error) {
            console.error(`[Cron] Critical error for academy "${academyId}":`, error);
        }
    }
}

async function processAcademy(env: Env, academyId: string): Promise<void> {
    const botToken = await academyBotToken(env, academyId);
    if (!botToken) {
        console.warn(`[Cron] Academy "${academyId}" skipped: no Telegram bot connected (token empty).`);
        return;
    }

    const bot = new TelegramBotClient(botToken);
    const db = new AcademyDatabase(env, academyId);
    const now = Date.now();

    // Stage 4: deliver queued payment receipts through THIS academy's own bot.
    // SENT receipts are never touched again, so cron retries cannot duplicate.
    try {
        const receipts = await dispatchReceipts(env, db, bot, academyId, now);
        if (receipts.sent || receipts.notices || receipts.failed) {
            console.log(`[Cron] Receipts for "${academyId}": sent=${receipts.sent} notices=${receipts.notices} failed=${receipts.failed}`);
        }
    } catch (e) {
        console.error(`[Cron] Receipt dispatch failed for "${academyId}":`, e);
    }

    // Cancel reminders whose schedule window has passed long ago (app was off / data stale)
    const pruned = await db.pruneStaleReminders(now, STALE_GRACE_DAYS);
    if (pruned > 0) console.log(`[Cron] Pruned ${pruned} stale reminders for "${academyId}".`);

    const dueReminders = await db.getDueReminders(now, 50, academyId);
    if (dueReminders.length === 0) return;

    console.log(`[Cron] Dispatching ${dueReminders.length} due reminders for "${academyId}".`);

    for (const reminder of dueReminders) {
        if (!reminder.chat_id) {
            await db.updateReminderStatus(reminder.id, 'FAILED', 'No Telegram Chat ID linked');
            continue;
        }

        // Stage 4: when the manager chose ONLINE payment for this installment,
        // attach the dedicated link (same student + same installment + exact
        // remaining amount) to the reminder. The same text is queued for SMS
        // as DISABLED — real SMS sending stays off until a panel is connected.
        let sendText = reminder.message_text;
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
                        sendText += `\n\n💳 پرداخت آنلاین این قسط (مبلغ ${inst.remaining_amount.toLocaleString()}):\n${link.url}`;
                        if (reminder.student_phone) {
                            try {
                                await env.DB!.prepare(`
                                    INSERT OR IGNORE INTO sms_outbox (id, academy_id, reminder_id, phone, body, status, error, created_at_epoch)
                                    VALUES (?,?,?,?,?,?,?,?)
                                `).bind(
                                    `paylink-${reminder.id}`, academyId, reminder.id, reminder.student_phone,
                                    sendText.replace(/<[^>]+>/g, ''), 'DISABLED', 'sms_provider_not_configured', Date.now()
                                ).run();
                            } catch (_) { /* SMS queue must never block the reminder */ }
                        }
                    } else if (link.reason) {
                        sendText += `\n\nℹ️ ${link.reason}`;
                    }
                }
            }
        } catch (e) {
            console.error(`[Cron] payment-link enrichment failed for reminder ${reminder.id}:`, e);
        }

        try {
            const sendRes = await bot.sendMessage({
                chatId: reminder.chat_id,
                text: sendText,
                parseMode: 'HTML',
                replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
            });

            if (sendRes.ok) {
                await db.updateReminderStatus(reminder.id, 'SENT');
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
            } else {
                const attempts = (reminder.retry_count || 0) + 1;
                const description = sendRes.description || 'Send failed';
                if (attempts < MAX_ATTEMPTS) {
                    // Transient error (network / Telegram 429): try again in a few minutes
                    await db.retryReminder(reminder.id, now + RETRY_DELAY_MS, `${description} (attempt ${attempts})`);
                } else {
                    await db.updateReminderStatus(reminder.id, 'FAILED', description);
                    await db.logMessage({
                        id: crypto.randomUUID(),
                        student_id: reminder.student_id,
                        student_name: reminder.student_name,
                        chat_id: reminder.chat_id,
                        message_type: `AUTO_${reminder.reminder_type}`,
                        content: reminder.message_text,
                        status: 'FAILED',
                        error_message: description,
                        timestamp_epoch: Date.now(),
                        timestamp_shamsi: ''
                    });
                }
            }
        } catch (err: any) {
            console.error(`[Cron] Failed to send reminder ${reminder.id}:`, err);
            const attempts = (reminder.retry_count || 0) + 1;
            if (attempts < MAX_ATTEMPTS) {
                await db.retryReminder(reminder.id, now + RETRY_DELAY_MS, err.message || 'Exception');
            } else {
                await db.updateReminderStatus(reminder.id, 'FAILED', err.message || 'Exception');
            }
        }
    }
}
