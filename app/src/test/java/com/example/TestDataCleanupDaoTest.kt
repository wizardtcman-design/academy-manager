package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.Contract
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.MessageLog
import com.example.data.model.Payment
import com.example.data.model.Reminder
import com.example.data.model.Student
import com.example.data.remote.FirebaseManager
import com.example.data.repository.AcademyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test-data cleanup — local cascade verification (mandatory flows):
 *   1. preview counts exactly the test student's rows,
 *   2. runTestDataCleanup removes the whole graph from Room (server is not
 *      configured here — the Worker side has its own vitest suite),
 *   3. the OTHER student, academy settings and courses survive untouched,
 *   4. resurrection-guard tombstones are written,
 *   5. a re-run is safe ("nothing to clean").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestDataCleanupDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AcademyRepository

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

    private suspend fun seedTwoStudents() {
        // Academy settings must survive the cleanup untouched.
        database.settingsDao().insertOrUpdate(AcademySettings(academyName = "آموزشگاه پارس"))
        val course = database.courseDao().let {
            val c = Course(id = "crs_1", title = "دوره واقعی", tuitionFee = 1000000L)
            it.insertCourse(c)
            c
        }

        // Test student with a full dependency graph.
        database.studentDao().insertStudent(
            Student(id = "std_test", firstName = "آزمایشی", lastName = "تستی", phone = "09120000001")
        )
        database.enrollmentDao().insertEnrollment(
            Enrollment(
                id = "enr_test", studentId = "std_test", studentName = "آزمایشی تستی",
                courseId = course.id, courseTitle = course.title, totalFee = 1000L, finalFee = 1000L
            )
        )
        database.installmentDao().insertInstallment(
            Installment(
                id = "ins_test", enrollmentId = "enr_test", studentId = "std_test",
                studentName = "آزمایشی تستی", courseId = course.id, amount = 1000L,
                dueDateShamsi = "1403/06/15", installmentNumber = 1
            )
        )
        database.paymentDao().insertPayment(
            Payment(id = "pay_test", installmentId = "ins_test", studentId = "std_test",
                studentName = "آزمایشی تستی", amount = 500L)
        )
        database.reminderDao().insertReminder(
            Reminder(id = "rem_test", studentId = "std_test", installmentId = "ins_test")
        )
        database.contractDao().insertContract(
            Contract(
                id = "con_test", enrollmentId = "enr_test", studentId = "std_test",
                studentName = "آزمایشی تستی", courseId = course.id, courseTitle = course.title,
                publicToken = "tok-a", publicTokenHash = "hash-a"
            )
        )
        database.messageLogDao().insertLog(MessageLog(id = "ml_test", studentId = "std_test"))
        database.auditLogDao().insertLog(AuditLog(entityType = "STUDENT", entityId = "std_test", actionType = "CREATE"))

        // Real student with the same shape — must survive everything.
        database.studentDao().insertStudent(
            Student(id = "std_real", firstName = "واقعی", lastName = "ماندگار", phone = "09120000002")
        )
        database.enrollmentDao().insertEnrollment(
            Enrollment(
                id = "enr_real", studentId = "std_real", studentName = "واقعی ماندگار",
                courseId = course.id, courseTitle = course.title, totalFee = 1000L, finalFee = 1000L
            )
        )
        database.installmentDao().insertInstallment(
            Installment(
                id = "ins_real", enrollmentId = "enr_real", studentId = "std_real",
                studentName = "واقعی ماندگار", courseId = course.id, amount = 1000L,
                dueDateShamsi = "1403/06/15", installmentNumber = 1
            )
        )
        database.contractDao().insertContract(
            Contract(
                id = "con_real", enrollmentId = "enr_real", studentId = "std_real",
                studentName = "واقعی ماندگار", courseId = course.id, courseTitle = course.title,
                publicToken = "tok-b", publicTokenHash = "hash-b"
            )
        )
    }

    @Test
    fun previewCountsOnlyTheSelectedStudent() = runBlocking {
        seedTwoStudents()
        val preview = repository.previewLocalTestData(listOf("std_test"))
        assertEquals(1, preview.found.size)
        assertTrue(preview.missingIds.isEmpty())
        assertEquals(1, preview.enrollments)
        assertEquals(1, preview.installments)
        assertEquals(1, preview.payments)
        assertEquals(1, preview.reminders)
        assertEquals(1, preview.contracts)
        assertEquals(1, preview.messageLogs)
        assertTrue(preview.auditLogs >= 1)
    }

    @Test
    fun fullCleanupRemovesOnlyTheTestStudentGraph() = runBlocking {
        seedTwoStudents()
        val report = repository.runTestDataCleanup(listOf("std_test")).getOrThrow()

        // Report: nothing remains locally; the Worker was not configured here.
        assertTrue(report.localRemainingClean)
        assertFalse(report.serverPurged)
        assertTrue(report.warnings.any { it.contains("سرور") })

        // The test student's whole graph is gone.
        assertNull(database.studentDao().getStudentById("std_test"))
        assertEquals(0, database.enrollmentDao().countForStudent("std_test"))
        assertEquals(0, database.installmentDao().countForStudent("std_test"))
        assertEquals(0, database.paymentDao().countForStudent("std_test"))
        assertEquals(0, database.reminderDao().countForStudent("std_test"))
        assertEquals(0, database.contractDao().countForStudent("std_test"))
        assertEquals(0, database.messageLogDao().countForStudent("std_test"))
        assertEquals(0, database.auditLogDao().countForEntityIds(listOf("std_test", "enr_test", "ins_test", "con_test", "pay_test")))

        // Resurrection guards are armed.
        val tombstones = database.pendingDeletionDao().getByType("STUDENT").first()
        assertTrue(tombstones.any { it.entityId == "std_test" })
        val workerTombstones = database.pendingDeletionDao().getPendingDeletions().first()
        assertTrue(workerTombstones.any { it.studentId == "std_test" })

        // The real student and academy base data survive untouched.
        assertNotNull(database.studentDao().getStudentById("std_real"))
        assertEquals(1, database.enrollmentDao().countForStudent("std_real"))
        assertEquals(1, database.installmentDao().countForStudent("std_real"))
        assertEquals(1, database.contractDao().countForStudent("std_real"))
        assertEquals("دوره واقعی", database.courseDao().getCourseById("crs_1")?.title)
        // The academy settings row (id current_settings) survives the cleanup.
        assertNotNull(database.settingsDao().getSettingsDirect())

        // Re-run is safe: nothing found.
        val rerun = repository.runTestDataCleanup(listOf("std_test"))
        assertTrue(rerun.isFailure) // student already gone from the device
    }
}
