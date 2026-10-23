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
//
// Stage 7: the same cron also runs the DAILY SERVER BACKUP once per
// Tehran calendar day, per academy. It does NOT depend on the bot
// connection and a backup failure never blocks reminder dispatch.
// ============================================================

import { Env } from '../types';
import { TelegramBotClient, academyBotToken, allowedAcademies } from '../telegram';
import { AcademyDatabase } from '../db';
import { dispatchReceipts } from '../receipts';
import { dispatchDueReminders, runOverdueDailyScheduler } from '../reminders';
import { runBackup } from '../backup';

/** Iran has had no DST since 2022; a fixed +03:30 offset defines the backup day. */
const TEHRAN_OFFSET_MS = 3.5 * 60 * 60 * 1000;

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
        // Backup runs in its own try/catch: a storage problem must never
        // delay or cancel a due reminder, and vice versa.
        try {
            await maybeDailyBackup(env, academyId);
        } catch (error) {
            console.error(`[Cron] Daily backup error for academy "${academyId}":`, error);
        }
    }
}

/**
 * Runs the daily backup once per Tehran calendar day per academy.
 * The last completed day is stored in academy_settings under the
 * tenant-scoped key "<academyId>:backup:last_day", so re-running the
 * cron within the same day never produces a second snapshot (the
 * manual retry button does).
 */
export async function maybeDailyBackup(env: Env, academyId: string): Promise<void> {
    if (!env.DB) return;
    const db = new AcademyDatabase(env, academyId);
    const now = Date.now();
    const tehranDay = new Date(now + TEHRAN_OFFSET_MS).toISOString().slice(0, 10);
    // Tenant-scoped marker key: academy A's backup day never satisfies academy B.
    const dayKey = `${academyId}:backup:last_day`;

    const last = await db.getSetting(dayKey);
    if (last === tehranDay) return;

    const outcome = await runBackup(env, academyId, 'AUTO');
    if (outcome.ok) {
        // Mark the day as done only after a VERIFIED healthy snapshot exists.
        await db.setSetting(dayKey, tehranDay);
        console.log(`[Cron] Daily backup for "${academyId}" stored (${outcome.storedBytes} bytes compressed).`);
    } else {
        // No day marker: the next cron minute retries automatically, and the
        // failure stays visible in /api/backup/status for the manager.
        console.error(`[Cron] Daily backup for "${academyId}" FAILED: ${outcome.error}`);
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
