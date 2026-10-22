// ============================================================
// Stage 4: payment receipts — ingestion, ordering and delivery.
//
// The receipt is THE app's own payment record (the same record shown
// in the app's "پرداخت‌ها" tab and its digital-receipt dialog). Nothing
// parallel is invented: the worker stores the app payload verbatim,
// renders the SAME fields, and delivers it ONLY through Telegram.
//
// Guarantees implemented here (all covered by payments-flow.test.ts):
//   - exactly one receipt row per payment id (PRIMARY KEY = app id),
//     so re-sync/retry can never create or re-send a duplicate;
//   - a payment only ever becomes a receipt after it is REAL: the app
//     pushes payments, and unpaid installments never produce one;
//   - the first installment (paid at registration) is held until the
//     contract is SIGNED and its PDF has been delivered, then sent
//     after a configurable delay (never before the contract);
//   - every send attempt is status-tracked (PENDING / WAITING_CONTRACT /
//     QUEUED / SENT / FAILED / REVERSED / REVERSAL_QUEUED /
//     REVERSED_NOTIFIED) with attempts + last_error for forensics and
//     controlled re-delivery;
//   - receipts are Telegram-only: no SMS is ever queued for a receipt.
// ============================================================

import { Env, PaymentReceiptRecord } from './types';
import { AcademyDatabase } from './db';
import { TelegramBotClient } from './telegram';
import { composeReceiptPdf, ReceiptPdfData } from './receipt-pdf';

export const MAX_RECEIPT_ATTEMPTS = 5;
const DISPATCH_LIMIT = 25;

/** The payment payload exactly as the Android app records it. */
export interface PaymentInput {
    id: string;
    transactionId?: string;
    paymentCode?: string;
    studentId: string;
    studentName?: string;
    installmentId?: string;
    installmentNumber?: number;
    amount: number;
    paymentDateShamsi?: string;
    paymentTime?: string;
    paymentMethod?: string;
    destinationAccountTitle?: string;
    trackingNumber?: string;
    notes?: string;
    isReversed?: boolean;
    reversalReason?: string;
    recordedBy?: string;
    createdAt?: number;
}

export type ReceiptRow = PaymentReceiptRecord;

/** Persian method labels — mirrors the app's DigitalReceiptDialog mapping. */
export function methodLabelFa(method: string): string {
    switch ((method || '').toUpperCase()) {
        case 'TRANSFER': return 'کارت به کارت';
        case 'POS': return 'دستگاه پوز (کارتخوان)';
        case 'CASH': return 'نقدی';
        case 'BANK_DEPOSIT': return 'واریز بانکی';
        case 'ONLINE': return 'پرداخت آنلاین';
        default: return 'سایر';
    }
}

function clampDelayMinutes(raw: string | undefined): number {
    const n = Number(raw || '');
    if (!Number.isFinite(n) || n <= 0) return 10; // sensible default: 10 minutes
    return Math.min(1440, Math.max(1, Math.round(n)));
}

// ============================================================
// Ingestion (dedupe + ordering)
// ============================================================
export interface IngestOutcome {
    created: number;
    updated: number;
    skipped: number;
}

/**
 * Idempotent push of app payments. A payment whose id already exists is
 * NEVER re-sent — only a reversal transition is applied. A reversed
 * payment never becomes a sendable receipt.
 */
