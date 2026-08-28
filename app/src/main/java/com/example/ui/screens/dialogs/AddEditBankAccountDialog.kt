package com.example.ui.screens.dialogs

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.BankAccount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditBankAccountDialog(
    initialAccount: BankAccount? = null,
    onDismiss: () -> Unit,
    onSave: (
        bankName: String,
        type: String,
        accountTitle: String,
        cardNumber: String,
        shabaNumber: String,
        accountNumber: String,
        accountHolder: String,
        isDefault: Boolean
    ) -> Unit
) {
    var bankName by remember { mutableStateOf(initialAccount?.bankName ?: "بانک ملت") }
    var accountTitle by remember { mutableStateOf(initialAccount?.accountTitle ?: "کارت اصلی واریز شهریه") }
    var cardNumber by remember { mutableStateOf(initialAccount?.cardNumber ?: "") }
    var shabaNumber by remember { mutableStateOf(initialAccount?.shabaNumber ?: "") }
    var accountNumber by remember { mutableStateOf(initialAccount?.accountNumber ?: "") }
    var accountHolder by remember { mutableStateOf(initialAccount?.accountHolder ?: "") }
    var isDefault by remember { mutableStateOf(initialAccount?.isDefault ?: (initialAccount == null)) }

    val popularBanks = listOf(
        "بانک ملت",
        "بانک ملی",
        "بانک سامان",
        "بانک صادرات",
        "بانک پاسارگاد",
        "بانک تجارت",
        "بانک سپه",
        "بانک رسالت",
        "بلوبانک",
        "بانک پارسیان",
        "بانک کشاورزی"
    )

    val accountTypes = listOf(
        "CARD" to "کارت بانکی (۱۶ رقمی)",
        "SHABA" to "شماره شبا (IBAN)",
        "ACCOUNT" to "شماره حساب جاری / پس‌انداز"
    )
    var selectedType by remember {
        mutableStateOf(accountTypes.find { it.first == (initialAccount?.type ?: "CARD") } ?: accountTypes.first())
    }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    val isValid = bankName.isNotBlank() && accountTitle.isNotBlank() && (cardNumber.isNotBlank() || shabaNumber.isNotBlank() || accountNumber.isNotBlank())

    // Card gradient colors based on bank
    val cardColors = remember(bankName) {
        when {
            bankName.contains("ملت") -> listOf(Color(0xFFB71C1C), Color(0xFFD32F2F), Color(0xFFFF5252))
            bankName.contains("ملی") -> listOf(Color(0xFF0D47A1), Color(0xFF1565C0), Color(0xFF1976D2))
            bankName.contains("سامان") -> listOf(Color(0xFF006064), Color(0xFF00838F), Color(0xFF00ACC1))
            bankName.contains("پاسارگاد") -> listOf(Color(0xFFE65100), Color(0xFFEF6C00), Color(0xFFFFA726))
            bankName.contains("بلو") -> listOf(Color(0xFF1E88E5), Color(0xFF29B6F6), Color(0xFF4FC3F7))
            bankName.contains("رسالت") -> listOf(Color(0xFF1B5E20), Color(0xFF2E7D32), Color(0xFF4CAF50))
            bankName.contains("تجارت") -> listOf(Color(0xFF283593), Color(0xFF3949AB), Color(0xFF5C6BC0))
            bankName.contains("صادرات") -> listOf(Color(0xFF4A148C), Color(0xFF6A1B9A), Color(0xFF8E24AA))
            else -> listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF475569))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(26.dp))
                .testTag("add_edit_bank_account_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (initialAccount == null) "افزودن کارت / حساب بانکی" else "ویرایش حساب بانکی",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "مشخصات حساب جهت واریز شهریه و نمایش در قبوض و پیام‌ها",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Live Visual Card Preview
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    shape = RoundedCornerShape(18.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(Brush.linearGradient(cardColors))
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CreditCard,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = bankName.ifBlank { "نام بانک" },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }

                                if (isDefault) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFFFD54F)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                tint = Color(0xFF5D4037),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "کارت پیش‌فرض",
                                                color = Color(0xFF5D4037),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Card Number
                            val cleanCard = cardNumber.filter { it.isDigit() }
                            val displayNum = if (cleanCard.length >= 16) {
                                "${cleanCard.substring(0, 4)}   ${cleanCard.substring(4, 8)}   ${cleanCard.substring(8, 12)}   ${cleanCard.substring(12, 16)}"
                            } else if (cleanCard.isNotEmpty()) {
                                cleanCard.chunked(4).joinToString("   ")
                            } else {
                                "----   ----   ----   ----"
                            }

                            Text(
                                text = displayNum,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Bottom row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column {
                                    Text(
                                        text = "صاحب حساب",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 9.sp
                                    )
                                    Text(
                                        text = accountHolder.ifBlank { "مدیریت آموزشگاه" },
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                if (shabaNumber.isNotBlank()) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "شماره شبا",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 9.sp
                                        )
                                        Text(
                                            text = if (shabaNumber.startsWith("IR", ignoreCase = true)) shabaNumber.uppercase() else "IR$shabaNumber",
                                            color = Color.White.copy(alpha = 0.95f),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick Bank Selection Chips
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "انتخاب سریع بانک:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(popularBanks) { b ->
                            FilterChip(
                                selected = bankName == b,
                                onClick = { bankName = b },
                                label = { Text(b, fontSize = 11.sp) },
                                leadingIcon = if (bankName == b) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }
                }

                // Account Title
                OutlinedTextField(
                    value = accountTitle,
                    onValueChange = { accountTitle = it },
                    label = { Text("عنوان حساب / برچسب *") },
                    placeholder = { Text("مثلاً: کارت اصلی واریز شهریه") },
                    leadingIcon = { Icon(Icons.Default.Title, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Bank Name Input
                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("نام بانک *") },
                    placeholder = { Text("مثلاً: بانک ملت") },
                    leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Card Number (16 Digits)
                OutlinedTextField(
                    value = cardNumber,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(16)
                        cardNumber = digits
                    },
                    label = { Text("شماره کارت بانکی (۱۶ رقم)") },
                    placeholder = { Text("مثلاً: ۶۱۰۴۳۳۷۸۹۹۹۹۱۲۳۴") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                    supportingText = {
                        Text("${cardNumber.filter { it.isDigit() }.length} از ۱۶ رقم وارد شده است")
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Shaba Number (IBAN)
                OutlinedTextField(
                    value = shabaNumber,
                    onValueChange = { input ->
                        var clean = input.filter { it.isLetterOrDigit() }.uppercase()
                        if (clean.startsWith("IR")) {
                            clean = "IR" + clean.removePrefix("IR").filter { it.isDigit() }.take(24)
                        } else {
                            clean = clean.filter { it.isDigit() }.take(24)
                        }
                        shabaNumber = clean
                    },
                    label = { Text("شماره شبا (IBAN)") },
                    placeholder = { Text("مثلاً: IR120120000000001234567890") },
                    leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Account Number
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it.filter { c -> c.isDigit() } },
                    label = { Text("شماره حساب (اختیاری)") },
                    placeholder = { Text("مثلاً: ۱۲۳۴۵۶۷۸۹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.CreditCard, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Account Holder
                OutlinedTextField(
                    value = accountHolder,
                    onValueChange = { accountHolder = it },
                    label = { Text("نام و نام خانوادگی صاحب حساب") },
                    placeholder = { Text("مثلاً: مدیریت آموزشگاه کامپیوتر") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Default Switch
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "حساب پیش‌فرض آموزشگاه",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "این حساب در پیامک‌ها، قبوض و ثبت پرداخت‌ها به صورت پیش‌فرض انتخاب می‌شود.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("انصراف")
                    }
                    Button(
                        onClick = {
                            if (isValid) {
                                onSave(
                                    bankName.trim(),
                                    selectedType.first,
                                    accountTitle.trim(),
                                    cardNumber.trim(),
                                    shabaNumber.trim(),
                                    accountNumber.trim(),
                                    accountHolder.trim(),
                                    isDefault
                                )
                            }
                        },
                        enabled = isValid,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("ذخیره مشخصات")
                    }
                }
            }
        }
    }
}
