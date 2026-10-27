package com.example.data.util

import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund

/**
 * Per-course accounting report — 100% JVM-pure (no Android imports), same
 * discipline as [ReportCalculator]. Every number is derived from the real
 * ledger rows passed in; nothing is assumed:
 *
 *   * capacity counter = non-cancelled enrollments (each counted once);
 *   * received = payments that are not reversed and not sample data, resolved
 *     to the course transitively (payment → installment → course, falling back
 *     to payment → enrollment → course) so each payment counts exactly once;
 *   * unknown/absent relations never produce money — they are simply not counted;
 *   * outstanding = contracted tuition − net received and may be negative when
 *     the course is overpaid (the UI shows it as-is; never coerced silently);
 *   * an installment counts as unpaid only when it is not CANCELLED and still
 *     has remainingAmount > 0 — unknown is NOT treated as settled.
 */
object CourseAccount {

    data class CourseAccountReport(
        val courseId: String,
        val courseTitle: String,
        val capacity: Int,                    // 0 = unlimited
        val validEnrollmentCount: Int,        // non-cancelled enrollments (capacity counter)
        val activeCount: Int,
        val completedCount: Int,
        val cancelledCount: Int,
        val tuitionContracted: Long,          // sum of finalFee of non-cancelled enrollments
        val received: Long,                   // valid (non-reversed, non-sample) payments
        val refunded: Long,                   // refunds of the course
        val netReceived: Long,                // received - refunded
        val outstanding: Long,                // tuitionContracted - netReceived (may be negative)
        val installmentUnpaidCount: Int,      // non-cancelled installments with remaining > 0
        val certificatePendingCount: Int,
        val certificateIssuedCount: Int,
        val certificateDeliveredCount: Int
    )

    fun build(
        course: Course,
        enrollments: List<Enrollment>,
        installments: List<Installment>,
        payments: List<Payment>,
        refunds: List<Refund>
    ): CourseAccountReport {
        val courseEnrollments = enrollments.filter { it.courseId == course.id }
        val valid = courseEnrollments.filter { it.status != "CANCELLED" }
        val cancelled = courseEnrollments.filter { it.status == "CANCELLED" }

        val installmentById = installments.associateBy { it.id }
        val enrollmentById = courseEnrollments.associateBy { it.id }

        fun courseOf(p: Payment): String? {
            installmentById[p.installmentId]?.let { return it.courseId }
            enrollmentById[p.enrollmentId]?.let { return it.courseId }
            return null
        }

        // Received money: every payment counted at most once — a payment belongs
        // to exactly one course via its installment (preferred) or enrollment.
        val received = payments
            .filter { !it.isReversed && !it.isSampleData }
            .filter { courseOf(it) == course.id }
            .sumOf { it.amount }

        // Refunds: resolve through the enrollment of this course, falling back
        // to the course title denormalized on the refund row.
        val refunded = refunds
            .filter { !it.isSampleData }
            .filter {
                (it.enrollmentId.isNotBlank() && enrollmentById[it.enrollmentId] != null) ||
                    it.courseTitle == course.title
            }
            .sumOf { it.amount }

        val tuitionContracted = valid.sumOf { it.finalFee }
        val netReceived = received - refunded

        val courseInstallments = installments.filter { it.courseId == course.id }
        val installmentUnpaidCount = courseInstallments
            .count { it.status != "CANCELLED" && it.remainingAmount > 0 }

        return CourseAccountReport(
            courseId = course.id,
            courseTitle = course.title,
            capacity = course.capacity,
            validEnrollmentCount = valid.size,
            activeCount = valid.count { it.status == "ACTIVE" },
            completedCount = valid.count { it.status == "COMPLETED" },
            cancelledCount = cancelled.size,
            tuitionContracted = tuitionContracted,
            received = received,
            refunded = refunded,
            netReceived = netReceived,
            outstanding = tuitionContracted - netReceived,
            installmentUnpaidCount = installmentUnpaidCount,
            certificatePendingCount = valid.count { it.certificateStatus == Enrollment.CERTIFICATE_PENDING },
            certificateIssuedCount = valid.count { it.certificateStatus == Enrollment.CERTIFICATE_ISSUED },
            certificateDeliveredCount = valid.count { it.certificateStatus == Enrollment.CERTIFICATE_DELIVERED }
        )
    }
}
