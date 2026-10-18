package com.example.data.util

import com.example.data.model.AcademySettings
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import java.util.UUID

data class StudentFinancialSummary(
    val studentId: String,
    val totalTuition: Long = 0L,
    val totalPaid: Long = 0L,
    val remainingDebt: Long = 0L,
    val paymentPercentage: Int = 0,
    val totalInstallmentsCount: Int = 0,
    val paidInstallmentsCount: Int = 0,
    val partialInstallmentsCount: Int = 0,
    val pendingInstallmentsCount: Int = 0,
    val overdueInstallmentsCount: Int = 0,
    val lastPaymentDateShamsi: String = "",
    val lastPaymentAmount: Long = 0L,
    val nearestDueInstallment: Installment? = null,
    val settlementStatus: String = "تسویه شده" // "تسویه کامل", "بدهکار", "دارای دیرکرد", "بدون دوره"
)

object FinanceUtil {

    /**
     * Generates a short, readable Student ID in the format `1405-001`:
     * the Jalali year of registration, a dash, then the student's running
     * number inside that year, zero-padded to three digits (widening past 999
     * on its own, so it never collides).
     *
     * This is a *display* code only. Every row keeps its stable UUID `id` and
     * every relation (enrollments, installments, payments, contracts, Telegram
     * chat) is keyed on that id, so changing this format can never move data.
     */
    fun generateStudentCode(year: Int, sequenceNumber: Int): String =
        "${year}-" + sequenceNumber.coerceAtLeast(1).toString().padStart(3, '0')

    /**
     * Oldest supported code shape, kept for migrating existing rows.
     * Returns the last numeric part of `ST-1403-0001` (4) or `1405-001` (1).
     */
    fun studentCodeSequence(code: String): Int? =
        code.trim().substringAfterLast('-').toIntOrNull()

    /** True for the current short format `1405-001` (year, dash, 3+ digits). */
    fun isShortStudentCode(code: String): Boolean =
        Regex("^\\d{4}-\\d{3,}$").matches(code.trim())

    /**
     * Generates a formatted unique Transaction ID like TRX-1403-0001
     */
    fun generateTransactionId(sequenceNumber: Int): String {
        val currentYear = PersianDateUtil.currentJalali().year
        val formattedSeq = String.format("%04d", sequenceNumber.coerceAtLeast(1))
        return "TRX-$currentYear-$formattedSeq"
    }

    /**
     * Generates a formatted unique Payment / Receipt code like PAY-1405-000001
     */
    fun generatePaymentCode(sequenceNumber: Int): String {
        val currentYear = PersianDateUtil.currentJalali().year
        val formattedSeq = String.format("%06d", sequenceNumber.coerceAtLeast(1))
        return "PAY-$currentYear-$formattedSeq"
    }

    /**
     * Generates a formatted unique Refund code like REF-1405-000001
     */
    fun generateRefundCode(sequenceNumber: Int): String {
        val currentYear = PersianDateUtil.currentJalali().year
        val formattedSeq = String.format("%06d", sequenceNumber.coerceAtLeast(1))
        return "REF-$currentYear-$formattedSeq"
    }

    /**
     * Generates a formatted unique Expense code like EXP-1405-000001
     */
    fun generateExpenseCode(sequenceNumber: Int): String {
        val currentYear = PersianDateUtil.currentJalali().year
        val formattedSeq = String.format("%06d", sequenceNumber.coerceAtLeast(1))
        return "EXP-$currentYear-$formattedSeq"
    }

