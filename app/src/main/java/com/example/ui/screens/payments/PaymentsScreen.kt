package com.example.ui.screens.payments

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BankAccount
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import com.example.data.repository.DebtorStudentItem
import com.example.data.util.PersianDateUtil
import com.example.ui.screens.dialogs.AddEditBankAccountDialog
import com.example.ui.screens.dialogs.DigitalReceiptDialog
import com.example.ui.screens.dialogs.RecordPaymentDialog
import com.example.ui.screens.dialogs.ReversePaymentDialog
import com.example.ui.screens.dialogs.SendMessageDialog
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PaymentsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val payments by viewModel.payments.collectAsState()
    val installments by viewModel.installments.collectAsState()
    val students by viewModel.students.collectAsState()
    val debtorStudents by viewModel.debtorStudents.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val messageTemplates by viewModel.messageTemplates.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("تراکنش‌های واریزی", "بدهکاران و معوقات", "تقویم اقساط", "حساب‌ها و کارت‌ها")

    // Dialog States
    var showRecordPaymentDialog by remember { mutableStateOf(false) }
    var selectedPaymentForReceipt by remember { mutableStateOf<Payment?>(null) }
    var paymentToReverse by remember { mutableStateOf<Payment?>(null) }
    var debtorToPay by remember { mutableStateOf<Pair<Student, Installment>?>(null) }
    var debtorToMessage by remember { mutableStateOf<Pair<Student, Installment?>?>(null) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<BankAccount?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // Open quick payment for first available pending installment or prompt
                    val pendingInst = installments.firstOrNull { it.status != "PAID" }
                    val student = pendingInst?.let { inst -> students.find { it.id == inst.studentId } }
                    if (pendingInst != null && student != null) {
                        debtorToPay = Pair(student, pendingInst)
                    } else if (students.isNotEmpty() && installments.isNotEmpty()) {
                        val firstInst = installments.first()
                        val st = students.find { it.id == firstInst.studentId } ?: students.first()
                        debtorToPay = Pair(st, firstInst)
                    } else {
                        viewModel.showMessage("لطفاً ابتدا هنرجو و دوره ثبت نمایید.")
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .padding(bottom = 72.dp)
                    .testTag("fab_record_payment")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ثبت واریزی جدید", fontWeight = FontWeight.Bold)
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

            Text(
                text = "مدیریت مالی، اقساط و واریزی‌ها",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Tab Bar
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> TransactionsTab(
                    payments = payments,
                    onViewReceipt = { selectedPaymentForReceipt = it },
                    onReversePayment = { paymentToReverse = it }
                )
                1 -> DebtorsTab(
                    debtors = debtorStudents,
                    installments = installments,
                    onPay = { st, inst -> debtorToPay = Pair(st, inst) },
                    onMessage = { st, inst -> debtorToMessage = Pair(st, inst) },
                    onCall = { phone ->
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                        context.startActivity(intent)
                    }
                )
                2 -> InstallmentCalendarTab(
                    installments = installments,
                    payments = payments,
                    students = students,
                    onPayInstallment = { st, inst -> debtorToPay = Pair(st, inst) },
                    onSendMessage = { st, inst -> debtorToMessage = Pair(st, inst) }
                )
                3 -> BankAccountsTab(
                    accounts = bankAccounts,
                    payments = payments,
                    onAddAccount = { showAddAccountDialog = true },
                    onDeleteAccount = { viewModel.deleteBankAccount(it) }
                )
            }
        }
    }

    // Dialogs

    // 1. Record Payment Dialog
    if (debtorToPay != null) {
        val (st, inst) = debtorToPay!!
        RecordPaymentDialog(
            student = st,
            installment = inst,
            bankAccounts = bankAccounts,
            onDismiss = { debtorToPay = null },
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
                        debtorToPay = null
                        selectedPaymentForReceipt = payment
                    }
                }
            }
        )
    }

    // 2. Digital Receipt Dialog
    if (selectedPaymentForReceipt != null) {
        DigitalReceiptDialog(
            payment = selectedPaymentForReceipt!!,
            settings = settings,
            onDismiss = { selectedPaymentForReceipt = null }
        )
    }

    // 3. Send Message Dialog
    if (debtorToMessage != null) {
        val (st, inst) = debtorToMessage!!
        val deepLink = viewModel.getStudentTelegramLink(st)
        SendMessageDialog(
            student = st,
            installment = inst,
            templates = messageTemplates,
            settings = settings,
            telegramDeepLink = deepLink,
            onDismiss = { debtorToMessage = null },
            onSendMessage = { text, channel ->
                viewModel.sendMessageToStudent(st, text, channel) { _ -> }
            }
        )
    }

    // 4. Reverse Payment Dialog
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

    // 5. Add / Edit Bank Account Dialog
    if (showAddAccountDialog || accountToEdit != null) {
        AddEditBankAccountDialog(
            initialAccount = accountToEdit,
            onDismiss = {
                showAddAccountDialog = false
                accountToEdit = null
            },
            onSave = { bName, type, aTitle, cNumber, sNumber, accNumber, aHolder, isDef ->
                if (accountToEdit != null) {
                    viewModel.updateBankAccount(
                        accountToEdit!!.copy(
                            bankName = bName,
                            type = type,
                            accountTitle = aTitle,
                            cardNumber = cNumber,
                            shabaNumber = sNumber,
                            accountNumber = accNumber,
                            accountHolder = aHolder,
                            isDefault = isDef
                        )
                    ) { success ->
                        if (success) {
                            showAddAccountDialog = false
                            accountToEdit = null
                        }
                    }
                } else {
                    viewModel.addBankAccount(
                        bankName = bName,
                        type = type,
                        accountTitle = aTitle,
                        cardNumber = cNumber,
                        shabaNumber = sNumber,
                        accountNumber = accNumber,
                        accountHolder = aHolder,
                        isDefault = isDef
                    ) { success ->
                        if (success) {
                            showAddAccountDialog = false
                            accountToEdit = null
                        }
                    }
                }
            }
        )
    }
}

