// ============================================================
// Financial health check over the data the Worker already stores
// (Stage 7). READ-ONLY: it never rewrites amounts, statuses or
// history — it only reports. The financial source of truth stays
// the Android app's payment ledger; the server stores the synced
// installments + per-student summaries, and this module verifies
// they agree with each other and with arithmetic reality.
//
// Checks per academy:
//   * installment arithmetic: remaining == amount - paid, paid in
//     [0, amount], no negative amounts;
//   * is_paid consistency (is_paid=1 must mean remaining == 0);
//   * per-student summary vs the sum of that student's installments
//     (tuition, paid, debt) — catches stale or half-written syncs;
//   * overdue installments computed from raw data (due date in the
//     past, Tehran timezone, not paid, not cancelled) instead of
//     trusting a possibly stale status string.
//
// Duplicate-payment prevention lives where payments are recorded
// (the app re-derives paid totals from its own ledger on every
// write); the totals here are what the app cross-checks against.
// ============================================================

import { Env } from './types';

export interface FinanceIssue {
    kind: string;          // machine-readable, e.g. 'REMAINING_MISMATCH'
    severity: 'ERROR' | 'WARNING';
    entityType: 'installment' | 'summary';
    entityId: string;
    studentId: string;
    message: string;       // Persian, safe to show to the manager
    expected?: string;
    actual?: string;
}

export interface FinanceHealthReport {
    ok: boolean;
    checkedAtEpoch: number;
    timezone: 'Asia/Tehran';
    studentsChecked: number;
    installmentsChecked: number;
    issues: FinanceIssue[];
    totals: {
        totalTuition: number;
        totalPaid: number;
        totalRemainingDebt: number;
        overdueCount: number;
        overdueAmount: number;
        partialPaidCount: number;
        unpaidCount: number;
    };
}

/** Iran has had no DST since 2022; the offset is a fixed +03:30. */
const TEHRAN_OFFSET_MS = 3.5 * 60 * 60 * 1000;

/** Start of "today" in Tehran, as a UTC epoch. */
export function startOfTodayTehran(nowEpoch: number): number {
    const shifted = Math.floor((nowEpoch + TEHRAN_OFFSET_MS) / 86400000) * 86400000;
    return shifted - TEHRAN_OFFSET_MS;
}

// NOTE: the synced installments table has no status column — cancellation is
// an app-side concept. The server verifies arithmetic consistency of the
// fields it actually stores (amount / paid / remaining / is_paid / due date).