export async function ingestPayments(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    payments: PaymentInput[]
): Promise<IngestOutcome> {
    const out: IngestOutcome = { created: 0, updated: 0, skipped: 0 };
    const requiresContract = (await db.getSetting('receipt_first_payment_requires_contract', 'true')) !== 'false';

    for (const p of payments || []) {
        if (!p || !p.id || !p.studentId || !(Number(p.amount) > 0)) { out.skipped++; continue; }
        const existing = await db.getPaymentReceipt(p.id, academyId);
        const now = Date.now();

        if (existing) {
            // Idempotency: same payment pushed again (sync/retry) changes nothing
            // EXCEPT a genuine reversal transition.
            if (p.isReversed && existing.is_reversed !== 1) {
                if (existing.send_status === 'SENT') {
                    await db.updatePaymentReceipt(existing.id, academyId, {
                        is_reversed: 1,
                        reversal_reason: p.reversalReason || '',
                        send_status: 'REVERSAL_QUEUED',
                        eligible_at_epoch: now,
                        updated_at_epoch: now
                    });
                } else {
                    // Was never sent: mark reversed, no notice needed.
                    await db.updatePaymentReceipt(existing.id, academyId, {
                        is_reversed: 1,
                        reversal_reason: p.reversalReason || '',
                        send_status: existing.send_status === 'REVERSED_NOTIFIED' ? existing.send_status : 'REVERSED',
                        updated_at_epoch: now
                    });
                }
                out.updated++;
                // Stage 5: reversal is part of the audited lifecycle (exactly once).
                try {
                    await db.insertPaymentEvent({
                        id: crypto.randomUUID(),
                        academy_id: academyId,
                        student_id: p.studentId,
                        installment_id: p.installmentId || existing.installment_id,
                        payment_id: p.id,
                        claim_id: '',
                        event_type: 'PAYMENT_REVERSED',
                        channel: 'app',
                        actor: p.recordedBy || 'system',
                        amount: existing.amount,
                        detail: p.reversalReason || '',
                        created_at_epoch: now
                    });
                } catch (_) { /* auditing must never block ingestion */ }
            } else {
                out.skipped++;
            }
            continue;
        }

        // Course title comes from the installment the payment belongs to.
        let courseTitle = '';
        if (p.installmentId) {
            const inst = await db.getInstallmentById(p.installmentId, academyId);
            courseTitle = inst?.course_title || '';
        }

        // Ordering rules:
        //  - reversed payment   -> REVERSED (never sent)
        //  - first installment  -> WAITING_CONTRACT (released after signed PDF + delay)
        //  - everything else    -> QUEUED now (it is a REAL, recorded payment)
        const isFirst = (Number(p.installmentNumber) || 0) <= 1;
        const status = p.isReversed
            ? 'REVERSED'
            : (isFirst && requiresContract ? 'WAITING_CONTRACT' : 'QUEUED');

        await db.insertPaymentReceipt({
            id: p.id,
            academy_id: academyId,
            student_id: p.studentId,
            installment_id: p.installmentId || '',
            installment_number: Number(p.installmentNumber) || 0,
            course_title: courseTitle,
            transaction_id: p.transactionId || p.paymentCode || '',
            tracking_number: p.trackingNumber || '',
            amount: Number(p.amount),
            student_name: p.studentName || '',
            paid_date_shamsi: p.paymentDateShamsi || '',
            paid_time: p.paymentTime || '',
            payment_method: p.paymentMethod || '',
            destination_account: p.destinationAccountTitle || '',
            notes: p.notes || '',
            is_reversed: p.isReversed ? 1 : 0,
            reversal_reason: p.reversalReason || '',
            source: 'manual',
            receipt_json: JSON.stringify(p),
            send_status: status,
            send_reason: isFirst ? 'after-contract' : 'payment',
            eligible_at_epoch: status === 'QUEUED' ? now : 0,
            attempts: 0,
            last_attempt_epoch: 0,
            telegram_message_id: '',
            last_error: '',
            created_at_epoch: now,
            updated_at_epoch: now
        });
        out.created++;

        // Stage 5: a REAL payment stops the installment's reminders right away
        // (the pre-send re-check in the dispatcher catches anything in flight
        // too) and lands in the payment lifecycle history. A partial payment
        // re-arms its five nobs automatically on the next installment sync.
        if (p.installmentId) {
            await db.cancelPendingRemindersForInstallment(p.installmentId);
            try {
                await db.insertPaymentEvent({
                    id: crypto.randomUUID(),
                    academy_id: academyId,
                    student_id: p.studentId,
                    installment_id: p.installmentId,
                    payment_id: p.id,
                    claim_id: '',
                    event_type: 'PAYMENT_REGISTERED',
                    channel: p.id.startsWith('clm-') ? 'telegram' : 'app',
                    actor: p.recordedBy || 'system',
                    amount: Number(p.amount),
                    detail: p.transactionId || p.paymentCode || '',
                    created_at_epoch: now
                });
            } catch (_) { /* auditing must never block ingestion */ }
        }
    }
    return out;
}

/**
 * Called by the contract flow right after the SIGNED PDF has actually been
 * delivered to the student's chat: releases that student's held first-
 * installment receipts with the configured delay. Never called for a
 * receipt that is not in WAITING_CONTRACT, and never for another tenant.
 */
