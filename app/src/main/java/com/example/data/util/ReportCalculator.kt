package com.example.data.util

import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund
import java.util.Locale

/**
 * Stage 7 — income report math, derived STRICTLY from the real payment
 * ledger (Room `payments` / `refunds`), never from tuition prices or unpaid
 * installments:
 *
 *   * a payment counts as collected income only when it is recorded and
 *     not reversed (`isReversed == false`);
 *   * a reversal/refund is subtracted as its own negative line — it never
 *     silently rewrites history;
 *   * tuition totals, remaining debt and overdue installments come from the
 *     installment plan and are reported SEPARATELY from collected income;
 *   * monthly breakdown uses the Jalali month prefix of the Shamsi date the
 *     manager actually picked at recording time (`paymentDateShamsi`), so the
 *     report matches what a human would call "this month" in Iran;
 *   * overdue is computed from raw data (due date in the past, Tehran fixed
 *     +03:30, not paid, not cancelled) instead of trusting a possibly stale
 *     status string.
 *
 * Kept 100% JVM-pure so the numbers are unit-testable without Android.
 */
object ReportCalculator {

    /** Iran has had no DST since 2022; the offset is a fixed +03:30. */
    private const val TEHRAN_OFFSET_MS = 12_600_000L // fixed +03:30 (no DST since 2022)

    data class NamedAmount(val name: String, val amount: Long, val count: Int = 0)

    data class MonthIncome(
        val year: Int,
        val month: Int,                 // 1..12 Jalali
        val monthName: String,
        val collected: Long,
        val refunds: Long,
        val net: Long,
        val transactions: Int
    )

    data class IncomeReport(
        val title: String,
        val year: Int,
        val month: Int?,                // null = the whole year
        val collected: Long,
        val refunds: Long,
        val netIncome: Long,
        val transactionsCount: Int,
        val payingStudentsCount: Int,
        val monthly: List<MonthIncome>,
        val byCourse: List<NamedAmount>,
        val byStudent: List<NamedAmount>,
        val byMethod: List<NamedAmount>,
        // Separately reported (NOT counted as income):
        val totalTuition: Long,
        val remainingDebt: Long,
        val overdueCount: Int,
        val overdueAmount: Long,
        val unpaidInstallmentsCount: Int
    )

    data class Query(
        val jalaliYear: Int,
        val jalaliMonth: Int?,          // null = whole year
        val courseTitleFilter: String? = null,
        val paymentMethodFilter: String? = null
    )