export async function financeHealth(env: Env, academyId: string, nowEpoch = Date.now()): Promise<FinanceHealthReport> {
    const db = env.DB!;
    const issues: FinanceIssue[] = [];

    const installments = await db.prepare(
        `SELECT id, student_id, amount, paid_amount, remaining_amount, is_paid,
                due_date_epoch, installment_number, course_title
         FROM installments WHERE academy_id = ?`
    ).bind(academyId).all<{
        id: string; student_id: string; amount: number; paid_amount: number;
        remaining_amount: number; is_paid: number; due_date_epoch: number;
        installment_number: number; course_title: string;
    }>();

    const summaries = await db.prepare(
        `SELECT student_id, total_tuition, total_paid, remaining_debt, settlement_status
         FROM financial_summaries WHERE academy_id = ?`
    ).bind(academyId).all<{
        student_id: string; total_tuition: number; total_paid: number;
        remaining_debt: number; settlement_status: string;
    }>();

    const todayStart = startOfTodayTehran(nowEpoch);

    let totalTuition = 0;
    let totalPaid = 0;
    let totalRemaining = 0;
    let overdueCount = 0;
    let overdueAmount = 0;
    let partialPaidCount = 0;
    let unpaidCount = 0;

    const perStudent = new Map<string, { amount: number; paid: number; remaining: number }>();

    for (const inst of installments.results) {
        const amount = Number(inst.amount) || 0;
        const paid = Number(inst.paid_amount) || 0;
        const remaining = Number(inst.remaining_amount) || 0;

        const agg = perStudent.get(inst.student_id) || { amount: 0, paid: 0, remaining: 0 };
        agg.amount += amount;
        agg.paid += paid;
        agg.remaining += remaining;
        perStudent.set(inst.student_id, agg);

        if (amount < 0) {
            issues.push({
                kind: 'NEGATIVE_AMOUNT', severity: 'ERROR', entityType: 'installment',
                entityId: inst.id, studentId: inst.student_id,
                message: `مبلغ قسط منفی است (قسط ${inst.installment_number} ${inst.course_title}).`,
                expected: '>= 0', actual: String(amount)
            });
            continue;
        }
        if (paid < 0) {
            issues.push({
                kind: 'NEGATIVE_PAID', severity: 'ERROR', entityType: 'installment',
                entityId: inst.id, studentId: inst.student_id,
                message: `مبلغ پرداخت‌شدهٔ قسط منفی است (قسط ${inst.installment_number}).`,
                expected: '>= 0', actual: String(paid)
            });
            continue;
        }
        if (paid > amount) {
            issues.push({
                kind: 'OVERPAID', severity: 'ERROR', entityType: 'installment',
                entityId: inst.id, studentId: inst.student_id,
                message: `پرداخت بیش از مبلغ قسط ثبت شده (قسط ${inst.installment_number} ${inst.course_title}).`,
                expected: `پرداخت <= ${amount}`, actual: String(paid)
            });
        }
        const expectedRemaining = Math.max(0, amount - paid);
        if (remaining !== expectedRemaining) {
            issues.push({
                kind: 'REMAINING_MISMATCH', severity: 'ERROR', entityType: 'installment',
                entityId: inst.id, studentId: inst.student_id,
                message: `ماندهٔ قسط ${inst.installment_number} (${inst.course_title}) با مبلغ و پرداخت نمی‌خواند.`,
                expected: String(expectedRemaining), actual: String(remaining)
            });
        }
        if (remaining > 0) unpaidCount++;
        if (paid > 0 && remaining > 0) partialPaidCount++;

        const shouldBePaid = amount > 0 && remaining <= 0;
        if (Boolean(inst.is_paid) !== shouldBePaid) {
            issues.push({
                kind: 'PAID_FLAG_MISMATCH', severity: 'ERROR', entityType: 'installment',
                entityId: inst.id, studentId: inst.student_id,
                message: `وضعیت پرداخت قسط ${inst.installment_number} (${inst.course_title}) با مانده هم‌خوان نیست.`,
                expected: shouldBePaid ? 'is_paid = 1' : 'is_paid = 0',
                actual: `is_paid = ${inst.is_paid}`
            });
        }

        const isOverdue = !shouldBePaid && inst.due_date_epoch > 0 && inst.due_date_epoch < todayStart;
        if (isOverdue) {
            overdueCount++;
            overdueAmount += remaining;
        }

        totalTuition += amount;
        totalPaid += paid;
        totalRemaining += remaining;
    }

    const studentsChecked = summaries.results.length;
    for (const sum of summaries.results) {
        const agg = perStudent.get(sum.student_id);
        if (!agg) {
            issues.push({
                kind: 'SUMMARY_WITHOUT_INSTALLMENTS', severity: 'WARNING', entityType: 'summary',
                entityId: sum.student_id, studentId: sum.student_id,
                message: 'خلاصهٔ مالی برای هنرجویی ثبت شده که هیچ قسطی در سرور ندارد.'
            });
            continue;
        }
        if (Number(sum.total_tuition) !== agg.amount) {
            issues.push({
                kind: 'SUMMARY_TUITION_MISMATCH', severity: 'ERROR', entityType: 'summary',
                entityId: sum.student_id, studentId: sum.student_id,
                message: 'شهریهٔ ثبت‌شده در خلاصهٔ مالی هنرجو با جمع اقساطش نمی‌خواند.',
                expected: String(agg.amount), actual: String(sum.total_tuition)
            });
        }
        if (Number(sum.total_paid) !== agg.paid) {
            issues.push({
                kind: 'SUMMARY_PAID_MISMATCH', severity: 'ERROR', entityType: 'summary',
                entityId: sum.student_id, studentId: sum.student_id,
                message: 'پرداخت واقعی در خلاصهٔ مالی هنرجو با جمع اقساطش نمی‌خواند.',
                expected: String(agg.paid), actual: String(sum.total_paid)
            });
        }
        const expectedDebt = Math.max(0, agg.amount - agg.paid);
        if (Number(sum.remaining_debt) !== expectedDebt) {
            issues.push({
                kind: 'SUMMARY_DEBT_MISMATCH', severity: 'ERROR', entityType: 'summary',
                entityId: sum.student_id, studentId: sum.student_id,
                message: 'بدهی باقی‌ماندهٔ هنرجو در خلاصهٔ مالی درست نیست.',
                expected: String(expectedDebt), actual: String(sum.remaining_debt)
            });
        }
    }

    return {
        ok: issues.every((i) => i.severity !== 'ERROR'),
        checkedAtEpoch: nowEpoch,
        timezone: 'Asia/Tehran',
        studentsChecked,
        installmentsChecked: installments.results.length,
        issues,
        totals: {
            totalTuition,
            totalPaid,
            totalRemainingDebt: totalRemaining,
            overdueCount,
            overdueAmount,
            partialPaidCount,
            unpaidCount
        }
    };
}