export async function releaseFirstReceiptsAfterContract(
    env: Env,
    db: AcademyDatabase,
    academyId: string,
    studentId: string
): Promise<number> {
    const delay = clampDelayMinutes(await db.getSetting('receipt_first_payment_delay_minutes', '10'));
    const eligible = Date.now() + delay * 60_000;
    const held = await db.listPaymentReceiptsByStatus(academyId, 'WAITING_CONTRACT', studentId);
    for (const r of held) {
        await db.updatePaymentReceipt(r.id, academyId, {
            send_status: 'QUEUED',
            send_reason: 'after-contract',
            eligible_at_epoch: eligible,
            updated_at_epoch: Date.now()
        });
    }
    return held.length;
}

// ============================================================
// Rendering — the SAME fields as the app's digital receipt
// ============================================================
export function renderReceiptText(r: ReceiptRow, academyName: string, currency: string): string {
    const money = `${r.amount.toLocaleString()} ${currency}`;
    const head = r.is_reversed
        ? `⚠️ <b>رسید برگشتی (ابطال پرداخت)</b>`
        : `🧾 <b>رسید پرداخت شهریه</b>`;
    const inst = r.installment_number > 0
        ? `قسط ${r.installment_number}${r.total_installments_hint ? ` از ${r.total_installments_hint}` : ''}${r.course_title ? ` — ${r.course_title}` : ''}`
        : (r.course_title || '-');
    return `${head}\n` +
        `━━━━━━━━━━━━━━━━━━━\n` +
        `🏫 <b>${academyName}</b>\n` +
        `🧾 کد تراکنش: <code>${r.transaction_id || r.id.slice(0, 8)}</code>\n` +
        `🗓 تاریخ: ${r.paid_date_shamsi || '-'}${r.paid_time ? ` — ${r.paid_time}` : ''}\n` +
        `👤 هنرجو: <b>${r.student_name}</b>\n` +
        `📚 ${inst}\n` +
        `💵 مبلغ: <b>${money}</b>\n` +
        `💳 روش پرداخت: ${methodLabelFa(r.payment_method)}\n` +
        (r.destination_account ? `🏦 حساب مقصد: ${r.destination_account}\n` : '') +
        (r.tracking_number ? `🔎 کد رهگیری: <code>${r.tracking_number}</code>\n` : '') +
        (r.source === 'online' ? `🌐 نوع: پرداخت آنلاین (تأیید شده توسط درگاه)\n` : '') +
        (r.is_reversed && r.reversal_reason ? `↩️ دلیل برگشت: ${r.reversal_reason}\n` : '') +
        (r.notes ? `📝 توضیحات: ${r.notes}\n` : '') +
        `━━━━━━━━━━━━━━━━━━━\n` +
        (r.is_reversed ? 'این پرداخت توسط آموزشگاه برگشت زده شده است.' : 'با تشکر از پرداخت شما 🌸');
}

export function receiptPdfData(r: ReceiptRow, academyName: string, academyPhone: string, currency: string): ReceiptPdfData {
    return {
        academyName,
        academyPhone,
        currency,
        transactionId: r.transaction_id || '',
        studentName: r.student_name || '',
        courseTitle: r.course_title || '',
        installmentNumber: r.installment_number || 0,
        totalInstallments: r.total_installments_hint || 0,
        amount: r.amount || 0,
        paidDateShamsi: r.paid_date_shamsi || '',
        paidTime: r.paid_time || '',
        methodLabel: methodLabelFa(r.payment_method),
        destinationAccount: r.destination_account || '',
        trackingNumber: r.tracking_number || '',
        notes: r.notes || '',
        isReversed: r.is_reversed === 1,
        reversalReason: r.reversal_reason || '',
        source: r.source || 'manual'
    };
}

export function composeReceiptPdfFor(r: ReceiptRow, academyName: string, academyPhone: string, currency: string): Uint8Array {
    return composeReceiptPdf(receiptPdfData(r, academyName, academyPhone, currency));
}

// ============================================================
// Delivery (cron-driven, per academy, own bot only)
// ============================================================
export interface DispatchOutcome { sent: number; failed: number; notices: number }

/**
 * Sends every QUEUED receipt whose eligibility time has passed, and every
 * queued reversal notice. A receipt without a linked chat stays QUEUED
 * (checked again on every cron tick — delivered as soon as the student
 * connects). SENT rows are never touched again (no duplicates on retry).
 */
