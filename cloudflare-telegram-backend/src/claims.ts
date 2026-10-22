// ============================================================
// Stage 5: card-to-card receipt claims + the ONE shared payment
// registration service.
//
// Flow (all server-verified, nothing trusted from the client):
//   1. the student sends a bank-transfer PHOTO in the bot, addressed to
//      one installment («قسط <n>» in the caption);
//   2. the worker stores the claim (evidence only — NEVER a payment),
//      notifies the manager(s) with inline buttons
//      «ثبت و تأیید پرداخت» / «رد رسید»;
//   3. approval is a single atomic CAS on the claim row; the winner
//      registers the REAL payment through the same service every other
//      path uses (Stage 4 ingest pipeline, first-installment contract
//      ordering included), updates the installment, cancels the
//      installment's reminders and audits everything in payment_events;
//   4. rejection changes nothing financially and keeps the claim history.
//
// Manager authorization is ALWAYS checked server-side (per academy):
//   - the academy setting `manager_chat_ids` (comma-separated), plus
//   - the deployment's ADMIN_CHAT_ID as the owner fallback.
// Hiding buttons in a UI would never be enough.
//
// The deterministic payment id `clm-<claim id>` makes the registration
// idempotent end-to-end: a double click, a webhook replay or a re-sync
// can never create a second payment or a second receipt.
// ============================================================

import { Env, PaymentClaimRecord, InstallmentRecord, StudentRecord } from './types';
import { AcademyDatabase } from './db';
import { TelegramBotClient } from './telegram';
import { ingestPayments } from './receipts';
import { epochToShamsi } from './shamsi';

export const CLAIM_PHOTO_MAX_BYTES = 200 * 1024;

// ============================================================
// Manager authorization (server-side, per academy)
// ============================================================
export async function managerChatIds(db: AcademyDatabase, env: Env, academyId: string): Promise<string[]> {
    const raw = await db.getSetting('manager_chat_ids', '');
    const ids = raw.split(',').map((s) => s.trim()).filter(Boolean);
    const owner = (env.ADMIN_CHAT_ID || '').trim();
    if (owner) ids.push(owner);
    return [...new Set(ids)];
}

export async function isManager(db: AcademyDatabase, env: Env, academyId: string, chatId: string): Promise<boolean> {
    const ids = await managerChatIds(db, env, academyId);
    return ids.includes(String(chatId).trim());
}

// ============================================================
// Submission (student → bot photo)
// ============================================================
export type SubmitResult = { ok: true; claimId: string } | { ok: false; reason: string }

export async function submitReceiptClaim(
    db: AcademyDatabase,
    academyId: string,
    student: StudentRecord,
    inst: InstallmentRecord,
    photoBytes: Uint8Array | null,
    photoFileId: string,
    note: string
): Promise<SubmitResult> {
    if (!inst || inst.student_id !== student.id) {
        return { ok: false, reason: 'قسط موردنظر به شما تعلق ندارد.' };
    }
    if (inst.is_paid === 1 || (inst.remaining_amount || 0) <= 0) {
        return { ok: false, reason: 'این قسط قبلاً به‌طور کامل پرداخت شده است.' };
    }
    // One live claim per installment: re-sending the photo must not flood the manager.
    const open = await db.listClaimsForInstallment(inst.id, ['PENDING'], academyId);
    if (open.length > 0) {
        return { ok: false, reason: 'یک رسید برای این قسط قبلاً ارسال شده و در انتظار بررسی مدیریت است. لطفاً منتظر بمانید.' };
    }

    const now = Date.now();
    const id = crypto.randomUUID();
    const claim: PaymentClaimRecord = {
        id,
        academy_id: academyId,
        student_id: student.id,
        student_name: student.full_name,
        installment_id: inst.id,
        installment_number: inst.installment_number,
        course_title: inst.course_title || '',
        claimed_amount: inst.remaining_amount,
        note: (note || '').slice(0, 500),
        photo_bytes: photoBytes,
        photo_file_id: photoFileId || '',
        status: 'PENDING',
        decided_by: '',
        decision_note: '',
        decided_at_epoch: 0,
        payment_id: '',
        telegram_chat_id: student.telegram_chat_id || '',
        manager_message_id: '',
        manager_chat_id: '',
        created_at_epoch: now,
        updated_at_epoch: now
    };
    await db.insertPaymentClaim(claim);
    await db.insertPaymentEvent({
        id: crypto.randomUUID(),
        academy_id: academyId,
        student_id: student.id,
        installment_id: inst.id,
        payment_id: '',
        claim_id: id,
        event_type: 'RECEIPT_SUBMITTED',
        channel: 'telegram',
        actor: 'student',
        amount: inst.remaining_amount,
        detail: note ? `note: ${note.slice(0, 120)}` : '',
        created_at_epoch: now
    });
    return { ok: true, claimId: id };
}

