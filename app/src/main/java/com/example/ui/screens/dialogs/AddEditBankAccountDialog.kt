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

    com.example.ui.components.AppFormDialog(
        title = if (initialAccount == null) "افزودن کارت / حساب" else "ویرایش حساب بانکی",
        subtitle = "برای واریز شهریه و نمایش در قبوض و پیام‌ها",
        icon = Icons.Default.AccountBalance,
        onDismiss = onDismiss,
        confirmText = "ذخیره مشخصات",
        onConfirm = {
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
        confirmEnabled = isValid,
        maxHeight = 780,
        testTag = "add_edit_bank_account_dialog"
    ) {
        // Live card preview
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
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
                                Icons.Default.CreditCard,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = bankName.ifBlank { "نام بانک" },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        if (isDefault) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFFFD54F)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF5D4037), modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("پیش‌فرض", color = Color(0xFF5D4037), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    val cleanCard = cardNumber.filter { it.isDigit() }
                    val displayNum = when {
                        cleanCard.length >= 16 ->
                            "${cleanCard.substring(0, 4)}   ${cleanCard.substring(4, 8)}   ${cleanCard.substring(8, 12)}   ${cleanCard.substring(12, 16)}"
                        cleanCard.isNotEmpty() -> cleanCard.chunked(4).joinToString("   ")
                        else -> "----   ----   ----   ----"
                    }
                    Text(
                        text = displayNum,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("صاحب حساب", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
                            Text(
                                text = accountHolder.ifBlank { "مدیریت آموزشگاه" },
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (shabaNumber.isNotBlank()) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("شماره شبا", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
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

        com.example.ui.components.FormSection(title = "شناسهٔ حساب", icon = Icons.Default.AccountBalance) {
            com.example.ui.components.FormTextField(
                value = accountTitle,
                onValueChange = { accountTitle = it },
                label = "عنوان حساب / برچسب *",
                leadingIcon = Icons.Default.Title
            )
            com.example.ui.components.FormTextField(
                value = bankName,
                onValueChange = { bankName = it },
                label = "نام بانک *",
                leadingIcon = Icons.Default.AccountBalance
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

        com.example.ui.components.FormSection(
            title = "شماره‌ها",
            icon = Icons.Default.CreditCard,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            com.example.ui.components.FormTextField(
                value = cardNumber,
                onValueChange = { input -> cardNumber = input.filter { it.isDigit() }.take(16) },
                label = "شماره کارت (۱۶ رقم)",
                leadingIcon = Icons.Default.CreditCard,
                supportingText = "${cardNumber.filter { it.isDigit() }.length} از ۱۶ رقم",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            com.example.ui.components.FormTextField(
                value = shabaNumber,
                onValueChange = { input ->
                    var clean = input.filter { it.isLetterOrDigit() }.uppercase()
                    clean = if (clean.startsWith("IR")) {
                        "IR" + clean.removePrefix("IR").filter { it.isDigit() }.take(24)
                    } else {
                        clean.filter { it.isDigit() }.take(24)
                    }
                    shabaNumber = clean
                },
                label = "شماره شبا (IBAN)",
                leadingIcon = Icons.Default.AccountBalance
            )
            com.example.ui.components.FormTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it.filter { c -> c.isDigit() } },
                label = "شماره حساب (اختیاری)",
                leadingIcon = Icons.Default.CreditCard,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            com.example.ui.components.FormTextField(
                value = accountHolder,
                onValueChange = { accountHolder = it },
                label = "نام صاحب حساب",
                leadingIcon = Icons.Default.Person
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("حساب پیش‌فرض آموزشگاه", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    "در قبوض و ثبت پرداخت‌ها به‌صورت پیش‌فرض انتخاب می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = isDefault, onCheckedChange = { isDefault = it })
        }
        Spacer(Modifier.height(4.dp))
    }
}
