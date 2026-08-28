package com.example.data.remote

import android.util.Log
import com.example.data.model.AcademyNotification
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.BankAccount
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Expense
import com.example.data.model.Installment
import com.example.data.model.MessageLog
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.model.Reminder
import com.example.data.model.Student
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.tasks.await

/**
 * FirebaseManager provides centralized, offline-safe access to Firestore collections:
 * 1. students
 * 2. courses
 * 3. enrollments
 * 4. installments
 * 5. payments
 * 6. expenses
 * 7. refunds
 * 8. bankAccounts
 * 9. messageLogs
 * 10. notifications
 * 11. auditLogs
 * 12. settings
 */
class FirebaseManager {

    private val tag = "FirebaseManager"

    private val firestore: FirebaseFirestore? by lazy {
        try {
            val db = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            db.firestoreSettings = settings
            db
        } catch (e: Exception) {
            Log.w(tag, "Firestore not initialized or unavailable: ${e.message}")
            null
        }
    }

    val auth: FirebaseAuth? by lazy {
        try {
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.w(tag, "FirebaseAuth not initialized: ${e.message}")
            null
        }
    }

    fun isReady(): Boolean {
        return firestore != null && auth?.currentUser != null
    }

    // Collections
    fun usersCollection() = if (isReady()) firestore?.collection("users") else null
    fun studentsCollection() = if (isReady()) firestore?.collection("students") else null
    fun coursesCollection() = if (isReady()) firestore?.collection("courses") else null
    fun enrollmentsCollection() = if (isReady()) firestore?.collection("enrollments") else null
    fun installmentsCollection() = if (isReady()) firestore?.collection("installments") else null
    fun paymentsCollection() = if (isReady()) firestore?.collection("payments") else null
    fun expensesCollection() = if (isReady()) firestore?.collection("expenses") else null
    fun refundsCollection() = if (isReady()) firestore?.collection("refunds") else null
    fun bankAccountsCollection() = if (isReady()) firestore?.collection("bankAccounts") else null
    fun messageLogsCollection() = if (isReady()) firestore?.collection("messageLogs") else null
    fun notificationsCollection() = if (isReady()) firestore?.collection("notifications") else null
    fun auditLogsCollection() = if (isReady()) firestore?.collection("auditLogs") else null
    fun settingsCollection() = if (isReady()) firestore?.collection("settings") else null
    fun remindersCollection() = if (isReady()) firestore?.collection("reminders") else null

    // --- Students Operations ---
    suspend fun saveStudentToFirestore(student: Student): Boolean {
        if (!isReady()) return false
        val col = studentsCollection() ?: return false
        return try {
            col.document(student.id).set(student.toMap(), SetOptions.merge()).await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveStudent skipped: ${e.message}")
            false
        }
    }

