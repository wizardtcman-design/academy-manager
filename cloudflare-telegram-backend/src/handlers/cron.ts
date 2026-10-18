// ============================================================
// Cloudflare Scheduled Cron Handler for Automated Background Reminders
// Runs every minute (see wrangler.toml) so reminder times are respected
// even when the Android app is closed or the phone is offline.
// ============================================================

import { Env } from '../types';
import { TelegramBotClient } from '../telegram';
import { AcademyDatabase } from '../db';

const MAX_ATTEMPTS = 3;
const RETRY_DELAY_MS = 5 * 60 * 1000;
const STALE_GRACE_DAYS = 2;

export async function handleScheduledEvent(
    event: ScheduledEvent,
    env: Env,
    ctx: ExecutionContext
): Promise<void> {
    const botToken = env.TELEGRAM_BOT_TOKEN;
    if (!botToken) {
        console.warn('[Cron] Scheduled task skipped: TELEGRAM_BOT_TOKEN not configured.');
        return;
    }

    const bot = new TelegramBotClient(botToken);
    const db = new AcademyDatabase(env);
    const now = Date.now();

    try {
        // Cancel reminders whose schedule window has passed long ago (app was off / data stale)
        const pruned = await db.pruneStaleReminders(now, STALE_GRACE_DAYS);
        if (pruned > 0) console.log(`[Cron] Pruned ${pruned} stale reminders.`);

        const dueReminders = await db.getDueReminders(now, 50);
        if (dueReminders.length === 0) return;

        console.log(`[Cron] Dispatching ${dueReminders.length} due reminders.`);

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
    } catch (error) {
        console.error('[Cron] Critical error in scheduled reminder execution:', error);
    }
}
