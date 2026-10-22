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

        try {
            const sendRes = await bot.sendMessage({
                chatId: reminder.chat_id,
                text: reminder.message_text,
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