    suspend fun saveStudentsBatch(students: List<Student>): Boolean {
        if (students.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            students.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("students")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveStudentsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchStudentsFromFirestore(): List<Student> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = studentsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Student.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchStudents skipped: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteStudentFromFirestore(studentId: String): Boolean {
        if (!isReady()) return false
        return try {
            studentsCollection()?.document(studentId)?.delete()?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore deleteStudent skipped: ${e.message}")
            false
        }
    }

    // --- Courses Operations ---
    suspend fun saveCourseToFirestore(course: Course): Boolean {
        if (!isReady()) return false
        return try {
            coursesCollection()?.document(course.id)?.set(course.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveCourse skipped: ${e.message}")
            false
        }
    }

    suspend fun saveCoursesBatch(courses: List<Course>): Boolean {
        if (courses.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            courses.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("courses")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveCoursesBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchCoursesFromFirestore(): List<Course> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = coursesCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Course.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchCourses skipped: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteCourseFromFirestore(courseId: String): Boolean {
        if (!isReady()) return false
        return try {
            coursesCollection()?.document(courseId)?.delete()?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore deleteCourse skipped: ${e.message}")
            false
        }
    }

    // --- Enrollments Operations ---
    suspend fun saveEnrollmentToFirestore(enrollment: Enrollment): Boolean {
        if (!isReady()) return false
        return try {
            enrollmentsCollection()?.document(enrollment.id)?.set(enrollment.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveEnrollment skipped: ${e.message}")
            false
        }
    }

    suspend fun saveEnrollmentsBatch(enrollments: List<Enrollment>): Boolean {
        if (enrollments.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            enrollments.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("enrollments")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveEnrollmentsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchEnrollmentsFromFirestore(): List<Enrollment> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = enrollmentsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Enrollment.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchEnrollments skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Installments Operations ---
    suspend fun saveInstallmentToFirestore(installment: Installment): Boolean {
        if (!isReady()) return false
        return try {
            installmentsCollection()?.document(installment.id)?.set(installment.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveInstallment skipped: ${e.message}")
            false
        }
    }

    suspend fun saveInstallmentsBatch(installments: List<Installment>): Boolean {
        if (installments.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            installments.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("installments")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveInstallmentsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchInstallmentsFromFirestore(): List<Installment> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = installmentsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Installment.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchInstallments skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Payments Operations ---
    suspend fun savePaymentToFirestore(payment: Payment): Boolean {
        if (!isReady()) return false
        return try {
            paymentsCollection()?.document(payment.id)?.set(payment.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore savePayment skipped: ${e.message}")
            false
        }
    }

    suspend fun savePaymentsBatch(payments: List<Payment>): Boolean {
        if (payments.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            payments.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("payments")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore savePaymentsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchPaymentsFromFirestore(): List<Payment> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = paymentsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Payment.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchPayments skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Expenses Operations ---
    suspend fun saveExpenseToFirestore(expense: Expense): Boolean {
        if (!isReady()) return false
        return try {
            expensesCollection()?.document(expense.id)?.set(expense.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveExpense skipped: ${e.message}")
            false
        }
    }

    suspend fun saveExpensesBatch(expenses: List<Expense>): Boolean {
        if (expenses.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            expenses.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("expenses")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveExpensesBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchExpensesFromFirestore(): List<Expense> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = expensesCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Expense.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchExpenses skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Refunds Operations ---
    suspend fun saveRefundToFirestore(refund: Refund): Boolean {
        if (!isReady()) return false
        return try {
            refundsCollection()?.document(refund.id)?.set(refund.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveRefund skipped: ${e.message}")
            false
        }
    }

    suspend fun saveRefundsBatch(refunds: List<Refund>): Boolean {
        if (refunds.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            refunds.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("refunds")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveRefundsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchRefundsFromFirestore(): List<Refund> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = refundsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { Refund.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchRefunds skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Bank Accounts Operations ---
    suspend fun saveBankAccountToFirestore(account: BankAccount): Boolean {
        if (!isReady()) return false
        return try {
            bankAccountsCollection()?.document(account.id)?.set(account.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveBankAccount skipped: ${e.message}")
            false
        }
    }

    suspend fun saveBankAccountsBatch(accounts: List<BankAccount>): Boolean {
        if (accounts.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            accounts.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("bankAccounts")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveBankAccountsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchBankAccountsFromFirestore(): List<BankAccount> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = bankAccountsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { BankAccount.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchBankAccounts skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Message Logs Operations ---
    suspend fun saveMessageLogToFirestore(log: MessageLog): Boolean {
        if (!isReady()) return false
        return try {
            messageLogsCollection()?.document(log.id)?.set(log.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveMessageLog skipped: ${e.message}")
            false
        }
    }

    suspend fun saveMessageLogsBatch(logs: List<MessageLog>): Boolean {
        if (logs.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            logs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("messageLogs")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveMessageLogsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchMessageLogsFromFirestore(): List<MessageLog> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = messageLogsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { MessageLog.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchMessageLogs skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Notifications Operations ---
    suspend fun saveNotificationToFirestore(notification: AcademyNotification): Boolean {
        if (!isReady()) return false
        return try {
            notificationsCollection()?.document(notification.id)?.set(notification.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveNotification skipped: ${e.message}")
            false
        }
    }

    suspend fun saveNotificationsBatch(notifications: List<AcademyNotification>): Boolean {
        if (notifications.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            notifications.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("notifications")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveNotificationsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchNotificationsFromFirestore(): List<AcademyNotification> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = notificationsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { AcademyNotification.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchNotifications skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Audit Logs Operations ---
    suspend fun logAuditEvent(log: AuditLog): Boolean {
        if (!isReady()) return false
        return try {
            auditLogsCollection()?.document(log.id)?.set(log.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore logAuditEvent skipped: ${e.message}")
            false
        }
    }

    suspend fun saveAuditLogsBatch(logs: List<AuditLog>): Boolean {
        if (logs.isEmpty() || !isReady()) return true
        val db = firestore ?: return false
        return try {
            logs.chunked(400).forEach { chunk ->
                val batch = db.batch()
                val col = db.collection("auditLogs")
                chunk.forEach { item ->
                    batch.set(col.document(item.id), item.toMap(), SetOptions.merge())
                }
                batch.commit().await()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveAuditLogsBatch skipped: ${e.message}")
            false
        }
    }

    suspend fun fetchAuditLogsFromFirestore(): List<AuditLog> {
        if (!isReady()) return emptyList()
        return try {
            val snapshot = auditLogsCollection()?.get()?.await() ?: return emptyList()
            snapshot.documents.mapNotNull { doc ->
                doc.data?.let { AuditLog.fromMap(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Firestore fetchAuditLogs skipped: ${e.message}")
            emptyList()
        }
    }

    // --- Settings Operations ---
    suspend fun saveSettingsToFirestore(settings: AcademySettings): Boolean {
        if (!isReady()) return false
        return try {
            settingsCollection()?.document(settings.id)?.set(settings.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveSettings skipped: ${e.message}")
            false
        }
    }

    suspend fun getSettingsFromFirestore(): AcademySettings? {
        if (!isReady()) return null
        return try {
            val doc = settingsCollection()?.document("current_settings")?.get()?.await()
            if (doc != null && doc.exists()) {
                val data = doc.data
                if (data != null) AcademySettings.fromMap(data) else null
            } else null
        } catch (e: Exception) {
            Log.w(tag, "Firestore getSettings skipped: ${e.message}")
            null
        }
    }

    // --- Reminders Operations ---
    suspend fun saveReminderToFirestore(reminder: Reminder): Boolean {
        if (!isReady()) return false
        return try {
            remindersCollection()?.document(reminder.id)?.set(reminder.toMap(), SetOptions.merge())?.await()
            true
        } catch (e: Exception) {
            Log.w(tag, "Firestore saveReminder skipped: ${e.message}")
            false
        }
    }
}
