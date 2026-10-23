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
import androidx.compose.material3.TextButton
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
    val refunds by viewModel.refunds.collectAsState()
    val enrollments by viewModel.enrollments.collectAsState()
    val installments by viewModel.installments.collectAsState()
    val bankAccounts by viewModel.bankAccounts.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val todayShamsi = remember { PersianDateUtil.currentShamsiString() }
    val currentJalali = remember { PersianDateUtil.currentJalali() }

    var selectedPeriod by remember { mutableStateOf("MONTHLY") } // "DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL"

    // Stage 7: any selected Jalali month / year — the current one is just the default.
    var selectedYear by remember { mutableIntStateOf(currentJalali.year) }
    var selectedMonth by remember { mutableIntStateOf(currentJalali.month) }
    var showHealthDialog by remember { mutableStateOf(false) }
    var healthSummary by remember { mutableStateOf("") }

    val periodTitle = when (selectedPeriod) {
        "DAILY" -> "گزارش روزانه (${todayShamsi})"
        "WEEKLY" -> "گزارش ۷ روز اخیر"
        "MONTHLY" -> "گزارش ماه ${PersianDateUtil.getMonthName(selectedMonth)} ${selectedYear}"
        "QUARTERLY" -> "گزارش ۳ ماهه فصلی"
        "ANNUAL" -> "گزارش سال ${selectedYear}"
        else -> "گزارش جامع"
    }

    // Filter payments based on period (selected month/year for MONTHLY & ANNUAL)
    val periodPayments = remember(payments, selectedPeriod, selectedYear, selectedMonth) {
        val validPayments = payments.filter { !it.isReversed }
        when (selectedPeriod) {
            "DAILY" -> validPayments.filter { it.paymentDateShamsi == todayShamsi }
            "WEEKLY" -> {
                val sevenDaysAgoEpoch = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                validPayments.filter { it.paymentDateEpoch >= sevenDaysAgoEpoch }
            }
            "MONTHLY" -> {
                val monthPrefix = String.format("%04d/%02d", selectedYear, selectedMonth)
                validPayments.filter { it.paymentDateShamsi.startsWith(monthPrefix) }
            }
            "QUARTERLY" -> {
                val ninetyDaysAgoEpoch = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                validPayments.filter { it.paymentDateEpoch >= ninetyDaysAgoEpoch }
            }
            "ANNUAL" -> {
                val yearPrefix = "$selectedYear/"
                validPayments.filter { it.paymentDateShamsi.startsWith(yearPrefix) }
            }
            else -> validPayments
        }
    }

    // Stage 7: full income report from the REAL payment ledger (never tuition
    // or unpaid installments), plus refunds, debt and overdue totals.
    val incomeReport = remember(payments, refunds, installments, enrollments, selectedYear, selectedMonth, selectedPeriod) {
        com.example.data.util.ReportCalculator.build(
            query = com.example.data.util.ReportCalculator.Query(
                jalaliYear = selectedYear,
                jalaliMonth = if (selectedPeriod == "MONTHLY") selectedMonth else null
            ),
            payments = payments,
            refunds = refunds,
            installments = installments,
            enrollments = enrollments
        )
    }

    val totalIncome = periodPayments.sumOf { it.amount }
    val transactionCount = periodPayments.size

    // Method breakdown
    val posIncome = periodPayments.filter { it.paymentMethod == "POS" }.sumOf { it.amount }
    val transferIncome = periodPayments.filter { it.paymentMethod == "TRANSFER" }.sumOf { it.amount }
    val cashIncome = periodPayments.filter { it.paymentMethod == "CASH" }.sumOf { it.amount }
    val otherIncome = totalIncome - posIncome - transferIncome - cashIncome

    // Debt metrics (Stage 7: computed from raw data, excluding cancelled)
    val totalDebt = incomeReport.remainingDebt
    val overdueCount = incomeReport.overdueCount
    val overdueAmount = incomeReport.overdueAmount
    val periodRefundTotal = incomeReport.refunds

    val reportSummaryText = buildString {
        appendLine("📊 $periodTitle")
        appendLine("آموزشگاه: ${settings?.academyName ?: "آموزشگاه کامپیوتر"}")
        appendLine("━━━━━━━━━━━━━━━━━━━")
        appendLine("▫️ مجموع درآمد وصولی: ${PersianDateUtil.formatCurrency(totalIncome)}")
        if (periodRefundTotal > 0) appendLine("▫️ برگشت وجه: ${PersianDateUtil.formatCurrency(periodRefundTotal)}")
        appendLine("▫️ تعداد کل تراکنش‌ها: ${PersianDateUtil.toPersianDigits(transactionCount.toString())}")
        appendLine("▫️ کارتخوان (POS): ${PersianDateUtil.formatCurrency(posIncome)}")
        appendLine("▫️ کارت به کارت: ${PersianDateUtil.formatCurrency(transferIncome)}")
        appendLine("▫️ نقدی: ${PersianDateUtil.formatCurrency(cashIncome)}")
        appendLine("▫️ مانده کل بدهی‌ها: ${PersianDateUtil.formatCurrency(totalDebt)}")
        appendLine("▫️ اقساط معوقه: ${PersianDateUtil.toPersianDigits(overdueCount.toString())} عدد (${PersianDateUtil.formatCurrency(overdueAmount)})")
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
                title = "بازه",
                onSelect = { selectedPeriod = it },
                groups = listOf(
                    com.example.ui.components.FilterGroup(
                        "بازه زمانی گزارش",
                        listOf(
                            com.example.ui.components.FilterOption("DAILY", "روزانه (امروز)"),
                            com.example.ui.components.FilterOption("WEEKLY", "۷ روز اخیر"),
                            com.example.ui.components.FilterOption("MONTHLY", "ماه دلخواه"),
                            com.example.ui.components.FilterOption("QUARTERLY", "فصلی (۳ ماهه)"),
                            com.example.ui.components.FilterOption("ANNUAL", "سال دلخواه")
                        )
                    )
                )
            )
        }

        // Stage 7: month / year picker for the MONTHLY & ANNUAL views
        if (selectedPeriod == "MONTHLY" || selectedPeriod == "ANNUAL") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (selectedPeriod == "MONTHLY") "انتخاب ماه گزارش" else "انتخاب سال گزارش",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selectedPeriod == "MONTHLY") {
                                (1..12).forEach { m ->
                                    FilterChip(
                                        selected = selectedMonth == m,
                                        onClick = { selectedMonth = m },
                                        label = { Text(PersianDateUtil.getMonthName(m), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            } else {
                                ((currentJalali.year - 4)..currentJalali.year).forEach { y ->
                                    FilterChip(
                                        selected = selectedYear == y,
                                        onClick = { selectedYear = y },
                                        label = { Text(PersianDateUtil.toPersianDigits(y.toString()), style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
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

        // Stage 7: monthly breakdown of the whole year (income from REAL payments only)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "درآمد ماه‌های سال ${PersianDateUtil.toPersianDigits(selectedYear.toString())} (وصول واقعی منهای برگشت وجه)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    incomeReport.monthly.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(m.monthName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = buildString {
                                    append(PersianDateUtil.formatCurrency(m.net))
                                    if (m.refunds > 0) append(" (برگشت: ${PersianDateUtil.formatCurrency(m.refunds)})")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (m.net > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Stage 7: breakdown by course
        if (incomeReport.byCourse.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "تفکیک درآمد بر اساس دوره",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        incomeReport.byCourse.take(12).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${entry.name} (${PersianDateUtil.toPersianDigits(entry.count.toString())} تراکنش)", style = MaterialTheme.typography.bodyMedium)
                                Text(PersianDateUtil.formatCurrency(entry.amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // Stage 7: breakdown by student
        if (incomeReport.byStudent.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "تفکیک درآمد بر اساس هنرجو",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        incomeReport.byStudent.take(12).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                Text(PersianDateUtil.formatCurrency(entry.amount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // Stage 7: tuition vs collected vs debt vs overdue (reported separately)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "وضعیت شهریه، بدهی و اقساط معوق",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    MethodBreakdownRow(title = "شهریهٔ کل برنامه‌ها", amount = incomeReport.totalTuition, icon = Icons.Default.Assessment)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    MethodBreakdownRow(title = "درآمد واقعاً وصول‌شده (کل زمان‌ها)", amount = incomeReport.collected, icon = Icons.Default.TrendingUp)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    MethodBreakdownRow(title = "بدهی باقی‌مانده", amount = incomeReport.remainingDebt, icon = Icons.Default.AccountBalance)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    MethodBreakdownRow(title = "مبلغ اقساط معوق", amount = incomeReport.overdueAmount, icon = Icons.Default.DateRange)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "تعداد اقساط معوق: ${PersianDateUtil.toPersianDigits(incomeReport.overdueCount.toString())} · اقساط پرداخت‌نشده: ${PersianDateUtil.toPersianDigits(incomeReport.unpaidInstallmentsCount.toString())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (incomeReport.overdueCount > 0) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

        // Stage 7: free Excel-compatible CSV export via the existing FileProvider
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            val csv = com.example.data.util.ReportCalculator.toCsv(incomeReport)
                            val dir = context.cacheDir.resolve("reports").apply { mkdirs() }
                            val file = java.io.File(dir, "income-report-${selectedYear}${if (selectedPeriod == "MONTHLY") "-" + "%02d".format(selectedMonth) else ""}.csv")
                            file.writeText(csv, Charsets.UTF_8)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "گزارش درآمد ${selectedYear}")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "خروجی CSV (قابل باز کردن در Excel)"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "خروجی CSV ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Summarize, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("خروجی CSV (Excel)")
                }

                OutlinedButton(
                    onClick = {
                        val health = com.example.data.util.FinanceHealthChecker.check(
                            payments = payments,
                            installments = installments,
                            refunds = refunds
                        )
                        healthSummary = buildString {
                            appendLine(if (health.ok) "✅ ناسازگاری بحرانی پیدا نشد." else "⚠️ ${PersianDateUtil.toPersianDigits(health.errors.size.toString())} ناسازگاری مهم یافت شد.")
                            if (health.warnings.isNotEmpty()) {
                                appendLine("هشدارها: ${PersianDateUtil.toPersianDigits(health.warnings.size.toString())} مورد")
                            }
                            appendLine()
                            appendLine("پرداخت‌های بررسی‌شده: ${PersianDateUtil.toPersianDigits(health.checkedPayments.toString())}")
                            appendLine("اقساط بررسی‌شده: ${PersianDateUtil.toPersianDigits(health.checkedInstallments.toString())}")
                            appendLine("مجموع وصول‌شده: ${PersianDateUtil.formatCurrency(health.totalCollected)}")
                            appendLine("مجموع برگشت وجه: ${PersianDateUtil.formatCurrency(health.totalRefunds)}")
                            appendLine("بدهی باقی‌مانده: ${PersianDateUtil.formatCurrency(health.totalRemainingDebt)}")
                            appendLine()
                            (health.errors + health.warnings).take(8).forEach { appendLine("• ${it.message}") }
                            appendLine()
                            appendLine("این بررسی فقط گزارش می‌دهد؛ بدون تأیید شما هیچ دادهٔ مالی تغییر نمی‌کند.")
                        }
                        showHealthDialog = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("بررسی سلامت مالی")
                }
            }
        }
    }

    // Stage 7: read-only financial health result dialog
    if (showHealthDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHealthDialog = false },
            confirmButton = {
                TextButton(onClick = { showHealthDialog = false }) { Text("متوجه شدم") }
            },
            title = {
                Text("نتیجهٔ بررسی سلامت مالی", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    healthSummary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        )
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
