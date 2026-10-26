package com.example

import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.util.ReportCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 — income report math is derived ONLY from real recorded payments:
 * monthly & yearly aggregation, by-course / by-student / by-method splits,
 * refund netting, partial payments, tuition/debt/overdue reported separately,
 * and the free Excel-compatible CSV export.
 */
class ReportCalculatorTest {

    private val year = 1404

    private fun payment(
        id: String,
        studentId: String,
        studentName: String,
        installmentId: String,
        amount: Long,
        dateShamsi: String,
        method: String = "CASH",
        reversed: Boolean = false,
        epoch: Long = 1_000_000L
    ): Payment = Payment(
        id = id,
        studentId = studentId,
        studentName = studentName,
        installmentId = installmentId,
        amount = amount,
        paymentDateShamsi = dateShamsi,
        paymentDateEpoch = epoch,
        paymentMethod = method,
        isReversed = reversed
    )

    private fun installment(
        id: String,
        studentId: String,
        courseId: String,
        courseTitle: String,
        amount: Long,
        paid: Long,
        dueEpoch: Long,
        status: String = "PARTIAL_PAID"
    ): Installment = Installment(
        id = id,
        studentId = studentId,
        courseId = courseId,
        courseTitle = courseTitle,
        amount = amount,
        paidAmount = paid,
        remainingAmount = (amount - paid).coerceAtLeast(0),
        dueDateEpoch = dueEpoch,
        status = status
    )

    private val enrollments = listOf(
        Enrollment(id = "enr-1", studentId = "s1", courseId = "c1", courseTitle = "سنتور", finalFee = 2_000_000, status = "ACTIVE"),
        Enrollment(id = "enr-2", studentId = "s2", courseId = "c2", courseTitle = "تار", finalFee = 1_000_000, status = "ACTIVE")
    )

    private val installments = listOf(
        installment("i1", "s1", "c1", "سنتور", 1_000_000, 1_000_000, dueEpoch = 5L, status = "PAID"),
        installment("i2", "s1", "c1", "سنتور", 1_000_000, 400_000, dueEpoch = 1L), // overdue
        installment("i3", "s2", "c2", "تار", 500_000, 0, dueEpoch = 1_800_100_000_000L, status = "PENDING") // due in the future
    )

    private val payments = listOf(
        payment("p1", "s1", "علی رضایی", "i1", 500_000, "1404/01/12", "POS"),
        payment("p2", "s1", "علی رضایی", "i1", 500_000, "1404/01/20", "TRANSFER"),
        payment("p3", "s1", "علی رضایی", "i2", 400_000, "1404/02/05", "TRANSFER"),
        payment("p4", "s2", "مریم کریمی", "i3", 100_000, "1404/02/10", "CASH"),
        payment("p5", "s2", "مریم کریمی", "i3", 50_000, "1403/12/28", "CASH"), // previous year
        payment("p6", "s2", "مریم کریمی", "i3", 999_999, "1404/02/10", "CASH", reversed = true) // reversed
    )

    private val refunds = listOf(
        Refund(id = "r1", studentId = "s2", studentName = "مریم کریمی", courseTitle = "تار", amount = 30_000, dateShamsi = "1404/02/15"),
        Refund(id = "r2", studentId = "s2", studentName = "مریم کریمی", courseTitle = "تار", amount = 5_000, dateShamsi = "1403/12/20")
    )

    private val nowEpoch = 1_800_000_000_000L // realistic 'now' so Tehran day math is meaningful

    // ---------- month report ----------

    @Test
    fun monthReportCountsOnlyRealNonReversedPaymentsOfThatMonth() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = 1),
            payments, refunds, installments, enrollments, nowEpoch
        )
        // Month 1: p1 (500k) + p2 (500k). Reversed p6 never counts.
        assertEquals(1_000_000L, report.collected)
        assertEquals(2, report.transactionsCount)
        assertEquals(1, report.payingStudentsCount)
        assertEquals(0L, report.refunds)
    }

    @Test
    fun yearReportAggregatesMonthlyBreakdownWithRefundNetting() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null),
            payments, refunds, installments, enrollments, nowEpoch
        )
        // Year 1404: 500+500+400+100 = 1.5M (p5 is 1403, p6 reversed)
        assertEquals(1_500_000L, report.collected)
        // Year 1404 refunds: 30k (r2 is 1403)
        assertEquals(30_000L, report.refunds)
        assertEquals(1_470_000L, report.netIncome)

        // 12 months, month 1 = 1M, month 2 = 500k - 30k refund shown separately
        assertEquals(12, report.monthly.size)
        assertEquals(1_000_000L, report.monthly[0].collected)
        assertEquals(500_000L, report.monthly[1].collected)
        assertEquals(30_000L, report.monthly[1].refunds)
        assertEquals(470_000L, report.monthly[1].net)
    }

    @Test
    fun breakdownsByCourseStudentAndMethodComeFromRealPayments() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null),
            payments, refunds, installments, enrollments, nowEpoch
        )
        val santur = report.byCourse.first { it.name == "سنتور" }
        val tar = report.byCourse.first { it.name == "تار" }
        assertEquals(1_400_000L, santur.amount)
        assertEquals(100_000L, tar.amount)

        val ali = report.byStudent.first { it.name == "علی رضایی" }
        assertEquals(1_400_000L, ali.amount)

        val cash = report.byMethod.first { it.name == "نقدی" }
        assertEquals(100_000L, cash.amount)
        val pos = report.byMethod.first { it.name == "کارتخوان (POS)" }
        assertEquals(500_000L, pos.amount)
    }

    @Test
    fun tuitionDebtAndOverdueAreReportedSeparatelyFromIncome() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null),
            payments, refunds, installments, enrollments, nowEpoch
        )
        // Tuition & debt are NOT income:
        assertEquals(2_500_000L, report.totalTuition)       // 1M + 1M + 500k
        assertEquals(1_100_000L, report.remainingDebt)      // 600k + 500k
        // Overdue: i2 (partial, dueEpoch 1 < todayStart) only; i1 is PAID, i3 due in future.
        assertEquals(1, report.overdueCount)
        assertEquals(600_000L, report.overdueAmount)
    }

    @Test
    fun methodAndCourseFiltersNarrowTheIncomeNumbers() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null, paymentMethodFilter = "CASH"),
            payments, refunds, installments, enrollments, nowEpoch
        )
        assertEquals(100_000L, report.collected)

        val santurOnly = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null, courseTitleFilter = "سنتور"),
            payments, refunds, installments, enrollments, nowEpoch
        )
        assertEquals(1_400_000L, santurOnly.collected)
    }

    // ---------- CSV ----------

    @Test
    fun csvExportCarriesBomTotalsAndBreakdowns() {
        val report = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = year, jalaliMonth = null),
            payments, refunds, installments, enrollments, nowEpoch
        )
        val csv = ReportCalculator.toCsv(report)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("درآمد وصول‌شده،1500000"))
        assertTrue(csv.contains("برگشت وجه،30000"))
        assertTrue(csv.contains("بدهی باقی‌مانده،1100000"))
        assertTrue(csv.contains("سنتور،1400000"))
    }

    // ---------- Tehran day boundary ----------

    @Test
    fun tehranDayStartUsesFixed330Offset() {
        // 2026-01-01 00:30 Tehran == 2025-12-31 21:00 UTC -> day started 20:30 UTC
        val utc = 1767214800000L
        assertEquals(utc - 30 * 60_000L, ReportCalculator.startOfTodayTehran(utc))
    }
}