    /**
     * Calculates installments schedule accurately.
     * Crucial: If 1st installment was paid or due on 13 Shahrivar, 2nd is 13 Mehr, 3rd is 13 Aban.
     * Uses PersianDateUtil.addMonths with day-clamping (e.g. 31 -> 30).
     */
    fun buildInstallmentsSchedule(
        enrollmentId: String,
        studentId: String,
        studentName: String,
        courseId: String,
        courseTitle: String,
        totalFee: Long,
        installmentCount: Int,
        installmentAmounts: List<Long>, // Custom amounts per installment
        firstInstallmentDateShamsi: String,
        firstInstallmentPaidNow: Boolean,
        isSampleData: Boolean = false
    ): List<Installment> {
        val count = installmentCount.coerceIn(1, 12)
        val validAmounts = if (installmentAmounts.size == count && installmentAmounts.sum() == totalFee) {
            installmentAmounts
        } else {
            // Default split equally
            val baseAmount = totalFee / count
            val remainder = totalFee % count
            List(count) { idx ->
                if (idx == 0) baseAmount + remainder else baseAmount
            }
        }

        // Parse first date
        val firstDateParts = firstInstallmentDateShamsi.split("/").mapNotNull { it.toIntOrNull() }
        val baseJalali = if (firstDateParts.size == 3) {
            PersianDateUtil.JalaliDate(firstDateParts[0], firstDateParts[1], firstDateParts[2])
        } else {
            PersianDateUtil.currentJalali()
        }

        val nowEpoch = System.currentTimeMillis()
        val todayShamsi = PersianDateUtil.currentShamsiString()

        return (0 until count).map { index ->
            val targetJalali = baseJalali.addMonths(index)
            val dueDateStr = targetJalali.format()
            val installmentAmount = validAmounts[index]

            if (index == 0 && firstInstallmentPaidNow) {
                Installment(
                    id = UUID.randomUUID().toString(),
                    enrollmentId = enrollmentId,
                    studentId = studentId,
                    studentName = studentName,
                    courseId = courseId,
                    courseTitle = courseTitle,
                    installmentNumber = index + 1,
                    totalInstallments = count,
                    amount = installmentAmount,
                    dueDateShamsi = dueDateStr,
                    dueDateEpoch = nowEpoch,
                    paidAmount = installmentAmount,
                    remainingAmount = 0L,
                    fullPaidDateShamsi = todayShamsi,
                    fullPaidDateEpoch = nowEpoch,
                    status = "PAID",
                    notes = "پرداخت همزمان با ثبت‌نام",
                    isSampleData = isSampleData,
                    createdAt = nowEpoch
                )
            } else {
                Installment(
                    id = UUID.randomUUID().toString(),
                    enrollmentId = enrollmentId,
                    studentId = studentId,
                    studentName = studentName,
                    courseId = courseId,
                    courseTitle = courseTitle,
                    installmentNumber = index + 1,
                    totalInstallments = count,
                    amount = installmentAmount,
                    dueDateShamsi = dueDateStr,
                    dueDateEpoch = nowEpoch + (index * 30L * 24L * 3600L * 1000L), // Approx epoch for ordering
                    paidAmount = 0L,
                    remainingAmount = installmentAmount,
                    fullPaidDateShamsi = "",
                    fullPaidDateEpoch = 0L,
                    status = "PENDING",
                    notes = "",
                    isSampleData = isSampleData,
                    createdAt = nowEpoch
                )
            }
        }
    }

    /**
     * Recalculates student's financial summary from actual DB records
     */
    fun calculateStudentFinancials(
        studentId: String,
        enrollments: List<Enrollment>,
        installments: List<Installment>,
        payments: List<Payment>
    ): StudentFinancialSummary {
        val studentEnrollments = enrollments.filter { it.studentId == studentId && it.status != "CANCELLED" }
        val studentInstallments = installments.filter { it.studentId == studentId }
        val studentPayments = payments.filter { it.studentId == studentId && !it.isReversed }

        val totalTuition = studentEnrollments.sumOf { it.finalFee }
        val totalPaid = studentPayments.sumOf { it.amount }
        val remainingDebt = (totalTuition - totalPaid).coerceAtLeast(0L)

        val percentage = if (totalTuition > 0) {
            ((totalPaid * 100) / totalTuition).toInt().coerceIn(0, 100)
        } else {
            100
        }

        val totalInstallmentsCount = studentInstallments.size
        val paidInstallmentsCount = studentInstallments.count { it.status == "PAID" }
        val partialInstallmentsCount = studentInstallments.count { it.status == "PARTIAL_PAID" }
        val overdueInstallmentsCount = studentInstallments.count { it.status == "OVERDUE" }
        val pendingInstallmentsCount = studentInstallments.count { it.status == "PENDING" || it.status == "NEAR_DUE" || it.status == "DUE_TODAY" }

        val lastPayment = studentPayments.maxByOrNull { it.paymentDateEpoch }
        val nearestDue = studentInstallments
            .filter { it.status != "PAID" }
            .minByOrNull { it.dueDateShamsi }

        val settlementStatus = when {
            studentEnrollments.isEmpty() -> "بدون دوره"
            remainingDebt == 0L && totalTuition > 0L -> "تسویه کامل"
            overdueInstallmentsCount > 0 -> "دارای دیرکرد"
            else -> "بدهکار"
        }

        return StudentFinancialSummary(
            studentId = studentId,
            totalTuition = totalTuition,
            totalPaid = totalPaid,
            remainingDebt = remainingDebt,
            paymentPercentage = percentage,
            totalInstallmentsCount = totalInstallmentsCount,
            paidInstallmentsCount = paidInstallmentsCount,
            partialInstallmentsCount = partialInstallmentsCount,
            pendingInstallmentsCount = pendingInstallmentsCount,
            overdueInstallmentsCount = overdueInstallmentsCount,
            lastPaymentDateShamsi = lastPayment?.paymentDateShamsi ?: "",
            lastPaymentAmount = lastPayment?.amount ?: 0L,
            nearestDueInstallment = nearestDue,
            settlementStatus = settlementStatus
        )
    }