/** The caption + photo the manager receives (real data, evidence only). */
export function renderClaimNotification(c: PaymentClaimRecord, currency: string): string {
    const numStr = c.installment_number > 0 ? `قسط ${c.installment_number}` : 'قسط';
    return `📥 <b>رسید پرداخت دریافتی — در انتظار بررسی</b>\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `👤 هنرجو: <b>${c.student_name}</b>\n` +
        `📚 دوره: ${c.course_title || '-'}\n` +
        `🔹 ${numStr}\n` +
        `💵 مبلغ ادعاشده: <b>${c.claimed_amount.toLocaleString()} ${currency}</b>\n` +
        `🗓 تاریخ دریافت: <b>${epochToShamsi(c.created_at_epoch)}</b>\n` +
        (c.note ? `📝 توضیحات هنرجو: ${c.note}\n` : '') +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `⚠️ این رسید صرفاً مدرک بررسی است؛ تا تأیید شما، پرداختی ثبت نمی‌شود.\n` +
        `برای رد با ذکر علت، به همین پیام پاسخ دهید.`;
}

/** Notifies every configured manager of THIS academy (with the decision buttons). */
export async function notifyManagersOfClaim(
    env: Env,
    db: AcademyDatabase,
    bot: TelegramBotClient,
    academyId: string,
    claim: PaymentClaimRecord,
    photoBytes: Uint8Array | null
): Promise<void> {
    const managers = await managerChatIds(db, env, academyId);
    if (managers.length === 0) return; // claim stays PENDING; /claims lists it any time
    const currency = await db.getSetting('currency_unit', 'تومان');
    const text = renderClaimNotification(claim, currency);
    const keyboard = TelegramBotClient.getClaimDecisionKeyboard(claim.id);

    let sentTo = '';
    let messageId = '';
    for (const chat of managers) {
        let res: Awaited<ReturnType<TelegramBotClient['sendPhoto']>>;
        if (photoBytes && photoBytes.length > 0) {
            try {
                res = await bot.sendPhoto({
                    chatId: chat, bytes: photoBytes, caption: text, replyMarkup: keyboard
                });
            } catch (e: any) {
                res = { ok: false, description: `network: ${e?.message || e}` } as any;
            }
        } else {
            // Photo missing/too large: the DECISION must still reach the manager.
            const r = await bot.sendMessage({ chatId: chat, text, parseMode: 'HTML', replyMarkup: keyboard });
            res = r as any;
        }
        if (res.ok) {
            sentTo = sentTo || chat;
            messageId = messageId || String((res.result as any)?.message_id || '');
        }
    }
    if (sentTo) {
        await db.updatePaymentClaim(claim.id, academyId, {
            manager_chat_id: sentTo,
            manager_message_id: messageId
        });
    }
}

// ============================================================
// Decision (manager → inline buttons / reply)
// ============================================================
export type DecisionOutcome =
    | { state: 'APPROVED'; paymentId: string; amount: number; receiptSent: boolean }
    | { state: 'REJECTED' }
    | { state: 'ALREADY_DECIDED'; status: string }
    | { state: 'FORBIDDEN' }
    | { state: 'NOT_FOUND' }
    | { state: 'ALREADY_PAID'; reason: string };

/**
 * Registers the REAL payment for an approved claim — the shared service:
 * deterministic payment id, Stage-4 ingest (contract ordering included),
 * installment update, reminder cancellation, audit event. Never re-runs
 * for the same claim (CAS + PK).
 */
export async function approveClaim(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    claimId: string,
    managerChatId: string,
    bot: TelegramBotClient | null
): Promise<DecisionOutcome> {
    const claim = await db.getPaymentClaim(claimId, academyId);
    if (!claim) return { state: 'NOT_FOUND' };

    // 1-2. manager authorization + identity of installment/student — both re-verified
    // server-side; the callback data alone proves nothing.
    if (!(await isManager(db, env, academyId, managerChatId))) return { state: 'FORBIDDEN' };
    const inst = await db.getInstallmentById(claim.installment_id, academyId);
    if (!inst || inst.student_id !== claim.student_id) {
        return { state: 'ALREADY_PAID', reason: 'قسط یا هنرجوی این رسید معتبر نیست.' };
    }

    // 3. payment must not already exist (another path registered it meanwhile)
    if (inst.is_paid === 1 || (inst.remaining_amount || 0) <= 0) {
        await db.decidePaymentClaim(claimId, academyId, 'REJECTED', managerChatId,
            'قسط پیش از تأیید، پرداخت شده بود', '');
        return { state: 'ALREADY_PAID', reason: 'این قسط قبلاً پرداخت شده است.' };
    }

    // 4-5. ATOMIC decision + REAL registration. The applied amount is the
    // remaining at approval time (capped — financial state may have moved
    // since submission); the deterministic id keeps the record unique.
    const applied = Math.min(claim.claimed_amount, inst.remaining_amount);
    const paymentId = `clm-${claim.id}`;
    const won = await db.decidePaymentClaim(claimId, academyId, 'APPROVED', managerChatId, '', paymentId);
    if (!won) {
        const fresh = await db.getPaymentClaim(claimId, academyId);
        return { state: 'ALREADY_DECIDED', status: fresh?.status || 'PENDING' };
    }

    const now = Date.now();
    const paidTotal = (inst.paid_amount || 0) + applied;
    const remaining = Math.max(0, (inst.amount || 0) - paidTotal);
    const fullyPaid = remaining <= 0;
    await db.updateInstallmentAfterPayment(inst.id, academyId, {
        paid_amount: paidTotal,
        remaining_amount: remaining,
        is_paid: fullyPaid ? 1 : (inst.is_paid || 0),
        paid_date_shamsi: fullyPaid ? epochToShamsi(now) : (inst.paid_date_shamsi || '')
    });

    // 6. audit: approval event. The PAYMENT_REGISTERED row is written by the
    // shared ingest pipeline below (exactly once, channel 'telegram') —
    // recording it here too would double-count the registration.
    await db.insertPaymentEvent({
        id: crypto.randomUUID(),
        academy_id: academyId,
        student_id: claim.student_id,
        installment_id: inst.id,
        payment_id: paymentId,
        claim_id: claim.id,
        event_type: 'RECEIPT_APPROVED',
        channel: 'telegram',
        actor: managerChatId,
        amount: applied,
        detail: '',
        created_at_epoch: now
    });

    // 8. the OFFICIAL receipt rides the EXISTING Stage-4 pipeline (the same
    // rows the app's payment tab produces): first installment waits for the
    // signed contract, others queue immediately. Telegram-only, dedupe by id.
    await ingestPayments(env, db, academyId, [{
        id: paymentId,
        transactionId: `CLM-${claim.id.slice(0, 8).toUpperCase()}`,
        studentId: claim.student_id,
        studentName: claim.student_name,
        installmentId: inst.id,
        installmentNumber: inst.installment_number,
        amount: applied,
        paymentDateShamsi: epochToShamsi(now),
        paymentTime: '',
        paymentMethod: 'TRANSFER',
        destinationAccountTitle: 'کارت به کارت (رسید هنرجو)',
        trackingNumber: '',
        notes: claim.note ? `رسید هنرجو: ${claim.note}` : 'رسید ارسالی هنرجو در ربات',
        recordedBy: `مدیر ${managerChatId}`,
        isReversed: false
    }]);

    // 9. stop the installment's remaining reminders (they re-arm on the next
    // installment sync only if the payment was partial)
    await db.cancelPendingRemindersForInstallment(inst.id);

    // 7-10. deliver now (best effort) and tell the manager the result
    let receiptSent = false;
    if (bot) {
        try {
            const { dispatchReceipts } = await import('./receipts');
            await dispatchReceipts(env, db, bot, academyId, Date.now());
            const row = await db.getPaymentReceipt(paymentId, academyId);
            receiptSent = row?.send_status === 'SENT';
        } catch (_) { /* stays queued; cron retries */ }
    }
    return { state: 'APPROVED', paymentId, amount: applied, receiptSent };
}

export async function rejectClaim(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    claimId: string,
    managerChatId: string,
    reason: string
): Promise<DecisionOutcome> {
    const claim = await db.getPaymentClaim(claimId, academyId);
    if (!claim) return { state: 'NOT_FOUND' };
    if (!(await isManager(db, env, academyId, managerChatId))) return { state: 'FORBIDDEN' };

    // History is kept: the row flips to REJECTED (never deleted), the
    // installment state and financials stay untouched, no receipt is created.
    const won = await db.decidePaymentClaim(claimId, academyId, 'REJECTED', managerChatId, reason || '', '');
    if (!won) {
        const fresh = await db.getPaymentClaim(claimId, academyId);
        return { state: 'ALREADY_DECIDED', status: fresh?.status || 'PENDING' };
    }
    await db.insertPaymentEvent({
        id: crypto.randomUUID(),
        academy_id: academyId,
        student_id: claim.student_id,
        installment_id: claim.installment_id,
        payment_id: '',
        claim_id: claim.id,
        event_type: 'RECEIPT_REJECTED',
        channel: 'telegram',
        actor: managerChatId,
        amount: 0,
        detail: reason ? `reason: ${reason.slice(0, 200)}` : '',
        created_at_epoch: Date.now()
    });
    return { state: 'REJECTED' };
}
