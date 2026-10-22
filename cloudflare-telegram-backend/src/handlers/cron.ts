// ============================================================
// Cloudflare Scheduled Cron Handler for Automated Background Reminders
// Runs every minute (see wrangler.toml) so reminder times are respected
// even when the Android app is closed or the phone is offline.
//
// Multi-tenant: every ALLOWED academy is processed with ITS OWN bot token
// (D1, migration 0004) and ITS OWN reminder rows. An academy without a
// connected bot is skipped — its reminders stay SCHEDULED and are never
// sent through another tenant's bot.
//
// Stage 5: reminder scheduling + delivery live in src/reminders.ts —
// five nobs per unpaid installment (-7/-4/-1/0/+1 at academy 10:00),
// dual-channel (Telegram + SMS-outbox) with independent, per-channel
// status, controlled catch-up after an outage, and a paid re-check
// right before every send.
// ============================================================

import { Env } from '../types';
import { TelegramBotClient, academyBotToken, allowedAcademies } from '../telegram';
import { AcademyDatabase } from '../db';
import { dispatchReceipts } from '../receipts';
import { dispatchDueReminders, runOverdueDailyScheduler } from '../reminders';

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

    // Stage 5: the five-nob reminder pipeline (Telegram + SMS-outbox).
    // All state transitions (supersede, paid-cancel, retry bound) live in
    // dispatchDueReminders so webhook- and cron-triggered runs behave alike.
    try {
        const rem = await dispatchDueReminders(env, db, bot, academyId, now);
        if (rem.sent || rem.retried || rem.failed || rem.cancelledPaid || rem.superseded || rem.skippedAuto) {
            console.log(`[Cron] Reminders for "${academyId}": sent=${rem.sent} retry=${rem.retried} failed=${rem.failed} paidCancel=${rem.cancelledPaid} superseded=${rem.superseded} autoOff=${rem.skippedAuto}`);
        }
    } catch (e) {
        console.error(`[Cron] Reminder dispatch failed for "${academyId}":`, e);
    }

    // Stage 6: extend the DAILY-OVERDUE chain of the SAME scheduler
    // (auto:<installment>:<day>, day >= 2). Config-gated (overdueDaily)
    // and stop-list-aware inside syncInstallmentReminders; runs even when
    // the app is offline so the daily continuation never stalls.
    try {
        const od = await runOverdueDailyScheduler(db, academyId, now);
        if (od.extended > 0) {
            console.log(`[Cron] Daily-overdue chain for "${academyId}": checked=${od.checked} extended=${od.extended}`);
        }
    } catch (e) {
        console.error(`[Cron] Daily-overdue extension failed for "${academyId}":`, e);
    }
}