export async function dispatchReceipts(
    env: Env,
    db: AcademyDatabase,
    bot: TelegramBotClient,
    academyId: string,
    now = Date.now()
): Promise<DispatchOutcome> {
    const out: DispatchOutcome = { sent: 0, failed: 0, notices: 0 };
    const academyName = await db.getSetting('academy_name', 'آموزشگاه');
    const currency = await db.getSetting('currency_unit', 'تومان');

    const due = await db.listDuePaymentReceipts(academyId, now, DISPATCH_LIMIT);
    for (const r of due) {
        // Reversal notices ride the same pipeline.
        if (r.send_status === 'REVERSAL_QUEUED') {
            const student = await db.getStudentById(r.student_id, academyId);
            const chat = student?.telegram_chat_id;
            if (!chat) continue; // stays queued until the student connects
            let res: Awaited<ReturnType<TelegramBotClient['sendMessage']>>;
            try {
                res = await bot.sendMessage({
                    chatId: chat,
                    text: renderReceiptText(r, academyName, currency),
                    parseMode: 'HTML',
                    replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
                });
            } catch (e: any) {
                res = { ok: false, description: `network: ${e?.message || e}` } as any;
            }
            if (res.ok) {
                await db.updatePaymentReceipt(r.id, academyId, {
                    send_status: 'REVERSED_NOTIFIED', telegram_message_id: String((res.result as any)?.message_id || ''),
                    last_attempt_epoch: Date.now(), updated_at_epoch: Date.now()
                });
                out.notices++;
            } else {
                await db.updatePaymentReceipt(r.id, academyId, {
                    attempts: r.attempts + 1, last_attempt_epoch: Date.now(),
                    last_error: (res as any).description || 'send failed', updated_at_epoch: Date.now()
                });
                out.failed++;
            }
            continue;
        }

        const student = await db.getStudentById(r.student_id, academyId);
        const chat = student?.telegram_chat_id;
        if (!chat) {
            await db.updatePaymentReceipt(r.id, academyId, {
                last_error: 'student has no linked Telegram chat yet',
                last_attempt_epoch: now, updated_at_epoch: now
            });
            continue; // stays QUEUED: delivered the moment the student connects
        }

        let res: Awaited<ReturnType<TelegramBotClient['sendMessage']>>;
        try {
            res = await bot.sendMessage({
                chatId: chat,
                text: renderReceiptText(r, academyName, currency),
                parseMode: 'HTML',
                replyMarkup: TelegramBotClient.getStudentReplyKeyboard()
            });
        } catch (e: any) {
            // A network outage is a FAILED ATTEMPT (tracked), never a crash:
            // the row stays QUEUED and the next cron tick retries, bounded.
            res = { ok: false, description: `network: ${e?.message || e}` } as any;
        }
        if (res.ok) {
            await db.updatePaymentReceipt(r.id, academyId, {
                send_status: 'SENT',
                telegram_message_id: String((res.result as any)?.message_id || ''),
                last_attempt_epoch: Date.now(), last_error: '', updated_at_epoch: Date.now()
            });
            await db.logMessage({
                id: crypto.randomUUID(),
                student_id: r.student_id,
                student_name: r.student_name,
                chat_id: chat,
                message_type: 'PAYMENT_RECEIPT_SENT',
                content: `${r.transaction_id || r.id} amount=${r.amount}`,
                status: 'SUCCESS',
                error_message: '',
                timestamp_epoch: Date.now(),
                timestamp_shamsi: ''
            });
            out.sent++;
        } else {
            const attempts = r.attempts + 1;
            const terminal = attempts >= MAX_RECEIPT_ATTEMPTS;
            await db.updatePaymentReceipt(r.id, academyId, {
                send_status: terminal ? 'FAILED' : 'QUEUED',
                attempts,
                last_attempt_epoch: Date.now(),
                last_error: (res as any).description || 'send failed',
                updated_at_epoch: Date.now()
            });
            out.failed++;
        }
    }
    return out;
}

// ============================================================
// Bot menu ("رسیدهای من"): printable versions, own receipts only
// ============================================================
export async function getReceiptsForStudent(db: AcademyDatabase, academyId: string, studentId: string): Promise<ReceiptRow[]> {
    const rows = await db.listPaymentReceiptsForStudent(academyId, studentId);
    // Attach "n of N" hints from the installments table for nicer rendering.
    for (const r of rows) {
        if (r.installment_id) {
            const inst = await db.getInstallmentById(r.installment_id, academyId);
            if (inst && inst.total_installments > 0) r.total_installments_hint = inst.total_installments;
        }
    }
    return rows;
}
