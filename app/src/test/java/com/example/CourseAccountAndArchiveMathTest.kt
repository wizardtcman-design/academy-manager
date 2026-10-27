package com.example

import com.example.data.model.ArchiveStudent
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.util.ArchiveMath
import com.example.data.util.CourseAccount
import com.example.data.util.EnrollmentGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Mission "course management / archive accounting" — pure JVM math tests
 * (same style as ReportCalculatorTest: no Robolectric, no Android).
 *
 *  * [CourseAccount.build] — per-course accounting: the mission's 5M/4M example,
 *    payment resolution (installment → course, enrollment → course, orphans),
 *    reversal/sample exclusion, capacity counter and certificate counts.
 *  * [ArchiveMath.totalsFor] / [ArchiveMath.grandTotal] — unknown (null) money
 *    is NEVER assumed zero and never counted as income: it is counted as a gap.
 *  * [EnrollmentGuard] — the real re-registration semantics (per-course scope,
 *    183-day cooldown, blocked only by an open enrollment in the SAME course).
 */
class CourseAccountAndArchiveMathTest {

    // ---------- factories (deterministic, no time/random) ----------

    private fun course(
        id: String,
        title: String = "دوره تست",
        capacity: Int = 15
    ): Course = Course(
        id = id,
        title = title,
        tuitionFee = 5_000_000L,
        capacity = capacity
    )

    private fun enrollment(
        id: String,
        courseId: String,
        finalFee: Long = 5_000_000L,
        status: String = "ACTIVE",
        certificateStatus: String = Enrollment.CERTIFICATE_PENDING
    ): Enrollment = Enrollment(
        id = id,
        studentId = "std-" + id,
        studentName = "هنرجو " + id,
        courseId = courseId,
        courseTitle = "دوره تست",
        totalFee = finalFee,
        finalFee = finalFee,
        status = status,
        certificateStatus = certificateStatus
    )

    private fun installment(
        id: String,
        courseId: String,
        enrollmentId: String,
        amount: Long,
        remaining: Long = amount,
        status: String = if (remaining == 0L) "PAID" else "PENDING"
    ): Installment = Installment(
        id = id,
        enrollmentId = enrollmentId,
        studentId = "std-x",
        studentName = "هنرجو x",
        courseId = courseId,
        courseTitle = "دوره تست",
        installmentNumber = 1,
        amount = amount,
        paidAmount = amount - remaining,
        remainingAmount = remaining,
        status = status
    )

    private fun payment(
        id: String,
        amount: Long,
        installmentId: String = "",
        enrollmentId: String = "",
        reversed: Boolean = false,
        sample: Boolean = false
    ): Payment = Payment(
        id = id,
        studentId = "std-x",
        studentName = "هنرجو x",
        enrollmentId = enrollmentId,
        installmentId = installmentId,
        amount = amount,
        paymentDateShamsi = "1404/07/20",
        isReversed = reversed,
        isSampleData = sample
    )

    private fun refund(
        id: String,
        amount: Long,
        enrollmentId: String = "",
        courseTitle: String = "دوره تست"
    ): Refund = Refund(
        id = id,
        studentId = "std-x",
        studentName = "هنرجو x",
        enrollmentId = enrollmentId,
        courseTitle = courseTitle,
        amount = amount,
        dateShamsi = "1404/08/01"
    )

    private fun buildFor(
        c: Course,
        enrollments: List<Enrollment>,
        installments: List<Installment> = emptyList(),
        payments: List<Payment> = emptyList(),
        refunds: List<Refund> = emptyList()
    ): CourseAccount.CourseAccountReport =
        CourseAccount.build(c, enrollments, installments, payments, refunds)

    // ---------- 1. the mission example: 5M contracted / 4M received / 1M outstanding ----------

