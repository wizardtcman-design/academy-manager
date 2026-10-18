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
import androidx.compose.material.icons.filled.Receipt
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

    com.example.ui.components.AppFormDialog(
        title = "ثبت دریافت شهریه",
        subtitle = "مبلغ، تاریخ و روش پرداخت را ثبت کنید",
        icon = Icons.Default.Payments,
        onDismiss = onDismiss,
        confirmText = "ثبت و صدور رسید",
        onConfirm = {
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
        confirmEnabled = isValidAmount,
        maxHeight = 760,
        testTag = "record_payment_dialog"
    ) {
        com.example.ui.components.FormSection(title = "جزئیات قسط", icon = Icons.Default.Receipt) {
            InfoRow("هنرجو", "${student.fullName} (${student.studentCode})")
            InfoRow("دوره و قسط", "${installment.courseTitle} - قسط ${installment.installmentNumber} از ${installment.totalInstallments}")
            InfoRow("مبلغ کل قسط", PersianDateUtil.formatCurrency(installment.amount))
            InfoRow(
                "مانده قابل پرداخت",
                PersianDateUtil.formatCurrency(installment.remainingAmount),
                valueColor = MaterialTheme.colorScheme.primary
            )
        }

        com.example.ui.components.FormSection(
            title = "مبلغ و تاریخ",
            icon = Icons.Default.Payments,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            com.example.ui.components.FormTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = "مبلغ واریزی (تومان) *",
                leadingIcon = Icons.Default.Payments,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                testTag = "payment_amount_input"
            )
            if (isPartial) {
                Text(
                    text = "این پرداخت ناقص است؛ ${PersianDateUtil.formatCurrency(installment.remainingAmount - parsedAmount)} به ماندهٔ قسط اضافه می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (isOver) {
                Text(
                    text = "مبلغ وارد شده از ماندهٔ این قسط بیشتر است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.example.ui.components.JalaliDateField(
                    value = paymentDateShamsi,
                    onValueChange = { paymentDateShamsi = it },
                    label = "تاریخ پرداخت",
                    modifier = Modifier.weight(1.3f)
                )
                com.example.ui.components.FormTextField(
                    value = paymentTime,
                    onValueChange = { paymentTime = it },
                    label = "ساعت",
                    leadingIcon = Icons.Default.AccessTime,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        com.example.ui.components.FormSection(
            title = "روش و حساب مقصد",
            icon = Icons.Default.AccountBalance,
            accent = MaterialTheme.colorScheme.secondary
        ) {
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
                    shape = RoundedCornerShape(14.dp),
                    colors = com.example.ui.components.FormTextFieldColors(),
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

            if (bankAccounts.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = accountDropdownExpanded,
                    onExpandedChange = { accountDropdownExpanded = !accountDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedAccount?.let { "${it.accountTitle} (${it.maskedNumber})" } ?: "انتخاب حساب/کارت مقصد",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("حساب / کارت مقصد") },
                        leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                        shape = RoundedCornerShape(14.dp),
                        colors = com.example.ui.components.FormTextFieldColors(),
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
            }
            com.example.ui.components.FormTextField(
                value = trackingNumber,
                onValueChange = { trackingNumber = it },
                label = "شماره پیگیری / کد ارجاع",
                leadingIcon = Icons.Default.Numbers
            )
            com.example.ui.components.FormTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "توضیحات و بابت (اختیاری)",
                leadingIcon = Icons.Default.Description,
                singleLine = false,
                minLines = 2,
                maxLines = 2
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
