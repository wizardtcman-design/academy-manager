package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.model.AcademyNotification
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.BankAccount
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Expense
import com.example.data.model.ExpenseCategory
import com.example.data.model.Installment
import com.example.data.model.MessageLog
import com.example.data.model.MessageTemplate
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.model.Reminder
import com.example.data.model.Student
import com.example.data.remote.FirebaseManager
import com.example.data.util.FinanceUtil
import com.example.data.util.PersianDateUtil
import com.example.data.util.StudentFinancialSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class SyncResultSummary(
    val isSuccess: Boolean = true,
    val pushedCount: Int = 0,
    val pulledCount: Int = 0,
    val message: String = "",
    val timestampShamsi: String = "",
    val timestampEpoch: Long = System.currentTimeMillis()
)

data class DashboardStats(
    val totalStudents: Int = 0,
    val totalCourses: Int = 0,
    val todayReceipts: Long = 0L,
    val thisWeekReceipts: Long = 0L,
    val thisMonthReceipts: Long = 0L,
    val thisMonthExpenses: Long = 0L,
    val thisMonthProfit: Long = 0L,
    val totalDebt: Long = 0L,
    val dueTodayCount: Int = 0,
    val dueTomorrowCount: Int = 0,
    val overdueCount: Int = 0,
    val followUpRequiredCount: Int = 0
) {
    val dueInstallmentsCount: Int get() = dueTodayCount
    val overdueInstallmentsCount: Int get() = overdueCount
}

data class ProfitAndLossReport(
    val periodTitle: String = "",
    val grossIncome: Long = 0L, // sum of valid payments - sum of refunds
    val totalReceipts: Long = 0L, // sum of valid payments
    val totalRefunds: Long = 0L, // sum of refunds
    val totalExpenses: Long = 0L, // sum of active expenses
    val netProfit: Long = 0L, // grossIncome - totalExpenses
    val profitMarginPercent: Int = 0,
    val expenseByCategory: Map<String, Long> = emptyMap(),
    val incomeByMethod: Map<String, Long> = emptyMap(),
    val expensesByMethod: Map<String, Long> = emptyMap()
)

data class AccountBalanceInfo(
    val accountId: String,
    val title: String,
    val subtitle: String,
    val isPettyCash: Boolean = false,
    val totalInflows: Long = 0L,
    val totalOutflows: Long = 0L,
    val currentBalance: Long = 0L
)

data class FinancialReportData(
    val periodTitle: String = "",
    val totalReceipts: Long = 0L,
    val transactionsCount: Int = 0,
    val payingStudentsCount: Int = 0,
    val totalCurrentDebt: Long = 0L,
    val collectedPercentage: Int = 0,
    val receiptsByMethod: Map<String, Long> = emptyMap(),
    val receiptsByAccount: Map<String, Long> = emptyMap(),
    val dailyTrend: List<Pair<String, Long>> = emptyList()
)

data class CalendarDayEvents(
    val jalaliDate: PersianDateUtil.JalaliDate,
    val dueInstallments: List<Installment> = emptyList(),
    val payments: List<Payment> = emptyList(),
    val overdueInstallments: List<Installment> = emptyList()
)

data class DebtorStudentItem(
    val student: Student,
    val courseTitle: String,
    val totalTuition: Long,
    val totalPaid: Long,
    val remainingDebt: Long,
    val totalInstallments: Int,
    val paidInstallments: Int,
    val remainingInstallments: Int,
    val nearestDueDateShamsi: String,
    val overdueDays: Int
)

