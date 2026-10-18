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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val contractRows by viewModel.contractRows.collectAsState()
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
    var enrollPicker by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<Student?>(null) }
    var selectedStudentForDetails by remember { mutableStateOf<Student?>(null) }
    var studentForEnrollment by remember { mutableStateOf<Student?>(null) }
    var installmentToPay by remember { mutableStateOf<Pair<Student, Installment>?>(null) }
    var studentForMessage by remember { mutableStateOf<Pair<Student, Installment?>?>(null) }
    var paymentForReceipt by remember { mutableStateOf<Payment?>(null) }
    var paymentToReverse by remember { mutableStateOf<Payment?>(null) }

    // The three most used filters stay as chips; the rest are grouped in the
    // filter sheet so the row always fits a phone screen.
    val filterOptions = listOf(
        com.example.ui.components.FilterOption("ALL", "همه"),
        com.example.ui.components.FilterOption("ACTIVE", "فعال"),
        com.example.ui.components.FilterOption("DEBTOR", "بدهکار"),
        com.example.ui.components.FilterOption("SETTLED", "تسویه"),
        com.example.ui.components.FilterOption("FINISHED", "پایان"),
        com.example.ui.components.FilterOption("WITHDRAWN", "انصراف")
    )
    val filterGroups = listOf(
        com.example.ui.components.FilterGroup("وضعیت مالی", listOf(filterOptions[0], filterOptions[2], filterOptions[3])),
        com.example.ui.components.FilterGroup("وضعیت دوره", listOf(filterOptions[1], filterOptions[4], filterOptions[5]))
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
        bottomBar = {
            com.example.ui.components.PinnedActionBar(
                title = "هنرجوی جدید",
                icon = Icons.Default.PersonAdd,
                subtitle = PersianDateUtil.toPersianDigits(students.size.toString()),
                onClick = { showAddStudentDialog = true },
                testTag = "fab_add_student"
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "هنرجویان آموزشگاه",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "جستجو، فیلتر و مدیریت پرونده هنرجویان",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Search bar. A custom row instead of OutlinedTextField: the field
            // locked its own height and the placeholder ended up clipped under
            // the bottom edge. Here height, vertical centring and line height are
            // explicit, so the hint always renders fully.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("students_search_bar"),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "جستجو",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.studentSearchQuery.value = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "جستجوی نام، کد، موبایل یا کدملی",
                                        fontSize = 12.5.sp,
                                        lineHeight = 17.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.studentSearchQuery.value = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "پاک کردن",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Collapsible, grouped filters
            com.example.ui.components.FilterBar(
                options = filterOptions,
                selectedKey = statusFilter,
                onSelect = { statusFilter = it },
                groups = filterGroups,
                title = "فیلتر"
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "فهرست هنرجویان",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${PersianDateUtil.toPersianDigits(filteredStudents.size.toString())} هنرجو",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = students.isNotEmpty() && courses.isNotEmpty()) {
                                enrollPicker = true
                            }
                            .testTag("enroll_shortcut")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "ثبت‌نام",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredStudents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
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
                    contentPadding = PaddingValues(bottom = 24.dp),
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
            },
            contracts = contractRows,
            onIssueContractLink = { enrollment, force ->
                viewModel.issueContractLink(enrollment, force)
            },
            onRefreshContract = { viewModel.refreshContract(it) },
            onRevokeContract = { viewModel.revokeContractLink(it) },
            onShareContractFile = { c, signed -> viewModel.shareContractFile(c, signed) },
            onOpenSignPage = { c -> viewModel.openUrlExternally(c.signUrl) }
        )
    }

    // 3.b Quick picker opened from the "ثبت‌نام" shortcut in the header.
    if (enrollPicker) {
        com.example.ui.screens.dialogs.EnrollStudentPickerDialog(
            students = students,
            onDismiss = { enrollPicker = false },
            onPick = { st ->
                enrollPicker = false
                studentForEnrollment = st
            }
        )
    }

    // 4. Enroll Student Dialog
    if (studentForEnrollment != null) {
        EnrollStudentDialog(
            students = students,
            courses = courses,
            existingEnrollments = enrollments,
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
                ) { success, error ->
                    if (success) {
                        studentForEnrollment = null
                    } else {
                        viewModel.showMessage(error ?: "ثبت‌نام ذخیره نشد. اطلاعات را بررسی کنید.")
                    }
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
                        .clip(RoundedCornerShape(15.dp)),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            )
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (student.fullName.isNotEmpty()) student.fullName.take(1) else "ه",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
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
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = PersianDateUtil.toPersianDigits(student.phone),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (student.studentCode.isNotBlank()) {
                            Text(
                                text = " · ",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = PersianDateUtil.toPersianDigits(student.studentCode),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
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