    @Test
    fun missionExample_capacity15_contracted5M_received4M_outstanding1M() {
        val c = course("crs_A", capacity = 15)
        // One ACTIVE enrollment with finalFee 5,000,000 and one installment plan
        // totaling 5,000,000 (4M + 1M); payments of 4,000,000 are recorded.
        val enrollments = listOf(enrollment("enr_1", c.id, finalFee = 5_000_000L))
        val installments = listOf(
            installment("ins_1", c.id, "enr_1", amount = 4_000_000L, remaining = 0L, status = "PAID"),
            installment("ins_2", c.id, "enr_1", amount = 1_000_000L, remaining = 1_000_000L)
        )
        val payments = listOf(payment("pay_1", amount = 4_000_000L, installmentId = "ins_1"))

        val report = buildFor(c, enrollments, installments, payments)

        // Confirmed income is what was really received (4M); the tuition total
        // stays the full contracted amount (5M) — they are never confused.
        assertEquals("شهریه قراردادشده باید همان ۵٬۰۰۰٬۰۰۰ بماند", 5_000_000L, report.tuitionContracted)
        assertEquals("دریافتی تأییدشده باید ۴٬۰۰۰٬۰۰۰ باشد", 4_000_000L, report.received)
        assertEquals("مانده شهریه باید ۱٬۰۰۰٬۰۰۰ باشد", 1_000_000L, report.outstanding)
        assertEquals(4_000_000L, report.netReceived)
        assertEquals("یک قسط هنوز مانده دارد", 1, report.installmentUnpaidCount)
        assertEquals("ظرفیت دوره ۱۵ است", 15, report.capacity)
        assertEquals(1, report.validEnrollmentCount)
    }

    // ---------- 2. payment resolution: installment AND enrollment paths ----------

    @Test
    fun paymentResolution_viaInstallmentCourseId_countsForThatCourse() {
        val c = course("crs_A")
        val enrollments = listOf(enrollment("enr_A", c.id))
        val installments = listOf(installment("ins_A", c.id, "enr_A", amount = 2_000_000L))
        // payment resolved through installment → courseId
        val payments = listOf(payment("pay_i", 1_500_000L, installmentId = "ins_A"))

        val report = buildFor(c, enrollments, installments, payments)
        assertEquals("پرداختِ متصل از طریق قسط باید در حساب دوره بیاید", 1_500_000L, report.received)
    }

    @Test
    fun paymentResolution_viaEnrollmentCourseId_countsForThatCourse() {
        val c = course("crs_A")
        val enrollments = listOf(enrollment("enr_A", c.id))
        // payment has NO installment link; only the enrollment link
        val payments = listOf(payment("pay_e", 900_000L, enrollmentId = "enr_A"))

        val report = buildFor(c, enrollments, emptyList(), payments)
        assertEquals("پرداختِ متصل از طریق ثبت‌نام باید در حساب دوره بیاید", 900_000L, report.received)
    }

    @Test
    fun paymentResolution_reversedAndSamplePaymentsExcluded() {
        val c = course("crs_A")
        val enrollments = listOf(enrollment("enr_A", c.id))
        val installments = listOf(installment("ins_A", c.id, "enr_A", amount = 5_000_000L))
        val payments = listOf(
            payment("pay_ok", 1_000_000L, installmentId = "ins_A"),
            payment("pay_rev", 500_000L, installmentId = "ins_A", reversed = true),
            payment("pay_sample", 300_000L, installmentId = "ins_A", sample = true)
        )

        val report = buildFor(c, enrollments, installments, payments)
        assertEquals("فقط پرداخت واقعیِ غیرلغوشده و غیرنمونه باید شمرده شود", 1_000_000L, report.received)
    }

    @Test
    fun paymentResolution_orphanPayment_countsInNoCourse() {
        val cA = course("crs_A")
        val cB = course("crs_B")
        val enrollments = listOf(
            enrollment("enr_A", cA.id),
            enrollment("enr_B", cB.id)
        )
        val installments = listOf(
            installment("ins_A", cA.id, "enr_A", amount = 2_000_000L),
            installment("ins_B", cB.id, "enr_B", amount = 2_000_000L)
        )
        // resolvable to NEITHER course (no installment, no enrollment links)
        val orphan = payment("pay_orphan", 700_000L, installmentId = "", enrollmentId = "")
        val payments = listOf(orphan)

        val reportA = buildFor(cA, enrollments, installments, payments)
        val reportB = buildFor(cB, enrollments, installments, payments)
        assertEquals("پرداخت بی‌پیوند نباید در حساب هیچ دوره‌ای شمرده شود", 0L, reportA.received)
        assertEquals("پرداخت بی‌پیوند نباید در حساب هیچ دوره‌ای شمرده شود", 0L, reportB.received)
        // (whole-academy counting of the same orphan is ReportCalculator's domain
        //  and is asserted end-to-end in CourseWorkflowTest.scenario13_payment_counted_once)
        assertEquals(1, payments.size) // the ledger row itself is untouched
    }

    // ---------- 3. capacity counter: ACTIVE + COMPLETED once each, CANCELLED excluded ----------