class AcademyRepository(
    private val database: AppDatabase,
    private val firebaseManager: FirebaseManager
) {
    private val studentDao = database.studentDao()
    private val courseDao = database.courseDao()
    private val settingsDao = database.settingsDao()
    private val auditLogDao = database.auditLogDao()
    private val enrollmentDao = database.enrollmentDao()
    private val installmentDao = database.installmentDao()
    private val paymentDao = database.paymentDao()
    private val notificationDao = database.notificationDao()
    val reminderDao = database.reminderDao()
    private val bankAccountDao = database.bankAccountDao()
    private val messageTemplateDao = database.messageTemplateDao()
    private val messageLogDao = database.messageLogDao()
    private val expenseDao = database.expenseDao()
    private val refundDao = database.refundDao()
    private val expenseCategoryDao = database.expenseCategoryDao()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Initialize default settings
            val current = settingsDao.getSettingsDirect()
            if (current == null) {
                val remote = firebaseManager.getSettingsFromFirestore()
                if (remote != null) {
                    settingsDao.insertOrUpdate(remote)
                } else {
                    val defaultSettings = AcademySettings()
                    settingsDao.insertOrUpdate(defaultSettings)
                    firebaseManager.saveSettingsToFirestore(defaultSettings)
                }
            }

            // 2. Initialize default templates
            val templates = messageTemplateDao.getAllTemplates().firstOrNull() ?: emptyList()
            if (templates.isEmpty()) {
                messageTemplateDao.insertTemplates(MessageTemplate.getDefaultTemplates())
            } else {
                val existingTypes = templates.map { it.type }.toSet()
                val missingTemplates = MessageTemplate.getDefaultTemplates().filter { it.type !in existingTypes }
                if (missingTemplates.isNotEmpty()) {
                    messageTemplateDao.insertTemplates(missingTemplates)
                }
            }

            // 3. Initialize default bank card if none exist
            val accounts = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
            if (accounts.isEmpty()) {
                val defaultCard = BankAccount(
                    id = "default_card_1",
                    bankName = "بانک ملت",
                    type = "CARD",
                    accountTitle = "کارت اصلی واریز شهریه",
                    cardNumber = "6104337899991234",
                    maskedNumber = "6104 - **** - **** - 1234",
                    shabaNumber = "IR120120000000001234567890",
                    accountHolder = "مدیریت آموزشگاه",
                    isDefault = true,
                    isActive = true
                )
                bankAccountDao.insertAccount(defaultCard)
            }

            // 4. Initialize default expense categories
            val categories = expenseCategoryDao.getAllCategories().firstOrNull() ?: emptyList()
            if (categories.isEmpty()) {
                val defaultCats = Expense.DEFAULT_CATEGORIES.map { catName ->
                    ExpenseCategory(name = catName, isDefault = true)
                }
                expenseCategoryDao.insertCategories(defaultCats)
            }
        }
    }

    // ==========================================
    // 1. STUDENTS
    // ==========================================
    val allStudents: Flow<List<Student>> = studentDao.getAllStudents()

    fun searchStudents(query: String): Flow<List<Student>> = studentDao.searchStudents(query)

    suspend fun getStudentById(id: String): Student? = studentDao.getStudentById(id)

    suspend fun getStudentByTelegramChatId(chatId: String): Student? = studentDao.getStudentByTelegramChatId(chatId)

    suspend fun getStudentByTelegramToken(token: String): Student? = studentDao.getStudentByTelegramToken(token)

    suspend fun getAllStudentsDirect(): List<Student> = studentDao.getAllStudentsDirect()

    suspend fun disconnectStudentTelegram(studentId: String): Result<Unit> {
        val student = studentDao.getStudentById(studentId) ?: return Result.failure(Exception("هنرجو یافت نشد."))
        val oldChatId = student.telegramChatId
        val updated = student.copy(
            telegramEnabled = false,
            telegramChatId = "",
            telegramUsername = "",
            telegramLinkedAt = 0L,
            updatedAt = System.currentTimeMillis()
        )
        studentDao.updateStudent(updated)
        firebaseManager.saveStudentToFirestore(updated)

        logAction(
            entityType = "STUDENT",
            entityId = student.id,
            actionType = "TELEGRAM_DISCONNECTED",
            previousValue = "Chat ID: $oldChatId",
            newValue = "Disconnected",
            description = "قطع اتصال تلگرام هنرجو: ${student.fullName} (شناسه چت قبلی: $oldChatId)"
        )
        return Result.success(Unit)
    }

    suspend fun addStudent(
        firstName: String,
        lastName: String,
        phone: String,
        nationalCode: String = "",
        address: String = "",
        notes: String = ""
    ): Result<Student> {
        if (firstName.isBlank() || lastName.isBlank()) {
            return Result.failure(Exception("نام و نام خانوادگی الزامی است."))
        }
        if (phone.isBlank()) {
            return Result.failure(Exception("شماره موبایل الزامی است."))
        }

        val totalStudentsCount = studentDao.getStudentsCount().firstOrNull() ?: 0
        val studentCode = FinanceUtil.generateStudentCode(totalStudentsCount + 1)
        val shamsiDate = PersianDateUtil.currentShamsiString()

        val student = Student(
            id = UUID.randomUUID().toString(),
            studentCode = studentCode,
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            phone = phone.trim(),
            nationalCode = nationalCode.trim(),
            address = address.trim(),
            notes = notes.trim(),
            registrationDateShamsi = shamsiDate,
            registrationDateEpoch = System.currentTimeMillis(),
            status = "ACTIVE",
            isActive = true,
            isSampleData = false
        )

        studentDao.insertStudent(student)
        Log.d("AcademyRepository", "[TRACE 2] Student saved in Room: ${student.id} (${student.fullName}, code: ${student.studentCode})")

        val firestoreSuccess = firebaseManager.saveStudentToFirestore(student)
        Log.d("AcademyRepository", "[TRACE 7/8] Direct firestore saveStudentToFirestore returned: $firestoreSuccess")

        logAction(
            entityType = "STUDENT",
            entityId = student.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "${student.fullName} (${student.studentCode})",
            description = "ثبت هنرجوی جدید: ${student.fullName} با کد ${student.studentCode}"
        )

        return Result.success(student)
    }

    suspend fun updateStudent(student: Student): Result<Unit> {
        val updated = student.copy(updatedAt = System.currentTimeMillis())
        studentDao.updateStudent(updated)
        firebaseManager.saveStudentToFirestore(updated)

        logAction(
            entityType = "STUDENT",
            entityId = student.id,
            actionType = "UPDATE",
            previousValue = "",
            newValue = student.fullName,
            description = "ویرایش اطلاعات هنرجو: ${student.fullName}"
        )

        return Result.success(Unit)
    }

    suspend fun deleteStudent(student: Student): Result<Unit> {
        studentDao.deleteStudent(student)
        firebaseManager.deleteStudentFromFirestore(student.id)

        logAction(
            entityType = "STUDENT",
            entityId = student.id,
            actionType = "DELETE",
            previousValue = student.fullName,
            newValue = "",
            description = "حذف هنرجو: ${student.fullName}"
        )

        return Result.success(Unit)
    }

    // ==========================================
    // 2. COURSES
    // ==========================================
    val allCourses: Flow<List<Course>> = courseDao.getAllCourses()

    suspend fun addCourse(
        title: String,
        durationMonths: Int = 3,
        tuitionFee: Long,
        installmentCount: Int = 3,
        description: String = "",
        isActive: Boolean = true
    ): Result<Course> {
        if (title.isBlank()) {
            return Result.failure(Exception("نام دوره الزامی است."))
        }
        if (tuitionFee <= 0) {
            return Result.failure(Exception("شهریه دوره باید بزرگتر از صفر باشد."))
        }

        val course = Course(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            durationMonths = if (durationMonths > 0) durationMonths else 3,
            tuitionFee = tuitionFee,
            installmentCount = if (installmentCount > 0) installmentCount else 3,
            description = description.trim(),
            isActive = isActive,
            isSampleData = false
        )

        courseDao.insertCourse(course)
        firebaseManager.saveCourseToFirestore(course)

        logAction(
            entityType = "COURSE",
            entityId = course.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "${course.title} (${course.tuitionFee})",
            description = "تعریف دوره جدید: ${course.title}"
        )

        return Result.success(course)
    }

    suspend fun updateCourse(course: Course): Result<Unit> {
        courseDao.updateCourse(course)
        firebaseManager.saveCourseToFirestore(course)

        logAction(
            entityType = "COURSE",
            entityId = course.id,
            actionType = "UPDATE",
            previousValue = "",
            newValue = course.title,
            description = "ویرایش مشخصات دوره: ${course.title}"
        )

        return Result.success(Unit)
    }

    suspend fun deleteCourse(course: Course): Result<Unit> {
        courseDao.deleteCourse(course)
        firebaseManager.deleteCourseFromFirestore(course.id)

        logAction(
            entityType = "COURSE",
            entityId = course.id,
            actionType = "DELETE",
            previousValue = course.title,
            newValue = "",
            description = "حذف دوره آموزشی: ${course.title}"
        )

        return Result.success(Unit)
    }

    // ==========================================
    // 3. ENROLLMENTS & INSTALLMENTS CREATION
    // ==========================================
    val allEnrollments: Flow<List<Enrollment>> = enrollmentDao.getAllEnrollments()

    fun getEnrollmentsForStudent(studentId: String): Flow<List<Enrollment>> =
        enrollmentDao.getEnrollmentsForStudent(studentId)

    val allInstallments: Flow<List<Installment>> = installmentDao.getAllInstallments()

    fun getInstallmentsForStudent(studentId: String): Flow<List<Installment>> =
        installmentDao.getInstallmentsForStudent(studentId)

    /**
     * Enrolls student in course, calculates custom installments and handles first installment date
     */
    suspend fun enrollStudentInCourse(
        student: Student,
        course: Course,
        startDateShamsi: String,
        discount: Long = 0L,
        installmentCount: Int = 3,
        customInstallmentAmounts: List<Long> = emptyList(),
        firstInstallmentDateShamsi: String,
        firstInstallmentPaidNow: Boolean,
        notes: String = ""
    ): Result<Enrollment> {
        val totalFee = course.tuitionFee
        val finalFee = (totalFee - discount).coerceAtLeast(0L)

        if (finalFee <= 0) {
            return Result.failure(Exception("شهریه نهایی نامعتبر است."))
        }

        // Validate installment amounts sum == finalFee
        val count = installmentCount.coerceIn(1, 12)
        val validAmounts = if (customInstallmentAmounts.size == count && customInstallmentAmounts.sum() == finalFee) {
            customInstallmentAmounts
        } else {
            val base = finalFee / count
            val remainder = finalFee % count
            List(count) { idx -> if (idx == 0) base + remainder else base }
        }

        if (validAmounts.sum() != finalFee) {
            return Result.failure(Exception("مجموع مبالغ اقساط (${PersianDateUtil.formatCurrency(validAmounts.sum())}) با شهریه کل (${PersianDateUtil.formatCurrency(finalFee)}) برابر نیست."))
        }

        // Calculate end date based on course duration in months
        val startParts = startDateShamsi.split("/").mapNotNull { it.toIntOrNull() }
        val startJalali = if (startParts.size == 3) {
            PersianDateUtil.JalaliDate(startParts[0], startParts[1], startParts[2])
        } else {
            PersianDateUtil.currentJalali()
        }
        val endJalali = startJalali.addMonths(course.durationMonths)
        val endDateShamsi = endJalali.format()

        val enrollmentId = UUID.randomUUID().toString()
        val enrollment = Enrollment(
            id = enrollmentId,
            studentId = student.id,
            studentName = student.fullName,
            courseId = course.id,
            courseTitle = course.title,
            registrationDateShamsi = PersianDateUtil.currentShamsiString(),
            registrationDateEpoch = System.currentTimeMillis(),
            startDateShamsi = startDateShamsi,
            endDateShamsi = endDateShamsi,
            totalFee = totalFee,
            discount = discount,
            finalFee = finalFee,
            installmentCount = count,
            firstInstallmentPaidOnDate = firstInstallmentPaidNow,
            firstInstallmentDateShamsi = firstInstallmentDateShamsi,
            firstInstallmentDateEpoch = System.currentTimeMillis(),
            status = "ACTIVE",
            notes = notes
        )

        enrollmentDao.insertEnrollment(enrollment)
        firebaseManager.saveEnrollmentToFirestore(enrollment)

        // Generate Installments
        val installments = FinanceUtil.buildInstallmentsSchedule(
            enrollmentId = enrollmentId,
            studentId = student.id,
            studentName = student.fullName,
            courseId = course.id,
            courseTitle = course.title,
            totalFee = finalFee,
            installmentCount = count,
            installmentAmounts = validAmounts,
            firstInstallmentDateShamsi = firstInstallmentDateShamsi,
            firstInstallmentPaidNow = firstInstallmentPaidNow
        )

        installmentDao.insertInstallments(installments)
        installments.forEach { installment ->
            firebaseManager.saveInstallmentToFirestore(installment)
        }

        // If first installment was paid now, create an independent Payment Transaction
        if (firstInstallmentPaidNow && installments.isNotEmpty()) {
            val firstInst = installments.first()
            val totalPayments = paymentDao.getAllPayments().firstOrNull()?.size ?: 0
            val trxId = FinanceUtil.generateTransactionId(totalPayments + 1)

            val payment = Payment(
                id = UUID.randomUUID().toString(),
                transactionId = trxId,
                paymentCode = trxId,
                studentId = student.id,
                studentName = student.fullName,
                enrollmentId = enrollmentId,
                installmentId = firstInst.id,
                installmentNumber = 1,
                amount = firstInst.amount,
                paymentDateShamsi = PersianDateUtil.currentShamsiString(),
                paymentDateEpoch = System.currentTimeMillis(),
                paymentTime = "همزمان با ثبت‌نام",
                paymentMethod = "TRANSFER",
                destinationAccountId = "",
                destinationAccountTitle = "حساب پیش‌فرض آموزشگاه",
                trackingNumber = "",
                receiptImage = "",
                notes = "پرداخت همزمان با ثبت‌نام دوره",
                recordedBy = "مدیر سیستم"
            )

            paymentDao.insertPayment(payment)
            firebaseManager.savePaymentToFirestore(payment)
        }

        logAction(
            entityType = "ENROLLMENT",
            entityId = enrollment.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "${student.fullName} در ${course.title}",
            description = "ثبت‌نام هنرجو ${student.fullName} در دوره ${course.title} با $count قسط"
        )

        return Result.success(enrollment)
    }

    /**
     * Soft cancels an enrollment and marks pending future installments as CANCELLED
     */
    suspend fun cancelEnrollment(enrollmentId: String, reason: String): Result<Unit> {
        val enrollment = enrollmentDao.getEnrollmentById(enrollmentId)
            ?: return Result.failure(Exception("ثبت‌نام مورد نظر یافت نشد."))

        val currentShamsi = PersianDateUtil.currentShamsiString()
        val updatedEnrollment = enrollment.copy(
            status = "CANCELLED",
            notes = if (enrollment.notes.isNotBlank()) "${enrollment.notes}\n[انصراف در تاریخ $currentShamsi به دلیل: $reason]" else "[انصراف در تاریخ $currentShamsi به دلیل: $reason]"
        )
        enrollmentDao.updateEnrollment(updatedEnrollment)
        firebaseManager.saveEnrollmentToFirestore(updatedEnrollment)

        // Cancel unpaid or pending installments for this enrollment
        val installments = installmentDao.getInstallmentsForEnrollment(enrollmentId).firstOrNull() ?: emptyList()
        installments.forEach { inst ->
            if (inst.status != "PAID") {
                val updatedInst = inst.copy(status = "CANCELLED")
                installmentDao.updateInstallment(updatedInst)
                firebaseManager.saveInstallmentToFirestore(updatedInst)
            }
        }

        logAction(
            entityType = "ENROLLMENT",
            entityId = enrollment.id,
            actionType = "CANCEL",
            previousValue = "وضعیت: فعال",
            newValue = "وضعیت: لغو / انصراف",
            reason = reason,
            description = "لغو ثبت‌نام هنرجو ${enrollment.studentName} در دوره ${enrollment.courseTitle} به دلیل: $reason"
        )

        return Result.success(Unit)
    }

    // ==========================================
    // 4. PAYMENTS & TRANSACTIONS
    // ==========================================
    val allPayments: Flow<List<Payment>> = paymentDao.getAllPayments()

    fun getPaymentsForStudent(studentId: String): Flow<List<Payment>> =
        paymentDao.getPaymentsForStudent(studentId)

    fun getPaymentsForInstallment(installmentId: String): Flow<List<Payment>> =
        paymentDao.getPaymentsForInstallment(installmentId)

    /**
     * Records an independent Payment transaction, supporting partial payments, updating installment and student settlement status
     */
    suspend fun recordPayment(
        studentId: String,
        installmentId: String,
        amount: Long,
        paymentDateShamsi: String,
        paymentTime: String,
        paymentMethod: String,
        destinationAccountId: String,
        destinationAccountTitle: String,
        trackingNumber: String = "",
        receiptImage: String = "",
        notes: String = ""
    ): Result<Payment> {
        if (amount <= 0) {
            return Result.failure(Exception("مبلغ پرداختی باید بزرگتر از صفر باشد."))
        }

        val student = studentDao.getStudentById(studentId)
            ?: return Result.failure(Exception("هنرجو یافت نشد."))
        val installment = installmentDao.getInstallmentById(installmentId)
            ?: return Result.failure(Exception("قسط یافت نشد."))

        val totalPayments = paymentDao.getAllPayments().firstOrNull()?.size ?: 0
        val trxId = FinanceUtil.generateTransactionId(totalPayments + 1)
        val nowEpoch = System.currentTimeMillis()

        val payment = Payment(
            id = UUID.randomUUID().toString(),
            transactionId = trxId,
            paymentCode = trxId,
            studentId = student.id,
            studentName = student.fullName,
            enrollmentId = installment.enrollmentId,
            installmentId = installment.id,
            installmentNumber = installment.installmentNumber,
            amount = amount,
            paymentDateShamsi = paymentDateShamsi,
            paymentDateEpoch = nowEpoch,
            paymentTime = paymentTime.ifBlank { "12:00" },
            paymentMethod = paymentMethod,
            destinationAccountId = destinationAccountId,
            destinationAccountTitle = destinationAccountTitle,
            trackingNumber = trackingNumber.trim(),
            receiptImage = receiptImage.trim(),
            notes = notes.trim(),
            recordedBy = "مدیر آموزشگاه"
        )

        paymentDao.insertPayment(payment)
        firebaseManager.savePaymentToFirestore(payment)

        // Update Installment
        val currentInstallmentPayments = paymentDao.getPaymentsForInstallment(installment.id).firstOrNull() ?: listOf(payment)
        val newPaidTotal = currentInstallmentPayments.filter { !it.isReversed }.sumOf { it.amount }
        val newRemaining = (installment.amount - newPaidTotal).coerceAtLeast(0L)
        val newStatus = when {
            newPaidTotal >= installment.amount -> "PAID"
            newPaidTotal > 0 -> "PARTIAL_PAID"
            else -> "PENDING"
        }

        val updatedInstallment = installment.copy(
            paidAmount = newPaidTotal,
            remainingAmount = newRemaining,
            status = newStatus,
            fullPaidDateShamsi = if (newStatus == "PAID") paymentDateShamsi else installment.fullPaidDateShamsi,
            fullPaidDateEpoch = if (newStatus == "PAID") nowEpoch else installment.fullPaidDateEpoch
        )

        installmentDao.updateInstallment(updatedInstallment)
        firebaseManager.saveInstallmentToFirestore(updatedInstallment)

        // Update Student status if completely settled
        val allStudentEnrollments = enrollmentDao.getEnrollmentsForStudent(student.id).firstOrNull() ?: emptyList()
        val allStudentPayments = paymentDao.getPaymentsForStudent(student.id).firstOrNull() ?: emptyList()
        val studentSummary = FinanceUtil.calculateStudentFinancials(
            studentId = student.id,
            enrollments = allStudentEnrollments,
            installments = listOf(updatedInstallment),
            payments = allStudentPayments
        )

        if (studentSummary.remainingDebt == 0L && studentSummary.totalTuition > 0) {
            val updatedStudent = student.copy(status = "SETTLED")
            studentDao.updateStudent(updatedStudent)
            firebaseManager.saveStudentToFirestore(updatedStudent)
        }

        // In-app Notification
        val notif = AcademyNotification(
            id = UUID.randomUUID().toString(),
            title = "ثبت دریافت شهریه",
            message = "مبلغ ${PersianDateUtil.formatCurrency(amount)} از هنرجو ${student.fullName} بابت قسط شماره ${installment.installmentNumber} ثبت شد.",
            type = "PAYMENT_RECEIVED",
            dateShamsi = PersianDateUtil.currentShamsiString(),
            dateEpoch = nowEpoch,
            targetScreen = "PAYMENTS"
        )
        notificationDao.insertNotification(notif)
        firebaseManager.saveNotificationToFirestore(notif)

        // Audit Log
        logAction(
            entityType = "PAYMENT",
            entityId = payment.id,
            actionType = "CREATE",
            previousValue = "مانده قبلی قسط: ${PersianDateUtil.formatCurrency(installment.remainingAmount)}",
            newValue = "پرداخت: ${PersianDateUtil.formatCurrency(amount)} (کد: $trxId)",
            description = "ثبت تراکنش پرداخت برای ${student.fullName} بابت قسط ${installment.installmentNumber}"
        )

        return Result.success(payment)
    }

    /**
     * Reverses/Corrects a payment transaction with mandatory reason and audit log
     */
    suspend fun reversePayment(
        payment: Payment,
        reason: String
    ): Result<Unit> {
        if (payment.isReversed) {
            return Result.failure(Exception("این تراکنش قبلاً لغو/اصلاح شده است."))
        }
        if (reason.isBlank()) {
            return Result.failure(Exception("ثبت دلیل اصلاح/لغو الزامی است."))
        }

        val nowEpoch = System.currentTimeMillis()
        val updatedPayment = payment.copy(
            isReversed = true,
            reversalReason = reason.trim(),
            reversedAtEpoch = nowEpoch,
            reversedBy = "مدیر آموزشگاه"
        )

        paymentDao.updatePayment(updatedPayment)
        firebaseManager.savePaymentToFirestore(updatedPayment)

        // Recalculate Installment
        val installment = installmentDao.getInstallmentById(payment.installmentId)
        if (installment != null) {
            val remainingPayments = paymentDao.getPaymentsForInstallment(installment.id).firstOrNull() ?: emptyList()
            val newPaidTotal = remainingPayments.filter { !it.isReversed }.sumOf { it.amount }
            val newRemaining = (installment.amount - newPaidTotal).coerceAtLeast(0L)
            val newStatus = when {
                newPaidTotal >= installment.amount -> "PAID"
                newPaidTotal > 0 -> "PARTIAL_PAID"
                else -> "PENDING"
            }

            val updatedInstallment = installment.copy(
                paidAmount = newPaidTotal,
                remainingAmount = newRemaining,
                status = newStatus,
                fullPaidDateShamsi = if (newStatus == "PAID") installment.fullPaidDateShamsi else "",
                fullPaidDateEpoch = if (newStatus == "PAID") installment.fullPaidDateEpoch else 0L
            )
            installmentDao.updateInstallment(updatedInstallment)
            firebaseManager.saveInstallmentToFirestore(updatedInstallment)
        }

        logAction(
            entityType = "PAYMENT",
            entityId = payment.id,
            actionType = "REVERSE",
            previousValue = "مبلغ: ${PersianDateUtil.formatCurrency(payment.amount)}",
            newValue = "لغو شد",
            reason = reason,
            description = "اصلاح و لغو تراکنش ${payment.transactionId} به دلیل: $reason"
        )

        return Result.success(Unit)
    }

    // ==========================================
    // 5. BANK ACCOUNTS & CARDS
    // ==========================================
    val allBankAccounts: Flow<List<BankAccount>> = bankAccountDao.getAllAccounts()

    val activeBankAccounts: Flow<List<BankAccount>> = bankAccountDao.getActiveAccounts()

    suspend fun addBankAccount(
        bankName: String,
        type: String = "CARD",
        accountTitle: String,
        cardNumber: String,
        shabaNumber: String = "",
        accountNumber: String = "",
        accountHolder: String = "",
        isDefault: Boolean = false
    ): Result<BankAccount> {
        if (bankName.isBlank() || accountTitle.isBlank()) {
            return Result.failure(Exception("نام بانک و عنوان حساب الزامی است."))
        }

        val allExisting = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
        val shouldBeDefault = isDefault || allExisting.isEmpty()

        if (shouldBeDefault) {
            allExisting.forEach { existing ->
                if (existing.isDefault) {
                    bankAccountDao.updateAccount(existing.copy(isDefault = false))
                }
            }
        }

        val masked = BankAccount.maskCardNumber(cardNumber)
        val account = BankAccount(
            id = UUID.randomUUID().toString(),
            bankName = bankName.trim(),
            type = type,
            accountTitle = accountTitle.trim(),
            cardNumber = cardNumber.trim(),
            maskedNumber = masked,
            shabaNumber = shabaNumber.trim(),
            accountNumber = accountNumber.trim(),
            accountHolder = accountHolder.trim(),
            isDefault = shouldBeDefault,
            isActive = true
        )

        bankAccountDao.insertAccount(account)

        logAction(
            entityType = "BANK_ACCOUNT",
            entityId = account.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "${account.accountTitle} ($masked)",
            description = "افزودن کارت/حساب آموزشگاه: ${account.accountTitle}"
        )

        return Result.success(account)
    }

    suspend fun updateBankAccount(account: BankAccount): Result<Unit> {
        val masked = BankAccount.maskCardNumber(account.cardNumber)
        if (account.isDefault) {
            val allExisting = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
            allExisting.forEach { existing ->
                if (existing.id != account.id && existing.isDefault) {
                    bankAccountDao.updateAccount(existing.copy(isDefault = false))
                }
            }
        }
        val updated = account.copy(maskedNumber = masked)
        bankAccountDao.updateAccount(updated)
        logAction(
            entityType = "BANK_ACCOUNT",
            entityId = account.id,
            actionType = "UPDATE",
            previousValue = "",
            newValue = "${account.accountTitle} ($masked)",
            description = "ویرایش کارت/حساب آموزشگاه: ${account.accountTitle}"
        )
        return Result.success(Unit)
    }

    suspend fun setDefaultBankAccount(account: BankAccount): Result<Unit> {
        val allExisting = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
        allExisting.forEach { existing ->
            val isTarget = existing.id == account.id
            if (existing.isDefault != isTarget) {
                bankAccountDao.updateAccount(existing.copy(isDefault = isTarget))
            }
        }
        return Result.success(Unit)
    }

    suspend fun deleteBankAccount(account: BankAccount): Result<Unit> {
        bankAccountDao.deleteAccount(account)
        // If deleted was default, make another one default
        if (account.isDefault) {
            val remaining = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
            if (remaining.isNotEmpty()) {
                bankAccountDao.updateAccount(remaining.first().copy(isDefault = true))
            }
        }
        logAction(
            entityType = "BANK_ACCOUNT",
            entityId = account.id,
            actionType = "DELETE",
            previousValue = account.accountTitle,
            newValue = "",
            description = "حذف حساب آموزشگاه: ${account.accountTitle}"
        )
        return Result.success(Unit)
    }

    // ==========================================
    // 6. EXPENSES & EXPENSE CATEGORIES
    // ==========================================
    val allExpenses: Flow<List<Expense>> = expenseDao.getAllExpenses()
    val activeExpenses: Flow<List<Expense>> = expenseDao.getAllActiveExpenses()
    val allExpenseCategories: Flow<List<ExpenseCategory>> = expenseCategoryDao.getAllCategories()

    suspend fun addExpense(
        title: String,
        amount: Long,
        dateShamsi: String,
        category: String,
        paymentMethod: String,
        sourceAccountId: String,
        sourceAccountTitle: String,
        trackingNumber: String = "",
        notes: String = "",
        receiptImage: String = ""
    ): Result<Expense> {
        if (title.isBlank()) {
            return Result.failure(Exception("عنوان هزینه الزامی است."))
        }
        if (amount <= 0) {
            return Result.failure(Exception("مبلغ هزینه باید بزرگتر از صفر باشد."))
        }

        val totalExpenses = expenseDao.getAllExpenses().firstOrNull()?.size ?: 0
        val expCode = "EXP-1405-${String.format("%06d", totalExpenses + 1)}"

        val dateEpoch = PersianDateUtil.parseShamsiToEpoch(dateShamsi)

        val expense = Expense(
            id = UUID.randomUUID().toString(),
            expenseCode = expCode,
            title = title.trim(),
            amount = amount,
            dateShamsi = dateShamsi.trim(),
            dateEpoch = dateEpoch,
            category = category.trim().ifEmpty { "سایر" },
            paymentMethod = paymentMethod,
            sourceAccountId = sourceAccountId,
            sourceAccountTitle = sourceAccountTitle.trim(),
            trackingNumber = trackingNumber.trim(),
            notes = notes.trim(),
            receiptImage = receiptImage,
            recordedBy = "مدیر آموزشگاه",
            isDeleted = false
        )

        expenseDao.insertExpense(expense)
        firebaseManager.saveExpenseToFirestore(expense)

        logAction(
            entityType = "EXPENSE",
            entityId = expense.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "${expense.title} - ${PersianDateUtil.formatCurrency(expense.amount)}",
            description = "ثبت هزینه جدید: ${expense.title} به مبلغ ${PersianDateUtil.formatCurrency(expense.amount)} تومان"
        )

        return Result.success(expense)
    }

    suspend fun updateExpense(expense: Expense): Result<Unit> {
        expenseDao.updateExpense(expense)
        firebaseManager.saveExpenseToFirestore(expense)

        logAction(
            entityType = "EXPENSE",
            entityId = expense.id,
            actionType = "UPDATE",
            previousValue = "",
            newValue = "${expense.title} - ${PersianDateUtil.formatCurrency(expense.amount)}",
            description = "ویرایش هزینه: ${expense.title}"
        )

        return Result.success(Unit)
    }

    suspend fun softDeleteExpense(expense: Expense, reason: String): Result<Unit> {
        val updated = expense.copy(
            isDeleted = true,
            deletedAt = System.currentTimeMillis(),
            deleteReason = reason.trim()
        )
        expenseDao.updateExpense(updated)
        firebaseManager.saveExpenseToFirestore(updated)

        logAction(
            entityType = "EXPENSE",
            entityId = expense.id,
            actionType = "DELETE",
            previousValue = "${expense.title} - ${PersianDateUtil.formatCurrency(expense.amount)}",
            newValue = "حذف شد (سافت دیلیت)",
            reason = reason,
            description = "حذف هزینه ${expense.expenseCode} (${expense.title}) به دلیل: $reason"
        )

        return Result.success(Unit)
    }

    suspend fun addExpenseCategory(name: String): Result<ExpenseCategory> {
        if (name.isBlank()) return Result.failure(Exception("نام دسته‌بندی الزامی است."))
        val cat = ExpenseCategory(name = name.trim(), isDefault = false)
        expenseCategoryDao.insertCategory(cat)
        return Result.success(cat)
    }

    // ==========================================
    // 7. REFUNDS (استرداد وجه)
    // ==========================================
    val allRefunds: Flow<List<Refund>> = refundDao.getAllRefunds()

    fun getRefundsForStudent(studentId: String): Flow<List<Refund>> =
        refundDao.getRefundsForStudent(studentId)

    fun getRefundsForEnrollment(enrollmentId: String): Flow<List<Refund>> =
        refundDao.getRefundsForEnrollment(enrollmentId)

    suspend fun recordRefund(
        studentId: String,
        studentName: String,
        enrollmentId: String,
        courseTitle: String,
        amount: Long,
        dateShamsi: String,
        paymentMethod: String,
        sourceAccountId: String,
        sourceAccountTitle: String,
        trackingNumber: String = "",
        reason: String
    ): Result<Refund> {
        if (amount <= 0) {
            return Result.failure(Exception("مبلغ استرداد باید بزرگتر از صفر باشد."))
        }
        if (reason.isBlank()) {
            return Result.failure(Exception("دلیل استرداد وجه الزامی است."))
        }

        val totalRefunds = refundDao.getAllRefunds().firstOrNull()?.size ?: 0
        val refCode = "REF-1405-${String.format("%06d", totalRefunds + 1)}"
        val dateEpoch = PersianDateUtil.parseShamsiToEpoch(dateShamsi)

        val refund = Refund(
            id = UUID.randomUUID().toString(),
            refundCode = refCode,
            studentId = studentId,
            studentName = studentName,
            enrollmentId = enrollmentId,
            courseTitle = courseTitle,
            amount = amount,
            dateShamsi = dateShamsi.trim(),
            dateEpoch = dateEpoch,
            paymentMethod = paymentMethod,
            sourceAccountId = sourceAccountId,
            sourceAccountTitle = sourceAccountTitle.trim(),
            trackingNumber = trackingNumber.trim(),
            reason = reason.trim(),
            recordedBy = "مدیر آموزشگاه"
        )

        refundDao.insertRefund(refund)
        firebaseManager.saveRefundToFirestore(refund)

        logAction(
            entityType = "REFUND",
            entityId = refund.id,
            actionType = "CREATE",
            previousValue = "",
            newValue = "استرداد: ${PersianDateUtil.formatCurrency(amount)} به ${studentName}",
            reason = reason,
            description = "ثبت استرداد وجه $refCode به مبلغ ${PersianDateUtil.formatCurrency(amount)} تومان به هنرجو $studentName"
        )

        return Result.success(refund)
    }

    // ==========================================
    // 8. PETTY CASH & RUNNING ACCOUNT BALANCES
    // ==========================================
    val accountBalances: Flow<List<AccountBalanceInfo>> = combine(
        bankAccountDao.getAllAccounts(),
        paymentDao.getAllPayments(),
        refundDao.getAllRefunds(),
        expenseDao.getAllExpenses()
    ) { accounts, payments, refunds, expenses ->
        val validPayments = payments.filter { !it.isReversed }
        val activeExpenses = expenses.filter { !it.isDeleted }

        val list = mutableListOf<AccountBalanceInfo>()

        // 1. Petty Cash (صندوق نقدی آموزشگاه)
        val cashIn = validPayments.filter { it.paymentMethod == "CASH" }.sumOf { it.amount }
        val cashRefunds = refunds.filter { it.paymentMethod == "CASH" }.sumOf { it.amount }
        val cashExpenses = activeExpenses.filter { it.paymentMethod == "CASH" }.sumOf { it.amount }
        val cashBalance = (cashIn - cashRefunds - cashExpenses)

        list.add(
            AccountBalanceInfo(
                accountId = "petty_cash",
                title = "صندوق نقدی آموزشگاه",
                subtitle = "مجموع دریافت‌های نقدی منهای پرداخت‌های نقدی",
                isPettyCash = true,
                totalInflows = cashIn,
                totalOutflows = cashRefunds + cashExpenses,
                currentBalance = cashBalance
            )
        )

        // 2. Each Bank Card / Account
        accounts.forEach { acc ->
            val accIn = validPayments.filter { it.destinationAccountId == acc.id }.sumOf { it.amount }
            val accRefunds = refunds.filter { it.sourceAccountId == acc.id }.sumOf { it.amount }
            val accExpenses = activeExpenses.filter { it.sourceAccountId == acc.id }.sumOf { it.amount }
            val accBalance = accIn - accRefunds - accExpenses

            list.add(
                AccountBalanceInfo(
                    accountId = acc.id,
                    title = acc.accountTitle,
                    subtitle = "${acc.bankName} - ${acc.maskedNumber}",
                    isPettyCash = false,
                    totalInflows = accIn,
                    totalOutflows = accRefunds + accExpenses,
                    currentBalance = accBalance
                )
            )
        }

        list
    }

    // ==========================================
    // 9. PROFIT & LOSS REPORT (گزارش سود و زیان)
    // ==========================================
    fun getProfitLossReport(period: String = "ALL"): Flow<ProfitAndLossReport> = combine(
        paymentDao.getAllPayments(),
        refundDao.getAllRefunds(),
        expenseDao.getAllActiveExpenses()
    ) { payments, refunds, expenses ->
        val currentJalali = PersianDateUtil.currentJalali()
        val todayShamsi = PersianDateUtil.currentShamsiString()
        val sevenDaysAgoEpoch = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val ninetyDaysAgoEpoch = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        val monthPrefix = String.format("%04d/%02d", currentJalali.year, currentJalali.month)
        val yearPrefix = "${currentJalali.year}/"

        val filteredPayments = payments.filter { !it.isReversed }.filter { p ->
            when (period) {
                "TODAY" -> p.paymentDateShamsi == todayShamsi
                "WEEK" -> p.paymentDateEpoch >= sevenDaysAgoEpoch
                "MONTH" -> p.paymentDateShamsi.startsWith(monthPrefix)
                "QUARTER" -> p.paymentDateEpoch >= ninetyDaysAgoEpoch
                "YEAR" -> p.paymentDateShamsi.startsWith(yearPrefix)
                else -> true
            }
        }

        val filteredRefunds = refunds.filter { r ->
            when (period) {
                "TODAY" -> r.dateShamsi == todayShamsi
                "WEEK" -> r.dateEpoch >= sevenDaysAgoEpoch
                "MONTH" -> r.dateShamsi.startsWith(monthPrefix)
                "QUARTER" -> r.dateEpoch >= ninetyDaysAgoEpoch
                "YEAR" -> r.dateShamsi.startsWith(yearPrefix)
                else -> true
            }
        }

        val filteredExpenses = expenses.filter { !it.isDeleted }.filter { e ->
            when (period) {
                "TODAY" -> e.dateShamsi == todayShamsi
                "WEEK" -> e.dateEpoch >= sevenDaysAgoEpoch
                "MONTH" -> e.dateShamsi.startsWith(monthPrefix)
                "QUARTER" -> e.dateEpoch >= ninetyDaysAgoEpoch
                "YEAR" -> e.dateShamsi.startsWith(yearPrefix)
                else -> true
            }
        }

        val periodTitle = when (period) {
            "TODAY" -> "امروز ($todayShamsi)"
            "WEEK" -> "۷ روز اخیر"
            "MONTH" -> "ماه ${PersianDateUtil.getMonthName(currentJalali.month)} ${currentJalali.year}"
            "QUARTER" -> "فصلی (۳ ماه اخیر)"
            "YEAR" -> "سال ${currentJalali.year}"
            else -> "کل دوره‌ها"
        }

        val totalReceipts = filteredPayments.sumOf { it.amount }
        val totalRefunds = filteredRefunds.sumOf { it.amount }
        val grossIncome = (totalReceipts - totalRefunds).coerceAtLeast(0L)
        val totalExpenses = filteredExpenses.sumOf { it.amount }
        val netProfit = (totalReceipts - totalRefunds) - totalExpenses
        val profitMargin = if (grossIncome > 0) ((netProfit.toDouble() / grossIncome.toDouble()) * 100).toInt() else 0

        val expenseByCategory = filteredExpenses.groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val incomeByMethod = filteredPayments.groupBy { it.paymentMethod }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val expensesByMethod = filteredExpenses.groupBy { it.paymentMethod }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        ProfitAndLossReport(
            periodTitle = periodTitle,
            grossIncome = grossIncome,
            totalReceipts = totalReceipts,
            totalRefunds = totalRefunds,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            profitMarginPercent = profitMargin,
            expenseByCategory = expenseByCategory,
            incomeByMethod = incomeByMethod,
            expensesByMethod = expensesByMethod
        )
    }

    // ==========================================
    // 6. MESSAGE TEMPLATES & LOGS
    // ==========================================
    val allTemplates: Flow<List<MessageTemplate>> = messageTemplateDao.getAllTemplates()

    val allMessageLogs: Flow<List<MessageLog>> = messageLogDao.getAllLogs()

    suspend fun updateTemplate(template: MessageTemplate): Result<Unit> {
        messageTemplateDao.updateTemplate(template.copy(updatedAt = System.currentTimeMillis()))
        return Result.success(Unit)
    }

    // ==========================================
    // 7. SETTINGS
    // ==========================================
    val settings: Flow<AcademySettings?> = settingsDao.getSettings()

    suspend fun updateSettings(
        academyName: String,
        phone: String,
        address: String,
        logoUrl: String,
        currencyUnit: String,
        reminderDaysBefore: Int,
        telegramBotToken: String = "",
        telegramBotUsername: String = "",
        telegramEnabled: Boolean = false,
        rubikaEnabled: Boolean = false
    ): Result<AcademySettings> {
        val current = settingsDao.getSettingsDirect() ?: AcademySettings()
        val updated = current.copy(
            academyName = academyName.trim().ifEmpty { "آموزشگاه کامپیوتر" },
            phone = phone.trim(),
            address = address.trim(),
            logoUrl = logoUrl.trim(),
            currencyUnit = currencyUnit.trim().ifEmpty { "تومان" },
            reminderDaysBefore = reminderDaysBefore,
            telegramBotToken = telegramBotToken.trim(),
            telegramBotUsername = telegramBotUsername.trim(),
            telegramEnabled = telegramEnabled,
            rubikaEnabled = rubikaEnabled,
            updatedAt = System.currentTimeMillis()
        )

        settingsDao.insertOrUpdate(updated)
        firebaseManager.saveSettingsToFirestore(updated)

        logAction(
            entityType = "SETTINGS",
            entityId = updated.id,
            actionType = "UPDATE",
            previousValue = current.academyName,
            newValue = updated.academyName,
            description = "بروزرسانی مشخصات و تنظیمات آموزشگاه"
        )

        return Result.success(updated)
    }

    suspend fun saveFullSettings(newSettings: AcademySettings): Result<AcademySettings> {
        val updated = newSettings.copy(updatedAt = System.currentTimeMillis())
        settingsDao.insertOrUpdate(updated)
        firebaseManager.saveSettingsToFirestore(updated)

        logAction(
            entityType = "SETTINGS",
            entityId = updated.id,
            actionType = "UPDATE",
            previousValue = "تنظیمات قبلی",
            newValue = updated.academyName,
            description = "بروزرسانی کامل تنظیمات آموزشگاه و پیام‌رسان‌ها"
        )
        return Result.success(updated)
    }

    // ==========================================
    // 8. AUDIT LOGS
    // ==========================================
    val auditLogs: Flow<List<AuditLog>> = auditLogDao.getAllLogs()

    suspend fun logAction(
        entityType: String,
        entityId: String,
        actionType: String,
        previousValue: String,
        newValue: String,
        reason: String = "",
        changedBy: String = "مدیر آموزشگاه",
        description: String
    ) {
        val log = AuditLog(
            id = UUID.randomUUID().toString(),
            entityType = entityType,
            entityId = entityId,
            actionType = actionType,
            previousValue = previousValue,
            newValue = newValue,
            reason = reason,
            changedBy = changedBy,
            timestampShamsi = PersianDateUtil.currentShamsiString(),
            timestampEpoch = System.currentTimeMillis(),
            description = description
        )
        auditLogDao.insertLog(log)
        firebaseManager.logAuditEvent(log)
    }

    suspend fun logCustomAudit(log: AuditLog) {
        auditLogDao.insertLog(log)
        firebaseManager.logAuditEvent(log)
    }

    // ==========================================
    // 9. DYNAMIC DASHBOARD STATS (Zero hardcoding)
    // ==========================================
    val dashboardStats: Flow<DashboardStats> = combine(
        studentDao.getStudentsCount(),
        courseDao.getCoursesCount(),
        paymentDao.getAllPayments(),
        installmentDao.getAllInstallments(),
        expenseDao.getAllActiveExpenses()
    ) { studentsCount, coursesCount, payments, installments, expenses ->
        val todayStart = getStartOfTodayEpoch()
        val todayEnd = getEndOfTodayEpoch()
        val weekStart = System.currentTimeMillis() - (7L * 24 * 3600 * 1000)
        val monthStart = getStartOfMonthEpoch()

        val validPayments = payments.filter { !it.isReversed }
        val todayReceipts = validPayments.filter { it.paymentDateEpoch in todayStart..todayEnd }.sumOf { it.amount }
        val weekReceipts = validPayments.filter { it.paymentDateEpoch >= weekStart }.sumOf { it.amount }
        val monthReceipts = validPayments.filter { it.paymentDateEpoch >= monthStart }.sumOf { it.amount }

        val monthExpenses = expenses.filter { it.dateEpoch >= monthStart }.sumOf { it.amount }
        val monthProfit = monthReceipts - monthExpenses

        val totalDebt = installments.filter { it.status != "PAID" && it.status != "CANCELLED" }.sumOf { it.amount - it.paidAmount }

        val dueToday = installments.count { it.status != "PAID" && it.status != "CANCELLED" && it.dueDateEpoch in todayStart..todayEnd }
        val tomorrowStart = todayEnd + 1
        val tomorrowEnd = tomorrowStart + (24L * 3600L * 1000L)
        val dueTomorrow = installments.count { it.status != "PAID" && it.status != "CANCELLED" && it.dueDateEpoch in tomorrowStart..tomorrowEnd }
        val overdue = installments.count { it.status == "OVERDUE" || (it.status != "PAID" && it.status != "CANCELLED" && it.dueDateEpoch < todayStart) }
        val followUps = dueToday + overdue

        DashboardStats(
            totalStudents = studentsCount,
            totalCourses = coursesCount,
            todayReceipts = todayReceipts,
            thisWeekReceipts = weekReceipts,
            thisMonthReceipts = monthReceipts,
            thisMonthExpenses = monthExpenses,
            thisMonthProfit = monthProfit,
            totalDebt = totalDebt,
            dueTodayCount = dueToday,
            dueTomorrowCount = dueTomorrow,
            overdueCount = overdue,
            followUpRequiredCount = followUps
        )
    }

    private fun getStartOfTodayEpoch(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getEndOfTodayEpoch(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    private fun getStartOfMonthEpoch(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ==========================================
    // 10. DEBTORS QUERY & SORTING
    // ==========================================
    val debtorStudents: Flow<List<DebtorStudentItem>> = combine(
        studentDao.getAllStudents(),
        enrollmentDao.getAllEnrollments(),
        installmentDao.getAllInstallments(),
        paymentDao.getAllPayments()
    ) { students, enrollments, installments, payments ->
        val nowEpoch = System.currentTimeMillis()
        val todayStart = getStartOfTodayEpoch()

        students.mapNotNull { student ->
            val studentEnrollments = enrollments.filter { it.studentId == student.id && it.status != "CANCELLED" }
            if (studentEnrollments.isEmpty()) return@mapNotNull null

            val studentInstallments = installments.filter { it.studentId == student.id }
            val studentPayments = payments.filter { it.studentId == student.id && !it.isReversed }

            val totalTuition = studentEnrollments.sumOf { it.finalFee }
            val totalPaid = studentPayments.sumOf { it.amount }
            val remainingDebt = (totalTuition - totalPaid).coerceAtLeast(0L)

            if (remainingDebt <= 0) return@mapNotNull null // Settled, not debtor

            val paidCount = studentInstallments.count { it.status == "PAID" }
            val remainingCount = studentInstallments.size - paidCount
            val nearestDue = studentInstallments.filter { it.status != "PAID" }.minByOrNull { it.dueDateShamsi }
            val courseTitle = studentEnrollments.firstOrNull()?.courseTitle ?: "دوره آموزشی"

            val overdueInst = studentInstallments.filter { it.status != "PAID" && it.dueDateEpoch < todayStart }
            val oldestOverdue = overdueInst.minByOrNull { it.dueDateEpoch }
            val overdueDays = if (oldestOverdue != null) {
                ((todayStart - oldestOverdue.dueDateEpoch) / (24L * 3600L * 1000L)).toInt().coerceAtLeast(1)
            } else 0

            DebtorStudentItem(
                student = student,
                courseTitle = courseTitle,
                totalTuition = totalTuition,
                totalPaid = totalPaid,
                remainingDebt = remainingDebt,
                totalInstallments = studentInstallments.size,
                paidInstallments = paidCount,
                remainingInstallments = remainingCount,
                nearestDueDateShamsi = nearestDue?.dueDateShamsi ?: "-",
                overdueDays = overdueDays
            )
        }
    }

    // ==========================================
    // 11. FINANCIAL REPORTS (Calculated strictly from DB)
    // ==========================================
    suspend fun getFinancialReport(
        periodType: String, // "TODAY", "WEEK", "MONTH", "3_MONTHS", "6_MONTHS", "YEAR", "CUSTOM"
        customStartEpoch: Long = 0L,
        customEndEpoch: Long = System.currentTimeMillis()
    ): FinancialReportData {
        val now = System.currentTimeMillis()
        val (startEpoch, endEpoch, title) = when (periodType) {
            "TODAY" -> Triple(getStartOfTodayEpoch(), getEndOfTodayEpoch(), "گزارش امروز")
            "WEEK" -> Triple(now - (7L * 24 * 3600 * 1000), now, "گزارش ۷ روز اخیر")
            "MONTH" -> Triple(getStartOfMonthEpoch(), now, "گزارش این ماه")
            "3_MONTHS" -> Triple(now - (90L * 24 * 3600 * 1000), now, "گزارش ۳ ماهه (فصلی)")
            "6_MONTHS" -> Triple(now - (180L * 24 * 3600 * 1000), now, "گزارش ۶ ماهه (نیم‌سال)")
            "YEAR" -> Triple(now - (365L * 24 * 3600 * 1000), now, "گزارش سالانه")
            else -> Triple(customStartEpoch, customEndEpoch, "گزارش بازه دلخواه")
        }

        val allPayments = paymentDao.getAllPayments().firstOrNull() ?: emptyList()
        val periodPayments = allPayments.filter { !it.isReversed && it.paymentDateEpoch in startEpoch..endEpoch }

        val totalReceipts = periodPayments.sumOf { it.amount }
        val transactionsCount = periodPayments.size
        val payingStudentsCount = periodPayments.map { it.studentId }.distinct().size

        val totalCurrentDebt = installmentDao.getTotalPendingDebt().firstOrNull() ?: 0L
        val totalAllTime = paymentDao.getTotalReceiptsSum().firstOrNull() ?: 0L
        val collectedPercent = if ((totalAllTime + totalCurrentDebt) > 0) {
            ((totalAllTime * 100) / (totalAllTime + totalCurrentDebt)).toInt()
        } else 0

        val byMethod = periodPayments.groupBy { it.paymentMethod }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        val byAccount = periodPayments.groupBy { it.destinationAccountTitle.ifEmpty { "حساب پیش‌فرض" } }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        // Daily trend
        val dailyTrend = periodPayments.groupBy { it.paymentDateShamsi }
            .map { Pair(it.key, it.value.sumOf { p -> p.amount }) }
            .sortedBy { it.first }

        return FinancialReportData(
            periodTitle = title,
            totalReceipts = totalReceipts,
            transactionsCount = transactionsCount,
            payingStudentsCount = payingStudentsCount,
            totalCurrentDebt = totalCurrentDebt,
            collectedPercentage = collectedPercent,
            receiptsByMethod = byMethod,
            receiptsByAccount = byAccount,
            dailyTrend = dailyTrend
        )
    }

    /**
     * Comprehensive Profit and Loss calculation (P&L) strictly derived from database
     */
    suspend fun calculateProfitAndLoss(
        periodType: String,
        customStartEpoch: Long = 0L,
        customEndEpoch: Long = System.currentTimeMillis()
    ): ProfitAndLossReport {
        val now = System.currentTimeMillis()
        val (startEpoch, endEpoch, title) = when (periodType) {
            "TODAY" -> Triple(getStartOfTodayEpoch(), getEndOfTodayEpoch(), "امروز")
            "WEEK" -> Triple(now - (7L * 24 * 3600 * 1000), now, "۷ روز اخیر")
            "MONTH" -> Triple(getStartOfMonthEpoch(), now, "این ماه")
            "3_MONTHS" -> Triple(now - (90L * 24 * 3600 * 1000), now, "۳ ماهه")
            "6_MONTHS" -> Triple(now - (180L * 24 * 3600 * 1000), now, "۶ ماهه")
            "YEAR" -> Triple(now - (365L * 24 * 3600 * 1000), now, "یک ساله")
            else -> Triple(customStartEpoch, customEndEpoch, "بازه دلخواه")
        }

        val allPayments = paymentDao.getAllPayments().firstOrNull() ?: emptyList()
        val allRefunds = refundDao.getAllRefunds().firstOrNull() ?: emptyList()
        val allExpenses = expenseDao.getAllActiveExpenses().firstOrNull() ?: emptyList()

        val periodPayments = allPayments.filter { !it.isReversed && it.paymentDateEpoch in startEpoch..endEpoch }
        val periodRefunds = allRefunds.filter { it.dateEpoch in startEpoch..endEpoch }
        val periodExpenses = allExpenses.filter { it.dateEpoch in startEpoch..endEpoch }

        val totalReceipts = periodPayments.sumOf { it.amount }
        val totalRefunds = periodRefunds.sumOf { it.amount }
        val grossIncome = (totalReceipts - totalRefunds).coerceAtLeast(0L)

        val totalExpenses = periodExpenses.sumOf { it.amount }
        val netProfit = grossIncome - totalExpenses

        val profitMarginPercent = if (grossIncome > 0) {
            ((netProfit.toDouble() / grossIncome.toDouble()) * 100).toInt()
        } else 0

        val expenseByCategory = periodExpenses.groupBy { it.category }
            .mapValues { it.value.sumOf { exp -> exp.amount } }

        val incomeByMethod = periodPayments.groupBy { it.paymentMethod }
            .mapValues { it.value.sumOf { p -> p.amount } }

        val expensesByMethod = periodExpenses.groupBy { it.paymentMethod }
            .mapValues { it.value.sumOf { exp -> exp.amount } }

        return ProfitAndLossReport(
            periodTitle = title,
            grossIncome = grossIncome,
            totalReceipts = totalReceipts,
            totalRefunds = totalRefunds,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            profitMarginPercent = profitMarginPercent,
            expenseByCategory = expenseByCategory,
            incomeByMethod = incomeByMethod,
            expensesByMethod = expensesByMethod
        )
    }

    // ==========================================
    // 12. BACKUP & RESTORE JSON
    // ==========================================
    suspend fun generateBackupJson(includeSensitiveSecrets: Boolean = false): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("exportDateShamsi", PersianDateUtil.currentShamsiString())
        root.put("exportEpoch", System.currentTimeMillis())
        root.put("version", "3.0")
        root.put("includesSecrets", includeSensitiveSecrets)

        // 1. Students
        val studentsArray = JSONArray()
        studentDao.getAllStudents().firstOrNull()?.forEach { s ->
            val sMap = s.toMap().toMutableMap()
            if (!includeSensitiveSecrets) {
                sMap["telegramLinkToken"] = ""
            }
            studentsArray.put(JSONObject(sMap))
        }
        root.put("students", studentsArray)

        // 2. Courses
        val coursesArray = JSONArray()
        courseDao.getAllCourses().firstOrNull()?.forEach { c ->
            coursesArray.put(JSONObject(c.toMap()))
        }
        root.put("courses", coursesArray)

        // 3. Enrollments
        val enrollmentsArray = JSONArray()
        enrollmentDao.getAllEnrollments().firstOrNull()?.forEach { e ->
            enrollmentsArray.put(JSONObject(e.toMap()))
        }
        root.put("enrollments", enrollmentsArray)

        // 4. Installments
        val installmentsArray = JSONArray()
        installmentDao.getAllInstallments().firstOrNull()?.forEach { i ->
            installmentsArray.put(JSONObject(i.toMap()))
        }
        root.put("installments", installmentsArray)

        // 5. Payments
        val paymentsArray = JSONArray()
        paymentDao.getAllPayments().firstOrNull()?.forEach { p ->
            paymentsArray.put(JSONObject(p.toMap()))
        }
        root.put("payments", paymentsArray)

        // 6. Expenses
        val expensesArray = JSONArray()
        expenseDao.getAllExpensesWithDeleted().firstOrNull()?.forEach { ex ->
            expensesArray.put(JSONObject(ex.toMap()))
        }
        root.put("expenses", expensesArray)

        // 7. Refunds
        val refundsArray = JSONArray()
        refundDao.getAllRefunds().firstOrNull()?.forEach { rf ->
            refundsArray.put(JSONObject(rf.toMap()))
        }
        root.put("refunds", refundsArray)

        // 8. Bank Accounts
        val accountsArray = JSONArray()
        bankAccountDao.getAllAccounts().firstOrNull()?.forEach { b ->
            accountsArray.put(JSONObject(b.toMap()))
        }
        root.put("bankAccounts", accountsArray)

        // 9. Message Logs
        val logsArray = JSONArray()
        messageLogDao.getAllLogs().firstOrNull()?.forEach { log ->
            logsArray.put(JSONObject(log.toMap()))
        }
        root.put("messageLogs", logsArray)

        // 10. Notifications
        val notifsArray = JSONArray()
        notificationDao.getAllNotifications().firstOrNull()?.forEach { n ->
            notifsArray.put(JSONObject(n.toMap()))
        }
        root.put("notifications", notifsArray)

        // 11. Audit Logs
        val auditArray = JSONArray()
        auditLogDao.getAllLogs().firstOrNull()?.forEach { a ->
            auditArray.put(JSONObject(a.toMap()))
        }
        root.put("auditLogs", auditArray)

        // 12. Settings
        val settings = settingsDao.getSettingsDirect()
        if (settings != null) {
            val setMap = settings.toMap().toMutableMap()
            if (!includeSensitiveSecrets) {
                // Sanitize sensitive credentials from public backups
                setMap["telegramBotToken"] = ""
                setMap["rubikaBotToken"] = ""
                setMap["appLockPin"] = ""
            }
            root.put("settings", JSONObject(setMap))
        }

        // Additional supporting entities
        val categoriesArray = JSONArray()
        expenseCategoryDao.getAllCategories().firstOrNull()?.forEach { cat ->
            categoriesArray.put(JSONObject(cat.toMap()))
        }
        root.put("expenseCategories", categoriesArray)

        val templatesArray = JSONArray()
        messageTemplateDao.getAllTemplates().firstOrNull()?.forEach { tmpl ->
            templatesArray.put(JSONObject(tmpl.toMap()))
        }
        root.put("messageTemplates", templatesArray)

        root.toString(2)
    }

    suspend fun restoreBackupJson(jsonString: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            var restoredCount = 0

            // 1. Students
            if (root.has("students")) {
                val array = root.getJSONArray("students")
                val students = mutableListOf<Student>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    students.add(Student.fromMap(map))
                }
                studentDao.insertStudents(students)
                restoredCount += students.size
            }

            // 2. Courses
            if (root.has("courses")) {
                val array = root.getJSONArray("courses")
                val courses = mutableListOf<Course>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    courses.add(Course.fromMap(map))
                }
                courseDao.insertCourses(courses)
                restoredCount += courses.size
            }

            // 3. Enrollments
            if (root.has("enrollments")) {
                val array = root.getJSONArray("enrollments")
                val enrollments = mutableListOf<Enrollment>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    enrollments.add(Enrollment.fromMap(map))
                }
                enrollmentDao.insertEnrollments(enrollments)
                restoredCount += enrollments.size
            }

            // 4. Installments
            if (root.has("installments")) {
                val array = root.getJSONArray("installments")
                val installments = mutableListOf<Installment>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    installments.add(Installment.fromMap(map))
                }
                installmentDao.insertInstallments(installments)
                restoredCount += installments.size
            }

            // 5. Payments (Never overwrite or drop valid payments)
            if (root.has("payments")) {
                val array = root.getJSONArray("payments")
                val payments = mutableListOf<Payment>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    payments.add(Payment.fromMap(map))
                }
                paymentDao.insertPayments(payments)
                restoredCount += payments.size
            }

            // 6. Expenses
            if (root.has("expenses")) {
                val array = root.getJSONArray("expenses")
                val expenses = mutableListOf<Expense>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    expenses.add(Expense.fromMap(map))
                }
                expenseDao.insertExpenses(expenses)
                restoredCount += expenses.size
            }

            // 7. Refunds
            if (root.has("refunds")) {
                val array = root.getJSONArray("refunds")
                val refunds = mutableListOf<Refund>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    refunds.add(Refund.fromMap(map))
                }
                refundDao.insertRefunds(refunds)
                restoredCount += refunds.size
            }

            // 8. Bank Accounts
            if (root.has("bankAccounts")) {
                val array = root.getJSONArray("bankAccounts")
                val accounts = mutableListOf<BankAccount>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    accounts.add(BankAccount.fromMap(map))
                }
                bankAccountDao.insertAccounts(accounts)
                restoredCount += accounts.size
            }

            // 9. Message Logs
            if (root.has("messageLogs")) {
                val array = root.getJSONArray("messageLogs")
                val logs = mutableListOf<MessageLog>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    logs.add(MessageLog.fromMap(map))
                }
                messageLogDao.insertLogs(logs)
                restoredCount += logs.size
            }

            // 10. Notifications
            if (root.has("notifications")) {
                val array = root.getJSONArray("notifications")
                val notifs = mutableListOf<AcademyNotification>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    notifs.add(AcademyNotification.fromMap(map))
                }
                notificationDao.insertNotifications(notifs)
                restoredCount += notifs.size
            }

            // 11. Audit Logs
            if (root.has("auditLogs")) {
                val array = root.getJSONArray("auditLogs")
                val auditLogs = mutableListOf<AuditLog>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    auditLogs.add(AuditLog.fromMap(map))
                }
                auditLogDao.insertLogs(auditLogs)
                restoredCount += auditLogs.size
            }

            // 12. Settings (Safe restore: preserve local tokens if restored are empty)
            if (root.has("settings")) {
                val obj = root.getJSONObject("settings")
                val map = mutableMapOf<String, Any?>()
                obj.keys().forEach { key -> map[key] = obj.get(key) }
                val importedSettings = AcademySettings.fromMap(map)
                val currentLocalSettings = settingsDao.getSettingsDirect() ?: AcademySettings()

                val finalSettings = importedSettings.copy(
                    telegramBotToken = if (importedSettings.telegramBotToken.isNotBlank()) importedSettings.telegramBotToken else currentLocalSettings.telegramBotToken,
                    rubikaBotToken = if (importedSettings.rubikaBotToken.isNotBlank()) importedSettings.rubikaBotToken else currentLocalSettings.rubikaBotToken,
                    appLockPin = if (importedSettings.appLockPin.isNotBlank()) importedSettings.appLockPin else currentLocalSettings.appLockPin
                )
                settingsDao.insertOrUpdate(finalSettings)
                restoredCount += 1
            }

            // Supporting categories
            if (root.has("expenseCategories")) {
                val array = root.getJSONArray("expenseCategories")
                val categories = mutableListOf<ExpenseCategory>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    obj.keys().forEach { key -> map[key] = obj.get(key) }
                    categories.add(ExpenseCategory.fromMap(map))
                }
                expenseCategoryDao.insertCategories(categories)
            }

            logAction(
                entityType = "SYSTEM",
                entityId = "backup_restore",
                actionType = "RESTORE",
                previousValue = "",
                newValue = "$restoredCount رکورد بازیابی شد",
                description = "بازیابی موفقیت‌آمیز نسخه پشتیبان شامل $restoredCount رکورد"
            )

            Result.success("بازیابی اطلاعات با موفقیت انجام شد ($restoredCount رکورد).")
        } catch (e: Exception) {
            Log.e("AcademyRepository", "Restore failed", e)
            Result.failure(Exception("خطا در خواندن فایل پشتیبان: ${e.message}"))
        }
    }

    // ==========================================
    // 13. CLOUD SYNC (Room <-> Firestore Two-Way)
    // ==========================================
    private fun <T> mergeEntities(
        local: List<T>,
        remote: List<T>,
        idSelector: (T) -> String,
        timestampSelector: (T) -> Long
    ): List<T> {
        val map = HashMap<String, T>()
        for (item in remote) {
            val id = idSelector(item)
            if (id.isNotBlank()) {
                map[id] = item
            }
        }
        for (item in local) {
            val id = idSelector(item)
            if (id.isNotBlank()) {
                val existing = map[id]
                if (existing == null || timestampSelector(item) >= timestampSelector(existing)) {
                    map[id] = item
                }
            }
        }
        return map.values.toList()
    }

    fun isFirebaseReady(): Boolean = firebaseManager.isReady()

    suspend fun performCloudSync(): Result<SyncResultSummary> = withContext(Dispatchers.IO) {
        try {
            if (!firebaseManager.isReady()) {
                Log.d("AcademyRepository", "Cloud sync skipped: Firebase Auth session not active")
                return@withContext Result.success(
                    SyncResultSummary(
                        isSuccess = true,
                        pushedCount = 0,
                        pulledCount = 0,
                        message = "اطلاعات به صورت محلی و آفلاین در دستگاه ذخیره و محافظت می‌شوند.",
                        timestampShamsi = PersianDateUtil.currentShamsiString()
                    )
                )
            }
            Log.d("AcademyRepository", "[TRACE 4] performCloudSync() started - Current auth: ${firebaseManager.auth?.currentUser?.uid ?: "NULL"}")
            var pushedCount = 0
            var pulledCount = 0

            // 1. Students
            val localStudents = studentDao.getAllStudents().firstOrNull() ?: emptyList()
            val remoteStudents = firebaseManager.fetchStudentsFromFirestore()
            val mergedStudents = mergeEntities(
                local = localStudents,
                remote = remoteStudents,
                idSelector = { it.id },
                timestampSelector = { it.updatedAt }
            )
            studentDao.insertStudents(mergedStudents)
            firebaseManager.saveStudentsBatch(mergedStudents)
            pushedCount += mergedStudents.size
            pulledCount += remoteStudents.size

            // 2. Courses
            val localCourses = courseDao.getAllCourses().firstOrNull() ?: emptyList()
            val remoteCourses = firebaseManager.fetchCoursesFromFirestore()
            val mergedCourses = mergeEntities(
                local = localCourses,
                remote = remoteCourses,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            courseDao.insertCourses(mergedCourses)
            firebaseManager.saveCoursesBatch(mergedCourses)
            pushedCount += mergedCourses.size
            pulledCount += remoteCourses.size

            // 3. Enrollments
            val localEnrollments = enrollmentDao.getAllEnrollments().firstOrNull() ?: emptyList()
            val remoteEnrollments = firebaseManager.fetchEnrollmentsFromFirestore()
            val mergedEnrollments = mergeEntities(
                local = localEnrollments,
                remote = remoteEnrollments,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            enrollmentDao.insertEnrollments(mergedEnrollments)
            firebaseManager.saveEnrollmentsBatch(mergedEnrollments)
            pushedCount += mergedEnrollments.size
            pulledCount += remoteEnrollments.size

            // 4. Installments
            val localInstallments = installmentDao.getAllInstallments().firstOrNull() ?: emptyList()
            val remoteInstallments = firebaseManager.fetchInstallmentsFromFirestore()
            val mergedInstallments = mergeEntities(
                local = localInstallments,
                remote = remoteInstallments,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            installmentDao.insertInstallments(mergedInstallments)
            firebaseManager.saveInstallmentsBatch(mergedInstallments)
            pushedCount += mergedInstallments.size
            pulledCount += remoteInstallments.size

            // 5. Payments (Financial safety: never lose payments)
            val localPayments = paymentDao.getAllPayments().firstOrNull() ?: emptyList()
            val remotePayments = firebaseManager.fetchPaymentsFromFirestore()
            val mergedPayments = mergeEntities(
                local = localPayments,
                remote = remotePayments,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            paymentDao.insertPayments(mergedPayments)
            firebaseManager.savePaymentsBatch(mergedPayments)
            pushedCount += mergedPayments.size
            pulledCount += remotePayments.size

            // 6. Expenses
            val localExpenses = expenseDao.getAllExpensesWithDeleted().firstOrNull() ?: emptyList()
            val remoteExpenses = firebaseManager.fetchExpensesFromFirestore()
            val mergedExpenses = mergeEntities(
                local = localExpenses,
                remote = remoteExpenses,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            expenseDao.insertExpenses(mergedExpenses)
            firebaseManager.saveExpensesBatch(mergedExpenses)
            pushedCount += mergedExpenses.size
            pulledCount += remoteExpenses.size

            // 7. Refunds
            val localRefunds = refundDao.getAllRefunds().firstOrNull() ?: emptyList()
            val remoteRefunds = firebaseManager.fetchRefundsFromFirestore()
            val mergedRefunds = mergeEntities(
                local = localRefunds,
                remote = remoteRefunds,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            refundDao.insertRefunds(mergedRefunds)
            firebaseManager.saveRefundsBatch(mergedRefunds)
            pushedCount += mergedRefunds.size
            pulledCount += remoteRefunds.size

            // 8. Bank Accounts
            val localAccounts = bankAccountDao.getAllAccounts().firstOrNull() ?: emptyList()
            val remoteAccounts = firebaseManager.fetchBankAccountsFromFirestore()
            val mergedAccounts = mergeEntities(
                local = localAccounts,
                remote = remoteAccounts,
                idSelector = { it.id },
                timestampSelector = { it.createdAt }
            )
            bankAccountDao.insertAccounts(mergedAccounts)
            firebaseManager.saveBankAccountsBatch(mergedAccounts)
            pushedCount += mergedAccounts.size
            pulledCount += remoteAccounts.size

            // 9. Message Logs
            val localLogs = messageLogDao.getAllLogs().firstOrNull() ?: emptyList()
            val remoteLogs = firebaseManager.fetchMessageLogsFromFirestore()
            val mergedLogs = mergeEntities(
                local = localLogs,
                remote = remoteLogs,
                idSelector = { it.id },
                timestampSelector = { it.sentAtEpoch }
            )
            messageLogDao.insertLogs(mergedLogs)
            firebaseManager.saveMessageLogsBatch(mergedLogs)
            pushedCount += mergedLogs.size
            pulledCount += remoteLogs.size

            // 10. Notifications
            val localNotifs = notificationDao.getAllNotifications().firstOrNull() ?: emptyList()
            val remoteNotifs = firebaseManager.fetchNotificationsFromFirestore()
            val mergedNotifs = mergeEntities(
                local = localNotifs,
                remote = remoteNotifs,
                idSelector = { it.id },
                timestampSelector = { it.dateEpoch }
            )
            notificationDao.insertNotifications(mergedNotifs)
            firebaseManager.saveNotificationsBatch(mergedNotifs)
            pushedCount += mergedNotifs.size
            pulledCount += remoteNotifs.size

            // 11. Audit Logs
            val localAudit = auditLogDao.getAllLogs().firstOrNull() ?: emptyList()
            val remoteAudit = firebaseManager.fetchAuditLogsFromFirestore()
            val mergedAudit = mergeEntities(
                local = localAudit,
                remote = remoteAudit,
                idSelector = { it.id },
                timestampSelector = { it.timestampEpoch }
            )
            auditLogDao.insertLogs(mergedAudit)
            firebaseManager.saveAuditLogsBatch(mergedAudit)
            pushedCount += mergedAudit.size
            pulledCount += remoteAudit.size

            // 12. Settings
            val localSettings = settingsDao.getSettingsDirect() ?: AcademySettings()
            val remoteSettings = firebaseManager.getSettingsFromFirestore()
            if (remoteSettings != null) {
                val mostRecent = if (remoteSettings.updatedAt > localSettings.updatedAt) {
                    remoteSettings.copy(
                        telegramBotToken = if (remoteSettings.telegramBotToken.isNotBlank()) remoteSettings.telegramBotToken else localSettings.telegramBotToken,
                        rubikaBotToken = if (remoteSettings.rubikaBotToken.isNotBlank()) remoteSettings.rubikaBotToken else localSettings.rubikaBotToken,
                        appLockPin = if (remoteSettings.appLockPin.isNotBlank()) remoteSettings.appLockPin else localSettings.appLockPin
                    )
                } else {
                    localSettings
                }
                settingsDao.insertOrUpdate(mostRecent)
                firebaseManager.saveSettingsToFirestore(mostRecent)
            } else {
                firebaseManager.saveSettingsToFirestore(localSettings)
            }

            val summary = SyncResultSummary(
                isSuccess = true,
                pushedCount = pushedCount,
                pulledCount = pulledCount,
                message = "همگام‌سازی ابری با موفقیت انجام شد ($pushedCount ارسال، $pulledCount دریافت)",
                timestampShamsi = PersianDateUtil.currentShamsiString(),
                timestampEpoch = System.currentTimeMillis()
            )

            logAction(
                entityType = "SYNC",
                entityId = "cloud_sync",
                actionType = "SYNC",
                previousValue = "",
                newValue = "$pushedCount ارسال شد، $pulledCount دریافت شد",
                description = "همگام‌سازی ابری با موفقیت انجام شد"
            )

            Result.success(summary)
        } catch (e: Exception) {
            Log.e("AcademyRepository", "Cloud sync failed", e)
            Result.failure(Exception("خطا در همگام‌سازی با سرور: ${e.message ?: "اتصال به اینترنت برقرار نیست"}"))
        }
    }

    suspend fun cancelEnrollment(
        enrollmentId: String,
        reason: String,
        refundAmount: Long = 0L,
        sourceAccountId: String = "",
        notes: String = ""
    ): Result<Unit> {
        val enrollment = enrollmentDao.getEnrollmentById(enrollmentId)
            ?: return Result.failure(Exception("ثبت‌نام یافت نشد."))

        val updatedEnrollment = enrollment.copy(
            status = "CANCELLED",
            notes = if (notes.isNotBlank()) "${enrollment.notes} | انصراف: $reason - $notes" else "${enrollment.notes} | انصراف: $reason"
        )
        enrollmentDao.updateEnrollment(updatedEnrollment)
        firebaseManager.saveEnrollmentToFirestore(updatedEnrollment)

        // Cancel unpaid installments
        val installments = installmentDao.getInstallmentsForEnrollment(enrollmentId).firstOrNull() ?: emptyList()
        installments.filter { it.status != "PAID" }.forEach { inst ->
            val cancelledInst = inst.copy(
                status = "CANCELLED",
                notes = "لغو شده به دلیل انصراف از دوره: $reason"
            )
            installmentDao.updateInstallment(cancelledInst)
            firebaseManager.saveInstallmentToFirestore(cancelledInst)
        }

        // If refund amount is specified, create a refund record
        if (refundAmount > 0) {
            val totalRefunds = refundDao.getAllRefunds().firstOrNull()?.size ?: 0
            val refCode = FinanceUtil.generateRefundCode(totalRefunds + 1)
            val sourceAccount = if (sourceAccountId.isNotBlank()) bankAccountDao.getAccountById(sourceAccountId) else null

            val refund = com.example.data.model.Refund(
                id = UUID.randomUUID().toString(),
                refundCode = refCode,
                studentId = enrollment.studentId,
                studentName = enrollment.studentName,
                enrollmentId = enrollment.id,
                courseTitle = enrollment.courseTitle,
                amount = refundAmount,
                dateShamsi = PersianDateUtil.currentShamsiString(),
                dateEpoch = System.currentTimeMillis(),
                paymentMethod = "TRANSFER",
                sourceAccountId = sourceAccountId,
                sourceAccountTitle = sourceAccount?.accountTitle ?: "حساب پیش‌فرض آموزشگاه",
                trackingNumber = "",
                reason = reason,
                recordedBy = "مدیر آموزشگاه"
            )
            refundDao.insertRefund(refund)
            firebaseManager.saveRefundToFirestore(refund)
        }

        logAction(
            entityType = "ENROLLMENT",
            entityId = enrollment.id,
            actionType = "CANCEL",
            previousValue = "ACTIVE",
            newValue = "CANCELLED",
            reason = reason,
            description = "انصراف هنرجو ${enrollment.studentName} از دوره ${enrollment.courseTitle} (استرداد: ${PersianDateUtil.formatCurrency(refundAmount)})"
        )

        return Result.success(Unit)
    }

    // ==========================================
    // 13. SAMPLE DATA (Optional test data)
    // ==========================================
    suspend fun populateSampleData() {
        val currentShamsi = PersianDateUtil.currentJalali()
        val sampleStudents = listOf(
            Student(
                id = "sample_s1",
                studentCode = "ST-1403-0001",
                firstName = "علی",
                lastName = "محمدی",
                phone = "۰۹۱۲۳۴۵۶۷۸۹",
                nationalCode = "۰۰۱۲۳۴۵۶۷۸",
                address = "تهران، خیابان آزادی",
                notes = "علاقه‌مند به برنامه‌نویسی پایتون",
                registrationDateShamsi = currentShamsi.format(),
                status = "DEBTOR",
                isActive = true,
                isSampleData = true
            ),
            Student(
                id = "sample_s2",
                studentCode = "ST-1403-0002",
                firstName = "سارا",
                lastName = "احمدی",
                phone = "۰۹۱۸۷۶۵۴۳۲۱",
                nationalCode = "۰۴۵۱۱۲۲۳۳۴",
                address = "تهران، شهرک غرب",
                notes = "دانشجوی طراحی وب",
                registrationDateShamsi = currentShamsi.format(),
                status = "ACTIVE",
                isActive = true,
                isSampleData = true
            ),
            Student(
                id = "sample_s3",
                studentCode = "ST-1403-0003",
                firstName = "رضا",
                lastName = "کریمی",
                phone = "۰۹۳۵۱۲۳۴۵۶۷",
                nationalCode = "۱۲۳۴۵۶۷۸۹۰",
                address = "تهران، رسالت",
                notes = "دوره مهارت‌های اداری ICDL",
                registrationDateShamsi = currentShamsi.format(),
                status = "SETTLED",
                isActive = true,
                isSampleData = true
            )
        )

        val sampleCourses = listOf(
            Course(
                id = "sample_c1",
                title = "برنامه‌نویسی پایتون و هوش مصنوعی",
                durationMonths = 3,
                tuitionFee = 12000000L,
                installmentCount = 3,
                description = "دوره جامع ۳ ماهه پایتون مقدماتی تا پیشرفته با ۳ قسط",
                isActive = true,
                isSampleData = true
            ),
            Course(
                id = "sample_c2",
                title = "طراحی وب فرانت‌اند مدرن",
                durationMonths = 3,
                tuitionFee = 9000000L,
                installmentCount = 3,
                description = "آموزش HTML, CSS, JavaScript و React",
                isActive = true,
                isSampleData = true
            ),
            Course(
                id = "sample_c3",
                title = "مهارت‌های هفتگانه رایانه (ICDL)",
                durationMonths = 2,
                tuitionFee = 4000000L,
                installmentCount = 2,
                description = "آموزش ویندوز، اینترنت و نرم‌افزارهای آفیس",
                isActive = true,
                isSampleData = true
            )
        )

        studentDao.insertStudents(sampleStudents)
        courseDao.insertCourses(sampleCourses)

        // Sample Enrollment & Installments (Scenario 1 & 2)
        val sampleEnrollment = Enrollment(
            id = "sample_e1",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            courseId = "sample_c1",
            courseTitle = "برنامه‌نویسی پایتون و هوش مصنوعی",
            registrationDateShamsi = currentShamsi.format(),
            startDateShamsi = currentShamsi.format(),
            endDateShamsi = currentShamsi.addMonths(3).format(),
            totalFee = 12000000L,
            discount = 0L,
            finalFee = 12000000L,
            installmentCount = 3,
            firstInstallmentPaidOnDate = true,
            firstInstallmentDateShamsi = currentShamsi.format(),
            status = "ACTIVE",
            isSampleData = true
        )
        enrollmentDao.insertEnrollment(sampleEnrollment)

        val inst1 = Installment(
            id = "sample_inst_1",
            enrollmentId = "sample_e1",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            courseId = "sample_c1",
            courseTitle = "برنامه‌نویسی پایتون و هوش مصنوعی",
            installmentNumber = 1,
            totalInstallments = 3,
            amount = 4000000L,
            dueDateShamsi = currentShamsi.format(),
            paidAmount = 4000000L,
            remainingAmount = 0L,
            fullPaidDateShamsi = currentShamsi.format(),
            status = "PAID",
            isSampleData = true
        )
        val inst2 = Installment(
            id = "sample_inst_2",
            enrollmentId = "sample_e1",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            courseId = "sample_c1",
            courseTitle = "برنامه‌نویسی پایتون و هوش مصنوعی",
            installmentNumber = 2,
            totalInstallments = 3,
            amount = 4000000L,
            dueDateShamsi = currentShamsi.addMonths(1).format(),
            paidAmount = 1500000L,
            remainingAmount = 2500000L,
            status = "PARTIAL_PAID",
            isSampleData = true
        )
        val inst3 = Installment(
            id = "sample_inst_3",
            enrollmentId = "sample_e1",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            courseId = "sample_c1",
            courseTitle = "برنامه‌نویسی پایتون و هوش مصنوعی",
            installmentNumber = 3,
            totalInstallments = 3,
            amount = 4000000L,
            dueDateShamsi = currentShamsi.addMonths(2).format(),
            paidAmount = 0L,
            remainingAmount = 4000000L,
            status = "PENDING",
            isSampleData = true
        )
        installmentDao.insertInstallments(listOf(inst1, inst2, inst3))

        val payment1 = Payment(
            id = "sample_p1",
            transactionId = "TRX-1403-0001",
            paymentCode = "TRX-1403-0001",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            enrollmentId = "sample_e1",
            installmentId = "sample_inst_1",
            installmentNumber = 1,
            amount = 4000000L,
            paymentDateShamsi = currentShamsi.format(),
            paymentTime = "10:30",
            paymentMethod = "POS",
            destinationAccountId = "default_card_1",
            destinationAccountTitle = "کارت اصلی آموزشگاه",
            trackingNumber = "98765432",
            notes = "قسط اول ثبت‌نام",
            isSampleData = true
        )
        val payment2 = Payment(
            id = "sample_p2",
            transactionId = "TRX-1403-0002",
            paymentCode = "TRX-1403-0002",
            studentId = "sample_s1",
            studentName = "علی محمدی",
            enrollmentId = "sample_e1",
            installmentId = "sample_inst_2",
            installmentNumber = 2,
            amount = 1500000L,
            paymentDateShamsi = currentShamsi.format(),
            paymentTime = "16:45",
            paymentMethod = "TRANSFER",
            destinationAccountId = "default_card_1",
            destinationAccountTitle = "کارت اصلی آموزشگاه",
            trackingNumber = "12345678",
            notes = "پرداخت بخشی از قسط دوم (پرداخت ناقص)",
            isSampleData = true
        )
        paymentDao.insertPayments(listOf(payment1, payment2))

        logAction(
            entityType = "SYSTEM",
            entityId = "sample_data",
            actionType = "CREATE",
            previousValue = "",
            newValue = "داده‌های نمونه آموزشی و مالی",
            description = "ایجاد داده‌های نمونه تستی"
        )
    }

    suspend fun clearSampleData() {
        studentDao.deleteSampleStudents()
        courseDao.deleteSampleCourses()
        enrollmentDao.deleteSampleEnrollments()
        installmentDao.deleteSampleInstallments()
        paymentDao.deleteSamplePayments()
        bankAccountDao.deleteSampleAccounts()

        logAction(
            entityType = "SYSTEM",
            entityId = "sample_data",
            actionType = "DELETE",
            previousValue = "داده‌های آزمایشی",
            newValue = "",
            description = "حذف تمامی داده‌های آزمایشی"
        )
    }

    // ==========================================
    // 10. REMINDERS
    // ==========================================
    val allReminders: Flow<List<Reminder>> = reminderDao.getAllReminders()
    val pendingReminders: Flow<List<Reminder>> = reminderDao.getPendingReminders()

    fun getRemindersForInstallment(installmentId: String): Flow<List<Reminder>> =
        reminderDao.getRemindersForInstallment(installmentId)

    suspend fun insertReminder(reminder: Reminder) = reminderDao.insertReminder(reminder)
    suspend fun insertReminders(reminders: List<Reminder>) = reminderDao.insertReminders(reminders)
    suspend fun updateReminder(reminder: Reminder) = reminderDao.updateReminder(reminder)
    suspend fun deleteReminder(id: String) = reminderDao.deleteReminderById(id)

    // ==========================================
    // 11. TELEGRAM BOT DIRECT HELPERS
    // ==========================================
    suspend fun getEnrollmentsForStudentDirect(studentId: String): List<Enrollment> =
        enrollmentDao.getEnrollmentsForStudent(studentId).firstOrNull() ?: emptyList()

    suspend fun getInstallmentsForStudentDirect(studentId: String): List<Installment> =
        installmentDao.getInstallmentsForStudent(studentId).firstOrNull() ?: emptyList()

    suspend fun getPaymentsForStudentDirect(studentId: String): List<Payment> =
        paymentDao.getPaymentsForStudent(studentId).firstOrNull() ?: emptyList()

    suspend fun getCourseById(courseId: String): Course? =
        courseDao.getCourseById(courseId)

    suspend fun getEnrollmentById(enrollmentId: String): Enrollment? =
        enrollmentDao.getEnrollmentById(enrollmentId)

    suspend fun getInstallmentById(installmentId: String): Installment? =
        installmentDao.getInstallmentById(installmentId)

    suspend fun getPaymentById(paymentId: String): Payment? =
        paymentDao.getPaymentById(paymentId)

    suspend fun getSettingsDirect(): AcademySettings? =
        settingsDao.getSettingsDirect()
}
