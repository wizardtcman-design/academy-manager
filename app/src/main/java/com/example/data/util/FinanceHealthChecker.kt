package com.example.data.util

import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund

/**
 * Stage 7 — app-side financial consistency check, run on demand from the
 * reports screen. STRICTLY READ-ONLY: it only reports; it never rewrites
 * any financial record. The owner decides what (if anything) to fix.
 *
 * Cross-checks the three phone-side artifacts against each other:
 *   * the payment ledger (`payments`, minus reversed rows),
 *   * the installment plan (`installments`: amount / paidAmount / remainingAmount),
 *   * the refund ledger (`refunds`).
 *
 * Detected problems:
 *   * PAID_MISMATCH      — installment.paidAmount != sum of its recorded payments
 *   * REMAINING_MISMATCH — remainingAmount != amount - paidAmount
 *   * OVERPAID           — recorded payments exceed the installment amount
 *   * DUPLICATE_TRANSACTION — the same transactionId on two different payment rows
 *   * DUPLICATE_PAYMENT  — same student+installment+amount within 2 minutes
 *                          (probable double tap; WARNING only, may be legitimate)
 *   * REFUND_EXCEEDS_PAID— refunds larger than what the student actually paid
 *   * NEGATIVE_AMOUNT    — negative stored amounts (data corruption)
 */
object FinanceHealthChecker {

    data class HealthIssue(
        val kind: String,
        val severity: Severity,
        val entity: String,
        val message: String
    )

    enum class Severity { ERROR, WARNING }

    data class HealthReport(
        val ok: Boolean,
        val checkedPayments: Int,
        val checkedInstallments: Int,
        val checkedRefunds: Int,
        val issues: List<HealthIssue>,
        val totalTuition: Long,
        val totalCollected: Long,
        val totalRefunds: Long,
        val totalRemainingDebt: Long
    ) {
        val errors: List<HealthIssue> get() = issues.filter { it.severity == Severity.ERROR }
        val warnings: List<HealthIssue> get() = issues.filter { it.severity == Severity.WARNING }
    }

