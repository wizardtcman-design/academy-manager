// ============================================================
// Cloudflare Scheduled Cron Handler for Automated Background Reminders
// ============================================================

import { Env } from '../types';
import { TelegramBotClient } from '../telegram';
import { AcademyDatabase } from '../db';

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

    console.log(`[Cron] Checking scheduled reminders due before ${new Date(now).toISOString()}...`);

    try {
        // Fetch up to 50 due reminders that haven't been sent yet
        const dueReminders = await db.getDueReminders(now, 50);

        if (dueReminders.length === 0) {
            console.log('[Cron] No due reminders found.');
            return;
        }

        console.log(`[Cron] Found ${dueReminders.length} due reminders to dispatch.`);

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
                    await db.updateReminderStatus(reminder.id, 'FAILED', sendRes.description || 'Send failed');
                    await db.logMessage({
                        id: crypto.randomUUID(),
                        student_id: reminder.student_id,
                        student_name: reminder.student_name,
                        chat_id: reminder.chat_id,
                        message_type: `AUTO_${reminder.reminder_type}`,
                        content: reminder.message_text,
                        status: 'FAILED',
                        error_message: sendRes.description || 'Send failed',
                        timestamp_epoch: Date.now(),
                        timestamp_shamsi: ''
                    });
                }
            } catch (err: any) {
                console.error(`[Cron] Failed to send reminder ${reminder.id}:`, err);
                await db.updateReminderStatus(reminder.id, 'FAILED', err.message);
            }
        }
    } catch (error) {
        console.error('[Cron] Critical error in scheduled reminder execution:', error);
    }
}