    fun build(
        query: Query,
        payments: List<Payment>,
        refunds: List<Refund>,
        installments: List<Installment>,
        enrollments: List<Enrollment>,
        nowEpoch: Long = System.currentTimeMillis()
    ): IncomeReport {
        val monthPrefixFormat = { m: Int -> String.format(Locale.US, "%04d/%02d", query.jalaliYear, m) }
        val inMonth = { shamsi: String, m: Int -> shamsi.startsWith(monthPrefixFormat(m)) }
        val inYear = { shamsi: String -> shamsi.startsWith("${query.jalaliYear}/") }

        val inPeriod = when {
            query.jalaliMonth != null -> { shamsi: String -> inMonth(shamsi, query.jalaliMonth!!) }
            else -> { shamsi: String -> inYear(shamsi) }
        }

        val periodPayments = payments.asSequence()
            .filter { !it.isReversed && !it.isSampleData }
            .filter { inPeriod(it.paymentDateShamsi) }
            .filter { query.paymentMethodFilter == null || it.paymentMethod == query.paymentMethodFilter }
            .toList()

        // Course filter: the payment's installment/enrollment resolves to a course title.
        val installmentById = installments.associateBy { it.id }
        val enrollmentById = enrollments.associateBy { it.id }
        fun courseTitleOf(p: Payment): String {
            installmentById[p.installmentId]?.let { return it.courseTitle }
            enrollmentById[p.enrollmentId]?.let { return it.courseTitle }
            return "نامشخص"
        }
        val filteredPayments = if (query.courseTitleFilter.isNullOrBlank()) periodPayments
        else periodPayments.filter { courseTitleOf(it) == query.courseTitleFilter }

        val periodRefunds = refunds.asSequence()
            .filter { !it.isSampleData }
            .filter { inPeriod(it.dateShamsi) }
            .filter { query.courseTitleFilter.isNullOrBlank() || it.courseTitle == query.courseTitleFilter }
            .toList()

        val collected = filteredPayments.sumOf { it.amount }
        val refunded = periodRefunds.sumOf { it.amount }
        val title = if (query.jalaliMonth != null) {
            "گزارش ماه ${PersianDateUtil.getMonthName(query.jalaliMonth!!)} ${query.jalaliYear}"
        } else {
            "گزارش سال ${query.jalaliYear}"
        }

        // Monthly breakdown for the whole year (12 rows, zeros included).
        val monthly = (1..12).map { m ->
            val monthPayments = filteredPayments.filter { inMonth(it.paymentDateShamsi, m) }
            val monthRefunds = periodRefunds.filter { inMonth(it.dateShamsi, m) }
            val c = monthPayments.sumOf { it.amount }
            val r = monthRefunds.sumOf { it.amount }
            MonthIncome(
                year = query.jalaliYear,
                month = m,
                monthName = PersianDateUtil.getMonthName(m),
                collected = c,
                refunds = r,
                net = c - r,
                transactions = monthPayments.size
            )
        }

        val byCourse = filteredPayments
            .groupBy { courseTitleOf(it) }
            .map { (name, list) -> NamedAmount(name, list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.amount }

        val byStudent = filteredPayments
            .groupBy { it.studentName.ifBlank { "نامشخص" } }
            .map { (name, list) -> NamedAmount(name, list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.amount }

        val byMethod = filteredPayments
            .groupBy { it.paymentMethod }
            .map { (method, list) -> NamedAmount(methodLabel(method), list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.amount }

        // Debt & overdue — computed from raw installment data (never trusted status strings).
        // CANCELLED installments leave the debt math entirely.
        val activeInstallments = installments.filter { it.status != "CANCELLED" && !it.isSampleData }
        val totalTuition = activeInstallments.sumOf { it.amount }
        val remainingDebt = activeInstallments.sumOf { it.remainingAmount.coerceAtLeast(0L) }
        val todayStart = startOfTodayTehran(nowEpoch)
        val overdue = activeInstallments.filter {
            !it.isPaid && it.remainingAmount > 0 && it.dueDateEpoch > 0 && it.dueDateEpoch < todayStart
        }

        return IncomeReport(
            title = title,
            year = query.jalaliYear,
            month = query.jalaliMonth,
            collected = collected,
            refunds = refunded,
            netIncome = collected - refunded,
            transactionsCount = filteredPayments.size,
            payingStudentsCount = filteredPayments.map { it.studentId }.distinct().size,
            monthly = monthly,
            byCourse = byCourse,
            byStudent = byStudent,
            byMethod = byMethod,
            totalTuition = totalTuition,
            remainingDebt = remainingDebt,
            overdueCount = overdue.size,
            overdueAmount = overdue.sumOf { it.remainingAmount },
            unpaidInstallmentsCount = activeInstallments.count { !it.isPaid && it.remainingAmount > 0 }
        )
    }

    /** Start of "today" in Tehran as a UTC epoch (fixed +03:30 — no DST since 2022). */
    fun startOfTodayTehran(nowEpoch: Long): Long {
        val shifted = Math.floorDiv(nowEpoch + TEHRAN_OFFSET_MS, 86_400_000L) * 86_400_000L
        return shifted - TEHRAN_OFFSET_MS
    }

    fun methodLabel(method: String): String = when (method) {
        "TRANSFER" -> "کارت به کارت"
        "POS" -> "کارتخوان (POS)"
        "CASH" -> "نقدی"
        "BANK_DEPOSIT" -> "واریز بانکی"
        else -> "سایر روش‌ها"
    }

    /**
     * Excel-openable CSV (UTF-8 with BOM so Excel renders Persian correctly).
     * Returns the text; the caller writes/shares the file.
     */
    fun toCsv(report: IncomeReport): String = buildString {
        appendLine(report.title)
        appendLine()
        appendLine("بخش،مبلغ (تومان)،تعداد")
        appendLine("درآمد وصول‌شده،${report.collected},${report.transactionsCount}")
        appendLine("برگشت وجه،${report.refunds},")
        appendLine("درآمد خالص،${report.netIncome},")
        appendLine()
        appendLine("تفکیک ماهانه،")
        report.monthly.forEach {
            appendLine("${it.year}/${"%02d".format(it.month)} (${it.monthName})،وصول: ${it.collected}،برگشت: ${it.refunds}،خالص: ${it.net}،${it.transactions} تراکنش")
        }
        appendLine()
        appendLine("تفکیک دوره،مبلغ،تعداد")
        report.byCourse.forEach { appendLine("${it.name}،${it.amount}،${it.count}") }
        appendLine()
        appendLine("تفکیک هنرجو،مبلغ،تعداد")
        report.byStudent.forEach { appendLine("${it.name}،${it.amount}،${it.count}") }
        appendLine()
        appendLine("تفکیک روش پرداخت،مبلغ،تعداد")
        report.byMethod.forEach { appendLine("${it.name}،${it.amount}،${it.count}") }
        appendLine()
        appendLine("شهریهٔ کل برنامه،${report.totalTuition},")
        appendLine("بدهی باقی‌مانده،${report.remainingDebt},")
        appendLine("اقساط معوق (تعداد)،${report.overdueCount},")
        appendLine("مبلغ اقساط معوق،${report.overdueAmount},")
    }.let { "\uFEFF$it" } // BOM: Excel reads UTF-8 Persian correctly
}