    fun check(
        payments: List<Payment>,
        installments: List<Installment>,
        refunds: List<Refund>
    ): HealthReport {
        val issues = mutableListOf<HealthIssue>()
        val realPayments = payments.filter { !it.isReversed && !it.isSampleData }
        val realInstallments = installments.filter { !it.isSampleData }
        val realRefunds = refunds.filter { !it.isSampleData }

        // --- installment arithmetic vs the real payment ledger ---
        val paidByInstallment = realPayments
            .filter { it.installmentId.isNotBlank() }
            .groupBy { it.installmentId }

        for (inst in realInstallments) {
            val ledgerPaid = paidByInstallment[inst.id]?.sumOf { it.amount } ?: 0L
            val label = "قسط ${inst.installmentNumber} (${inst.courseTitle})"

            if (inst.amount < 0 || inst.paidAmount < 0 || inst.remainingAmount < 0) {
                issues += HealthIssue("NEGATIVE_AMOUNT", Severity.ERROR, inst.id, "$label مبلغ منفی دارد.")
                continue
            }
            if (ledgerPaid != inst.paidAmount) {
                issues += HealthIssue(
                    "PAID_MISMATCH", Severity.ERROR, inst.id,
                    "$label: مبلغ پرداخت‌شدهٔ ثبت‌شده با پرداخت‌های واقعی نمی‌خواند " +
                        "(دفتر: ${PersianDateUtil.formatCurrency(ledgerPaid)}، قسط: ${PersianDateUtil.formatCurrency(inst.paidAmount)})."
                )
            }
            val expectedRemaining = (inst.amount - ledgerPaid).coerceAtLeast(0L)
            if (inst.remainingAmount != expectedRemaining) {
                issues += HealthIssue(
                    "REMAINING_MISMATCH", Severity.ERROR, inst.id,
                    "$label: ماندهٔ ثبت‌شده (${PersianDateUtil.formatCurrency(inst.remainingAmount)}) " +
                        "با محاسبهٔ درست (${PersianDateUtil.formatCurrency(expectedRemaining)}) نمی‌خواند."
                )
            }
            if (ledgerPaid > inst.amount) {
                issues += HealthIssue(
                    "OVERPAID", Severity.ERROR, inst.id,
                    "$label: مجموع پرداخت‌ها (${PersianDateUtil.formatCurrency(ledgerPaid)}) از مبلغ قسط بیشتر است."
                )
            }
        }

        // --- duplicate transaction ids ---
        val byTrxId = realPayments.filter { it.transactionId.isNotBlank() }.groupBy { it.transactionId }
        for ((trx, list) in byTrxId) {
            val distinct = list.map { it.id }.distinct()
            if (distinct.size > 1) {
                issues += HealthIssue(
                    "DUPLICATE_TRANSACTION", Severity.ERROR, trx,
                    "کد تراکنش $trx روی ${distinct.size} پرداخت مختلف ثبت شده است (احتمال ثبت دوباره)."
                )
            }
        }

        // --- probable double-tap: same student+installment+amount within 2 minutes ---
        val sorted = realPayments.filter { it.installmentId.isNotBlank() }.sortedBy { it.paymentDateEpoch }
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size &&
                sorted[j].studentId == sorted[i].studentId &&
                sorted[j].installmentId == sorted[i].installmentId &&
                sorted[j].amount == sorted[i].amount &&
                sorted[j].paymentDateEpoch - sorted[i].paymentDateEpoch <= 2 * 60 * 1000L
            ) {
                issues += HealthIssue(
                    "DUPLICATE_PAYMENT", Severity.WARNING, sorted[j].id,
                    "دو پرداخت یکسان برای یک قسط در فاصلهٔ کم ثبت شده است " +
                        "(${PersianDateUtil.formatCurrency(sorted[j].amount)}؛ ${sorted[i].paymentDateShamsi} و ${sorted[j].paymentDateShamsi}). " +
                        "اگر دوباره‌ثبت است، از «اصلاح/لغو تراکنش» با ثبت دلیل حذفش کنید."
                )
                j++
            }
            i = j
        }

        // --- refunds must never exceed what the student actually paid ---
        val paidByStudent = realPayments.groupBy { it.studentId }.mapValues { it.value.sumOf { p -> p.amount } }
        val refundedByStudent = realRefunds.groupBy { it.studentId }.mapValues { it.value.sumOf { r -> r.amount } }
        for ((studentId, refunded) in refundedByStudent) {
            val paid = paidByStudent[studentId] ?: 0L
            if (refunded > paid) {
                issues += HealthIssue(
                    "REFUND_EXCEEDS_PAID", Severity.ERROR, studentId,
                    "مجموع برگشت وجه این هنرجو (${PersianDateUtil.formatCurrency(refunded)}) " +
                        "از مجموع پرداخت‌هایش (${PersianDateUtil.formatCurrency(paid)}) بیشتر است."
                )
            }
        }

        val totalTuition = realInstallments.filter { it.status != "CANCELLED" }.sumOf { it.amount }
        val totalCollected = realPayments.sumOf { it.amount }
        val totalRefunds = realRefunds.sumOf { it.amount }
        val totalDebt = realInstallments.filter { it.status != "CANCELLED" }.sumOf { it.remainingAmount.coerceAtLeast(0L) }

        return HealthReport(
            ok = issues.none { it.severity == Severity.ERROR },
            checkedPayments = realPayments.size,
            checkedInstallments = realInstallments.size,
            checkedRefunds = realRefunds.size,
            issues = issues,
            totalTuition = totalTuition,
            totalCollected = totalCollected,
            totalRefunds = totalRefunds,
            totalRemainingDebt = totalDebt
        )
    }
}
