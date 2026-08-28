package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.BankAccount
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.MessageLog
import com.example.data.model.MessageTemplate
import com.example.data.model.Payment
import com.example.data.model.Reminder
import com.example.data.model.Student
import com.example.data.model.User
import com.example.data.remote.FirebaseManager
import com.example.data.repository.AcademyRepository
import com.example.data.repository.AuthRepository
import com.example.data.repository.DashboardStats
import com.example.data.repository.DebtorStudentItem
import com.example.data.repository.FinancialReportData
import com.example.data.repository.SyncResultSummary
import com.example.data.util.FinanceUtil
import com.example.data.util.StudentFinancialSummary
import com.example.service.messaging.MessageSendResult
import com.example.service.messaging.MessagingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val firebaseManager = FirebaseManager()
    private val database = AppDatabase.getDatabase(application)
    private val authRepository = AuthRepository(application, firebaseManager)
    val academyRepository = AcademyRepository(database, firebaseManager)

    // Network and auto-sync manager
    val networkSyncManager = com.example.service.NetworkSyncManager(application, academyRepository)
    val isOnline: StateFlow<Boolean> = networkSyncManager.isOnline

    // Messaging manager
    private val messagingManager = MessagingManager(
        context = application,
        messageTemplateDao = database.messageTemplateDao(),
        messageLogDao = database.messageLogDao(),
        currentSettings = null
    )

    // Auth State
    val currentUser: StateFlow<User?> = authRepository.currentUser
    val isAuthenticated: StateFlow<Boolean> = authRepository.isAuthenticated

    // Search query for students
    val studentSearchQuery = MutableStateFlow("")

    val students: StateFlow<List<Student>> = studentSearchQuery
        .flatMapLatest { query -> academyRepository.searchStudents(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val courses: StateFlow<List<Course>> = academyRepository.allCourses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enrollments: StateFlow<List<Enrollment>> = academyRepository.allEnrollments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val installments: StateFlow<List<Installment>> = academyRepository.allInstallments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val payments: StateFlow<List<Payment>> = academyRepository.allPayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bankAccounts: StateFlow<List<BankAccount>> = academyRepository.allBankAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeBankAccounts: StateFlow<List<BankAccount>> = academyRepository.activeBankAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messageTemplates: StateFlow<List<MessageTemplate>> = academyRepository.allTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val messageLogs: StateFlow<List<MessageLog>> = academyRepository.allMessageLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<com.example.data.model.Reminder>> = academyRepository.allReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminderScheduler = com.example.service.ReminderScheduler(application)

    val settings: StateFlow<AcademySettings?> = academyRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AcademySettings())

    val dashboardStats: StateFlow<DashboardStats> = academyRepository.dashboardStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardStats())

    val debtorStudents: StateFlow<List<DebtorStudentItem>> = academyRepository.debtorStudents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<AuditLog>> = academyRepository.auditLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Cloud Sync State
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow<SyncResultSummary?>(null)
    val lastSyncSummary: StateFlow<SyncResultSummary?> = _lastSyncSummary.asStateFlow()

    // UI Feedback state
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    // Telegram polling and state
    private val _isCheckingTelegram = MutableStateFlow(false)
    val isCheckingTelegram: StateFlow<Boolean> = _isCheckingTelegram.asStateFlow()

    init {
        networkSyncManager.startListening()
        viewModelScope.launch {
            settings.collect { s ->
                messagingManager.updateSettings(s)
            }
        }
        // Background polling for real Telegram student connections (/start)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(4000)
                try {
                    val currentSettings = settings.value
                    if (currentSettings?.telegramEnabled == true && !currentSettings.telegramBotToken.isNullOrBlank()) {
                        val pollResult = messagingManager.telegramProvider.fetchAndProcessUpdates(academyRepository)
                        if (pollResult.newConnectionsCount > 0) {
                            showMessage("${pollResult.newConnectionsCount} هنرجوی جدید با موفقیت به ربات تلگرام متصل شدند.")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Telegram auto-poll background check: ${e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        networkSyncManager.stopListening()
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun showMessage(msg: String) {
        _userMessage.value = msg
    }

    // --- Authentication ---
    fun login(email: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authRepository.login(email, pass)
            if (result.isSuccess) {
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "خطا در ورود")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun changePassword(oldPass: String, newPass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val result = authRepository.changePassword(oldPass, newPass)
            if (result.isSuccess) {
                showMessage("رمز عبور با موفقیت تغییر یافت.")
                onResult(true, null)
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "خطا در تغییر رمز عبور")
            }
        }
    }

    // --- Students ---
    fun addStudent(
        firstName: String,
        lastName: String,
        phone: String,
        nationalCode: String,
        address: String = "",
        notes: String = "",
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.addStudent(
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                nationalCode = nationalCode,
                address = address,
                notes = notes
            )
            if (res.isSuccess) {
                showMessage("هنرجوی جدید (${res.getOrNull()?.studentCode}) با موفقیت ثبت شد.")
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun updateStudent(student: Student, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = academyRepository.updateStudent(student)
            if (res.isSuccess) {
                showMessage("اطلاعات هنرجو با موفقیت بروزرسانی شد.")
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun deleteStudent(student: Student) {
        viewModelScope.launch {
            academyRepository.deleteStudent(student)
            showMessage("هنرجو با موفقیت حذف گردید.")
        }
    }

    fun getStudentFinancialSummary(studentId: String): StudentFinancialSummary {
        val currentEnrollments = enrollments.value
        val currentInstallments = installments.value
        val currentPayments = payments.value
        return FinanceUtil.calculateStudentFinancials(
            studentId = studentId,
            enrollments = currentEnrollments,
            installments = currentInstallments,
            payments = currentPayments
        )
    }

    // --- Courses ---
    fun addCourse(
        title: String,
        durationMonths: Int,
        tuitionFee: Long,
        installmentCount: Int,
        description: String,
        isActive: Boolean,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.addCourse(
                title = title,
                durationMonths = durationMonths,
                tuitionFee = tuitionFee,
                installmentCount = installmentCount,
                description = description,
                isActive = isActive
            )
            if (res.isSuccess) {
                showMessage("دوره آموزشی جدید با موفقیت ایجاد شد.")
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun updateCourse(course: Course, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = academyRepository.updateCourse(course)
            if (res.isSuccess) {
                showMessage("دوره با موفقیت ویرایش شد.")
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            academyRepository.deleteCourse(course)
            showMessage("دوره آموزشی با موفقیت حذف گردید.")
        }
    }

    // --- Enrollment ---
    fun enrollStudent(
        student: Student,
        course: Course,
        startDateShamsi: String,
        discount: Long,
        installmentCount: Int,
        customInstallmentAmounts: List<Long>,
        firstInstallmentDateShamsi: String,
        firstInstallmentPaidNow: Boolean,
        notes: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.enrollStudentInCourse(
                student = student,
                course = course,
                startDateShamsi = startDateShamsi,
                discount = discount,
                installmentCount = installmentCount,
                customInstallmentAmounts = customInstallmentAmounts,
                firstInstallmentDateShamsi = firstInstallmentDateShamsi,
                firstInstallmentPaidNow = firstInstallmentPaidNow,
                notes = notes
            )
            if (res.isSuccess) {
                showMessage("ثبت‌نام ${student.fullName} با موفقیت انجام و اقساط صادر شد.")
                // Auto schedule reminders for newly generated installments
                settings.value?.let { currentSettings ->
                    val insts = installments.value.filter { it.studentId == student.id && !it.isPaid }
                    for (inst in insts) {
                        reminderScheduler.scheduleRemindersForInstallment(inst, currentSettings)
                    }
                }
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    // --- Payments ---
    fun recordPayment(
        studentId: String,
        installmentId: String,
        amount: Long,
        paymentDateShamsi: String,
        paymentTime: String,
        paymentMethod: String,
        destinationAccountId: String,
        destinationAccountTitle: String,
        trackingNumber: String,
        receiptImage: String,
        notes: String,
        onComplete: (Boolean, Payment?, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.recordPayment(
                studentId = studentId,
                installmentId = installmentId,
                amount = amount,
                paymentDateShamsi = paymentDateShamsi,
                paymentTime = paymentTime,
                paymentMethod = paymentMethod,
                destinationAccountId = destinationAccountId,
                destinationAccountTitle = destinationAccountTitle,
                trackingNumber = trackingNumber,
                receiptImage = receiptImage,
                notes = notes
            )
            if (res.isSuccess) {
                val p = res.getOrNull()
                showMessage("پرداخت با موفقیت ثبت شد (کد تراکنش: ${p?.transactionId}).")
                onComplete(true, p, null)
            } else {
                onComplete(false, null, res.exceptionOrNull()?.message)
            }
        }
    }

    fun reversePayment(
        payment: Payment,
        reason: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.reversePayment(payment, reason)
            if (res.isSuccess) {
                showMessage("تراکنش ${payment.transactionId} لغو و مانده قسط اصلاح شد.")
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message)
            }
        }
    }

    // --- Bank Accounts ---
    fun addBankAccount(
        bankName: String,
        type: String = "CARD",
        accountTitle: String,
        cardNumber: String,
        shabaNumber: String = "",
        accountNumber: String = "",
        accountHolder: String = "",
        isDefault: Boolean = false,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = academyRepository.addBankAccount(
                bankName = bankName,
                type = type,
                accountTitle = accountTitle,
                cardNumber = cardNumber,
                shabaNumber = shabaNumber,
                accountNumber = accountNumber,
                accountHolder = accountHolder,
                isDefault = isDefault
            )
            if (res.isSuccess) {
                showMessage("کارت/حساب آموزشگاه با موفقیت ذخیره شد.")
                onComplete(true)
            } else {
                showMessage("خطا در ذخیره حساب: ${res.exceptionOrNull()?.message}")
                onComplete(false)
            }
        }
    }

    fun updateBankAccount(
        account: BankAccount,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = academyRepository.updateBankAccount(account)
            if (res.isSuccess) {
                showMessage("مشخصات حساب بانکی بروزرسانی شد.")
                onComplete(true)
            } else {
                showMessage("خطا در بروزرسانی: ${res.exceptionOrNull()?.message}")
                onComplete(false)
            }
        }
    }

    fun setDefaultBankAccount(
        account: BankAccount,
        onComplete: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = academyRepository.setDefaultBankAccount(account)
            if (res.isSuccess) {
                showMessage("کارت ${account.bankName} به عنوان حساب پیش‌فرض واریز انتخاب شد.")
                onComplete(true)
            } else {
                onComplete(false)
            }
        }
    }

    fun deleteBankAccount(account: BankAccount) {
        viewModelScope.launch {
            academyRepository.deleteBankAccount(account)
            showMessage("حساب بانکی ${account.accountTitle} حذف شد.")
        }
    }

    // --- Templates ---
    fun updateTemplate(template: MessageTemplate) {
        viewModelScope.launch {
            academyRepository.updateTemplate(template)
            showMessage("متن الگو ذخیره شد.")
        }
    }

    // --- Messaging & Telegram ---
    fun sendMessageToStudent(
        student: Student,
        messageText: String,
        channel: String,
        onResult: (MessageSendResult) -> Unit
    ) {
        viewModelScope.launch {
            val (result, _) = messagingManager.sendMessage(student, messageText, channel)
            when (result) {
                is MessageSendResult.Success -> showMessage("پیام تلگرام با موفقیت ارسال شد.")
                is MessageSendResult.Failed -> showMessage("خطا در ارسال: ${result.error}")
                is MessageSendResult.NotConnected -> showMessage("عدم اتصال: ${result.reason}")
                is MessageSendResult.ManualActionRequired -> showMessage("متن پیام آماده کپی/اشتراک‌گذاری شد.")
            }
            onResult(result)
        }
    }

    fun prepareMessage(
        templateType: String,
        student: Student,
        courseTitle: String = "",
        installmentNumber: Int = 1,
        installmentAmount: Long = 0L,
        paidAmount: Long = 0L,
        remainingDebt: Long = 0L,
        dueDateShamsi: String = "",
        onResult: (String) -> Unit
    ) {
        viewModelScope.launch {
            val text = messagingManager.prepareMessage(
                templateType = templateType,
                student = student,
                courseTitle = courseTitle,
                installmentNumber = installmentNumber,
                installmentAmount = installmentAmount,
                paidAmount = paidAmount,
                remainingDebt = remainingDebt,
                dueDateShamsi = dueDateShamsi
            )
            onResult(text)
        }
    }

    fun testTelegramBot(token: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = messagingManager.telegramProvider.testBotConnection(token)
            onResult(res.first, res.second)
        }
    }

    fun testCloudflareWorker(workerUrl: String, apiSecret: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val res = messagingManager.telegramProvider.testCloudflareWorkerConnection(workerUrl, apiSecret)
            onResult(res.first, res.second)
        }
    }

    fun checkTelegramUpdates(showFeedback: Boolean = true, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isCheckingTelegram.value = true
            try {
                val pollResult = messagingManager.telegramProvider.fetchAndProcessUpdates(academyRepository)
                if (showFeedback) {
                    showMessage(pollResult.message)
                }
                onResult(pollResult.isSuccess, pollResult.message)
            } catch (e: Exception) {
                val err = "خطا در بررسی پیام‌های ربات تلگرام: ${e.localizedMessage}"
                if (showFeedback) showMessage(err)
                onResult(false, err)
            } finally {
                _isCheckingTelegram.value = false
            }
        }
    }

    fun disconnectStudentTelegram(studentId: String, onComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val res = academyRepository.disconnectStudentTelegram(studentId)
            if (res.isSuccess) {
                showMessage("اتصال تلگرام هنرجو با موفقیت قطع شد.")
                onComplete(true, null)
            } else {
                val msg = res.exceptionOrNull()?.message ?: "خطا در قطع اتصال تلگرام"
                showMessage(msg)
                onComplete(false, msg)
            }
        }
    }

    fun getStudentTelegramLink(student: Student): String {
        val botUser = settings.value?.telegramBotUsername ?: ""
        return messagingManager.telegramProvider.generateStudentDeepLink(student, botUser)
    }

    // --- Financial Reports ---
    fun loadFinancialReport(
        periodType: String,
        customStart: Long = 0L,
        customEnd: Long = System.currentTimeMillis(),
        onResult: (FinancialReportData) -> Unit
    ) {
        viewModelScope.launch {
            val data = academyRepository.getFinancialReport(periodType, customStart, customEnd)
            onResult(data)
        }
    }

    // --- Settings ---
    fun saveSettings(
        academyName: String,
        phone: String,
        address: String,
        logoUrl: String,
        currencyUnit: String,
        reminderDays: Int,
        telegramBotToken: String = "",
        telegramBotUsername: String = "",
        telegramEnabled: Boolean = false,
        rubikaEnabled: Boolean = false,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val res = academyRepository.updateSettings(
                academyName = academyName,
                phone = phone,
                address = address,
                logoUrl = logoUrl,
                currencyUnit = currencyUnit,
                reminderDaysBefore = reminderDays,
                telegramBotToken = telegramBotToken,
                telegramBotUsername = telegramBotUsername,
                telegramEnabled = telegramEnabled,
                rubikaEnabled = rubikaEnabled
            )
            if (res.isSuccess) {
                showMessage("تنظیمات آموزشگاه با موفقیت ذخیره شد.")
                onComplete(true)
            } else {
                onComplete(false)
            }
        }
    }

    fun updateSettings(newSettings: AcademySettings, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (newSettings.managerUsername.isNotBlank()) {
                authRepository.setManagerUsername(newSettings.managerUsername)
            }
            val res = academyRepository.saveFullSettings(newSettings)
            if (res.isSuccess) {
                showMessage("تنظیمات با موفقیت ذخیره شد.")
                onComplete(true)
            } else {
                onComplete(false)
            }
        }
    }

    // --- Cloud Sync ---
    fun syncWithCloud(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _isSyncing.value = true
            val result = academyRepository.performCloudSync()
            _isSyncing.value = false
            if (result.isSuccess) {
                val summary = result.getOrNull()
                _lastSyncSummary.value = summary
                val msg = summary?.message ?: "همگام‌سازی ابری با موفقیت انجام شد."
                showMessage(msg)
                onResult(true, msg)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "خطا در همگام‌سازی ابری"
                showMessage(errorMsg)
                onResult(false, errorMsg)
            }
        }
    }

    // --- Export & Restore Backup ---
    fun exportDatabaseJson(includeSecrets: Boolean = false, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = academyRepository.generateBackupJson(includeSensitiveSecrets = includeSecrets)
            onResult(json)
        }
    }

    fun generateBackup(onResult: (String) -> Unit) {
        exportDatabaseJson(includeSecrets = false, onResult = onResult)
    }

    fun restoreBackupJson(jsonString: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = academyRepository.restoreBackupJson(jsonString)
            if (result.isSuccess) {
                val msg = result.getOrNull() ?: "بازیابی با موفقیت انجام شد."
                showMessage(msg)
                onResult(true, msg)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "خطا در بازیابی نسخه پشتیبان"
                showMessage(errorMsg)
                onResult(false, errorMsg)
            }
        }
    }

    // --- Sample Data ---
    fun loadSampleData() {
        viewModelScope.launch {
            academyRepository.populateSampleData()
            showMessage("داده‌های نمونه آموزشی و مالی اضافه شدند.")
        }
    }

    fun clearSampleData() {
        viewModelScope.launch {
            academyRepository.clearSampleData()
            showMessage("داده‌های نمونه با موفقیت پاک شدند.")
        }
    }

    // --- Reminder Scheduling Actions ---
    fun rescheduleAllReminders(onComplete: (Int) -> Unit = {}) {
        viewModelScope.launch {
            reminderScheduler.rescheduleAllPendingReminders()
            val list = reminders.value
            showMessage("تمام یادآوری‌های سیستم مجدداً زمانبندی شدند.")
            onComplete(list.size)
        }
    }

    fun cancelReminder(reminderId: String) {
        viewModelScope.launch {
            reminderScheduler.cancelReminder(reminderId)
            showMessage("یادآوری با موفقیت لغو شد.")
        }
    }

    fun triggerTestNotification(installment: Installment) {
        reminderScheduler.triggerImmediateTestReminder(installment, installment.studentName)
        showMessage("اعلان تستی برای ${installment.studentName} ارسال شد.")
    }

    fun triggerTestGeneralNotification(title: String, body: String) {
        val helper = com.example.service.NotificationHelper(getApplication())
        val notifId = (System.currentTimeMillis() % 100000).toInt()
        helper.showInstallmentDueNotification(
            notificationId = notifId,
            studentName = title,
            amountFormatted = "",
            dueDateShamsi = com.example.data.util.PersianDateUtil.currentShamsiString(),
            dueCategory = body
        )
        showMessage("اعلان تستی سیستم با موفقیت ارسال شد.")
    }

    fun scheduleAutomaticTelegramTest(
        student: Student,
        delayMinutes: Int = 1,
        exactHour: Int? = null,
        exactMinute: Int? = null,
        templateType: String = "REMINDER_3_DAYS",
        customAmount: Long = 2500000L,
        onResult: (Reminder) -> Unit = {}
    ) {
        viewModelScope.launch {
            val targetEpoch = if (exactHour != null && exactMinute != null) {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, exactHour)
                cal.set(java.util.Calendar.MINUTE, exactMinute)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    // if exact time has passed today, schedule for today + 1 min or keep it forward
                    cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                }
                cal.timeInMillis
            } else {
                System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
            }

            val scheduled = reminderScheduler.scheduleAutomaticTelegramTestReminder(
                student = student,
                targetEpoch = targetEpoch,
                templateType = templateType,
                customAmount = customAmount
            )
            val minStr = if (exactHour != null && exactMinute != null) {
                String.format("%02d:%02d", exactHour, exactMinute)
            } else {
                "$delayMinutes دقیقه دیگر"
            }
            showMessage("تست ارسال خودکار تلگرام برای $minStr زمانبندی شد (AlarmManager فعال شد).")
            onResult(scheduled)
        }
    }
}
