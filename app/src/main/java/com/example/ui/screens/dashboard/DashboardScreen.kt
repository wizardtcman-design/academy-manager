package com.example.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AssignmentLate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import com.example.data.model.User
import com.example.data.repository.DashboardStats
import com.example.data.util.PersianDateUtil
import com.example.ui.screens.dialogs.AddEditStudentDialog
import com.example.ui.screens.dialogs.DigitalReceiptDialog
import com.example.ui.screens.dialogs.EnrollStudentDialog
import com.example.ui.screens.dialogs.RecordPaymentDialog
import com.example.ui.screens.dialogs.SendMessageDialog
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.TealSecondary
import com.example.ui.viewmodel.MainViewModel

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    user: User?,
    settings: AcademySettings?,
    stats: DashboardStats,
    onNavigateToStudents: () -> Unit,
    onNavigateToCourses: () -> Unit,
    onNavigateToPayments: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenAddStudent: () -> Unit,
    onLoadSampleData: () -> Unit,
    onClearSampleData: () -> Unit
) {
    val currentShamsiFull = PersianDateUtil.currentShamsiFullString()
    val dayOfWeek = PersianDateUtil.getDayOfWeekPersian()
    val currencyUnit = settings?.currencyUnit ?: "تومان"

    val installments by viewModel.installments.collectAsState()
    val students by viewModel.students.collectAsState()
    val courses by viewModel.courses.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val messageTemplates by viewModel.messageTemplates.collectAsState()

    val urgentInstallments = remember(installments) {
        installments.filter { it.status == "OVERDUE" || it.status == "DUE_TODAY" }
            .sortedBy { it.dueDateEpoch }
            .take(5)
    }

    var showEnrollDialog by remember { mutableStateOf(false) }
    var installmentToPay by remember { mutableStateOf<Pair<Student, Installment>?>(null) }
    var studentToMessage by remember { mutableStateOf<Pair<Student, Installment?>?>(null) }
    var paymentForReceipt by remember { mutableStateOf<Payment?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Academy Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = settings?.academyName ?: "آموزشگاه کامپیوتر",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "خوش‌آمدید، ${user?.fullName ?: "مدیر آموزشگاه"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape),
                                color = Color.White.copy(alpha = 0.2f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.School,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Date banner inside header
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$dayOfWeek، $currentShamsiFull",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Actions Section
        item {
            Text(
                text = "دسترسی سریع",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionButton(
                    title = "ثبت هنرجو",
                    icon = Icons.Default.PersonAdd,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_action_add_student"),
                    onClick = onOpenAddStudent
                )

                QuickActionButton(
                    title = "ثبت‌نام دوره",
                    icon = Icons.Default.School,
                    color = TealSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_action_enroll_course"),
                    onClick = { showEnrollDialog = true }
                )

                QuickActionButton(
                    title = "گزارش مالی",
                    icon = Icons.Default.Assessment,
                    color = AmberTertiary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_action_daily_report"),
                    onClick = onNavigateToReports
                )
            }
        }

        // 6 Live Statistics Cards
        item {
            Text(
                text = "آمار و وضعیت مالی آموزشگاه",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        title = "تعداد کل هنرجویان",
                        value = "${PersianDateUtil.toPersianDigits(stats.totalStudents.toString())} نفر",
                        icon = Icons.Default.People,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToStudents() }
                    )

                    StatCard(
                        title = "تعداد دوره‌ها",
                        value = "${PersianDateUtil.toPersianDigits(stats.totalCourses.toString())} دوره",
                        icon = Icons.Default.School,
                        tint = TealSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToCourses() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        title = "دریافتی امروز",
                        value = PersianDateUtil.formatCurrency(stats.todayReceipts, currencyUnit),
                        icon = Icons.Default.TrendingUp,
                        tint = StatusSuccess,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToPayments() }
                    )

                    StatCard(
                        title = "بدهی کل هنرجویان",
                        value = PersianDateUtil.formatCurrency(stats.totalDebt, currencyUnit),
                        icon = Icons.Default.AccountBalanceWallet,
                        tint = StatusWarning,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToPayments() }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(
                        title = "اقساط سررسیدشده",
                        value = "${PersianDateUtil.toPersianDigits(stats.dueInstallmentsCount.toString())} مورد",
                        icon = Icons.Default.EventNote,
                        tint = StatusWarning,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToPayments() }
                    )

                    StatCard(
                        title = "اقساط معوقه و عقب‌افتاده",
                        value = "${PersianDateUtil.toPersianDigits(stats.overdueInstallmentsCount.toString())} مورد",
                        icon = Icons.Default.AssignmentLate,
                        tint = StatusError,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToPayments() }
                    )
                }
            }
        }

        // Urgent Follow-up Section
        if (urgentInstallments.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اقساط نیازمند پیگیری فوری",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "مشاهده همه",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigateToPayments() }
                    )
                }
            }

            items(urgentInstallments, key = { it.id }) { inst ->
                val st = students.find { it.id == inst.studentId }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(inst.studentName, fontWeight = FontWeight.Bold)
                            Text(
                                text = PersianDateUtil.formatCurrency(inst.remainingAmount),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${inst.courseTitle} (قسط ${inst.installmentNumber})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("سررسید: ${inst.dueDateShamsi}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }

                        if (st != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { studentToMessage = Pair(st, inst) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Button(
                                    onClick = { installmentToPay = Pair(st, inst) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ثبت پرداخت", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sample Data Control Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "پایگاه داده آفلاین Room و Cloud فعال است",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = "جهت بررسی و تست سریع امکانات، می‌توانید داده‌های نمونه را بارگذاری کرده و در صورت تمایل با ۱ کلیک حذف کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onLoadSampleData,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ایجاد داده نمونه", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onClearSampleData,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("حذف داده نمونه", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Dialogs on Dashboard
    if (showEnrollDialog) {
        EnrollStudentDialog(
            students = students,
            courses = courses,
            onDismiss = { showEnrollDialog = false },
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
                    if (success) showEnrollDialog = false
                }
            }
        )
    }

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

    if (paymentForReceipt != null) {
        DigitalReceiptDialog(
            payment = paymentForReceipt!!,
            settings = settings,
            onDismiss = { paymentForReceipt = null }
        )
    }

    if (studentToMessage != null) {
        val (st, inst) = studentToMessage!!
        val deepLink = viewModel.getStudentTelegramLink(st)
        SendMessageDialog(
            student = st,
            installment = inst,
            templates = messageTemplates,
            settings = settings,
            telegramDeepLink = deepLink,
            onDismiss = { studentToMessage = null },
            onSendMessage = { text, channel ->
                viewModel.sendMessageToStudent(st, text, channel) { _ -> }
            }
        )
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = tint.copy(alpha = 0.12f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
