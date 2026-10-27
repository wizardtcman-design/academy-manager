package com.example.ui.screens.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.ui.unit.sp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.runtime.rememberCoroutineScope
import com.example.data.model.AcademySettings
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import com.example.data.util.PdfDocumentHelper
import com.example.data.util.PersianDateUtil
import com.example.data.util.StudentFinancialSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StudentDetailsDialog(
    student: Student,
    financialSummary: StudentFinancialSummary,
    enrollments: List<Enrollment>,
    installments: List<Installment>,
    payments: List<Payment>,
    courses: List<Course> = emptyList(),
    settings: AcademySettings? = null,
    reminders: List<com.example.data.model.Reminder> = emptyList(),
    telegramLink: String,
    onDismiss: () -> Unit,
    onEditStudent: (Student) -> Unit,
    onDeleteStudent: (Student) -> Unit,
    onNewEnrollment: (Student) -> Unit,
    onPayInstallment: (Student, Installment) -> Unit,
    onSetPaymentMethod: (Installment, String) -> Unit = { _, _ -> },
    onTestReminder: (Installment) -> Unit = {},
    onCancelReminder: (String) -> Unit = {},
    onViewReceipt: (Payment) -> Unit,
    onReversePayment: (Payment) -> Unit,
    onSendMessage: (Student, Installment?) -> Unit,
    contracts: List<com.example.data.model.Contract> = emptyList(),
    onIssueContractLink: (Enrollment, Boolean) -> Unit = { _, _ -> },
    onRefreshContract: (Enrollment) -> Unit = {},
    onRevokeContract: (Enrollment) -> Unit = {},
    onShareContractFile: (com.example.data.model.Contract, Boolean) -> Unit = { _, _ -> },
    onOpenSignPage: (com.example.data.model.Contract) -> Unit = {},
    onRegenerateTelegramLink: (Student) -> Unit = {},
    onResendSignLink: (Student) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("اقساط و دوره‌ها", "تراکنش‌های پرداخت", "پیام‌ها و تلگرام")

    val studentEnrollments = enrollments.filter { it.studentId == student.id }
    val studentInstallments = installments.filter { it.studentId == student.id }.sortedBy { it.installmentNumber }
    val studentPayments = payments.filter { it.studentId == student.id }.sortedByDescending { it.paymentDateEpoch }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .heightIn(max = 750.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("student_details_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = student.fullName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "کد هنرجو: ${student.studentCode.ifEmpty { "-" }} | ثبت‌نام: ${student.registrationDateShamsi}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = { onEditStudent(student) }) {
                            Icon(Icons.Default.Edit, contentDescription = "ویرایش", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "بستن")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Financial Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("وضعیت تسویه:", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.width(6.dp))
                                StatusBadge(financialSummary.settlementStatus)
                            }
                            Text(
                                text = "وصول شده: ${financialSummary.paymentPercentage}٪",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { financialSummary.paymentPercentage / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("کل شهریه", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(PersianDateUtil.formatCurrency(financialSummary.totalTuition), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                            Column {
                                Text("مجموع پرداختی", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(PersianDateUtil.formatCurrency(financialSummary.totalPaid), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Column {
                                Text("مانده بدهی", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    PersianDateUtil.formatCurrency(financialSummary.remainingDebt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (financialSummary.remainingDebt > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Communication Bar: primary action on its own line so the
                // labels never squeeze into vertical "شکسته" words.
                Button(
                    onClick = { onSendMessage(student, financialSummary.nearestDueInstallment) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ارسال پیام / یادآوری", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionButton(
                        icon = Icons.Default.Phone,
                        label = "تماس",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${student.phone}"))
                            context.startActivity(intent)
                        }
                    )
                    QuickActionButton(
                        icon = Icons.Default.Add,
                        label = "ثبت دوره",
                        modifier = Modifier.weight(1f),
                        onClick = { onNewEnrollment(student) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tab Content
                when (selectedTabIndex) {
                    0 -> InstallmentsTab(
                        student = student,
                        courses = courses,
                        settings = settings,
                        enrollments = studentEnrollments,
                        installments = studentInstallments,
                        reminders = reminders,
                        onPayInstallment = { inst -> onPayInstallment(student, inst) },
                        onSendMessage = { inst -> onSendMessage(student, inst) },
                        onSetPaymentMethod = onSetPaymentMethod,
                        onTestReminder = onTestReminder,
                        onCancelReminder = onCancelReminder,
                        contracts = contracts,
                        onIssueContractLink = onIssueContractLink,
                        onRefreshContract = onRefreshContract,
                        onRevokeContract = onRevokeContract,
                        onShareContractFile = onShareContractFile,
                        onOpenSignPage = onOpenSignPage
                    )
                    1 -> PaymentsTab(
                        payments = studentPayments,
                        onViewReceipt = onViewReceipt,
                        onReversePayment = onReversePayment
                    )
                    2 -> TelegramAndMessagesTab(
                        student = student,
                        telegramLink = telegramLink,
                        onRegenerateTelegramLink = onRegenerateTelegramLink,
                        onResendSignLink = onResendSignLink
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallmentsTab(
    student: Student,
    courses: List<Course>,
    settings: AcademySettings?,
    enrollments: List<Enrollment>,
    installments: List<Installment>,
    reminders: List<com.example.data.model.Reminder> = emptyList(),
    onPayInstallment: (Installment) -> Unit,
    onSetPaymentMethod: (Installment, String) -> Unit = { _, _ -> },
    onSendMessage: (Installment) -> Unit,
    onTestReminder: (Installment) -> Unit = {},
    onCancelReminder: (String) -> Unit = {},
    contracts: List<com.example.data.model.Contract> = emptyList(),
    onIssueContractLink: (Enrollment, Boolean) -> Unit = { _, _ -> },
    onRefreshContract: (Enrollment) -> Unit = {},
    onRevokeContract: (Enrollment) -> Unit = {},
    onShareContractFile: (com.example.data.model.Contract, Boolean) -> Unit = { _, _ -> },
    onOpenSignPage: (com.example.data.model.Contract) -> Unit = {},
    onRegenerateTelegramLink: (Student) -> Unit = {},
    onResendSignLink: (Student) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (installments.isEmpty() && enrollments.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("هیچ دوره یا اقساطی برای این هنرجو ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Enrolled Courses Section with PDF Contract Generator
            if (enrollments.isNotEmpty()) {
                item {
                    Text(
                        text = "دوره‌های ثبت‌نامی و قرارداد آموزشی",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }

                items(enrollments) { enrollment ->
                    val courseObj = courses.find { it.id == enrollment.courseId }
                    val enrollmentInsts = installments.filter { it.enrollmentId == enrollment.id }
                    val contract = contracts.firstOrNull { it.enrollmentId == enrollment.id }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = enrollment.courseTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "ثبت‌نام: ${enrollment.registrationDateShamsi}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "شهریه نهایی: ${PersianDateUtil.formatCurrency(enrollment.finalFee)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${PersianDateUtil.toPersianDigits(enrollment.installmentCount.toString())} قسط",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Mission: read-only certificate status on the student's
                            // enrollment rows (editing lives in Course Details).
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (enrollment.certificateStatus) {
                                        com.example.data.model.Enrollment.CERTIFICATE_PENDING ->
                                            com.example.ui.theme.StatusWarning.copy(alpha = 0.13f)
                                        com.example.data.model.Enrollment.CERTIFICATE_ISSUED ->
                                            com.example.ui.theme.StatusInfo.copy(alpha = 0.13f)
                                        com.example.data.model.Enrollment.CERTIFICATE_DELIVERED ->
                                            com.example.ui.theme.StatusSuccess.copy(alpha = 0.13f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = "مدرک: " + com.example.data.util.CourseStatusLabels
                                            .certificateStatusLabel(enrollment.certificateStatus),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when (enrollment.certificateStatus) {
                                            com.example.data.model.Enrollment.CERTIFICATE_PENDING ->
                                                com.example.ui.theme.StatusWarning
                                            com.example.data.model.Enrollment.CERTIFICATE_ISSUED ->
                                                com.example.ui.theme.StatusInfo
                                            com.example.data.model.Enrollment.CERTIFICATE_DELIVERED ->
                                                com.example.ui.theme.StatusSuccess
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (enrollment.enrolledViaOverride) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            com.example.ui.theme.StatusWarning.copy(alpha = 0.65f)
                                        )
                                    ) {
                                        Text(
                                            text = "ثبت‌نام استثنایی",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = com.example.ui.theme.StatusWarning,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Contract PDF Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val pdfFile = PdfDocumentHelper.generateEnrollmentContractPdf(
                                                    context = context,
                                                    enrollment = enrollment,
                                                    student = student,
                                                    course = courseObj,
                                                    installments = enrollmentInsts,
                                                    settings = settings
                                                )
                                                withContext(Dispatchers.Main) {
                                                    PdfDocumentHelper.sharePdfFile(
                                                        context = context,
                                                        pdfFile = pdfFile,
                                                        title = "قرارداد ثبت‌نام ${student.fullName}"
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "خطا در ساخت PDF قرارداد: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("اشتراک قرارداد PDF", style = MaterialTheme.typography.bodySmall)
                                }

                                OutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val pdfFile = PdfDocumentHelper.generateEnrollmentContractPdf(
                                                    context = context,
                                                    enrollment = enrollment,
                                                    student = student,
                                                    course = courseObj,
                                                    installments = enrollmentInsts,
                                                    settings = settings
                                                )
                                                withContext(Dispatchers.Main) {
                                                    PdfDocumentHelper.printOrOpenPdf(
                                                        context = context,
                                                        pdfFile = pdfFile,
                                                        jobName = "Contract_${enrollment.id.take(8)}"
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(context, "خطا در چاپ: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(0.7f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("چاپ", style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            ContractStatusBlock(
                                enrollment = enrollment,
                                contract = contract,
                                settings = settings,
                                onIssue = { force -> onIssueContractLink(enrollment, force) },
                                onRefresh = { onRefreshContract(enrollment) },
                                onRevoke = { onRevokeContract(enrollment) },
                                onShare = { signed -> contract?.let { onShareContractFile(it, signed) } },
                                onOpenSign = { contract?.let { onOpenSignPage(it) } }
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "لیست سررسید اقساط و یادآوری‌ها",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
            }

            // 2. Installments List with Rich Reminder Status
            items(installments) { inst ->
                val instReminders = reminders.filter { it.installmentId == inst.id }
                val nextScheduled = instReminders.filter { it.status == "SCHEDULED" }.minByOrNull { it.scheduledTimeEpoch }
                val lastSent = instReminders.filter { it.status == "SENT" }.maxByOrNull { it.createdAt }
                val lastFailed = instReminders.filter { it.status == "FAILED" }.maxByOrNull { it.createdAt }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "قسط ${inst.installmentNumber} از ${inst.totalInstallments} (${inst.courseTitle})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            InstallmentStatusBadge(inst.status)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("سررسید: ${inst.dueDateShamsi}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("مبلغ قسط: ${PersianDateUtil.formatCurrency(inst.amount)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }

                        if (inst.paidAmount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("پرداخت شده: ${PersianDateUtil.formatCurrency(inst.paidAmount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (inst.remainingAmount > 0) {
                                    Text("مانده: ${PersianDateUtil.formatCurrency(inst.remainingAmount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Reminder Status Line
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NotificationsActive,
                                        contentDescription = null,
                                        tint = if (inst.status == "PAID") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    val reminderText = when {
                                        inst.status == "PAID" -> "قسط تسویه شد (یادآوری غیرفعال)"
                                        nextScheduled != null -> {
                                            val jalaliTarget = PersianDateUtil.formatEpochToShamsi(nextScheduled.scheduledTimeEpoch)
                                            val timePart = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(nextScheduled.scheduledTimeEpoch))
                                            "یادآوری بعدی: $jalaliTarget ساعت $timePart"
                                        }
                                        lastSent != null -> "ارسال شد: ${lastSent.sendTimeShamsi.ifEmpty { "موفق" }}"
                                        lastFailed != null -> "ناموفق: ${lastFailed.errorMessage.take(25)}"
                                        else -> "یادآوری در حال زمانبندی"
                                    }

                                    Text(
                                        text = reminderText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = if (lastFailed != null && inst.status != "PAID") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (inst.status != "PAID") {
                                    // Quick Test Reminder Button
                                    Text(
                                        text = "تست اعلان",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { onTestReminder(inst) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (inst.status != "PAID") {
                            Spacer(modifier = Modifier.height(8.dp))
                            // Stage 9: the online gateway is COMPLETELY HIDDEN —
                            // «آنلاین» is no longer selectable anywhere in the UI.
                            // Card-to-card and POS remain the official methods; a
                            // historical ONLINE row still renders its label below.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "TRANSFER" to "کارت‌به‌کارت",
                                    "POS" to "کارتخوان"
                                ).forEach { (code, label) ->
                                    FilterChip(
                                        selected = inst.paymentMethod == code,
                                        onClick = { onSetPaymentMethod(inst, code) },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onSendMessage(inst) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = "یادآوری قسط", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = { onPayInstallment(inst) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ثبت پرداخت", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentsTab(
    payments: List<Payment>,
    onViewReceipt: (Payment) -> Unit,
    onReversePayment: (Payment) -> Unit
) {
    if (payments.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("هیچ تراکنش پرداختی ثبت نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(payments) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (p.isReversed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "کد: ${p.transactionId}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (p.isReversed) {
                                Text("لغو شده", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    text = PersianDateUtil.formatCurrency(p.amount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("تاریخ: ${p.paymentDateShamsi} ${p.paymentTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("قسط ${p.installmentNumber}", style = MaterialTheme.typography.bodySmall)
                        }

                        if (p.trackingNumber.isNotBlank()) {
                            Text("پیگیری: ${p.trackingNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (p.isReversed && p.reversalReason.isNotBlank()) {
                            Text("دلیل لغو: ${p.reversalReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        if (!p.isReversed) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onViewReceipt(p) },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(9.dp)
                                    ) {
                                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "رسید دیجیتال",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { onReversePayment(p) },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(9.dp)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "اصلاح / لغو",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramAndMessagesTab(
    student: Student,
    telegramLink: String,
    onRegenerateTelegramLink: (Student) -> Unit,
    onResendSignLink: (Student) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "ارتباط ربات تلگرام با هنرجو",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (student.isTelegramConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ربات تلگرام متصل است (شناسه چت: ${student.telegramChatId})", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("هنرجو هنوز ربات تلگرام را استارت نکرده است.", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "با اشتراک‌گذاری لینک اختصاصی زیر با هنرجو، اتصال تلگرام او به صورت خودکار برقرار می‌گردد:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("لینک تلگرام", telegramLink)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "لینک اتصال کپی شد.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("کپی لینک اختصاصی استارت تلگرام", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ابزار اضطراری مدیر: لینک فعلی را باطل و یک لینک یک‌بارمصرف جدید می‌سازد؛ لینک قبلی دیگر کار نمی‌کند.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onRegenerateTelegramLink(student) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تولید لینک جدید", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { onResendSignLink(student) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ارسال لینک امضا", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (bgColor, textColor, label) = when (status) {
        "تسویه کامل", "SETTLED" -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "تسویه کامل")
        "دارای دیرکرد" -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "دارای دیرکرد")
        "بدهکار", "DEBTOR" -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary, "بدهکار")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, status)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InstallmentStatusBadge(status: String) {
    val (bgColor, textColor, label) = when (status) {
        "PAID" -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "پرداخت شد")
        "PARTIAL_PAID" -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.tertiary, "پرداخت ناقص")
        "OVERDUE" -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "دیرکرد")
        "DUE_TODAY" -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, "سررسید امروز")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "پرداخت نشده")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/**
 * Compact status of the single digital contract of one enrollment: link state,
 * expiry, signature date, and the actions (issue / refresh / share / revoke).
 */
@Composable
private fun ContractStatusBlock(
    enrollment: Enrollment,
    contract: com.example.data.model.Contract?,
    settings: AcademySettings?,
    onIssue: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRevoke: () -> Unit,
    onShare: (Boolean) -> Unit,
    onOpenSign: () -> Unit
) {
    val status = contract?.status ?: "DRAFT"
    val signed = contract?.isSigned == true
    val hasLink = !contract?.signUrl.isNullOrBlank() && !signed &&
        (contract?.expiresAtEpoch ?: 0L) > System.currentTimeMillis()
    val expired = (contract?.expiresAtEpoch ?: 0L) in 1..System.currentTimeMillis()

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (signed) Icons.Default.Verified else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (signed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "قرارداد ${enrollment.contractNumber.ifBlank { contract?.id?.take(6) ?: "—" }}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                color = when {
                    signed -> MaterialTheme.colorScheme.primaryContainer
                    status == "FAILED" || status == "REVOKED" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    (contract?.statusFa ?: "پیش‌نویس") + if (expired) " (مهلت تمام شد)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (signed) {
            Text(
                PersianDateUtil.toPersianDigits("امضاشده در ${contract?.signedAtShamsi.orEmpty()}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (hasLink) {
            Text(
                PersianDateUtil.toPersianDigits(
                    "لینک امضا تا ${PersianDateUtil.formatEpochToShamsi(contract!!.expiresAtEpoch)} معتبر است"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!settings?.hasContractSignature.orFalse() || !settings?.hasContractSeal.orFalse()) {
            Text(
                "هشدار: مهر یا امضای آموزشگاه در تنظیمات ثبت نشده است.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        contract?.lastError?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            when {
                signed -> {
                    OutlinedButton(onClick = { onShare(true) }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("فایل امضاشده", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onRefresh, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("بروزرسانی", fontSize = 11.sp)
                    }
                }
                hasLink -> {
                    val context2 = LocalContext.current
                    Button(
                        onClick = onOpenSign,
                        enabled = com.example.service.messaging.TelegramStudentLink.isValidSignUrl(contract?.signUrl.orEmpty()),
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("باز کردن صفحه امضا", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val clipboard = context2.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("لینک امضای قرارداد", contract?.signUrl.orEmpty()))
                            Toast.makeText(context2, "لینک امضا کپی شد؛ برای هنرجو بفرستید.", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("کپی لینک", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onRefresh, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("بروزرسانی", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onRevoke, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("لغو لینک", fontSize = 11.sp)
                    }
                }
                else -> {
                    Button(onClick = { onIssue(expired || contract?.status == "EXPIRED") }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(if (expired) "صدور لینک جدید" else "ساخت لینک امضا", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onShare(false) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("فایل قرارداد", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun Boolean?.orFalse(): Boolean = this == true
