package com.example

import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.util.FinanceHealthChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 — the app-side financial health check:
 * installment arithmetic vs the real payment ledger, duplicate transactions,
 * probable double-tap payments, refunds exceeding payments — and PROOF that
 * the checker only reports (it cannot mutate anything: it receives plain
 * lists, not DAOs).
 */
class FinanceHealthCheckerTest {

    private fun payment(
        id: String,
        installmentId: String,
        studentId: String = "s1",
        amount: Long,
        trx: String = "",
        epoch: Long = 1_000_000L
    ) = Payment(id = id, installmentId = installmentId, studentId = studentId, amount = amount, transactionId = trx, paymentDateEpoch = epoch)

    private fun installment(
        id: String,
        amount: Long,
        paid: Long,
        remaining: Long = (amount - paid).coerceAtLeast(0)
    ) = Installment(id = id, studentId = "s1", amount = amount, paidAmount = paid, remainingAmount = remaining, status = "PARTIAL_PAID")

    @Test
    fun consistentLedgerPassesWithZeroErrors() {
        val report = FinanceHealthChecker.check(
            payments = listOf(
                payment("p1", "i1", amount = 300_000, trx = "TRX-1404-0001"),
                payment("p2", "i1", amount = 200_000, trx = "TRX-1404-0002")
            ),
            installments = listOf(installment("i1", amount = 500_000, paid = 500_000)),
            refunds = emptyList()
        )
        assertTrue(report.ok)
        assertEquals(0, report.errors.size)
        assertEquals(500_000L, report.totalCollected)
        assertEquals(0L, report.totalRemainingDebt)
    }

    @Test
    fun staleInstallmentTotalsAreFlaggedAgainstTheLedger() {
        val report = FinanceHealthChecker.check(
            payments = listOf(payment("p1", "i1", amount = 300_000)),
            installments = listOf(installment("i1", amount = 500_000, paid = 450_000, remaining = 50_000)),
            refunds = emptyList()
        )
        val kinds = report.errors.map { it.kind }
        assertTrue(kinds.contains("PAID_MISMATCH"))        // ledger says 300k, installment says 450k
        assertTrue(kinds.contains("REMAINING_MISMATCH"))   // correct remaining is 200k
    }

    @Test
    fun overpayIsAnError() {
        val report = FinanceHealthChecker.check(
            payments = listOf(
                payment("p1", "i1", amount = 600_000),
                payment("p2", "i1", amount = 100_000)
            ),
            installments = listOf(installment("i1", amount = 500_000, paid = 700_000, remaining = 0)),
            refunds = emptyList()
        )
        assertTrue(report.errors.map { it.kind }.contains("OVERPAID"))
    }

    @Test
    fun duplicateTransactionIdsAreErrors() {
        val report = FinanceHealthChecker.check(
            payments = listOf(
                payment("p1", "i1", amount = 100_000, trx = "TRX-DUP"),
                payment("p2", "i1", amount = 100_000, trx = "TRX-DUP")
            ),
            installments = listOf(installment("i1", amount = 200_000, paid = 200_000)),
            refunds = emptyList()
        )
        assertTrue(report.errors.map { it.kind }.contains("DUPLICATE_TRANSACTION"))
    }

    @Test
    fun probableDoubleTapWithinTwoMinutesIsAWarning() {
        val report = FinanceHealthChecker.check(
            payments = listOf(
                payment("p1", "i1", amount = 100_000, epoch = 1_000_000),
                payment("p2", "i1", amount = 100_000, epoch = 1_000_000 + 90_000)
            ),
            installments = listOf(installment("i1", amount = 200_000, paid = 200_000)),
            refunds = emptyList()
        )
        assertTrue(report.warnings.map { it.kind }.contains("DUPLICATE_PAYMENT"))
        // Two legitimate payments 3 hours apart are not flagged.
        val clean = FinanceHealthChecker.check(
            payments = listOf(
                payment("p1", "i1", amount = 100_000, epoch = 1_000_000),
                payment("p2", "i1", amount = 100_000, epoch = 1_000_000 + 3 * 3600 * 1000)
            ),
            installments = listOf(installment("i1", amount = 200_000, paid = 200_000)),
            refunds = emptyList()
        )
        assertTrue(clean.warnings.isEmpty())
    }

    @Test
    fun refundLargerThanPaidIsAnError() {
        val report = FinanceHealthChecker.check(
            payments = listOf(payment("p1", "i1", studentId = "s1", amount = 100_000)),
            installments = listOf(installment("i1", amount = 500_000, paid = 100_000)),
            refunds = listOf(Refund(id = "r1", studentId = "s1", amount = 200_000))
        )
        assertTrue(report.errors.map { it.kind }.contains("REFUND_EXCEEDS_PAID"))
        assertEquals(100_000L, report.totalCollected)
        assertEquals(200_000L, report.totalRefunds)
    }

    @Test
    fun reversedPaymentsDoNotCountAsCollected() {
        val reversed = payment("p1", "i1", amount = 400_000).copy(isReversed = true, reversalReason = "اشتباه")
        val report = FinanceHealthChecker.check(
            payments = listOf(reversed),
            installments = listOf(installment("i1", amount = 500_000, paid = 0, remaining = 500_000)),
            refunds = emptyList()
        )
        assertTrue(report.ok)
        assertEquals(0L, report.totalCollected)
    }
}