    /**
     * Replace template variables safely for Student messages and notifications.
     * Supports both English variable names ({studentName}, {amount}, ...) and Persian names ({نام}, {مبلغ}, ...)
     */
    fun fillMessageTemplate(
        templateText: String,
        student: Student,
        courseTitle: String = "",
        installmentNumber: Int = 1,
        installmentAmount: Long = 0L,
        paidAmount: Long = 0L,
        remainingDebt: Long = 0L,
        dueDateShamsi: String = "",
        receiptNumber: String = "",
        settings: AcademySettings? = null
    ): String {
        val academyName = settings?.academyName?.ifEmpty { "آموزشگاه کامپیوتر" } ?: "آموزشگاه کامپیوتر"
        val currency = settings?.currencyUnit ?: "تومان"
        val studentFullName = student.fullName.ifBlank { "${student.firstName} ${student.lastName}".trim().ifBlank { "هنرجو" } }
        val formattedInstallmentAmount = PersianDateUtil.formatCurrency(installmentAmount, "")
        val formattedPaidAmount = PersianDateUtil.formatCurrency(paidAmount, "")
        val formattedRemainingDebt = PersianDateUtil.formatCurrency(remainingDebt, "")
        val formattedInstNumber = installmentNumber.toString()

        return templateText
            // English variables
            .replace("{studentName}", studentFullName)
            .replace("{installmentNumber}", formattedInstNumber)
            .replace("{amount}", formattedInstallmentAmount)
            .replace("{dueDate}", dueDateShamsi)
            .replace("{remainingDebt}", formattedRemainingDebt)
            .replace("{courseName}", courseTitle)
            .replace("{academyName}", academyName)
            .replace("{paymentAmount}", formattedPaidAmount)
            .replace("{receiptNumber}", receiptNumber.ifBlank { "PAY-${PersianDateUtil.currentJalali().year}-0001" })
            .replace("{currencyUnit}", currency)
            // Persian aliases
            .replace("{نام}", studentFullName)
            .replace("{نام_هنرجو}", studentFullName)
            .replace("{نام_خانوادگی}", student.lastName)
            .replace("{نام_دوره}", courseTitle)
            .replace("{دوره}", courseTitle)
            .replace("{شماره_قسط}", formattedInstNumber)
            .replace("{مبلغ_قسط}", formattedInstallmentAmount)
            .replace("{مبلغ_پرداخت}", formattedPaidAmount)
            .replace("{مبلغ}", formattedInstallmentAmount)
            .replace("{تاریخ}", dueDateShamsi)
            .replace("{تاریخ_سررسید}", dueDateShamsi)
            .replace("{مانده}", formattedRemainingDebt)
            .replace("{مانده_بدهی}", formattedRemainingDebt)
            .replace("{نام_آموزشگاه}", academyName)
            .replace("{شماره_رسید}", receiptNumber.ifBlank { "PAY-${PersianDateUtil.currentJalali().year}-0001" })
            .replace("{کد_پیگیری}", receiptNumber.ifBlank { "PAY-${PersianDateUtil.currentJalali().year}-0001" })
            .replace("{واحد_پول}", currency)
    }

    /**
     * Replace template variables for Admin system notifications
     */
    fun fillAdminNotificationTemplate(
        templateText: String,
        studentName: String,
        courseTitle: String = "",
        installmentNumber: Int = 1,
        installmentAmount: Long = 0L,
        remainingDebt: Long = 0L,
        dueDateShamsi: String = "",
        settings: AcademySettings? = null
    ): String {
        val academyName = settings?.academyName?.ifEmpty { "آموزشگاه" } ?: "آموزشگاه"
        val currency = settings?.currencyUnit ?: "تومان"
        val formattedAmount = PersianDateUtil.formatCurrency(installmentAmount, "")
        val formattedRemaining = PersianDateUtil.formatCurrency(remainingDebt, "")
        val formattedNumber = installmentNumber.toString()

        return templateText
            .replace("{studentName}", studentName)
            .replace("{installmentNumber}", formattedNumber)
            .replace("{amount}", formattedAmount)
            .replace("{dueDate}", dueDateShamsi)
            .replace("{remainingDebt}", formattedRemaining)
            .replace("{courseName}", courseTitle.ifBlank { "دوره آموزشی" })
            .replace("{academyName}", academyName)
            .replace("{currencyUnit}", currency)
            // Persian aliases
            .replace("{نام}", studentName)
            .replace("{شماره_قسط}", formattedNumber)
            .replace("{مبلغ}", formattedAmount)
            .replace("{مبلغ_قسط}", formattedAmount)
            .replace("{تاریخ_سررسید}", dueDateShamsi)
            .replace("{تاریخ}", dueDateShamsi)
            .replace("{مانده}", formattedRemaining)
            .replace("{دوره}", courseTitle.ifBlank { "دوره آموزشی" })
            .replace("{نام_دوره}", courseTitle.ifBlank { "دوره آموزشی" })
    }
}
