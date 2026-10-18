package com.example.ui.screens.reports

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Payment
import com.example.data.util.PersianDateUtil
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ReportsScreen(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val payments by viewModel.payments.collectAsState()
    val enrollments by viewModel.enrollments.collectAsState()
    val installments by viewModel.installments.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var selectedPeriod by remember { mutableStateOf("MONTHLY") } // "DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL"

    val todayShamsi = remember { PersianDateUtil.currentShamsiString() }
    val currentJalali = remember { PersianDateUtil.currentJalali() }

    val periodTitle = when (selectedPeriod) {
        "DAILY" -> "گزارش روزانه (${todayShamsi})"
        "WEEKLY" -> "گزارش ۷ روز اخیر"
        "MONTHLY" -> "گزارش ماه ${PersianDateUtil.getMonthName(currentJalali.month)} ${currentJalali.year}"
        "QUARTERLY" -> "گزارش ۳ ماهه فصلی"
        "ANNUAL" -> "گزارش سال ${currentJalali.year}"
        else -> "گزارش جامع"
    }

    // Filter payments based on period
    val periodPayments = remember(payments, selectedPeriod) {
        val validPayments = payments.filter { !it.isReversed }
        when (selectedPeriod) {
            "DAILY" -> validPayments.filter { it.paymentDateShamsi == todayShamsi }
            "WEEKLY" -> {
                val sevenDaysAgoEpoch = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                validPayments.filter { it.paymentDateEpoch >= sevenDaysAgoEpoch }
            }
            "MONTHLY" -> {
                val monthPrefix = String.format("%04d/%02d", currentJalali.year, currentJalali.month)
                validPayments.filter { it.paymentDateShamsi.startsWith(monthPrefix) }
            }
            "QUARTERLY" -> {
                val ninetyDaysAgoEpoch = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                validPayments.filter { it.paymentDateEpoch >= ninetyDaysAgoEpoch }
            }
            "ANNUAL" -> {
                val yearPrefix = "${currentJalali.year}/"
                validPayments.filter { it.paymentDateShamsi.startsWith(yearPrefix) }
            }
            else -> validPayments
        }
    }

    val totalIncome = periodPayments.sumOf { it.amount }
    val transactionCount = periodPayments.size

    // Method breakdown
    val posIncome = periodPayments.filter { it.paymentMethod == "POS" }.sumOf { it.amount }
    val transferIncome = periodPayments.filter { it.paymentMethod == "TRANSFER" }.sumOf { it.amount }
    val cashIncome = periodPayments.filter { it.paymentMethod == "CASH" }.sumOf { it.amount }
    val otherIncome = totalIncome - posIncome - transferIncome - cashIncome

    // Debt metrics
    val totalDebt = installments.filter { it.status != "PAID" }.sumOf { it.remainingAmount }
    val overdueCount = installments.count { it.status == "OVERDUE" }

    val reportSummaryText = buildString {
        appendLine("📊 $periodTitle")
        appendLine("آموزشگاه: ${settings?.academyName ?: "آموزشگاه کامپیوتر"}")
        appendLine("━━━━━━━━━━━━━━━━━━━")
        appendLine("▫️ مجموع درآمد وصولی: ${PersianDateUtil.formatCurrency(totalIncome)}")
        appendLine("▫️ تعداد کل تراکنش‌ها: ${PersianDateUtil.toPersianDigits(transactionCount.toString())}")
        appendLine("▫️ کارتخوان (POS): ${PersianDateUtil.formatCurrency(posIncome)}")
        appendLine("▫️ کارت به کارت: ${PersianDateUtil.formatCurrency(transferIncome)}")
        appendLine("▫️ نقدی: ${PersianDateUtil.formatCurrency(cashIncome)}")
        appendLine("▫️ مانده کل بدهی‌ها: ${PersianDateUtil.formatCurrency(totalDebt)}")
        appendLine("▫️ تعداد اقساط معوقه: ${PersianDateUtil.toPersianDigits(overdueCount.toString())}")
        appendLine("━━━━━━━━━━━━━━━━━━━")
        appendLine("تاریخ صدور گزارش: $todayShamsi")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "مرکز گزارش‌های مالی و مدیریتی",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "تحلیل هوشمند و برخط درآمدها، مطالبات و گردش حساب‌ها",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Period filter: grouped in a sheet so all options fit narrow screens
        item {
            com.example.ui.components.FilterBar(
                options = listOf(
                    com.example.ui.components.FilterOption("DAILY", "امروز"),
                    com.example.ui.components.FilterOption("WEEKLY", "۷ روز"),
                    com.example.ui.components.FilterOption("MONTHLY", "ماه جاری"),
                    com.example.ui.components.FilterOption("QUARTERLY", "فصلی"),
                    com.example.ui.components.FilterOption("ANNUAL", "سالانه")
                ),
                selectedKey = selectedPeriod,
                onSelect = { selectedPeriod = it },
                groups = listOf(
                    com.example.ui.components.FilterGroup(
                        "بازه زمانی گزارش",
                        listOf(
                            com.example.ui.components.FilterOption("DAILY", "روزانه (امروز)"),
                            com.example.ui.components.FilterOption("WEEKLY", "۷ روز اخیر"),
                            com.example.ui.components.FilterOption("MONTHLY", "ماه جاری"),
                            com.example.ui.components.FilterOption("QUARTERLY", "فصلی (۳ ماهه)"),
                            com.example.ui.components.FilterOption("ANNUAL", "سالانه")
                        )
                    )
                )
            )
        }

        // Income Hero Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = periodTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "مجموع واریزی‌ها و درآمد دوره:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = PersianDateUtil.formatCurrency(totalIncome),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تعداد تراکنش‌های موفق:", style = MaterialTheme.typography.bodySmall)
                        Text("${PersianDateUtil.toPersianDigits(transactionCount.toString())} فقره", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Methods Breakdown Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تفکیک روش‌های دریافت",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    MethodBreakdownRow(title = "دستگاه پوز (کارتخوان)", amount = posIncome, icon = Icons.Default.PointOfSale)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    MethodBreakdownRow(title = "کارت به کارت", amount = transferIncome, icon = Icons.Default.CreditCard)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    MethodBreakdownRow(title = "نقدی", amount = cashIncome, icon = Icons.Default.TrendingUp)

                    if (otherIncome > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                        MethodBreakdownRow(title = "سایر روش‌ها", amount = otherIncome, icon = Icons.Default.AccountBalance)
                    }
                }
            }
        }

        // Bank Accounts Distribution Card
        if (bankAccounts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ورودی مالی به حساب‌های آموزشگاه در این دوره",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        bankAccounts.forEach { acc ->
                            val accTotal = periodPayments.filter { it.destinationAccountId == acc.id }.sumOf { it.amount }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(acc.accountTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("${acc.bankName} (${acc.maskedNumber})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(PersianDateUtil.formatCurrency(accTotal), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        // Action Buttons (Share & Copy Report)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("گزارش مالی", reportSummaryText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "خلاصه گزارش کپی شد.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("کپی گزارش")
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, reportSummaryText)
                        }
                        context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری گزارش مالی"))
                    },
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ارسال و اشتراک‌گذاری")
                }
            }
        }
    }
}

@Composable
private fun MethodBreakdownRow(
    title: String,
    amount: Long,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = PersianDateUtil.formatCurrency(amount),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