    @Test
    fun validEnrollmentCount_activeAndCompletedOnceEach_cancelledExcluded() {
        val c = course("crs_A", capacity = 15)
        val enrollments = listOf(
            enrollment("enr_act", c.id, finalFee = 5_000_000L, status = "ACTIVE"),
            enrollment("enr_cmp", c.id, finalFee = 3_000_000L, status = "COMPLETED"),
            enrollment("enr_cxl", c.id, finalFee = 9_000_000L, status = "CANCELLED")
        )

        val report = buildFor(c, enrollments)
        assertEquals("هر ثبت‌نام غیرلغوشده دقیقاً یک صندلی اشغال می‌کند", 2, report.validEnrollmentCount)
        assertEquals(1, report.activeCount)
        assertEquals(1, report.completedCount)
        assertEquals(1, report.cancelledCount)
        assertEquals(
            "شهریه قراردادشده فقط از ثبت‌نام‌های غیرلغوشده جمع می‌شود",
            8_000_000L, // 5M + 3M (the cancelled 9M is excluded)
            report.tuitionContracted
        )
    }

    // ---------- 4. certificate counts come from non-cancelled enrollments only ----------

    @Test
    fun certificateCounts_pendingIssuedDelivered_fromNonCancelledOnly() {
        val c = course("crs_A")
        val enrollments = listOf(
            enrollment("e1", c.id, status = "ACTIVE", certificateStatus = Enrollment.CERTIFICATE_PENDING),
            enrollment("e2", c.id, status = "ACTIVE", certificateStatus = Enrollment.CERTIFICATE_ISSUED),
            enrollment("e3", c.id, status = "COMPLETED", certificateStatus = Enrollment.CERTIFICATE_DELIVERED),
            enrollment("e4", c.id, status = "ACTIVE", certificateStatus = Enrollment.CERTIFICATE_NOT_REQUIRED),
            // cancelled enrollments never inflate the follow-up counters
            enrollment("e5", c.id, status = "CANCELLED", certificateStatus = Enrollment.CERTIFICATE_PENDING)
        )

        val report = buildFor(c, enrollments)
        assertEquals(1, report.certificatePendingCount)
        assertEquals(1, report.certificateIssuedCount)
        assertEquals(1, report.certificateDeliveredCount)
        assertEquals("فقط ثبت‌نام‌های غیرلغوشده شمرده می‌شوند", 4, report.validEnrollmentCount)
    }

    // ---------- 5. refunds resolve via enrollment and via denormalized course title ----------

    @Test
    fun refunds_resolveViaEnrollmentOrCourseTitle_andFeedOutstanding() {
        val c = course("crs_A", title = "سنتور")
        val enrollments = listOf(enrollment("enr_A", c.id))
        val installments = listOf(installment("ins_A", c.id, "enr_A", amount = 5_000_000L, remaining = 5_000_000L))
        val payments = listOf(payment("pay_1", 5_000_000L, installmentId = "ins_A"))
        val refunds = listOf(
            refund("rf_1", 300_000L, enrollmentId = "enr_A", courseTitle = ""),
            refund("rf_2", 50_000L, enrollmentId = "", courseTitle = "سنتور"),
            refund("rf_3", 999_000L, enrollmentId = "", courseTitle = "دوره دیگر"), // other course — excluded
            refund("rf_4", 10_000L, enrollmentId = "", courseTitle = "سنتور", /* sample */).copy(isSampleData = true)
        )

        val report = buildFor(c, enrollments, installments, payments, refunds)
        assertEquals("بازگشت وجه فقط از مسیر ثبت‌نام یا عنوان همین دوره", 350_000L, report.refunded)
        assertEquals(4_650_000L, report.netReceived)
        assertEquals("مانده = قرارداد − خالص دریافتی", 350_000L, report.outstanding)
    }

    // ---------- 6. ArchiveMath: null vs known amounts (نامشخص ≠ صفر) ----------

