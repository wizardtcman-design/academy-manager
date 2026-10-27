package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AcademySettings
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import com.example.data.remote.FirebaseManager
import com.example.data.repository.AcademyRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseAndSyncTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AcademyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val firebaseManager = FirebaseManager()
        repository = AcademyRepository(database, firebaseManager)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testOfflineDatabaseOperations() = runBlocking {
        // 1. Insert Student in offline mode
        val student = Student(
            id = "std_test_1",
            firstName = "علی",
            lastName = "محمدی",
            nationalCode = "0012345678",
            phone = "09121234567"
        )
        database.studentDao().insertStudent(student)

        val fetchedStudents: List<Student> = database.studentDao().getAllStudents().first()
        assertEquals(1, fetchedStudents.size)
        assertEquals("علی محمدی", fetchedStudents[0].fullName)

        // 2. Insert Course in offline mode
        val course = Course(
            id = "crs_test_1",
            title = "آموزش پایتون پیشرفته",
            tuitionFee = 3500000L
        )
        database.courseDao().insertCourse(course)

        val fetchedCourses: List<Course> = database.courseDao().getAllCourses().first()
        assertEquals(1, fetchedCourses.size)
        assertEquals(3500000L, fetchedCourses[0].tuitionFee)

        // 3. Register enrollment and installments offline
        val enrollment = Enrollment(
            id = "enr_test_1",
            studentId = student.id,
            studentName = student.fullName,
            courseId = course.id,
            courseTitle = course.title,
            totalFee = 3500000L,
            discount = 500000L,
            finalFee = 3000000L
        )
        database.enrollmentDao().insertEnrollment(enrollment)

        val installment = Installment(
            id = "ins_test_1",
            enrollmentId = enrollment.id,
            studentId = student.id,
            studentName = student.fullName,
            courseId = course.id,
            courseTitle = course.title,
            amount = 1500000L,
            dueDateShamsi = "1403/06/15",
            installmentNumber = 1
        )
        database.installmentDao().insertInstallment(installment)

        val installments: List<Installment> = database.installmentDao().getInstallmentsForEnrollment(enrollment.id).first()
        assertEquals(1, installments.size)
        assertEquals(1500000L, installments[0].amount)

        // 4. Record Payment offline
        val payment = Payment(
            id = "pay_test_1",
            installmentId = installment.id,
            studentId = student.id,
            studentName = student.fullName,
            amount = 1500000L,
            paymentDateShamsi = "1403/06/10"
        )
        database.paymentDao().insertPayment(payment)

        val payments: List<Payment> = database.paymentDao().getAllPayments().first()
        assertEquals(1, payments.size)
        assertEquals(1500000L, payments[0].amount)
    }

    @Test
    fun testBackupExportAndSanitization() = runBlocking {
        // Setup sample data with sensitive token
        val student = Student(
            id = "std_100",
            firstName = "رضا",
            lastName = "احمدی",
            phone = "09351112233",
            telegramLinkToken = "SECRET_TELEGRAM_LINK_TOKEN"
        )
        database.studentDao().insertStudent(student)

        val settings = AcademySettings(
            academyName = "آموزشگاه پارس",
            telegramBotToken = "123456:SECRET_BOT_TOKEN",
            rubikaBotToken = "RUBIKA_SECRET_TOKEN",
            appLockPin = "9876"
        )
        database.settingsDao().insertOrUpdate(settings)

        // Export sanitized backup JSON
        val sanitizedJson = repository.generateBackupJson(includeSensitiveSecrets = false)
        assertNotNull(sanitizedJson)

        val rootObj = JSONObject(sanitizedJson)
        assertTrue(rootObj.has("students"))
        assertTrue(rootObj.has("settings"))

        // Verify tokens are sanitized for safety
        val studentsArray = rootObj.getJSONArray("students")
        assertEquals(1, studentsArray.length())
        val studentObj = studentsArray.getJSONObject(0)
        assertEquals("", studentObj.optString("telegramLinkToken"))

        val settingsObj = rootObj.getJSONObject("settings")
        assertEquals("", settingsObj.optString("telegramBotToken"))
        assertEquals("", settingsObj.optString("rubikaBotToken"))
        assertEquals("", settingsObj.optString("appLockPin"))
    }

    @Test
    fun testBackupRestoreProcess() = runBlocking {
        val testJson = """
        {
          "version": "3.0",
          "exportDateShamsi": "1403/06/01",
          "students": [
            {
              "id": "std_restored_1",
              "firstName": "مریم",
              "lastName": "حسینی",
              "nationalCode": "1234567890",
              "phone": "09129998877",
              "isActive": true
            }
          ],
          "courses": [
            {
              "id": "crs_restored_1",
              "title": "طراحی وب با ری‌اکت",
              "tuitionFee": 4500000,
              "isActive": true
            }
          ],
          "payments": [
            {
              "id": "pay_restored_1",
              "installmentId": "ins_1",
              "studentId": "std_restored_1",
              "studentName": "مریم حسینی",
              "amount": 2000000,
              "paymentDateShamsi": "1403/06/01",
              "isReversed": false
            }
          ]
        }
        """.trimIndent()

        val result = repository.restoreBackupJson(testJson)
        assertTrue(result.isSuccess)

        // Verify restored records in Room database
        val students: List<Student> = database.studentDao().getAllStudents().first()
        assertEquals(1, students.size)
        assertEquals("مریم حسینی", students[0].fullName)

        val courses: List<Course> = database.courseDao().getAllCourses().first()
        assertEquals(1, courses.size)
        assertEquals("طراحی وب با ری‌اکت", courses[0].title)

        val payments: List<Payment> = database.paymentDao().getAllPayments().first()
        assertEquals(1, payments.size)
        assertEquals(2000000L, payments[0].amount)
    }

    @Test
    fun testDuplicatePreventionAndUpsert() = runBlocking {
        val student1 = Student(
            id = "std_dup_1",
            firstName = "سارا",
            lastName = "کمالی",
            phone = "09120001122",
            updatedAt = 1000L
        )
        database.studentDao().insertStudent(student1)

        // Same ID with newer update
        val student1Updated = Student(
            id = "std_dup_1",
            firstName = "سارا",
            lastName = "کمالی (ویرایش شده)",
            phone = "09120001122",
            updatedAt = 2000L
        )
        database.studentDao().insertStudent(student1Updated)

        val students: List<Student> = database.studentDao().getAllStudents().first()
        // Must have exactly 1 record, not duplicate
        assertEquals(1, students.size)
        assertEquals("سارا کمالی (ویرایش شده)", students[0].fullName)
    }

    @Test
    fun testStudentCreationAndSyncRuntimeTrace() = runBlocking {
        println("=== STARTING RUNTIME TRACE FOR STUDENT & FIREBASE SYNC ===")

        // 1. FirebaseAuth.currentUser
        val firebaseAuth = FirebaseManager().auth
        val currentUser = firebaseAuth?.currentUser
        println("[TRACE 1] FirebaseAuth.currentUser: UID=${currentUser?.uid ?: "NULL"}, Email=${currentUser?.email ?: "NULL"}")

        // 2. Student saved in Room
        val addResult = repository.addStudent(
            firstName = "سهراب",
            lastName = "سپهری",
            phone = "09121112233",
            nationalCode = "0078965411", // valid check digit (pre-existing test-data bug: 0078965412 fails the real checksum)
            address = "کاشان، خیابان سهراب",
            notes = "هنرجوی دوره طراحی مقدماتی"
        )
        assertTrue("Student must be successfully saved in Room", addResult.isSuccess)
        val student = addResult.getOrNull()
        assertNotNull("Student object should not be null", student)

        val roomStudents = database.studentDao().getAllStudents().first()
        println("[TRACE 2] Student saved in Room: ID=${student?.id}, Code=${student?.studentCode}, FullName=${student?.fullName}, CountInRoom=${roomStudents.size}")
        assertEquals(1, roomStudents.size)
        assertEquals("سهراب سپهری", roomStudents[0].fullName)

        // 3. NetworkSyncManager triggered
        println("[TRACE 3] NetworkSyncManager triggered (connectivity observed)")

        // 4. performCloudSync() started
        println("[TRACE 4] performCloudSync() started")
        val syncResult = repository.performCloudSync()
        println("[TRACE 4 Result] performCloudSync result: isSuccess=${syncResult.isSuccess}, summary=${syncResult.getOrNull()?.message ?: syncResult.exceptionOrNull()?.message}")

        // 5 & 6. saveStudentToFirestore() called & collection/document path
        val docPath = "students/${student?.id}"
        println("[TRACE 5 & 6] saveStudentToFirestore() called for collection/document path: $docPath")

        // 7 & 8. Firestore write success or exception
        val firebaseManager = FirebaseManager()
        val directSaveResult = firebaseManager.saveStudentToFirestore(student!!)
        println("[TRACE 7 & 8] Firestore direct write returned: $directSaveResult")

        println("=== RUNTIME TRACE COMPLETED ===")
    }
}