// ----------------------------------------------------
// TAB 1: TRANSACTIONS LIST
// ----------------------------------------------------
@Composable
private fun TransactionsTab(
    payments: List<Payment>,
    onViewReceipt: (Payment) -> Unit,
    onReversePayment: (Payment) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(payments, searchQuery) {
        if (searchQuery.isBlank()) payments
        else payments.filter {
            it.studentName.contains(searchQuery, ignoreCase = true) ||
            it.transactionId.contains(searchQuery, ignoreCase = true) ||
            it.trackingNumber.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("جستجوی تراکنش با کد، نام هنرجو یا پیگیری...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotBlank()) "تراکنشی یافت نشد." else "هنوز تراکنش پرداختی ثبت نشده است.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { payment ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (payment.isReversed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (payment.isReversed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Payment,
                                                contentDescription = null,
                                                tint = if (payment.isReversed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(payment.studentName, fontWeight = FontWeight.Bold)
                                        Text("کد: ${payment.transactionId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                if (payment.isReversed) {
                                    Text("لغو شده", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text(
                                        PersianDateUtil.formatCurrency(payment.amount),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("تاریخ: ${payment.paymentDateShamsi} ${payment.paymentTime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("قسط ${payment.installmentNumber}", style = MaterialTheme.typography.bodySmall)
                            }

                            if (payment.trackingNumber.isNotBlank()) {
                                Text("پیگیری بانکی: ${payment.trackingNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            if (payment.destinationAccountTitle.isNotBlank()) {
                                Text("حساب مقصد: ${payment.destinationAccountTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            if (payment.isReversed && payment.reversalReason.isNotBlank()) {
                                Text("علت لغو: ${payment.reversalReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }

                            if (!payment.isReversed) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onReversePayment(payment) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("اصلاح / لغو", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    OutlinedButton(
                                        onClick = { onViewReceipt(payment) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("رسید دیجیتال", style = MaterialTheme.typography.labelSmall)
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

// ----------------------------------------------------
// TAB 2: DEBTORS LIST
// ----------------------------------------------------
@Composable
private fun DebtorsTab(
    debtors: List<DebtorStudentItem>,
    installments: List<Installment>,
    onPay: (Student, Installment) -> Unit,
    onMessage: (Student, Installment?) -> Unit,
    onCall: (String) -> Unit
) {
    var sortBy by remember { mutableStateOf("DEBT") } // "DEBT", "OVERDUE", "DUE_DATE"

    val sortedList = remember(debtors, sortBy) {
        when (sortBy) {
            "DEBT" -> debtors.sortedByDescending { it.remainingDebt }
            "OVERDUE" -> debtors.sortedByDescending { it.overdueDays }
            "DUE_DATE" -> debtors.sortedBy { it.nearestDueDateShamsi }
            else -> debtors
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sorting Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = sortBy == "DEBT",
                onClick = { sortBy = "DEBT" },
                label = { Text("بیشترین بدهی", style = MaterialTheme.typography.bodySmall) }
            )
            FilterChip(
                selected = sortBy == "OVERDUE",
                onClick = { sortBy = "OVERDUE" },
                label = { Text("بیشترین دیرکرد", style = MaterialTheme.typography.bodySmall) }
            )
            FilterChip(
                selected = sortBy == "DUE_DATE",
                onClick = { sortBy = "DUE_DATE" },
                label = { Text("نزدیک‌ترین سررسید", style = MaterialTheme.typography.bodySmall) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (sortedList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("در حال حاضر هیچ هنرجوی بدهکاری وجود ندارد. تمام حساب‌ها تسویه هستند!", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedList, key = { it.student.id }) { item ->
                    val pendingInst = installments.find { it.studentId == item.student.id && it.status != "PAID" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(item.student.fullName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Text(item.courseTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = PersianDateUtil.formatCurrency(item.remainingDebt),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    if (item.overdueDays > 0) {
                                        Text(
                                            text = "${item.overdueDays} روز دیرکرد",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("اقساط باقی‌مانده: ${item.remainingInstallments} از ${item.totalInstallments}", style = MaterialTheme.typography.bodySmall)
                                Text("سررسید بعدی: ${item.nearestDueDateShamsi}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onCall(item.student.phone) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تماس", style = MaterialTheme.typography.bodySmall)
                                }

                                OutlinedButton(
                                    onClick = { onMessage(item.student, pendingInst) },
                                    modifier = Modifier.weight(1.2f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("یادآوری", style = MaterialTheme.typography.bodySmall)
                                }

                                if (pendingInst != null) {
                                    Button(
                                        onClick = { onPay(item.student, pendingInst) },
                                        modifier = Modifier.weight(1.2f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
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
}

// ----------------------------------------------------
// TAB 3: SHAMSI CALENDAR
// ----------------------------------------------------
@Composable
private fun InstallmentCalendarTab(
    installments: List<Installment>,
    payments: List<Payment>,
    students: List<Student>,
    onPayInstallment: (Student, Installment) -> Unit,
    onSendMessage: (Student, Installment) -> Unit
) {
    val currentJalali = remember { PersianDateUtil.currentJalali() }
    var selectedYear by remember { mutableIntStateOf(currentJalali.year) }
    var selectedMonth by remember { mutableIntStateOf(currentJalali.month) }
    var selectedDay by remember { mutableIntStateOf(currentJalali.day) }

    val daysInMonth = if (selectedMonth <= 6) 31 else if (selectedMonth <= 11) 30 else 29

    val selectedDateStr = String.format("%04d/%02d/%02d", selectedYear, selectedMonth, selectedDay)

    val dayDueInstallments = remember(installments, selectedDateStr) {
        installments.filter { it.dueDateShamsi == selectedDateStr }
    }

    val dayPayments = remember(payments, selectedDateStr) {
        payments.filter { it.paymentDateShamsi == selectedDateStr && !it.isReversed }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Month Navigation
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (selectedMonth > 1) selectedMonth-- else { selectedMonth = 12; selectedYear-- }
                }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "ماه قبل")
                }

                Text(
                    text = "${PersianDateUtil.getMonthName(selectedMonth)} $selectedYear",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                IconButton(onClick = {
                    if (selectedMonth < 12) selectedMonth++ else { selectedMonth = 1; selectedYear++ }
                }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "ماه بعد")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Days Grid (1 to 31/30/29)
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items((1..daysInMonth).toList()) { day ->
                val dateStr = String.format("%04d/%02d/%02d", selectedYear, selectedMonth, day)
                val hasDue = installments.any { it.dueDateShamsi == dateStr && it.status != "PAID" }
                val hasPaid = payments.any { it.paymentDateShamsi == dateStr && !it.isReversed }
                val isSelected = selectedDay == day

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                hasDue -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                hasPaid -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                        )
                        .clickable { selectedDay = day },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = PersianDateUtil.toPersianDigits(day.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isSelected || hasDue) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "رویدادهای تاریخ $selectedDateStr:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dayDueInstallments.isEmpty() && dayPayments.isEmpty()) {
                item {
                    Text("هیچ سررسید قسط یا دریافتی در این روز ثبت نشده است.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items(dayDueInstallments) { inst ->
                val st = students.find { it.id == inst.studentId }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("سررسید قسط: ${inst.studentName}", fontWeight = FontWeight.Bold)
                            Text(PersianDateUtil.formatCurrency(inst.remainingAmount), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Text("${inst.courseTitle} - قسط ${inst.installmentNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (inst.status != "PAID" && st != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = { onSendMessage(st, inst) }) {
                                    Icon(Icons.Default.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                                Button(
                                    onClick = { onPayInstallment(st, inst) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("ثبت پرداخت", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            items(dayPayments) { p ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("دریافتی: ${p.studentName}", fontWeight = FontWeight.Bold)
                            Text("ساعت: ${p.paymentTime} | کد: ${p.transactionId}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(PersianDateUtil.formatCurrency(p.amount), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// TAB 4: BANK ACCOUNTS & CARDS
// ----------------------------------------------------
@Composable
private fun BankAccountsTab(
    accounts: List<BankAccount>,
    payments: List<Payment>,
    onAddAccount: () -> Unit,
    onDeleteAccount: (BankAccount) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("کارت‌ها و حساب‌های بانکی آموزشگاه", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Button(
                onClick = onAddAccount,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("کارت جدید", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (accounts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("هیچ حساب یا کارت بانکی تعریف نشده است.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(accounts, key = { it.id }) { acc ->
                    val accPayments = payments.filter { it.destinationAccountId == acc.id && !it.isReversed }
                    val totalReceived = accPayments.sumOf { it.amount }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(acc.accountTitle, fontWeight = FontWeight.Bold)
                                        Text(acc.bankName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { onDeleteAccount(acc) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = acc.maskedNumber,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (acc.accountHolder.isNotBlank()) {
                                Text("صاحب حساب: ${acc.accountHolder}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("مجموع واریزی‌ها به این کارت:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = PersianDateUtil.formatCurrency(totalReceived),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
