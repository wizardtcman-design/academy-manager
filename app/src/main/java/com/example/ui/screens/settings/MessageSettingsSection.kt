package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.MessageTemplate
import com.example.data.model.Student
import com.example.data.util.FinanceUtil
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageSettingsSection(
    viewModel: MainViewModel,
    settings: AcademySettings?,
    // Admin notification states (7 Days Before)
    remind7Days: Boolean,
    remind7DaysTime: String,
    adminTitle7Days: String,
    adminTemplate7Days: String,
    onAdmin7DaysChange: (Boolean, String, String, String) -> Unit,

    // 3 Days Before
    remind3Days: Boolean,
    remind3DaysTime: String,
    adminTitle3Days: String,
    adminTemplate3Days: String,
    onAdmin3DaysChange: (Boolean, String, String, String) -> Unit,

    // 1 Day Before
    remind1Day: Boolean,
    remind1DayTime: String,
    adminTitle1Day: String,
    adminTemplate1Day: String,
    onAdmin1DayChange: (Boolean, String, String, String) -> Unit,

    // Due Day
    remindDueDay: Boolean,
    remindDueDayTime: String,
    adminTitleDueDay: String,
    adminTemplateDueDay: String,
    onAdminDueDayChange: (Boolean, String, String, String) -> Unit,

    // 1 Day After
    remind1DayAfter: Boolean,
    remind1DayAfterTime: String,
    adminTitle1DayAfter: String,
    adminTemplate1DayAfter: String,
    onAdmin1DayAfterChange: (Boolean, String, String, String) -> Unit,

    // 3 Days After
    remindOverdue: Boolean,
    remind3DaysAfterTime: String,
    adminTitle3DaysAfter: String,
    adminTemplate3DaysAfter: String,
    onAdmin3DaysAfterChange: (Boolean, String, String, String) -> Unit,

    // Direct save callback for instant single card save
    onSaveSingleAdminReminder: (AcademySettings) -> Unit = {},

    // Externally driven selected tab so the settings hub can jump here
    selectedTab: Int = 0,
    onSelectedTabChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val templates by viewModel.messageTemplates.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MarkChatUnread,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "پیام‌ها و یادآوری‌ها",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "مرکز تنظیم و ویرایش متن اعلان‌های مدیر و پیام‌های ارسالی به هنرجویان",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tab Selector: الف) پیام‌های مدیر | ب) پیام‌های هنرجویان
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectedTabChange(0) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("الف) پیام‌های مدیر", fontWeight = FontWeight.Bold)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectedTabChange(1) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("ب) پیام‌های هنرجویان", fontWeight = FontWeight.Bold)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { onSelectedTabChange(2) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("ج) تنظیمات سرور ربات", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            if (selectedTab == 0) {
                // ==========================================
                // SUB-SECTION A: پیام‌های مدیر (Admin Notifications)
                // ==========================================
                Text(
                    text = "مدیر می‌تواند عنوان و متن هر نوتیفیکیشن را ویرایش، پیش‌نمایش و ذخیره کند. این متن‌ها مستقیماً در اعلان‌های اندروید استفاده می‌شوند:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. 7 Days Before
                AdminReminderConfigCard(
                    categoryTitle = "۷ روز قبل از سررسید",
                    checked = remind7Days,
                    time = remind7DaysTime,
                    titleText = adminTitle7Days,
                    templateText = adminTemplate7Days,
                    defaultTitle = "🔔 یادآوری ۷ روز قبل: {studentName}",
                    defaultTemplate = "یادآوری: ۷ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({courseName})",
                    onCheckedChange = { checked -> onAdmin7DaysChange(checked, remind7DaysTime, adminTitle7Days, adminTemplate7Days) },
                    onTimeChange = { time -> onAdmin7DaysChange(remind7Days, time, adminTitle7Days, adminTemplate7Days) },
                    onTitleChange = { title -> onAdmin7DaysChange(remind7Days, remind7DaysTime, title, adminTemplate7Days) },
                    onTemplateChange = { text -> onAdmin7DaysChange(remind7Days, remind7DaysTime, adminTitle7Days, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminder7DaysEnabled = remind7Days,
                            reminder7DaysTime = remind7DaysTime,
                            reminder7DaysAdminTitle = adminTitle7Days,
                            reminder7DaysAdminTemplate = adminTemplate7Days
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان ۷ روز قبل با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

                // 2. 3 Days Before
                AdminReminderConfigCard(
                    categoryTitle = "۳ روز قبل از سررسید",
                    checked = remind3Days,
                    time = remind3DaysTime,
                    titleText = adminTitle3Days,
                    templateText = adminTemplate3Days,
                    defaultTitle = "🔔 یادآوری ۳ روز قبل: {studentName}",
                    defaultTemplate = "یادآوری: ۳ روز تا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} باقی مانده است.",
                    onCheckedChange = { checked -> onAdmin3DaysChange(checked, remind3DaysTime, adminTitle3Days, adminTemplate3Days) },
                    onTimeChange = { time -> onAdmin3DaysChange(remind3Days, time, adminTitle3Days, adminTemplate3Days) },
                    onTitleChange = { title -> onAdmin3DaysChange(remind3Days, remind3DaysTime, title, adminTemplate3Days) },
                    onTemplateChange = { text -> onAdmin3DaysChange(remind3Days, remind3DaysTime, adminTitle3Days, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminder3DaysEnabled = remind3Days,
                            reminder3DaysTime = remind3DaysTime,
                            reminder3DaysAdminTitle = adminTitle3Days,
                            reminder3DaysAdminTemplate = adminTemplate3Days
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان ۳ روز قبل با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

                // 3. 1 Day Before
                AdminReminderConfigCard(
                    categoryTitle = "۱ روز قبل از سررسید (فردا)",
                    checked = remind1Day,
                    time = remind1DayTime,
                    titleText = adminTitle1Day,
                    templateText = adminTemplate1Day,
                    defaultTitle = "⏰ یادآوری قسط فردا: {studentName}",
                    defaultTemplate = "یادآوری: فردا موعد قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} ({dueDate}) است.",
                    onCheckedChange = { checked -> onAdmin1DayChange(checked, remind1DayTime, adminTitle1Day, adminTemplate1Day) },
                    onTimeChange = { time -> onAdmin1DayChange(remind1Day, time, adminTitle1Day, adminTemplate1Day) },
                    onTitleChange = { title -> onAdmin1DayChange(remind1Day, remind1DayTime, title, adminTemplate1Day) },
                    onTemplateChange = { text -> onAdmin1DayChange(remind1Day, remind1DayTime, adminTitle1Day, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminder1DayEnabled = remind1Day,
                            reminder1DayTime = remind1DayTime,
                            reminder1DayAdminTitle = adminTitle1Day,
                            reminder1DayAdminTemplate = adminTemplate1Day
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان ۱ روز قبل با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

                // 4. Due Day
                AdminReminderConfigCard(
                    categoryTitle = "روز سررسید قسط (امروز)",
                    checked = remindDueDay,
                    time = remindDueDayTime,
                    titleText = adminTitleDueDay,
                    templateText = adminTemplateDueDay,
                    defaultTitle = "🚨 سررسید قسط امروز: {studentName}",
                    defaultTemplate = "یادآوری فوری: قسط {installmentNumber} هنرجو {studentName} به مبلغ {amount} امروز سررسید می‌شود.",
                    onCheckedChange = { checked -> onAdminDueDayChange(checked, remindDueDayTime, adminTitleDueDay, adminTemplateDueDay) },
                    onTimeChange = { time -> onAdminDueDayChange(remindDueDay, time, adminTitleDueDay, adminTemplateDueDay) },
                    onTitleChange = { title -> onAdminDueDayChange(remindDueDay, remindDueDayTime, title, adminTemplateDueDay) },
                    onTemplateChange = { text -> onAdminDueDayChange(remindDueDay, remindDueDayTime, adminTitleDueDay, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminderDueDayEnabled = remindDueDay,
                            reminderDueDayTime = remindDueDayTime,
                            reminderDueDayAdminTitle = adminTitleDueDay,
                            reminderDueDayAdminTemplate = adminTemplateDueDay
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان روز سررسید با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

                // 5. 1 Day After (Overdue)
                AdminReminderConfigCard(
                    categoryTitle = "۱ روز بعد از سررسید (دیرکرد)",
                    checked = remind1DayAfter,
                    time = remind1DayAfterTime,
                    titleText = adminTitle1DayAfter,
                    templateText = adminTemplate1DayAfter,
                    defaultTitle = "⚠️ هشدار ۱ روز دیرکرد: {studentName}",
                    defaultTemplate = "هشدار دیرکرد: ۱ روز از موعد قسط {installmentNumber} هنرجو {studentName} گذشته است. مانده: {remainingDebt}",
                    onCheckedChange = { checked -> onAdmin1DayAfterChange(checked, remind1DayAfterTime, adminTitle1DayAfter, adminTemplate1DayAfter) },
                    onTimeChange = { time -> onAdmin1DayAfterChange(remind1DayAfter, time, adminTitle1DayAfter, adminTemplate1DayAfter) },
                    onTitleChange = { title -> onAdmin1DayAfterChange(remind1DayAfter, remind1DayAfterTime, title, adminTemplate1DayAfter) },
                    onTemplateChange = { text -> onAdmin1DayAfterChange(remind1DayAfter, remind1DayAfterTime, adminTitle1DayAfter, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminder1DayAfterEnabled = remind1DayAfter,
                            reminder1DayAfterTime = remind1DayAfterTime,
                            reminder1DayAfterAdminTitle = adminTitle1DayAfter,
                            reminder1DayAfterAdminTemplate = adminTemplate1DayAfter
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان دیرکرد ۱ روز با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

                // 6. 3 Days After (Warning)
                AdminReminderConfigCard(
                    categoryTitle = "۳ روز بعد از سررسید (اخطار معوقه)",
                    checked = remindOverdue,
                    time = remind3DaysAfterTime,
                    titleText = adminTitle3DaysAfter,
                    templateText = adminTemplate3DaysAfter,
                    defaultTitle = "⚠️ اخطار ۳ روز معوقه: {studentName}",
                    defaultTemplate = "اخطار معوقه: قسط {installmentNumber} هنرجو {studentName} دوره {courseName} به مبلغ {amount} معوق شده است.",
                    onCheckedChange = { checked -> onAdmin3DaysAfterChange(checked, remind3DaysAfterTime, adminTitle3DaysAfter, adminTemplate3DaysAfter) },
                    onTimeChange = { time -> onAdmin3DaysAfterChange(remindOverdue, time, adminTitle3DaysAfter, adminTemplate3DaysAfter) },
                    onTitleChange = { title -> onAdmin3DaysAfterChange(remindOverdue, remind3DaysAfterTime, title, adminTemplate3DaysAfter) },
                    onTemplateChange = { text -> onAdmin3DaysAfterChange(remindOverdue, remind3DaysAfterTime, adminTitle3DaysAfter, text) },
                    onSave = {
                        val base = settings ?: AcademySettings()
                        val updated = base.copy(
                            reminder3DaysAfterEnabled = remindOverdue,
                            reminder3DaysAfterTime = remind3DaysAfterTime,
                            reminder3DaysAfterAdminTitle = adminTitle3DaysAfter,
                            reminder3DaysAfterAdminTemplate = adminTemplate3DaysAfter
                        )
                        viewModel.updateSettings(updated) {
                            Toast.makeText(context, "الگوی اعلان معوقه ۳ روز با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    settings = settings
                )

            } else {
                // ==========================================
                // SUB-SECTION B: پیام‌های هنرجویان (Student Messages)
                // ==========================================
                Text(
                    text = "مدیر می‌تواند متن هر پیام ارسالی به هنرجو را کاملاً پاک کند و متن دلخواه بنویسد، پیش‌نمایش واقعی را ببیند و ذخیره کند:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Render student message templates
                templates.forEach { tmpl ->
                    StudentMessageTemplateCard(
                        template = tmpl,
                        settings = settings,
                        onSave = { updatedTmpl ->
                            viewModel.updateTemplate(updatedTmpl)
                            Toast.makeText(context, "الگوی «${updatedTmpl.title}» ذخیره شد.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            if (selectedTab == 2) {
                // ==========================================
                // SUB-SECTION C: تنظیمات سرور پیام‌رسانی (Stage 6)
                // The worker is the source of truth: per-event policy,
                // templates, daily overdue reminders, channel status and
                // per-installment stop/resume live here.
                // ==========================================
                ServerMessagingSection(
                    students = viewModel.students.collectAsState().value,
                    installments = viewModel.installments.collectAsState().value
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// COMPOSABLE: Admin Notification Template Card with Title, Body, Edit, Save, Preview & Reset
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminReminderConfigCard(
    categoryTitle: String,
    checked: Boolean,
    time: String,
    titleText: String,
    templateText: String,
    defaultTitle: String,
    defaultTemplate: String,
    onCheckedChange: (Boolean) -> Unit,
    onTimeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onTemplateChange: (String) -> Unit,
    onSave: () -> Unit,
    settings: AcademySettings?
) {
    var isExpanded by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = categoryTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (checked) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ساعت اعلان: $time",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (checked) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable { showTimeDialog = true }
                                .padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = time,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        // Edit Toggle Button
                        IconButton(
                            onClick = { isExpanded = !isExpanded },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.EditNote,
                                contentDescription = "ویرایش عنوان و متن نوتیفیکیشن",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
            }

            AnimatedVisibility(visible = checked && isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider()

                    // 1. Notification Title Field
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "عنوان اعلان (Notification Title):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "قابل ویرایش و شخصی‌سازی",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = titleText,
                            onValueChange = onTitleChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("مثلاً: 🔔 سررسید قسط {studentName}") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                    }

                    // 2. Notification Body Field
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "متن اعلان (Notification Body):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            // Reset button
                            OutlinedButton(
                                onClick = {
                                    onTitleChange(defaultTitle)
                                    onTemplateChange(defaultTemplate)
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("بازیابی متن پیش‌فرض", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                            }
                        }

                        OutlinedTextField(
                            value = templateText,
                            onValueChange = onTemplateChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("متن اعلان را اینجا بنویسید یا ویرایش کنید...") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl),
                            shape = RoundedCornerShape(10.dp),
                            minLines = 2,
                            maxLines = 5
                        )
                    }

                    // Variable chips for quick insertion
                    Text(
                        text = "متغیرهای هوشمند (لمس برای افزودن به متن):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val adminVars = listOf(
                        "{studentName}" to "نام هنرجو",
                        "{amount}" to "مبلغ قسط",
                        "{dueDate}" to "تاریخ سررسید",
                        "{installmentNumber}" to "شماره قسط",
                        "{remainingDebt}" to "مانده بدهی",
                        "{courseName}" to "نام دوره",
                        "{academyName}" to "نام آموزشگاه"
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        adminVars.forEach { (tag, label) ->
                            SuggestionChip(
                                onClick = { onTemplateChange(templateText + " " + tag) },
                                label = { Text("$label $tag", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp) }
                            )
                        }
                    }

                    // Real-time Android Notification Preview
                    val samplePreviewTitle = FinanceUtil.fillAdminNotificationTemplate(
                        templateText = titleText,
                        studentName = "علی رضایی",
                        courseTitle = "طراحی وب با وردپرس",
                        installmentNumber = 2,
                        installmentAmount = 4000000L,
                        remainingDebt = 4000000L,
                        dueDateShamsi = "۱۴۰۳/۰۵/۲۴",
                        settings = settings
                    )

                    val samplePreviewBody = FinanceUtil.fillAdminNotificationTemplate(
                        templateText = templateText,
                        studentName = "علی رضایی",
                        courseTitle = "طراحی وب با وردپرس",
                        installmentNumber = 2,
                        installmentAmount = 4000000L,
                        remainingDebt = 4000000L,
                        dueDateShamsi = "۱۴۰۳/۰۵/۲۴",
                        settings = settings
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RemoveRedEye,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "پیش‌نمایش زنده در نوتیفیکیشن‌بار گوشی:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = samplePreviewTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = samplePreviewBody,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Save Button
                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ذخیره تغییرات $categoryTitle", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showTimeDialog) {
        val timeOptions = listOf("08:00", "09:00", "10:00", "11:00", "12:00", "14:00", "16:00", "18:00", "20:00")
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            title = { Text("ساعت یادآوری: $categoryTitle", style = MaterialTheme.typography.titleMedium) },
            text = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    timeOptions.forEach { t ->
                        FilterChip(
                            selected = time == t,
                            onClick = {
                                onTimeChange(t)
                                showTimeDialog = false
                            },
                            label = { Text(t, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTimeDialog = false }) {
                    Text("بستن")
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------------------
// COMPOSABLE: Student Message Template Card with Full Edit, Save, Reset, Live Preview & Chips
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudentMessageTemplateCard(
    template: MessageTemplate,
    settings: AcademySettings?,
    onSave: (MessageTemplate) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var text by remember(template) { mutableStateOf(template.templateText) }
    var isEnabled by remember(template) { mutableStateOf(template.isAutoSendEnabled) }
    var sendTime by remember(template) { mutableStateOf(template.sendTime) }
    var targetChannel by remember(template) { mutableStateOf(template.targetChannel) }
    var showTimeDialog by remember { mutableStateOf(false) }

    // Default template text fallback
    val defaultItem = MessageTemplate.getDefaultTemplates().find { it.type == template.type }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "کانال: ${when(targetChannel) {
                                    "TELEGRAM" -> "ربات تلگرام"
                                    "RUBIKA" -> "روبیکا"
                                    "WHATSAPP" -> "واتساپ"
                                    "MANUAL" -> "دستی / پیامک"
                                    else -> "خودکار (هوشمند)"
                                }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp
                            )
                        }

                        if (isEnabled) {
                            Text(
                                text = "زمان: $sendTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Edit Toggle Button
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Edit,
                            contentDescription = "ویرایش متن الگو",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            onSave(template.copy(isAutoSendEnabled = it, templateText = text, sendTime = sendTime, targetChannel = targetChannel))
                        }
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider()

                    // Time and Channel Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "زمان ارسال:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.clickable { showTimeDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AccessTime, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(sendTime, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Target Channel Selector
                    Text(
                        text = "کانال ارسال پیام به هنرجو:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    val isTelegramAvailable = settings?.telegramEnabled == true && !settings.telegramBotToken.isNullOrBlank()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = targetChannel == "AUTO",
                            onClick = { targetChannel = "AUTO" },
                            label = { Text("خودکار", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = targetChannel == "TELEGRAM",
                            onClick = { targetChannel = "TELEGRAM" },
                            label = { Text("تلگرام", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = targetChannel == "WHATSAPP",
                            onClick = { targetChannel = "WHATSAPP" },
                            label = { Text("واتساپ", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = targetChannel == "MANUAL",
                            onClick = { targetChannel = "MANUAL" },
                            label = { Text("دستی / پیامک", fontSize = 11.sp) }
                        )
                    }

                    if (targetChannel == "TELEGRAM" && !isTelegramAvailable) {
                        Text(
                            text = "⚠️ ربات تلگرام در تنظیمات فعال یا متصل نیست (در صورت عدم اتصال، پیام برای اشتراک‌گذاری دستی آماده می‌شود).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Template Body Text Field with Reset Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "متن پیام ارسالی به هنرجو:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (defaultItem != null) {
                            OutlinedButton(
                                onClick = { text = defaultItem.templateText },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("بازیابی متن پیش‌فرض", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("متن پیام را وارد کنید. می‌توانید از متغیرهای زیر استفاده کنید...") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl),
                        shape = RoundedCornerShape(10.dp),
                        minLines = 3,
                        maxLines = 6
                    )

                    // Variable Chips to Insert
                    Text(
                        text = "متغیرهای هوشمند (لمس برای افزودن به متن پیام):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val studentVars = listOf(
                        "{studentName}" to "نام هنرجو",
                        "{amount}" to "مبلغ قسط",
                        "{dueDate}" to "تاریخ سررسید",
                        "{installmentNumber}" to "شماره قسط",
                        "{remainingDebt}" to "مانده بدهی",
                        "{courseName}" to "نام دوره",
                        "{academyName}" to "نام آموزشگاه",
                        "{paymentAmount}" to "مبلغ واریزی",
                        "{receiptNumber}" to "کد پیگیری"
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        studentVars.forEach { (tag, label) ->
                            SuggestionChip(
                                onClick = { text += " $tag" },
                                label = { Text("$label $tag", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp) }
                            )
                        }
                    }

                    // LIVE DYNAMIC REAL PREVIEW BALLOON
                    val sampleStudent = Student(
                        id = "sample_1",
                        studentCode = "ST-1403-01",
                        firstName = "علی",
                        lastName = "رضایی",
                        phone = "09123456789"
                    )

                    val livePreviewText = FinanceUtil.fillMessageTemplate(
                        templateText = text,
                        student = sampleStudent,
                        courseTitle = "دوره جامع ICDL",
                        installmentNumber = 2,
                        installmentAmount = 4000000L,
                        paidAmount = 4000000L,
                        remainingDebt = 4000000L,
                        dueDateShamsi = "۲۴ مرداد ۱۴۰۳",
                        receiptNumber = "TRX-1403-9821",
                        settings = settings
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.RemoveRedEye, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = "پیش‌نمایش زنده پیام دریافتی هنرجو:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = livePreviewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    // Save Template Button
                    Button(
                        onClick = {
                            onSave(template.copy(
                                templateText = text,
                                isAutoSendEnabled = isEnabled,
                                sendTime = sendTime,
                                targetChannel = targetChannel
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ذخیره الگوی ${template.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showTimeDialog) {
        val timeOptions = listOf("بلافاصله", "08:00", "09:00", "10:00", "11:00", "12:00", "14:00", "16:00", "18:00", "20:00")
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            title = { Text("انتخاب زمان ارسال پیام به هنرجو", style = MaterialTheme.typography.titleMedium) },
            text = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    timeOptions.forEach { t ->
                        FilterChip(
                            selected = sendTime == t,
                            onClick = {
                                sendTime = t
                                showTimeDialog = false
                            },
                            label = { Text(t, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTimeDialog = false }) {
                    Text("بستن")
                }
            }
        )
    }
}
