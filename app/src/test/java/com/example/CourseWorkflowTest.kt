package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.model.Student
import com.example.data.remote.FirebaseManager
import com.example.data.repository.AcademyRepository
import com.example.data.util.ArchiveMath
import com.example.data.util.CourseAccount
import com.example.data.util.ReportCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 17 mission scenarios of the course-management mission, driven end-to-end
 * through [AcademyRepository] on an in-memory Room database (offline no-op
 * Firebase — same pattern as [DatabaseAndSyncTest]).
 *
 * Scenario numbering matches the mission brief; scenario 16 (real Room
 * migration 7→8) lives in [Migration7to8Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CourseWorkflowTest {

    companion object {
        // Fixed, deterministic "start of course" epoch (2024-09-19, before any logic depends on it).
        private const val COURSE_START_EPOCH = 1_727_364_600_000L
    }

    private lateinit var database: AppDatabase
    private lateinit var repository: AcademyRepository

    private var studentSeq = 0
    private var courseSeq = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AcademyRepository(database, FirebaseManager())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------- helpers (deterministic, no sleeps) ----------------

    private suspend fun newStudent(name: String): Student {
        studentSeq += 1
        val student = Student(
            id = "std_wf_$studentSeq",
            firstName = name,
            lastName = "تستی",
            phone = "091200" + studentSeq.toString().padStart(5, '0')
        )
        database.studentDao().insertStudent(student)
        return student
    }

    private suspend fun newCourse(
        title: String,
        fee: Long = 5_000_000L,
        capacity: Int = 15,
        academicYear: String = "1404",
        startShamsi: String = "1404/07/15",
        startTime: String = "16:30",
        startEpoch: Long = COURSE_START_EPOCH
    ): Course = repository.addCourse(
        title = title,
        tuitionFee = fee,
        capacity = capacity,
        academicYear = academicYear,
        startShamsi = startShamsi,
        startTime = startTime,
        startEpoch = startEpoch
    ).getOrThrow()

    private suspend fun enroll(
        student: Student,
        course: Course,
        installmentCount: Int = 1,
        discount: Long = 0L,
        allowOverCapacity: Boolean = false,
        firstInstallmentPaidNow: Boolean = false
    ): Enrollment = repository.enrollStudentInCourse(
        student = student,
        course = course,
        startDateShamsi = "1404/07/10",
        discount = discount,
        installmentCount = installmentCount,
        firstInstallmentDateShamsi = "1404/07/10",
        firstInstallmentPaidNow = firstInstallmentPaidNow,
        allowOverCapacity = allowOverCapacity
    ).getOrThrow()

    private suspend fun recordPay(
        studentId: String,
        installmentId: String,
        amount: Long,
        dateShamsi: String = "1404/07/20",
        method: String = "CASH"
    ): Payment = repository.recordPayment(
        studentId = studentId,
        installmentId = installmentId,
        amount = amount,
        paymentDateShamsi = dateShamsi,
        paymentTime = "12:00",
        paymentMethod = method,
        destinationAccountId = "",
        destinationAccountTitle = "صندوق دوره"
    ).getOrThrow()

    /** Pays every non-cancelled installment of the student in full (via the repository). */
    private suspend fun payAllInstallments(studentId: String) {
        database.installmentDao().getInstallmentsForStudent(studentId).first()
            .filter { it.status != "CANCELLED" && it.remainingAmount > 0 }
            .forEach { inst ->
                recordPay(studentId, inst.id, inst.remainingAmount)
            }
    }

    private suspend fun reload(courseId: String): Course =
        database.courseDao().getCourseById(courseId)!!

    private suspend fun singleInstallment(enrollmentId: String): Installment =
        database.installmentDao().getInstallmentsForEnrollmentDirect(enrollmentId).single()

    private fun assertFailure(result: Result<*>, messagePart: String? = null) {
        assertTrue("انتظار شکست عملیات را داشتیم", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        if (messagePart != null) {
            assertTrue("پیام خطا باید شامل «$messagePart» باشد — پیام واقعی: $message", message.contains(messagePart))
        }
    }

    private suspend fun courseAccountReport(course: Course): CourseAccount.CourseAccountReport {
        val enrollments = database.enrollmentDao().getEnrollmentsForCourse(course.id)
        val installments = database.installmentDao().getInstallmentsForCourseDirect(course.id)
        val payments = database.paymentDao().getAllPayments().first()
        val refunds = database.refundDao().getAllRefunds().first()
        return CourseAccount.build(course, enrollments, installments, payments, refunds)
    }

    // =====================================================================
    // Scenario 1 — create a course with exact date/time and capacity
    // =====================================================================

    @Test
    fun scenario1_createCourse_withExactDateAndCapacity() = runBlocking {
        val course = repository.addCourse(
            title = "دوره پایتون مقدماتی",
            tuitionFee = 5_000_000L,
            capacity = 15,
            academicYear = "1404",
            startShamsi = "1404/07/15",
            startTime = "16:30",
            startEpoch = COURSE_START_EPOCH
        ).getOrThrow()

        val loaded = reload(course.id)
        assertEquals("دوره پایتون مقدماتی", loaded.title)
        assertEquals("ظرفیت ثبت‌شده باید ۱۵ باشد", 15, loaded.capacity)
        assertEquals(5_000_000L, loaded.tuitionFee)
        assertEquals("1404", loaded.academicYear)
        assertEquals("1404/07/15", loaded.startShamsi)
        assertEquals("16:30", loaded.startTime)
        assertEquals("epoch شروع باید عیناً ذخیره شود", COURSE_START_EPOCH, loaded.startEpoch)
        // the three separate status groups start in their initial state
        assertEquals(Course.ENROLLMENT_OPEN, loaded.enrollmentStatus)
        assertEquals(Course.HOLDING_ONGOING, loaded.holdingStatus)
        assertEquals(Course.FINAL_INCOMPLETE, loaded.finalStatus)
    }

    // =====================================================================
    // Scenario 2 — enrolling one student increments the capacity counter
    // =====================================================================

    @Test
    fun scenario2_enrollStudent_incrementsCapacityCounter() = runBlocking {
        val course = newCourse("دوره ظرفیت ۱۵")
        val student = newStudent("آرش")

        enroll(student, course, installmentCount = 1)

        assertEquals("شمارنده ظرفیت باید ۱ شود", 1, database.enrollmentDao().countValidEnrollments(course.id))
        val loaded = reload(course.id)
        assertEquals("با یک ثبت‌نام از ۱۵ صندلی، دوره باید باز بماند", Course.ENROLLMENT_OPEN, loaded.enrollmentStatus)

        val report = courseAccountReport(loaded)
        assertEquals(1, report.validEnrollmentCount)
        assertEquals(15, report.capacity)
        assertEquals("دوره با ظرفیت ۱۵ و یک ثبت‌نام، همچنان باز است", 14, report.capacity - report.validEnrollmentCount)
    }

    // =====================================================================
    // Scenario 3 — capacity full blocks new enrollment; explicit override works
    // =====================================================================

    @Test
    fun scenario3_capacityFull_blocksNewEnrollment_andShowsState() = runBlocking {
        val course = newCourse("دوره کوچک", fee = 1_000_000L, capacity = 2)
        val s1 = newStudent("نفر اول")
        val s2 = newStudent("نفر دوم")
        val s3 = newStudent("نفر سوم")

        enroll(s1, course)
        enroll(s2, course)
        var loaded = reload(course.id)
        assertEquals("با پر شدن ظرفیت، وضعیت باید ظرفیتِ تکمیل شود", Course.ENROLLMENT_CAPACITY_FULL, loaded.enrollmentStatus)

        // Without explicit consent: blocked, with the Persian capacity message.
        val blocked = repository.enrollStudentInCourse(
            student = s3, course = loaded, startDateShamsi = "1404/07/10",
            firstInstallmentDateShamsi = "1404/07/10", firstInstallmentPaidNow = false,
            allowOverCapacity = false
        )
        assertFailure(blocked, "ظرفیت")
        assertEquals("ثبت‌نام ردشده نباید به شمارنده اضافه کند", 2, database.enrollmentDao().countValidEnrollments(course.id))

        // With explicit manager consent: allowed, recorded on the enrollment, capacity untouched.
        val override = repository.enrollStudentInCourse(
            student = s3, course = loaded, startDateShamsi = "1404/07/10",
            firstInstallmentDateShamsi = "1404/07/10", firstInstallmentPaidNow = false,
            allowOverCapacity = true
        ).getOrThrow()
        assertTrue("ثبت‌نام استثنایی باید روی خود ثبت‌نام ثبت شود", override.enrolledViaOverride)
        assertTrue("یادداشت ثبت‌نام باید صریحاً سرریز ظرفیت را بگوید", override.notes.contains("استثنایی"))

        loaded = reload(course.id)
        assertEquals("مقدار ظرفیت هرگز نباید تغییر کند", 2, loaded.capacity)
        assertEquals("وضعیت همچنان ظرفیت تکمیل می‌ماند", Course.ENROLLMENT_CAPACITY_FULL, loaded.enrollmentStatus)
        assertEquals(3, database.enrollmentDao().countValidEnrollments(course.id))
    }

    // =====================================================================
    // Scenario 4 — manual close stops new enrollments, keeps tracking everything
    // =====================================================================

    @Test
    fun scenario4_manualClose_beforeSettlement_stopsNewEnrollments_keepsTracking() = runBlocking {
        val course = newCourse("دوره دستی بسته‌شونده")
        val s1 = newStudent("هنرجوی بدهکار")
        val s2 = newStudent("داوطلب جدید")
        val enrollment = enroll(s1, course, installmentCount = 1) // 5M, unpaid
        recordPay(s1.id, singleInstallment(enrollment.id).id, 4_000_000L) // 1M remains

        val paymentsBefore = database.paymentDao().getAllPayments().first().sortedBy { it.id }
        val installmentsBefore = database.installmentDao().getInstallmentsForEnrollmentDirect(enrollment.id)
        val enrollmentsBefore = database.enrollmentDao().getEnrollmentsForCourse(course.id).sortedBy { it.id }

        assertTrue(repository.closeEnrollmentManually(course.id).isSuccess)
        var loaded = reload(course.id)
        assertEquals("بستن دستی باید ثبت‌نام را ببندد", Course.ENROLLMENT_CLOSED, loaded.enrollmentStatus)
        assertEquals("بستن دستی هرگز نباید پایان دوره را تسویه/تغییر دهد", Course.FINAL_INCOMPLETE, loaded.finalStatus)

        // New enrollments are stopped (message mentions the manager's close).
        val rejected = repository.enrollStudentInCourse(
            student = s2, course = loaded, startDateShamsi = "1404/07/10",
            firstInstallmentDateShamsi = "1404/07/10", firstInstallmentPaidNow = false
        )
        assertFailure(rejected, "بسته")

        // Existing tracking is untouched and still queryable.
        assertEquals(enrollmentsBefore, database.enrollmentDao().getEnrollmentsForCourse(course.id).sortedBy { it.id })
        assertEquals(installmentsBefore, database.installmentDao().getInstallmentsForEnrollmentDirect(enrollment.id))
        assertEquals(paymentsBefore, database.paymentDao().getAllPayments().first().sortedBy { it.id })
        assertEquals(1_000_000L, singleInstallment(enrollment.id).remainingAmount)

        val report = courseAccountReport(loaded)
        assertEquals("بدهی بعد از بستن هم باید دیده شود", 1, report.installmentUnpaidCount)
        assertEquals(1_000_000L, report.outstanding)

        // Re-opening recomputes the truth: 1 valid < capacity 15 → OPEN.
        assertTrue(repository.openEnrollment(course.id).isSuccess)
        loaded = reload(course.id)
        assertEquals(Course.ENROLLMENT_OPEN, loaded.enrollmentStatus)
    }

    // =====================================================================
    // Scenario 5 — opening a new course while a previous one is incomplete
    // =====================================================================

    @Test
    fun scenario5_openNewCourse_whilePreviousIncomplete() = runBlocking {
        val previous = newCourse("دوره قبلی")
        val sharedStudent = newStudent("هنرجوی مشترک")
        enroll(sharedStudent, previous, installmentCount = 1)
        assertTrue(repository.closeEnrollmentManually(previous.id).isSuccess)

        val next = newCourse("دوره جدید")
        assertNotEquals("دوره‌ها باید مستقل باشند", previous.id, next.id)

        val loadedPrevious = reload(previous.id)
        assertEquals(Course.ENROLLMENT_CLOSED, loadedPrevious.enrollmentStatus)
        assertEquals("دوره قبلی ناتمام می‌ماند", Course.FINAL_INCOMPLETE, loadedPrevious.finalStatus)

        // The incomplete previous course must not block the new one.
        val newEnrollment = enroll(sharedStudent, next, installmentCount = 1)
        assertEquals(next.id, newEnrollment.courseId)
        assertEquals(1, database.enrollmentDao().countValidEnrollments(next.id))
        assertEquals("هر دوره شمارنده مستقل خودش را دارد", 1, database.enrollmentDao().countValidEnrollments(previous.id))
        assertEquals(2, database.courseDao().getAllCourses().first().size)
    }

    // =====================================================================
    // Scenario 6 — students of a closed course remain trackable
    // =====================================================================

    @Test
    fun scenario6_trackStudents_ofClosedCourse() = runBlocking {
        val course = newCourse("دوره بسته", capacity = 2)
        val s1 = newStudent("ماندگار")
        val s2 = newStudent("منصرف")
        val s3 = newStudent("جایگزین")
        val enr1 = enroll(s1, course)
        val enr2 = enroll(s2, course)
        assertEquals(Course.ENROLLMENT_CAPACITY_FULL, reload(course.id).enrollmentStatus)

        assertTrue(repository.closeEnrollmentManually(course.id).isSuccess)

        // After close, the roster is still listed with its active statuses.
        val afterClose = database.enrollmentDao().getEnrollmentsForCourse(course.id)
        assertEquals(2, afterClose.size)
        assertTrue(afterClose.all { it.status == "ACTIVE" })

        // Cancelling one keeps the roster, flips only that enrollment.
        assertTrue(repository.cancelEnrollment(enr2.id, "انصراف هنرجو").isSuccess)
        val afterCancel = database.enrollmentDao().getEnrollmentsForCourse(course.id)
        assertEquals(2, afterCancel.size)
        assertEquals("CANCELLED", afterCancel.first { it.id == enr2.id }.status)
        assertEquals("ACTIVE", afterCancel.first { it.id == enr1.id }.status)
        assertEquals("شمارنده ظرفیت باید کاهش یابد", 1, database.enrollmentDao().countValidEnrollments(course.id))

        // Re-opening reflects the freed seat and a new student fits again.
        assertTrue(repository.openEnrollment(course.id).isSuccess)
        assertEquals(Course.ENROLLMENT_OPEN, reload(course.id).enrollmentStatus)
        val enr3 = enroll(s3, course)
        assertEquals(course.id, enr3.courseId)
        assertEquals(2, database.enrollmentDao().countValidEnrollments(course.id))
        assertEquals(Course.ENROLLMENT_CAPACITY_FULL, reload(course.id).enrollmentStatus)
    }

    // =====================================================================
    // Scenario 7 — installments, debt and certificates survive a manual close
    // =====================================================================

    @Test
    fun scenario7_installments_debt_certificates_preserved_afterClose() = runBlocking {
        val course = newCourse("دوره اقساط باز")
        val student = newStudent("هنرجوی قسطی")
        val enrollment = enroll(student, course, installmentCount = 1)
        val installment = singleInstallment(enrollment.id)
        val payment = recordPay(student.id, installment.id, 4_000_000L) // partial: 1M remains
        val installmentBefore = singleInstallment(enrollment.id)

        assertTrue(repository.closeEnrollmentManually(course.id).isSuccess)

        // Installment unchanged after close.
        val installmentAfter = singleInstallment(enrollment.id)
        assertEquals("قسط نباید با بستن ثبت‌نام تغییر کند", installmentBefore, installmentAfter)
        assertEquals(1_000_000L, installmentAfter.remainingAmount)

        // Payment intact.
        // Payments are looked up by the INSTALLMENT they pay for (the returned
        // Payment object's id is the transaction id, not an installment id).
        val payments = database.paymentDao().getPaymentsForInstallment(installment.id).first()
        assertEquals(1, payments.size)
        assertEquals(4_000_000L, payments[0].amount)
        assertFalse("پرداخت نباید لغو شده باشد", payments[0].isReversed)

        // Certificate management still works on a closed course and persists.
        assertTrue(repository.setCertificateStatus(enrollment.id, Enrollment.CERTIFICATE_ISSUED).isSuccess)
        assertEquals(
            "وضعیت مدرک صادرشده باید ماندگار باشد",
            Enrollment.CERTIFICATE_ISSUED,
            database.enrollmentDao().getEnrollmentById(enrollment.id)!!.certificateStatus
        )
        assertEquals(Course.FINAL_INCOMPLETE, reload(course.id).finalStatus)
    }

    // =====================================================================
    // Scenario 8 — completion check blocks finalize while anything is unresolved
    // =====================================================================

    @Test
    fun scenario8_completionCheck_blocks_finalize_withUnresolved() = runBlocking {
        val course = newCourse("دوره نیمه‌تمام", capacity = 2)
        val student = newStudent("هنرجوی معلق")
        val enrollment = enroll(student, course, installmentCount = 1) // unpaid + PENDING certificate

        val check = repository.completionCheck(course.id).getOrThrow()
        assertFalse("بررسی پایان نباید آماده باشد", check.isReady)
        assertEquals("هنرجوی فعال باید در فهرست باشد", listOf(student.fullName), check.activeStudents)
        assertTrue("قسط پرداخت‌نشده باید گزارش شود", check.unpaidInstallments.isNotEmpty())
        assertTrue("مدرک منتظر باید گزارش شود", check.missingCertificates.isNotEmpty())
        assertTrue(check.unpaidInstallments[0].contains(student.fullName))
        assertEquals(listOf(student.fullName), check.missingCertificates)

        assertFailure(repository.markCourseReady(course.id), "بررسی پایان کامل ناموفق")
        assertFailure(repository.finalizeCourse(course.id), "دوره ابتدا باید آماده بررسی پایان شود")
        assertEquals(Course.FINAL_INCOMPLETE, reload(course.id).finalStatus)
        assertNotNull(database.enrollmentDao().getEnrollmentById(enrollment.id))
    }

    // =====================================================================
    // Scenario 9 — READY then FINALIZED: explicit-only, nothing ever deleted
    // =====================================================================

    @Test
    fun scenario9_ready_then_finalize_explicitOnly() = runBlocking {
        val course = newCourse("دوره پایان‌پذیر", capacity = 2)
        val s1 = newStudent("هنرجوی اول")
        val s2 = newStudent("هنرجوی دوم")
        val enr1 = enroll(s1, course, installmentCount = 1)
        val enr2 = enroll(s2, course, installmentCount = 1)

        // Gate order: finalize is impossible before markCourseReady — even on INCOMPLETE.
        assertFailure(repository.finalizeCourse(course.id), "دوره ابتدا باید آماده بررسی پایان شود")

        // Resolve everything.
        payAllInstallments(s1.id)
        payAllInstallments(s2.id)
        database.enrollmentDao().updateEnrollment(database.enrollmentDao().getEnrollmentById(enr1.id)!!.copy(status = "COMPLETED"))
        database.enrollmentDao().updateEnrollment(database.enrollmentDao().getEnrollmentById(enr2.id)!!.copy(status = "COMPLETED"))
        assertTrue(repository.setCertificateStatus(enr1.id, Enrollment.CERTIFICATE_DELIVERED).isSuccess)
        assertTrue(repository.setCertificateStatus(enr2.id, Enrollment.CERTIFICATE_DELIVERED).isSuccess)

        val check = repository.completionCheck(course.id).getOrThrow()
        assertTrue("با حل شدن همه موارد، بررسی پایان باید آماده شود: $check", check.isReady)

        // finalize BEFORE markCourseReady still fails (explicit-only, 2-gate).
        assertFailure(repository.finalizeCourse(course.id), "دوره ابتدا باید آماده بررسی پایان شود")

        assertTrue(repository.markCourseReady(course.id).isSuccess)
        assertEquals(Course.FINAL_READY, reload(course.id).finalStatus)

        // Snapshot before finalize: finalize deletes NOTHING.
        val enrollmentsBefore = database.enrollmentDao().getEnrollmentsForCourse(course.id).sortedBy { it.id }
        val installmentsBefore = database.installmentDao().getInstallmentsForCourseDirect(course.id).sortedBy { it.id }
        val paymentsBefore = database.paymentDao().getAllPayments().first().sortedBy { it.id }

        assertTrue(repository.finalizeCourse(course.id).isSuccess)
        val finalized = reload(course.id)
        assertEquals(Course.FINAL_FINALIZED, finalized.finalStatus)
        assertEquals("پایان‌یافته ثبت‌نام جدید هم نمی‌پذیرد", Course.ENROLLMENT_CLOSED, finalized.enrollmentStatus)

        assertEquals("هیچ ثبت‌نامی حذف نشده", enrollmentsBefore, database.enrollmentDao().getEnrollmentsForCourse(course.id).sortedBy { it.id })
        assertEquals("هیچ قسطی حذف نشده", installmentsBefore, database.installmentDao().getInstallmentsForCourseDirect(course.id).sortedBy { it.id })
        assertEquals("هیچ پرداختی حذف نشده", paymentsBefore, database.paymentDao().getAllPayments().first().sortedBy { it.id })
        assertTrue(installmentsBefore.all { it.status == "PAID" && it.remainingAmount == 0L })

        // Post-finalize locks.
        assertFailure(repository.markCourseReady(course.id), "قبلاً")
        assertFailure(repository.openEnrollment(course.id), "پایان‌یافته قابل بازگشایی نیست")
        val lateAttempt = repository.enrollStudentInCourse(
            student = newStudent("تلاش دیرهنگام"), course = finalized,
            startDateShamsi = "1404/07/10", firstInstallmentDateShamsi = "1404/07/10",
            firstInstallmentPaidNow = false, allowOverCapacity = true
        )
        assertFailure(lateAttempt, "پایان کامل تأیید شده")
    }

    // =====================================================================
    // Scenario 10 — tuition vs received are different numbers (5M / 4M example)
    // =====================================================================

    @Test
    fun scenario10_tuition_vs_received_differentAmounts() = runBlocking {
        val course = newCourse("دوره پنج میلیونی", fee = 5_000_000L, capacity = 15)
        val student = newStudent("هنرجوی چهار میلیونی")
        val enrollment = enroll(student, course, installmentCount = 1) // single 5M installment
        recordPay(student.id, singleInstallment(enrollment.id).id, 4_000_000L)

        val loaded = reload(course.id)
        val report = courseAccountReport(loaded)
        assertEquals("شهریه کل ۵٬۰۰۰٬۰۰۰ می‌ماند", 5_000_000L, report.tuitionContracted)
        assertEquals("دریافتی واقعی ۴٬۰۰۰٬۰۰۰ است", 4_000_000L, report.received)
        assertEquals("مانده ۱٬۰۰۰٬۰۰۰ است", 1_000_000L, report.outstanding)
        assertEquals(4_000_000L, report.netReceived)
        assertEquals(1, report.installmentUnpaidCount)
        assertEquals(4_000_000L, singleInstallment(enrollment.id).paidAmount)
        assertEquals("PARTIAL_PAID", singleInstallment(enrollment.id).status)
    }

    // =====================================================================
    // Scenario 11 — archive money with unknown amount is never counted as income
    // =====================================================================

    @Test
    fun scenario11_archive_unknownAmount_notCountedAsIncome() = runBlocking {
        // Live side: one real payment of 1,000,000.
        val course = newCourse("دوره زنده")
        val student = newStudent("هنرجوی زنده")
        val enrollment = enroll(student, course, installmentCount = 1)
        recordPay(student.id, singleInstallment(enrollment.id).id, 1_000_000L)

        // Archive side: one unknown (نامشخص) student + one fully known student.
        val archiveCourse = repository.addArchiveCourse("دوره قدیمی ۱۴۰۲", academicYear = "1402").getOrThrow()
        repository.addArchiveStudent(archiveCourse.id, "هنرجوی بی‌سند مالی").getOrThrow()
        repository.addArchiveStudent(
            archiveCourse.id, "هنرجوی با سند", tuitionAmount = 6_000_000L, receivedAmount = 4_000_000L
        ).getOrThrow()

        val archiveStudents = database.archiveDao().getArchiveStudentsDirect()
        val totals = ArchiveMath.totalsFor(archiveStudents)
        assertEquals(2, totals.studentCount)
        assertEquals("مبلغ نامشخص باید نامشخص شمرده شود، نه صفر", 1, totals.unknownReceivedCount)
        assertEquals("مجموع دریافتی مشخص بدون مبلغ نامشخص", 4_000_000L, totals.knownReceivedTotal)
        assertEquals(1, totals.unknownTuitionCount)
        assertEquals(6_000_000L, totals.knownTuitionTotal)
        assertTrue(archiveStudents.first { it.receivedAmount == null }.hasUnknownInfo)
        assertFalse(archiveStudents.first { it.receivedAmount != null }.hasUnknownInfo)

        // Whole-academy grand total = live collected + archive KNOWN only.
        val liveCollected = database.paymentDao().getAllPayments().first()
            .filter { !it.isReversed && !it.isSampleData }
            .sumOf { it.amount }
        assertEquals(1_000_000L, liveCollected)
        assertEquals(
            "جمع کل = بایگانی مشخص + زنده (نامشخص هرگز به درآمد اضافه نمی‌شود)",
            5_000_000L,
            ArchiveMath.grandTotal(totals.knownReceivedTotal, liveCollected)
        )

        // The two ledgers stay separate: the live course is not an archive course.
        assertEquals(listOf(archiveCourse.id), database.archiveDao().getAllArchiveCourses().first().map { it.id })
        assertEquals(1, database.courseDao().getAllCourses().first().size)
    }

    // =====================================================================
    // Scenario 12 — period report math + whole-academy (live net + archive known)
    // =====================================================================

    @Test
    fun scenario12_reports_period_math() = runBlocking {
        // Course A: two payments inside 1404.
        val courseA = newCourse("دوره گزارش الف")
        val sA = newStudent("هنرجوی الف")
        val enrA = enroll(sA, courseA, installmentCount = 1)
        recordPay(sA.id, singleInstallment(enrA.id).id, 1_000_000L, dateShamsi = "1404/01/12", method = "CASH")
        recordPay(sA.id, singleInstallment(enrA.id).id, 500_000L, dateShamsi = "1404/02/05", method = "POS")

        // Course B: one payment in 1403 (all-time differs from the 1404 period).
        val courseB = newCourse("دوره گزارش ب")
        val sB = newStudent("هنرجوی ب")
        val enrB = enroll(sB, courseB, installmentCount = 1)
        recordPay(sB.id, singleInstallment(enrB.id).id, 2_000_000L, dateShamsi = "1403/12/20")

        // A real refund of 30,000 dated inside 1404.
        database.refundDao().insertRefund(
            Refund(
                id = "rf_wf_1",
                studentId = sA.id,
                studentName = sA.fullName,
                enrollmentId = enrA.id,
                courseTitle = courseA.title,
                amount = 30_000L,
                dateShamsi = "1404/02/15"
            )
        )

        val payments = database.paymentDao().getAllPayments().first()
        val refunds = database.refundDao().getAllRefunds().first()
        val installments = database.installmentDao().getAllInstallments().first()
        val enrollments = database.enrollmentDao().getAllEnrollments().first()

        val yearReport = ReportCalculator.build(
            ReportCalculator.Query(jalaliYear = 1404, jalaliMonth = null),
            payments, refunds, installments, enrollments
        )
        assertEquals("فقط پرداخت‌های ۱۴۰۴: ۱م + ۰٫۵م", 1_500_000L, yearReport.collected)
        assertEquals(30_000L, yearReport.refunds)
        assertEquals("خالص سال ۱۴۰۴", 1_470_000L, yearReport.netIncome)

        // Whole academy (all-time) = live net + archive known.
        val archiveCourse = repository.addArchiveCourse("دوره بایگانی گزارش").getOrThrow()
        repository.addArchiveStudent(archiveCourse.id, "هنرجوی بایگانی", tuitionAmount = 5_000_000L, receivedAmount = 4_000_000L).getOrThrow()
        val archiveTotals = ArchiveMath.totalsFor(database.archiveDao().getArchiveStudentsDirect())

        val allTimeLiveCollected = payments.filter { !it.isReversed && !it.isSampleData }.sumOf { it.amount } // 3.5M
        val allTimeLiveNet = allTimeLiveCollected - refunds.sumOf { it.amount }                              // 3.47M
        assertEquals(3_500_000L, allTimeLiveCollected)
        assertEquals(3_470_000L, allTimeLiveNet)
        assertEquals(
            "جمع کل آموزشگاه = خالص زنده + بایگانی مشخص",
            7_470_000L,
            ArchiveMath.grandTotal(archiveTotals.knownReceivedTotal, allTimeLiveNet)
        )
    }

    // =====================================================================
    // Scenario 13 — every payment counts exactly once (live XOR archive)
    // =====================================================================

    @Test
    fun scenario13_payment_counted_once() = runBlocking {
        val courseA = newCourse("دوره شمارش الف", fee = 2_000_000L)
        val courseB = newCourse("دوره شمارش ب", fee = 1_000_000L)
        val sA = newStudent("پرداخت‌کننده الف")
        val sB = newStudent("پرداخت‌کننده ب")
        val enrA = enroll(sA, courseA, installmentCount = 1)
        val enrB = enroll(sB, courseB, installmentCount = 1)
        recordPay(sA.id, singleInstallment(enrA.id).id, 1_500_000L)
        recordPay(sB.id, singleInstallment(enrB.id).id, 1_000_000L)

        // A legacy payment resolvable to no course: still real money for the academy,
        // but it belongs to NO course account.
        val orphan = Payment(
            id = "pay_orphan_wf",
            studentId = sA.id,
            studentName = sA.fullName,
            installmentId = "",
            enrollmentId = "",
            amount = 700_000L,
            paymentDateShamsi = "1404/05/05"
        )
        database.paymentDao().insertPayment(orphan)

        val payments = database.paymentDao().getAllPayments().first()
        val enrollments = database.enrollmentDao().getAllEnrollments().first()
        val installments = database.installmentDao().getAllInstallments().first()

        val reportA = CourseAccount.build(reload(courseA.id), enrollments, installments, payments, emptyList())
        val reportB = CourseAccount.build(reload(courseB.id), enrollments, installments, payments, emptyList())
        assertEquals("پرداخت الف فقط در حساب دوره الف", 1_500_000L, reportA.received)
        assertEquals("پرداخت ب فقط در حساب دوره ب", 1_000_000L, reportB.received)

        // Whole academy: every payment exactly once (incl. the orphan, which no course claims).
        val wholeAcademy = payments.filter { !it.isReversed && !it.isSampleData }.sumOf { it.amount }
        assertEquals(3_200_000L, wholeAcademy)
        assertEquals(
            "جمع حساب‌های دوره + یتیم = کل آموزشگاه (بدون دوبار شمردن)",
            3_200_000L,
            reportA.received + reportB.received + 700_000L
        )

        // Archive is a SEPARATE ledger: archive money never duplicates live payments.
        val archiveCourse = repository.addArchiveCourse("دوره بایگانی شمارش").getOrThrow()
        repository.addArchiveStudent(archiveCourse.id, "هنرجوی بایگانی", tuitionAmount = 2_000_000L, receivedAmount = 1_000_000L).getOrThrow()
        val archiveTotals = ArchiveMath.totalsFor(database.archiveDao().getArchiveStudentsDirect())
        assertEquals(1_000_000L, archiveTotals.knownReceivedTotal)
        assertEquals(
            "جمع نهایی = کل زنده + بایگانی مشخص — هیچ رکوردی دو بار شمرده نمی‌شود",
            4_200_000L,
            ArchiveMath.grandTotal(archiveTotals.knownReceivedTotal, wholeAcademy)
        )
        assertEquals("دوره‌های زنده در بایگانی نیستند", 2, database.courseDao().getAllCourses().first().size)
        assertEquals(listOf(archiveCourse.id), database.archiveDao().getAllArchiveCourses().first().map { it.id })
    }

    // =====================================================================
    // Scenario 14 — re-enrolling an old student into a NEW course
    // =====================================================================

    @Test
    fun scenario14_reEnroll_oldStudent_newCourse() = runBlocking {
        val courseA = newCourse("دوره اول هنرجو", fee = 5_000_000L)
        val student = newStudent("هنرجوی بازگشتی")
        val enrA = enroll(student, courseA, installmentCount = 1)
        recordPay(student.id, singleInstallment(enrA.id).id, 5_000_000L) // fully settled
        database.enrollmentDao().updateEnrollment(
            database.enrollmentDao().getEnrollmentById(enrA.id)!!.copy(status = "COMPLETED")
        )

        val enrollmentsABefore = database.enrollmentDao().getEnrollmentsForCourse(courseA.id).sortedBy { it.id }
        val paymentsABefore = database.paymentDao().getAllPayments().first().sortedBy { it.id }

        // Same student, NEW course: always allowed (guard is per-course).
        val courseB = newCourse("دوره دوم همان هنرجو", fee = 3_000_000L)
        val enrB = enroll(student, courseB, installmentCount = 1)
        assertEquals(courseB.id, enrB.courseId)
        assertEquals(student.id, enrB.studentId)

        // Course A data untouched by the new enrollment.
        assertEquals(enrollmentsABefore, database.enrollmentDao().getEnrollmentsForCourse(courseA.id).sortedBy { it.id })
        assertEquals(paymentsABefore, database.paymentDao().getAllPayments().first().sortedBy { it.id })

        // Debts are per-course: A settled, B has a fresh unpaid installment.
        val reportA = courseAccountReport(reload(courseA.id))
        val reportB = courseAccountReport(reload(courseB.id))
        assertEquals("دوره اول تسویه‌شده است", 0L, reportA.outstanding)
        assertEquals("بدهی دوره دوم مستقل است", 3_000_000L, reportB.outstanding)
        assertEquals(1, reportA.completedCount)
        assertEquals("در دوره دوم ثبت‌نام فعال است نه تکمیل‌شده", 1, reportB.activeCount)
        assertEquals(1, reportB.installmentUnpaidCount)
        assertEquals(0, reportA.installmentUnpaidCount)
    }

    // =====================================================================
    // Scenario 15 — one shared contact (student) across several courses
    // =====================================================================

    @Test
    fun scenario15_sharedContact_multipleCourses() = runBlocking {
        val shared = newStudent("شماره تماس مشترک")
        val courseA = newCourse("دوره مشترک الف", fee = 2_000_000L)
        val courseB = newCourse("دوره مشترک ب", fee = 3_000_000L)
        val enrA = enroll(shared, courseA, installmentCount = 1)
        val enrB = enroll(shared, courseB, installmentCount = 1)
        assertEquals("هر دو ثبت‌نام از یک هنرجوی واحدند", shared.id, enrA.studentId)
        assertEquals(shared.id, enrB.studentId)

        assertTrue(repository.cancelEnrollment(enrA.id, "انصراف از دوره الف").isSuccess)

        val aAfter = database.enrollmentDao().getEnrollmentById(enrA.id)!!
        val bAfter = database.enrollmentDao().getEnrollmentById(enrB.id)!!
        assertEquals("لغو فقط در دوره الف", "CANCELLED", aAfter.status)
        assertEquals("دوره ب نباید دست بخورد", "ACTIVE", bAfter.status)

        // Only the cancelled course's unpaid installments are cancelled.
        assertEquals("CANCELLED", singleInstallment(enrA.id).status)
        val bInstallment = singleInstallment(enrB.id)
        assertEquals("PENDING", bInstallment.status)
        assertEquals(3_000_000L, bInstallment.remainingAmount)

        assertEquals(0, database.enrollmentDao().countValidEnrollments(courseA.id))
        assertEquals(1, database.enrollmentDao().countValidEnrollments(courseB.id))
        val reportB = courseAccountReport(reload(courseB.id))
        assertEquals("حساب دوره ب دست‌نخورده", 0L, reportB.received)
        assertEquals(3_000_000L, reportB.outstanding)
    }

    // =====================================================================
    // Scenario 16 — REAL Room migration 7→8 (lives in Migration7to8Test)
    // =====================================================================

    // =====================================================================
    // Scenario 17 — regression: existing behavior through the new course path
    // =====================================================================

    @Test
    fun scenario17_regression_existingBehavior() = runBlocking {
        // enroll → recordPayment (existing repository API) → installment PAID + student settled.
        val course = newCourse("دوره رگرسیون", fee = 3_000_000L)
        val student = newStudent("هنرجوی رگرسیون")
        val enrollment = enroll(student, course, installmentCount = 1)
        val installment = singleInstallment(enrollment.id)
        assertEquals(3_000_000L, installment.remainingAmount)

        recordPay(student.id, installment.id, 3_000_000L)
        val paid = database.installmentDao().getInstallmentById(installment.id)!!
        assertTrue("قسط باید پرداخت‌شده شود", paid.isPaid)
        assertEquals("PAID", paid.status)
        assertEquals(0L, paid.remainingAmount)
        assertEquals(3_000_000L, paid.paidAmount)
        assertEquals("جریان تسویه هنرجو نباید تغییر کند", "SETTLED", database.studentDao().getStudentById(student.id)!!.status)
        assertFailure(
            repository.recordPayment(student.id, installment.id, 1_000_000L, "1404/08/02", "12:00", "CASH", "", ""),
            "قبلاً"
        )

        // Backup JSON v4.0 carries the new archive sections.
        val archiveCourse = repository.addArchiveCourse("دوره بایگانی رگرسیون", academicYear = "1401").getOrThrow()
        repository.addArchiveStudent(archiveCourse.id, "هنرجوی بایگانی", tuitionAmount = 4_000_000L, receivedAmount = null).getOrThrow()

        val backup = repository.generateBackupJson(includeSensitiveSecrets = false)
        val root = JSONObject(backup)
        assertEquals("4.0", root.getString("version"))
        assertTrue("پشتیبان نسخه ۴ باید بخش بایگانی دوره‌ها را داشته باشد", root.has("archiveCourses"))
        assertTrue("پشتیبان نسخه ۴ باید بخش بایگانی هنرجویان را داشته باشد", root.has("archiveStudents"))
        assertEquals(1, root.getJSONArray("archiveCourses").length())
        assertEquals(1, root.getJSONArray("archiveStudents").length())
        // null = نامشخص stays ABSENT from the JSON (never serialized as 0)
        val archivedStudentJson = root.getJSONArray("archiveStudents").getJSONObject(0)
        assertFalse("مبلغ نامشخص نباید در پشتیبان صفر شود", archivedStudentJson.has("receivedAmount"))
        assertEquals(4_000_000L, archivedStudentJson.getLong("tuitionAmount"))

        // Mutate (wipe), then restore the v4.0 backup — everything comes back.
        database.clearAllTables()
        assertEquals(0, database.courseDao().getAllCourses().first().size)
        assertEquals(0, database.archiveDao().getArchiveStudentsDirect().size)

        val restore = repository.restoreBackupJson(backup)
        assertTrue("بازیابی پشتیبان v4 باید موفق شود: ${restore.exceptionOrNull()}", restore.isSuccess)
        assertTrue(restore.getOrNull()!!.contains("بایگانی"))

        val restoredCourses = database.courseDao().getAllCourses().first()
        assertEquals(1, restoredCourses.size)
        assertEquals(course.id, restoredCourses[0].id)
        assertEquals(3_000_000L, restoredCourses[0].tuitionFee)
        assertEquals(15, restoredCourses[0].capacity)
        assertEquals(Course.FINAL_INCOMPLETE, restoredCourses[0].finalStatus)

        val restoredEnrollment = database.enrollmentDao().getEnrollmentById(enrollment.id)
        assertNotNull("ثبت‌نام باید برگردد", restoredEnrollment)
        assertEquals("PAID", singleInstallment(enrollment.id).status)
        assertEquals(1, database.paymentDao().getAllPayments().first().size)

        val restoredArchiveStudents = database.archiveDao().getArchiveStudentsDirect()
        assertEquals(1, restoredArchiveStudents.size)
        assertEquals("هنرجوی بایگانی", restoredArchiveStudents[0].fullName)
        assertNull("مبلغ نامشخص باید همان نامشخص (null) بماند", restoredArchiveStudents[0].receivedAmount)
        assertEquals(4_000_000L, restoredArchiveStudents[0].tuitionAmount)
        assertEquals("دوره بایگانی رگرسیون", database.archiveDao().getAllArchiveCourses().first()[0].title)
    }
}
