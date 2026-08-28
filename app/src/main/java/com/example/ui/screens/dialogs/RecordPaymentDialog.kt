package com.example.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BankAccount
import com.example.data.model.Installment
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPaymentDialog(
    student: Student,
    installment: Installment,
    bankAccounts: List<BankAccount>,
    onDismiss: () -> Unit,
    onRecordPayment: (
        amount: Long,
        paymentDateShamsi: String,
        paymentTime: String,
        paymentMethod: String,
        destinationAccountId: String,
        destinationAccountTitle: String,
        trackingNumber: String,
        notes: String
    ) -> Unit
) {
    val initialAmount = installment.remainingAmount.coerceAtLeast(1L)
    var amountText by remember { mutableStateOf(initialAmount.toString()) }

    val todayShamsi = remember { PersianDateUtil.currentShamsiString() }
    val currentTime = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
    var paymentDateShamsi by remember { mutableStateOf(todayShamsi) }
    var paymentTime by remember { mutableStateOf(currentTime) }

    val paymentMethods = listOf(
        "TRANSFER" to "کارت به کارت",
        "POS" to "کارتخوان (POS)",
        "CASH" to "نقدی",
        "BANK_DEPOSIT" to "واریز به حساب بانکی",
        "OTHER" to "سایر روش‌ها"
    )
    var selectedMethod by remember { mutableStateOf(paymentMethods.first()) }
    var methodDropdownExpanded by remember { mutableStateOf(false) }

    var selectedAccount by remember { mutableStateOf(bankAccounts.find { it.isDefault } ?: bankAccounts.firstOrNull()) }
    var accountDropdownExpanded by remember { mutableStateOf(false) }

    var trackingNumber by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val parsedAmount = amountText.toLongOrNull() ?: 0L
    val isValidAmount = parsedAmount > 0
    val isPartial = parsedAmount < installment.remainingAmount
    val isOver = parsedAmount > installment.remainingAmount

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("record_payment_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Text(
                    text = "ثبت تراکنش دریافت شهریه",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Installment Info Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("هنرجو:", style = MaterialTheme.typography.bodyMedium)
                            Text("${student.fullName} (${student.studentCode})", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("دوره و قسط:", style = MaterialTheme.typography.bodyMedium)
                            Text("${installment.courseTitle} - قسط ${installment.installmentNumber} از ${installment.totalInstallments}", fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("مبلغ کل قسط:", style = MaterialTheme.typography.bodyMedium)
                            Text(PersianDateUtil.formatCurrency(installment.amount))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("مانده قابل پرداخت:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(PersianDateUtil.formatCurrency(installment.remainingAmount), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount Field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("مبلغ واریزی (تومان) *") },
                    leadingIcon = { Icon(Icons.Default.Payments, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_amount_input"),
                    singleLine = true
                )

                if (isPartial) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "توجه: این پرداخت به صورت «پرداخت ناقص» ثبت شده و مبلغ ${PersianDateUtil.formatCurrency(installment.remainingAmount - parsedAmount)} به عنوان مانده قسط باقی می‌ماند.",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (isOver) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "هشدار: مبلغ وارد شده از مانده این قسط بیشتر است.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Date & Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = paymentDateShamsi,
                        onValueChange = { paymentDateShamsi = it },
                        label = { Text("تاریخ پرداخت") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = paymentTime,
                        onValueChange = { paymentTime = it },
                        label = { Text("ساعت") },
                        leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Payment Method
                ExposedDropdownMenuBox(
                    expanded = methodDropdownExpanded,
                    onExpandedChange = { methodDropdownExpanded = !methodDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedMethod.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("روش پرداخت") },
                        leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = methodDropdownExpanded,
                        onDismissRequest = { methodDropdownExpanded = false }
                    ) {
                        paymentMethods.forEach { method ->
                            DropdownMenuItem(
                                text = { Text(method.second) },
                                onClick = {
                                    selectedMethod = method
                                    methodDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Destination Bank Account
                if (bankAccounts.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = accountDropdownExpanded,
                        onExpandedChange = { accountDropdownExpanded = !accountDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedAccount?.let { "${it.accountTitle} (${it.maskedNumber})" } ?: "انتخاب حساب/کارت مقصد",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("حساب / کارت مقصد آموزشگاه") },
                            leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = accountDropdownExpanded,
                            onDismissRequest = { accountDropdownExpanded = false }
                        ) {
                            bankAccounts.forEach { acc ->
                                DropdownMenuItem(
                                    text = { Text("${acc.accountTitle} - ${acc.bankName} (${acc.maskedNumber})") },
                                    onClick = {
                                        selectedAccount = acc
                                        accountDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Tracking Number
                OutlinedTextField(
                    value = trackingNumber,
                    onValueChange = { trackingNumber = it },
                    label = { Text("شماره پیگیری / کد ارجاع بانکی") },
                    leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("توضیحات و بابت") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("انصراف")
                    }
                    Button(
                        onClick = {
                            if (isValidAmount) {
                                onRecordPayment(
                                    parsedAmount,
                                    paymentDateShamsi,
                                    paymentTime,
                                    selectedMethod.first,
                                    selectedAccount?.id ?: "",
                                    selectedAccount?.accountTitle ?: "حساب آموزشگاه",
                                    trackingNumber,
                                    notes
                                )
                            }
                        },
                        enabled = isValidAmount,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("submit_payment_button")
                    ) {
                        Text("ثبت و صدور رسید")
                    }
                }
            }
        }
    }
}
