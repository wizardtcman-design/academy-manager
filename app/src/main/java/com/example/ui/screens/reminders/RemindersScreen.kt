package com.example.ui.screens.reminders

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademySettings
import com.example.ui.screens.settings.MessageSettingsSection
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()

    var remind7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysEnabled ?: true) }
    var remind7DaysTime by remember(settings) { mutableStateOf(settings?.reminder7DaysTime ?: "10:00") }
    var adminTitle7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysAdminTitle ?: "🔔 یادآوری ۷ روز قبل: {studentName}") }
    var adminTemplate7Days by remember(settings) { mutableStateOf(settings?.reminder7DaysAdminTemplate ?: "یادآوری: ۷ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({courseName})") }

    var remind3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysEnabled ?: true) }
    var remind3DaysTime by remember(settings) { mutableStateOf(settings?.reminder3DaysTime ?: "10:00") }
    var adminTitle3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysAdminTitle ?: "🔔 یادآوری ۳ روز قبل: {studentName}") }
    var adminTemplate3Days by remember(settings) { mutableStateOf(settings?.reminder3DaysAdminTemplate ?: "یادآوری: ۳ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} باقی مانده است.") }

    var remind1Day by remember(settings) { mutableStateOf(settings?.reminder1DayEnabled ?: true) }
    var remind1DayTime by remember(settings) { mutableStateOf(settings?.reminder1DayTime ?: "10:00") }
    var adminTitle1Day by remember(settings) { mutableStateOf(settings?.reminder1DayAdminTitle ?: "⏰ یادآوری قسط فردا: {studentName}") }
    var adminTemplate1Day by remember(settings) { mutableStateOf(settings?.reminder1DayAdminTemplate ?: "یادآوری: فردا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate}) است.") }

    var remindDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayEnabled ?: true) }
    var remindDueDayTime by remember(settings) { mutableStateOf(settings?.reminderDueDayTime ?: "09:00") }
    var adminTitleDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayAdminTitle ?: "🚨 سررسید قسط امروز: {studentName}") }
    var adminTemplateDueDay by remember(settings) { mutableStateOf(settings?.reminderDueDayAdminTemplate ?: "یادآوری فوری: قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} امروز سررسید می‌شود.") }

    var remind1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterEnabled ?: true) }
    var remind1DayAfterTime by remember(settings) { mutableStateOf(settings?.reminder1DayAfterTime ?: "11:00") }
    var adminTitle1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterAdminTitle ?: "⚠️ هشدار ۱ روز دیرکرد: {studentName}") }
    var adminTemplate1DayAfter by remember(settings) { mutableStateOf(settings?.reminder1DayAfterAdminTemplate ?: "هشدار دیرکرد: ۱ روز از موعد قسط {installmentNumber} هنرجو {studentName} گذشته است. مانده: {remainingDebt}") }

    var remindOverdue by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterEnabled ?: true) }
    var remind3DaysAfterTime by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterTime ?: "11:00") }
    var adminTitle3DaysAfter by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterAdminTitle ?: "⚠️ اخطار ۳ روز معوقه: {studentName}") }
    var adminTemplate3DaysAfter by remember(settings) { mutableStateOf(settings?.reminder3DaysAfterAdminTemplate ?: "اخطار معوقه: قسط {installmentNumber} هنرجو {studentName} دوره {courseName} به مبلغ {amount} معوق شده است.") }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "مدیریت پیام‌ها و یادآوری‌ها",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Section
            MessageSettingsSection(
                viewModel = viewModel,
                settings = settings,
                remind7Days = remind7Days,
                remind7DaysTime = remind7DaysTime,
                adminTitle7Days = adminTitle7Days,
                adminTemplate7Days = adminTemplate7Days,
                onAdmin7DaysChange = { checked, time, title, tmpl ->
                    remind7Days = checked
                    remind7DaysTime = time
                    adminTitle7Days = title
                    adminTemplate7Days = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminder7DaysEnabled = checked,
                        reminder7DaysTime = time,
                        reminder7DaysAdminTitle = title,
                        reminder7DaysAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                },
                remind3Days = remind3Days,
                remind3DaysTime = remind3DaysTime,
                adminTitle3Days = adminTitle3Days,
                adminTemplate3Days = adminTemplate3Days,
                onAdmin3DaysChange = { checked, time, title, tmpl ->
                    remind3Days = checked
                    remind3DaysTime = time
                    adminTitle3Days = title
                    adminTemplate3Days = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminder3DaysEnabled = checked,
                        reminder3DaysTime = time,
                        reminder3DaysAdminTitle = title,
                        reminder3DaysAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                },
                remind1Day = remind1Day,
                remind1DayTime = remind1DayTime,
                adminTitle1Day = adminTitle1Day,
                adminTemplate1Day = adminTemplate1Day,
                onAdmin1DayChange = { checked, time, title, tmpl ->
                    remind1Day = checked
                    remind1DayTime = time
                    adminTitle1Day = title
                    adminTemplate1Day = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminder1DayEnabled = checked,
                        reminder1DayTime = time,
                        reminder1DayAdminTitle = title,
                        reminder1DayAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                },
                remindDueDay = remindDueDay,
                remindDueDayTime = remindDueDayTime,
                adminTitleDueDay = adminTitleDueDay,
                adminTemplateDueDay = adminTemplateDueDay,
                onAdminDueDayChange = { checked, time, title, tmpl ->
                    remindDueDay = checked
                    remindDueDayTime = time
                    adminTitleDueDay = title
                    adminTemplateDueDay = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminderDueDayEnabled = checked,
                        reminderDueDayTime = time,
                        reminderDueDayAdminTitle = title,
                        reminderDueDayAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                },
                remind1DayAfter = remind1DayAfter,
                remind1DayAfterTime = remind1DayAfterTime,
                adminTitle1DayAfter = adminTitle1DayAfter,
                adminTemplate1DayAfter = adminTemplate1DayAfter,
                onAdmin1DayAfterChange = { checked, time, title, tmpl ->
                    remind1DayAfter = checked
                    remind1DayAfterTime = time
                    adminTitle1DayAfter = title
                    adminTemplate1DayAfter = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminder1DayAfterEnabled = checked,
                        reminder1DayAfterTime = time,
                        reminder1DayAfterAdminTitle = title,
                        reminder1DayAfterAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                },
                remindOverdue = remindOverdue,
                remind3DaysAfterTime = remind3DaysAfterTime,
                adminTitle3DaysAfter = adminTitle3DaysAfter,
                adminTemplate3DaysAfter = adminTemplate3DaysAfter,
                onAdmin3DaysAfterChange = { checked, time, title, tmpl ->
                    remindOverdue = checked
                    remind3DaysAfterTime = time
                    adminTitle3DaysAfter = title
                    adminTemplate3DaysAfter = tmpl
                    val updated = (settings ?: AcademySettings()).copy(
                        reminder3DaysAfterEnabled = checked,
                        reminder3DaysAfterTime = time,
                        reminder3DaysAfterAdminTitle = title,
                        reminder3DaysAfterAdminTemplate = tmpl
                    )
                    viewModel.updateSettings(updated) {}
                }
            )

            // Test and Reschedule Action Buttons Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ابزارهای بررسی و زمان‌بندی یادآوری‌ها",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.triggerTestGeneralNotification(
                                    title = "🔔 تست اعلان مدیر: سررسید قسط",
                                    body = "قسط شماره ۲ هنرجو علی رضایی دوره طراحی وب به مبلغ ۴,۰۰۰,۰۰۰ تومان فردا سررسید می‌شود."
                                )
                                Toast.makeText(context, "نوتیفیکیشن تست به نوار اعلان گوشی ارسال شد.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تست اعلان زنده", style = MaterialTheme.typography.bodySmall)
                        }

                        FilledTonalButton(
                            onClick = {
                                viewModel.rescheduleAllReminders()
                                Toast.makeText(context, "تمام یادآوری‌های اقساط مجدداً زمان‌بندی شدند.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("زمان‌بندی مجدد", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