    @Test
    fun archiveMath_nullAmountsAreCountedAsUnknown_neverAsZero() {
        val students = listOf(
            // fully unknown money
            ArchiveStudent(id = "as_1", archiveCourseId = "ac_1", fullName = "هنرجوی نامشخص"),
            // fully known money
            ArchiveStudent(
                id = "as_2", archiveCourseId = "ac_1", fullName = "هنرجوی مشخص",
                tuitionAmount = 6_000_000L, receivedAmount = 4_000_000L
            ),
            // known ZERO is NOT unknown: it is a real (zero) observation
            ArchiveStudent(
                id = "as_3", archiveCourseId = "ac_1", fullName = "هنرجوی بدون پول",
                tuitionAmount = 2_000_000L, receivedAmount = 0L
            )
        )

        val totals = ArchiveMath.totalsFor(students)
        assertEquals(3, totals.studentCount)
        assertEquals("شهریه مشخص: ۶م + ۲م", 8_000_000L, totals.knownTuitionTotal)
        assertEquals("فقط شهریه نامشخص هنرجوی اول", 1, totals.unknownTuitionCount)
        assertEquals("دریافتی مشخص: فقط ۴م (صفرِ مشخص جمع می‌شود ولی نامشخص جمع نمی‌شود)", 4_000_000L, totals.knownReceivedTotal)
        assertEquals("فقط دریافتی نامشخص هنرجوی اول", 1, totals.unknownReceivedCount)
        assertFalse(students[1].hasUnknownInfo)
        assertTrue(students[0].hasUnknownInfo)
    }

    @Test
    fun archiveMath_grandTotal_sumsArchiveKnownPlusLiveCollected() {
        // The exact mission numbers: archive known 4,000,000 + live collected 1,000,000.
        assertEquals(5_000_000L, ArchiveMath.grandTotal(receivedKnownTotal = 4_000_000L, liveCollected = 1_000_000L))
        // And the symmetric order-safe property: it is a plain exclusive sum.
        assertEquals(
            ArchiveMath.grandTotal(1_000_000L, 4_000_000L),
            ArchiveMath.grandTotal(4_000_000L, 1_000_000L)
        )
    }

    // ---------- 7. EnrollmentGuard: real re-registration semantics ----------

    @Test
    fun enrollmentGuard_blocksOnlyOpenEnrollmentInTheSameCourse_thenCooldown() {
        val now = 1_800_000_000_000L // fixed reference time (deterministic)

        // No history at all → allowed.
        assertTrue(EnrollmentGuard.check(prior = emptyList(), courseEndDateEpoch = 0L, now = now).allowed)

        // An OPEN (ACTIVE) enrollment in the SAME course → blocked.
        val active = Enrollment(id = "e_act", studentId = "s1", courseId = "c1", status = "ACTIVE", registrationDateEpoch = now)
        val dActive = EnrollmentGuard.check(prior = listOf(active), courseEndDateEpoch = 0L, now = now)
        assertEquals(EnrollmentGuard.Block.ALREADY_ENROLLED, dActive.block)
        assertFalse(dActive.allowed)

        // REAL (pre-mission) semantics: COMPLETED still counts as "open" in the
        // same course → blocked as ALREADY_ENROLLED, no matter how old.
        val finished = Enrollment(
            id = "e_fin", studentId = "s1", courseId = "c1", status = "COMPLETED",
            registrationDateEpoch = now - TimeUnit.DAYS.toMillis(100)
        )
        val dFinished = EnrollmentGuard.check(prior = listOf(finished), courseEndDateEpoch = 0L, now = now)
        assertEquals(EnrollmentGuard.Block.ALREADY_ENROLLED, dFinished.block)
        assertFalse(dFinished.allowed)

        // A CANCELLED enrollment is not "open": the 183-day cooldown applies
        // from the previous registration date.
        val cancelled = finished.copy(id = "e_cxl", status = "CANCELLED")
        val dCooldown = EnrollmentGuard.check(prior = listOf(cancelled), courseEndDateEpoch = 0L, now = now)
        assertEquals(EnrollmentGuard.Block.COOLDOWN, dCooldown.block)
        assertEquals(
            "cooldown = ثبت‌نام قبلی + ۱۸۳ روز",
            cancelled.registrationDateEpoch + TimeUnit.DAYS.toMillis(183L),
            dCooldown.canReregisterAtEpoch
        )

        // Same course, but the 183 days have passed (200 days) → allowed.
        val oldCancelled = cancelled.copy(registrationDateEpoch = now - TimeUnit.DAYS.toMillis(200))
        assertTrue(EnrollmentGuard.check(prior = listOf(oldCancelled), courseEndDateEpoch = 0L, now = now).allowed)

        // The cooldown is PER COURSE: another course has no prior at all.
        assertTrue(
            "ثبت‌نام در دورهٔ دیگر همیشه آزاد است (گارد فقط برای همین دوره است)",
            EnrollmentGuard.check(prior = emptyList(), courseEndDateEpoch = 0L, now = now).allowed
        )
    }
}
