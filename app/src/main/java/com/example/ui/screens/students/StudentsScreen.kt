package com.example.ui.screens.students

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil
import com.example.ui.screens.dialogs.AddEditStudentDialog
import com.example.ui.screens.dialogs.DigitalReceiptDialog
import com.example.ui.screens.dialogs.EnrollStudentDialog
import com.example.ui.screens.dialogs.RecordPaymentDialog
import com.example.ui.screens.dialogs.ReversePaymentDialog
import com.example.ui.screens.dialogs.SendMessageDialog
import com.example.ui.screens.dialogs.StudentDetailsDialog
import com.example.ui.viewmodel.MainViewModel

@Composable
fun StudentsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val students by viewModel.students.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val enrollments by viewModel.enrollments.collectAsState()
    val installments by viewModel.installments.collectAsState()
    val payments by viewModel.payments.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val messageTemplates by viewModel.messageTemplates.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val searchQuery by viewModel.studentSearchQuery.collectAsState()

    var statusFilter by remember { mutableStateOf("ALL") }

    // Dialogs state
    var showAddStudentDialog by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<Student?>(null) }
    var selectedStudentForDetails by remember { mutableStateOf<Student?>(null) }
    var studentForEnrollment by remember { mutableStateOf<Student?>(null) }
    var installmentToPay by remember { mutableStateOf<Pair<Student, Installment>?>(null) }
    var studentForMessage by remember { mutableStateOf<Pair<Student, Installment?>?>(null) }
    var paymentForReceipt by remember { mutableStateOf<Payment?>(null) }
    var paymentToReverse by remember { mutableStateOf<Payment?>(null) }

    val filterOptions = listOf(
        "ALL" to "همه",
        "ACTIVE" to "فعال",
        "DEBTOR" to "بدهکار",
        "SETTLED" to "تسویه کامل",
        "FINISHED" to "پایان دوره",
        "WITHDRAWN" to "انصراف داده"
    )

    val filteredStudents = remember(students, statusFilter, enrollments, installments, payments) {
        if (statusFilter == "ALL") {
            students
        } else {
            students.filter { s ->
                val summary = viewModel.getStudentFinancialSummary(s.id)
                when (statusFilter) {
                    "DEBTOR" -> summary.remainingDebt > 0
                    "SETTLED" -> summary.remainingDebt == 0L && summary.totalTuition > 0L
                    "ACTIVE" -> s.status == "ACTIVE" || summary.remainingDebt > 0
                    "FINISHED" -> s.status == "FINISHED"
                    "WITHDRAWN" -> s.status == "WITHDRAWN"
                    else -> true
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddStudentDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(bottom = 72.dp)
                    .testTag("fab_add_student")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "افزودن هنرجو")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("هنرجوی جدید", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.studentSearchQuery.value = it },
                placeholder = { Text("جستجوی نام، کدهنرجویی، موبایل یا کدملی...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "جستجو",
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.studentSearchQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "پاک کردن")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("students_search_bar"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterOptions.forEach { option ->
                    FilterChip(
                        selected = statusFilter == option.first,
                        onClick = { statusFilter = option.first },
                        label = { Text(option.second, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "هنرجویان آموزشگاه",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${PersianDateUtil.toPersianDigits(filteredStudents.size.toString())} هنرجو",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredStudents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Text(
                            text = if (searchQuery.isNotEmpty()) "هنرجویی با این مشخصات یافت نشد." else "هنوز هنرجویی ثبت نشده است.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(filteredStudents, key = { _, st -> st.id }) { index, student ->
                        val summary = viewModel.getStudentFinancialSummary(student.id)
                        com.example.ui.components.RevealItem(index = index) {
                        StudentItemCard(
                            student = student,
                            totalTuition = summary.totalTuition,
                            totalPaid = summary.totalPaid,
                            remainingDebt = summary.remainingDebt,
                            settlementStatus = summary.settlementStatus,
                            onClick = { selectedStudentForDetails = student },
                            onCall = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${student.phone}"))
                                context.startActivity(intent)
                            },
                            onMessage = {
                                studentForMessage = Pair(student, summary.nearestDueInstallment)
                            }
                        )
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---

    // 1. Add Student
    if (showAddStudentDialog) {
        AddEditStudentDialog(
            studentToEdit = null,
            onDismiss = { showAddStudentDialog = false },
            onConfirm = { fName, lName, phone, natCode, notes ->
                viewModel.addStudent(fName, lName, phone, natCode, address = "", notes = notes) { success, _ ->
                    if (success) showAddStudentDialog = false
                }
            }
        )
    }

    // 2. Edit Student
    if (studentToEdit != null) {
        AddEditStudentDialog(
            studentToEdit = studentToEdit,
            onDismiss = { studentToEdit = null },
            onConfirm = { fName, lName, phone, natCode, notes ->
                val updated = studentToEdit!!.copy(
                    firstName = fName,
                    lastName = lName,
                    phone = phone,
                    nationalCode = natCode,
                    notes = notes
                )
                viewModel.updateStudent(updated) { success, _ ->
                    if (success) studentToEdit = null
                }
            }
        )
    }

    // 3. Student Details
    if (selectedStudentForDetails != null) {
        val s = selectedStudentForDetails!!
        val summary = viewModel.getStudentFinancialSummary(s.id)
        val deepLink = viewModel.getStudentTelegramLink(s)

        StudentDetailsDialog(
            student = s,
            financialSummary = summary,
            enrollments = enrollments,
            installments = installments,
            payments = payments,
            courses = courses,
            settings = settings,
            reminders = reminders,
            telegramLink = deepLink,
            onDismiss = { selectedStudentForDetails = null },
            onEditStudent = { st ->
                studentToEdit = st
                selectedStudentForDetails = null
            },
            onDeleteStudent = { st ->
                viewModel.deleteStudent(st)
                selectedStudentForDetails = null
            },
            onNewEnrollment = { st ->
                studentForEnrollment = st
            },
            onPayInstallment = { st, inst ->
                installmentToPay = Pair(st, inst)
            },
            onTestReminder = { inst ->
                viewModel.triggerTestNotification(inst)
            },
            onCancelReminder = { remId ->
                viewModel.cancelReminder(remId)
            },
            onViewReceipt = { p ->
                paymentForReceipt = p
            },
            onReversePayment = { p ->
                paymentToReverse = p
            },
            onSendMessage = { st, inst ->
                studentForMessage = Pair(st, inst)
            }
        )
    }

    // 4. Enroll Student Dialog
    if (studentForEnrollment != null) {
        EnrollStudentDialog(
            students = students,
            courses = courses,
            initialStudent = studentForEnrollment,
            onDismiss = { studentForEnrollment = null },
            onEnroll = { st, crs, startD, disc, count, amounts, firstD, paidNow, nts ->
                viewModel.enrollStudent(
                    student = st,
                    course = crs,
                    startDateShamsi = startD,
                    discount = disc,
                    installmentCount = count,
                    customInstallmentAmounts = amounts,
                    firstInstallmentDateShamsi = firstD,
                    firstInstallmentPaidNow = paidNow,
                    notes = nts
                ) { success, _ ->
                    if (success) studentForEnrollment = null
                }
            }
        )
    }

    // 5. Record Payment Dialog
    if (installmentToPay != null) {
        val (st, inst) = installmentToPay!!
        RecordPaymentDialog(
            student = st,
            installment = inst,
            bankAccounts = bankAccounts,
            onDismiss = { installmentToPay = null },
            onRecordPayment = { amount, pDate, pTime, method, accId, accTitle, tracking, nts ->
                viewModel.recordPayment(
                    studentId = st.id,
                    installmentId = inst.id,
                    amount = amount,
                    paymentDateShamsi = pDate,
                    paymentTime = pTime,
                    paymentMethod = method,
                    destinationAccountId = accId,
                    destinationAccountTitle = accTitle,
                    trackingNumber = tracking,
                    receiptImage = "",
                    notes = nts
                ) { success, payment, _ ->
                    if (success && payment != null) {
                        installmentToPay = null
                        paymentForReceipt = payment
                    }
                }
            }
        )
    }

    // 6. Digital Receipt Dialog
    if (paymentForReceipt != null) {
        DigitalReceiptDialog(
            payment = paymentForReceipt!!,
            settings = settings,
            onDismiss = { paymentForReceipt = null }
        )
    }

    // 7. Send Message Dialog
    if (studentForMessage != null) {
        val (st, inst) = studentForMessage!!
        val deepLink = viewModel.getStudentTelegramLink(st)
        SendMessageDialog(
            student = st,
            installment = inst,
            templates = messageTemplates,
            settings = settings,
            telegramDeepLink = deepLink,
            onDismiss = { studentForMessage = null },
            onSendMessage = { text, channel ->
                viewModel.sendMessageToStudent(st, text, channel) { _ -> }
            }
        )
    }

    // 8. Reverse Payment Dialog
    if (paymentToReverse != null) {
        ReversePaymentDialog(
            payment = paymentToReverse!!,
            onDismiss = { paymentToReverse = null },
            onConfirmReverse = { reason ->
                viewModel.reversePayment(paymentToReverse!!, reason) { success, _ ->
                    if (success) paymentToReverse = null
                }
            }
        )
    }
}

@Composable
private fun StudentItemCard(
    student: Student,
    totalTuition: Long,
    totalPaid: Long,
    remainingDebt: Long,
    settlementStatus: String,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "studentPress"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(press)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .testTag("student_card_${student.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = if (pressed) 2.dp else 7.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.20f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar
                Surface(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (student.fullName.isNotEmpty()) student.fullName.take(1) else "ه",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = student.fullName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (student.studentCode.isNotBlank()) {
                            Text(
                                text = "(${student.studentCode})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = PersianDateUtil.toPersianDigits(student.phone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Quick Communication Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCall,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "تماس", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onMessage,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = "ارسال پیام", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Financial Summary Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مانده بدهی: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = PersianDateUtil.formatCurrency(remainingDebt),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (remainingDebt > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                // Status Badge
                val (bgColor, textColor, label) = when {
                    settlementStatus == "تسویه کامل" || remainingDebt == 0L && totalTuition > 0L ->
                        Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "تسویه کامل")
                    remainingDebt > 0 ->
                        Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "بدهکار")
                    else ->
                        Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "بدون دوره")
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = label, color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
