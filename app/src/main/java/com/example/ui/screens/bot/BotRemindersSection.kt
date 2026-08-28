package com.example.ui.screens.bot

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.MessageTemplate
import com.example.data.model.Reminder
import com.example.data.model.Student
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import java.util.Calendar

data class BotReminderRule(
    val id: String,
    val title: String,
    val description: String,
    val defaultTime: String,
    val messageTypeName: String,
    val emoji: String,
    val targetProperty: String
)

val reminderRules = listOf(
    BotReminderRule(
        id = "rule_7_days",
        title = "۷ روز قبل از سررسید",
        description = "ارسال پیام پیش‌آگاهی برای آماده‌سازی وجه و اطلاع‌رسانی اولیه به هنرجو.",
        defaultTime = "10:00",
        messageTypeName = "پیش‌آگاهی ۷ روز قبل (REMINDER_7_DAYS)",
        emoji = "🗓️",
        targetProperty = "reminder7Days"
    ),
    BotReminderRule(
        id = "rule_3_days",
        title = "۳ روز قبل از سررسید",
        description = "یادآوری نزدیک شدن به موعد و ارسال شماره کارت پیش‌فرض جهت سهولت واریز.",
        defaultTime = "10:00",
        messageTypeName = "یادآوری ۳ روز قبل (REMINDER_3_DAYS)",
        emoji = "⏳",
        targetProperty = "reminder3Days"
    ),
    BotReminderRule(
        id = "rule_1_day",
        title = "۱ روز قبل (فردا)",
        description = "یادآوری موعد پرداخت فردا برای جلوگیری از تاخیر و معوق شدن شهریه.",
        defaultTime = "10:00",
        messageTypeName = "یادآوری ۱ روز قبل (REMINDER_1_DAY)",
        emoji = "🔔",
        targetProperty = "reminder1Day"
    ),
    BotReminderRule(
        id = "rule_due_day",
        title = "روز موعد سررسید (امروز)",
        description = "اعلام فرا رسیدن موعد قسط در همان روز و درخواست ارسال فیش پرداختی.",
        defaultTime = "09:00",
        messageTypeName = "یادآوری روز سررسید (REMINDER_DUE_DAY)",
        emoji = "🎯",
        targetProperty = "reminderDueDay"
    ),
    BotReminderRule(
        id = "rule_overdue_1",
        title = "۱ روز بعد از موعد (دیرکرد)",
        description = "اخطار معوق شدن قسط و درخواست برقراری ارتباط سریع با امور مالی.",
        defaultTime = "11:00",
        messageTypeName = "اخطار معوقه (OVERDUE)",
        emoji = "⚠️",
        targetProperty = "reminder1DayAfter"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotRemindersSection(
    settings: AcademySettings?,
    students: List<Student> = emptyList(),
    reminders: List<Reminder> = emptyList(),
    templates: List<MessageTemplate> = emptyList(),
    onUpdateSettings: (AcademySettings) -> Unit,
    onScheduleTestReminder: (student: Student, delayMinutes: Int, exactHour: Int?, exactMinute: Int?, templateType: String, amount: Long) -> Unit = { _, _, _, _, _, _ -> },
    onCancelReminder: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var currentSettings by remember(settings) {
        mutableStateOf(settings ?: AcademySettings())
    }

    // Filter students connected to Telegram
    val connectedStudents = remember(students) {
        students.filter { it.isTelegramConnected && it.telegramChatId.isNotBlank() }
    }

    // State for Automatic Test Scheduler
    var selectedStudent by remember(connectedStudents) {
        mutableStateOf(connectedStudents.firstOrNull() ?: students.firstOrNull())
    }
    var isStudentDropdownExpanded by remember { mutableStateOf(false) }

    // Test timing mode: QUICK_MINUTES (1-5 min) or EXACT_TIME (hour + minute)
    var timingMode by remember { mutableStateOf("QUICK") } // "QUICK" or "EXACT"
    var selectedQuickMinutes by remember { mutableStateOf(1) }

    // Exact time inputs
    val currentCal = Calendar.getInstance()
    var exactHourStr by remember { mutableStateOf(String.format("%02d", currentCal.get(Calendar.HOUR_OF_DAY))) }
    var exactMinuteStr by remember {
        val nextMin = (currentCal.get(Calendar.MINUTE) + 2) % 60
        mutableStateOf(String.format("%02d", nextMin))
    }

    // Template selection for test
    val defaultTemplates = remember(templates) {
        if (templates.isNotEmpty()) templates else MessageTemplate.getDefaultTemplates()
    }
    var selectedTemplate by remember(defaultTemplates) {
        mutableStateOf(defaultTemplates.firstOrNull { it.type == "REMINDER_3_DAYS" } ?: defaultTemplates.firstOrNull())
    }
    var isTemplateDropdownExpanded by remember { mutableStateOf(false) }

    // Recent test reminders from the system
    val testReminders = remember(reminders) {
        reminders.filter { it.type == "TELEGRAM_TEST" }.sortedByDescending { it.createdAt }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "در این بخش می‌توانید زمانبندی و ساعت ارسال خودکار پیام‌های یادآوری تلگرام را تعیین کرده و اجرای سیستم در پس‌زمینه (AlarmManager) را به صورت واقعی آزمایش فرمایید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // ==========================================
        // TEST MODE: Real Scheduled Reminder Tester
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_automatic_test_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = IndigoPrimary.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Alarm,
                                    contentDescription = null,
                                    tint = IndigoPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "ارسال خودکار آزمایشی (تست زمانبند AlarmManager)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "تست اجرای زمانبندی پس‌زمینه بدون شبیه‌سازی یا دستکاری",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // 1. Select Telegram Connected Student
                Text(
                    text = "۱. انتخاب هنرجوی متصل به تلگرام برای تست:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (connectedStudents.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = "هنوز هیچ هنرجویی به ربات تلگرام متصل نشده است. ابتدا از تب 'هنرجویان تلگرام' یک هنرجو را با ارسال لینک متصل نمایید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                } else {
                    ExposedDropdownMenuBox(
                        expanded = isStudentDropdownExpanded,
                        onExpandedChange = { isStudentDropdownExpanded = !isStudentDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedStudent?.let { "${it.fullName} (Chat ID: ${it.telegramChatId})" } ?: "انتخاب هنرجو...",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("هنرجوی تستی متصل به تلگرام") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isStudentDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .testTag("select_test_student_field")
                        )
                        ExposedDropdownMenu(
                            expanded = isStudentDropdownExpanded,
                            onDismissRequest = { isStudentDropdownExpanded = false }
                        ) {
                            connectedStudents.forEach { st ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(st.fullName, fontWeight = FontWeight.Bold)
                                            Text("Chat ID: ${st.telegramChatId} | همراه: ${st.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                        }
                                    },
                                    onClick = {
                                        selectedStudent = st
                                        isStudentDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. Select Template for Test
                if (defaultTemplates.isNotEmpty()) {
                    Text(
                        text = "۲. انتخاب الگوی یادآوری:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    ExposedDropdownMenuBox(
                        expanded = isTemplateDropdownExpanded,
                        onExpandedChange = { isTemplateDropdownExpanded = !isTemplateDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedTemplate?.title ?: "انتخاب الگو...",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("قالب پیام یادآوری") },
                            leadingIcon = { Icon(Icons.Default.AccessTime, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isTemplateDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .testTag("select_test_template_field")
                        )
                        ExposedDropdownMenu(
                            expanded = isTemplateDropdownExpanded,
                            onDismissRequest = { isTemplateDropdownExpanded = false }
                        ) {
                            defaultTemplates.forEach { tmpl ->
                                DropdownMenuItem(
                                    text = {
                                        Text(tmpl.title)
                                    },
                                    onClick = {
                                        selectedTemplate = tmpl
                                        isTemplateDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. Select Time Mode (Quick 1-5 min OR Exact Hour+Minute)
                Text(
                    text = "۳. زمان اجرای تست در زمانبند پس‌زمینه:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { timingMode = "QUICK" },
                        colors = if (timingMode == "QUICK") ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("چند دقیقه آینده (۱ تا ۵ دقیقه)", style = MaterialTheme.typography.bodySmall)
                    }

                    FilledTonalButton(
                        onClick = { timingMode = "EXACT" },
                        colors = if (timingMode == "EXACT") ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else ButtonDefaults.filledTonalButtonColors(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("ساعت و دقیقه دقیق", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (timingMode == "QUICK") {
                    // Quick Buttons (1 min, 2 min, 3 min, 5 min)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 3, 5).forEach { mins ->
                            val isSel = selectedQuickMinutes == mins
                            OutlinedButton(
                                onClick = { selectedQuickMinutes = mins },
                                colors = if (isSel) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors(),
                                border = if (isSel) ButtonDefaults.outlinedButtonBorder.copy(width = 2.dp) else ButtonDefaults.outlinedButtonBorder,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_delay_${mins}_min"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("$mins دقیقه دیگر", style = MaterialTheme.typography.bodySmall, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                } else {
                    // Exact Time inputs (Hour and Minute)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = exactHourStr,
                            onValueChange = { if (it.length <= 2 && it.all { char -> char.isDigit() }) exactHourStr = it },
                            label = { Text("ساعت (۰-۲۳)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("exact_hour_input"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        Text(":", fontWeight = FontWeight.Bold, fontSize = 20.sp)

                        OutlinedTextField(
                            value = exactMinuteStr,
                            onValueChange = { if (it.length <= 2 && it.all { char -> char.isDigit() }) exactMinuteStr = it },
                            label = { Text("دقیقه (۰-۵۹)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("exact_minute_input"),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                // Trigger Button
                Button(
                    onClick = {
                        val st = selectedStudent
                        if (st == null) {
                            Toast.makeText(context, "لطفاً ابتدا یک هنرجوی متصل به تلگرام انتخاب کنید.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (timingMode == "QUICK") {
                            onScheduleTestReminder(
                                st,
                                selectedQuickMinutes,
                                null,
                                null,
                                selectedTemplate?.type ?: "REMINDER_3_DAYS",
                                2500000L
                            )
                            Toast.makeText(context, "تست زمانبندی برای $selectedQuickMinutes دقیقه دیگر در AlarmManager ثبت شد.", Toast.LENGTH_LONG).show()
                        } else {
                            val h = exactHourStr.toIntOrNull()?.coerceIn(0, 23) ?: 10
                            val m = exactMinuteStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
                            onScheduleTestReminder(
                                st,
                                0,
                                h,
                                m,
                                selectedTemplate?.type ?: "REMINDER_3_DAYS",
                                2500000L
                            )
                            Toast.makeText(context, "تست زمانبندی برای ساعت ${String.format("%02d:%02d", h, m)} ثبت شد.", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = selectedStudent != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submit_automatic_test_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ارسال خودکار آزمایشی (ثبت زمانبند واقعی)")
                }

                // 4. Test Execution Status & History
                if (testReminders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "وضعیت و تاریخچه تست‌های خودکار زمانبندی‌شده:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    testReminders.take(5).forEach { rem ->
                        TestReminderStatusCard(
                            reminder = rem,
                            onCancel = { onCancelReminder(rem.id) }
                        )
                    }
                }
            }
        }

        // ==========================================
        // Standard 5 Bot Reminder Rules Settings
        // ==========================================
        Text(
            text = "قوانین زمانبندی خودکار اقساط",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        reminderRules.forEach { rule ->
            val (enabled, time) = when (rule.id) {
                "rule_7_days" -> currentSettings.reminder7DaysEnabled to currentSettings.reminder7DaysTime
                "rule_3_days" -> currentSettings.reminder3DaysEnabled to currentSettings.reminder3DaysTime
                "rule_1_day" -> currentSettings.reminder1DayEnabled to currentSettings.reminder1DayTime
                "rule_due_day" -> currentSettings.reminderDueDayEnabled to currentSettings.reminderDueDayTime
                else -> currentSettings.reminder1DayAfterEnabled to currentSettings.reminder1DayAfterTime
            }

            ReminderRuleCard(
                rule = rule,
                initialEnabled = enabled,
                initialTime = time,
                onSaveRule = { newEnabled, newTime ->
                    val updated = when (rule.id) {
                        "rule_7_days" -> currentSettings.copy(reminder7DaysEnabled = newEnabled, reminder7DaysTime = newTime)
                        "rule_3_days" -> currentSettings.copy(reminder3DaysEnabled = newEnabled, reminder3DaysTime = newTime)
                        "rule_1_day" -> currentSettings.copy(reminder1DayEnabled = newEnabled, reminder1DayTime = newTime)
                        "rule_due_day" -> currentSettings.copy(reminderDueDayEnabled = newEnabled, reminderDueDayTime = newTime)
                        else -> currentSettings.copy(reminder1DayAfterEnabled = newEnabled, reminder1DayAfterTime = newTime)
                    }
                    currentSettings = updated
                    onUpdateSettings(updated)
                    Toast.makeText(context, "قانون ${rule.title} با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun TestReminderStatusCard(
    reminder: Reminder,
    onCancel: () -> Unit
) {
    val statusColor = when (reminder.status) {
        "SENT" -> StatusSuccess
        "FAILED" -> StatusError
        "SCHEDULED" -> AmberTertiary
        "CANCELLED" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }

    val statusTitle = when (reminder.status) {
        "SENT" -> "ارسال با موفقیت انجام شد"
        "FAILED" -> "خطا در ارسال پیام"
        "SCHEDULED" -> "در انتظار اجرا توسط سیستم (AlarmManager)"
        "CANCELLED" -> "لغو شده"
        else -> reminder.status
    }

    val timeFormatted = remember(reminder.scheduledTimeEpoch) {
        val cal = Calendar.getInstance().apply { timeInMillis = reminder.scheduledTimeEpoch }
        String.format("%02d:%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("test_reminder_status_${reminder.id}"),
        shape = RoundedCornerShape(12.dp),
        color = statusColor.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when (reminder.status) {
                            "SENT" -> Icons.Default.CheckCircle
                            "FAILED" -> Icons.Default.Error
                            "SCHEDULED" -> Icons.Default.HourglassEmpty
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = reminder.studentName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "زمان اجرا: ساعت $timeFormatted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (reminder.status == "SCHEDULED") {
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("لغو یادآوری", fontSize = 12.sp)
                    }
                }
            }

            if (reminder.errorMessage.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StatusError.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                        Text(
                            text = "دلیل خطا: ${reminder.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            if (reminder.sendTimeShamsi.isNotBlank()) {
                Text(
                    text = "زمان ثبت نهایی: ${reminder.sendTimeShamsi}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ReminderRuleCard(
    rule: BotReminderRule,
    initialEnabled: Boolean,
    initialTime: String,
    onSaveRule: (Boolean, String) -> Unit
) {
    var isEnabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }
    var time by remember(initialTime) { mutableStateOf(initialTime) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bot_reminder_card_${rule.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = rule.emoji, fontSize = 22.sp)
                    Column {
                        Text(
                            text = rule.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isEnabled) "فعال در سیستم یادآوری" else "غیرفعال",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isEnabled) StatusSuccess else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { isEnabled = it },
                    modifier = Modifier.testTag("switch_${rule.id}")
                )
            }

            Text(
                text = rule.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Time and Linked Message Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("ساعت ارسال") },
                    placeholder = { Text("مثلاً 10:00") },
                    leadingIcon = {
                        Icon(Icons.Default.AccessTime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("time_${rule.id}"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1.5f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "نوع قالب مرتبط:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = rule.messageTypeName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Save Button
            Button(
                onClick = {
                    onSaveRule(isEnabled, time)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_${rule.id}"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ذخیره تغییرات این قانون")
            }
        }
    }
}
