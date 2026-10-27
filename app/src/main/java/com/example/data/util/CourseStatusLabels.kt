package com.example.data.util

import com.example.data.model.ArchiveStudent
import com.example.data.model.Course
import com.example.data.model.Enrollment

/**
 * Persian display labels for the THREE SEPARATE course status groups and the
 * certificate statuses. Kept out of the Room entities on purpose: data classes
 * stored by Room must stay free of computed/display logic, and label text is a
 * pure UI concern. 100% JVM-pure so it is unit-testable without Android.
 */
object CourseStatusLabels {

    /** enrollmentStatus: OPEN / CAPACITY_FULL / CLOSED */
    fun enrollmentStatusLabel(status: String): String = when (status) {
        Course.ENROLLMENT_OPEN -> "ثبت‌نام باز"
        Course.ENROLLMENT_CAPACITY_FULL -> "ظرفیت تکمیل"
        Course.ENROLLMENT_CLOSED -> "ثبت‌نام بسته"
        else -> "نامشخص"
    }

    /** holdingStatus: ONGOING / ENDED */
    fun holdingStatusLabel(status: String): String = when (status) {
        Course.HOLDING_ONGOING -> "در حال برگزاری"
        Course.HOLDING_ENDED -> "پایان برگزاری"
        else -> "نامشخص"
    }

    /** finalStatus: INCOMPLETE / READY / FINALIZED */
    fun finalStatusLabel(status: String): String = when (status) {
        Course.FINAL_INCOMPLETE -> "ناتمام"
        Course.FINAL_READY -> "آماده بررسی پایان"
        Course.FINAL_FINALIZED -> "پایان کامل تأییدشده"
        else -> "نامشخص"
    }

    /** certificateStatus: PENDING / ISSUED / DELIVERED / NOT_REQUIRED */
    fun certificateStatusLabel(status: String): String = when (status) {
        Enrollment.CERTIFICATE_PENDING -> "در انتظار مدرک"
        Enrollment.CERTIFICATE_ISSUED -> "صادر شده"
        Enrollment.CERTIFICATE_DELIVERED -> "تحویل شده"
        Enrollment.CERTIFICATE_NOT_REQUIRED -> "مدرک لازم نیست"
        else -> "نامشخص"
    }
}

/**
 * Pure math over the archive tables. Archive students may have UNKNOWN money
 * amounts (null): unknown values are never assumed zero and never counted as
 * income/debt — they are counted separately so the manager sees the gap.
 */
data class ArchiveTotals(
    val studentCount: Int,
    val knownTuitionTotal: Long,
    val unknownTuitionCount: Int,
    val knownReceivedTotal: Long,
    val unknownReceivedCount: Int
)

object ArchiveMath {

    /**
     * Sums ONLY the known (non-null) amounts; unknown amounts are counted as
     * occurrences, never as zero. `receivedAmount` is never treated as income
     * of the live ledger — archive money and live money are never mixed.
     */
    fun totalsFor(students: List<ArchiveStudent>): ArchiveTotals = ArchiveTotals(
        studentCount = students.size,
        knownTuitionTotal = students.mapNotNull { it.tuitionAmount }.sum(),
        unknownTuitionCount = students.count { it.tuitionAmount == null },
        knownReceivedTotal = students.mapNotNull { it.receivedAmount }.sum(),
        unknownReceivedCount = students.count { it.receivedAmount == null }
    )

    /**
     * Grand received total for a report that combines archive history with the
     * live ledger. Both sides are already exclusive (a course is either archived
     * or live — never both), so a plain sum counts every payment exactly once.
     */
    fun grandTotal(receivedKnownTotal: Long, liveCollected: Long): Long =
        receivedKnownTotal + liveCollected
}
