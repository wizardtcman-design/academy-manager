package com.example.ui.screens.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Student
import com.example.ui.screens.dialogs.SendMessageDialog
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

data class BotTabItem(
    val section: BotTabSection,
    val icon: ImageVector
)

val botTabItems = listOf(
    BotTabItem(BotTabSection.DASHBOARD, Icons.Default.Dashboard),
    BotTabItem(BotTabSection.STUDENTS, Icons.Default.People),
    BotTabItem(BotTabSection.MESSAGES, Icons.Default.Message),
    BotTabItem(BotTabSection.BUTTONS, Icons.Default.TouchApp),
    BotTabItem(BotTabSection.REMINDERS, Icons.Default.Alarm),
    BotTabItem(BotTabSection.LOGS, Icons.Default.History),
    BotTabItem(BotTabSection.SETTINGS, Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val students by viewModel.students.collectAsState()
    val templates by viewModel.messageTemplates.collectAsState()
    val messageLogs by viewModel.messageLogs.collectAsState()
    val isCheckingTelegram by viewModel.isCheckingTelegram.collectAsState()

    var currentSection by remember { mutableStateOf(BotTabSection.DASHBOARD) }
    var directMessageStudent by remember { mutableStateOf<Student?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("bot_management_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "مدیریت ربات تلگرام",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (settings?.telegramEnabled == true) "سیستم پیام‌رسانی فعال است" else "سیستم پیام‌رسانی غیرفعال",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scrollable Tab Row for the 7 sections
            ScrollableTabRow(
                selectedTabIndex = currentSection.index,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[currentSection.index]),
                        color = MaterialTheme.colorScheme.primary,
                        height = 3.dp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bot_sections_tab_row")
            ) {
                botTabItems.forEach { tabItem ->
                    val isSelected = currentSection == tabItem.section
                    Tab(
                        selected = isSelected,
                        onClick = { currentSection = tabItem.section },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = tabItem.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = tabItem.section.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }

            // Section content in a scrollable area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                when (currentSection) {
                    BotTabSection.DASHBOARD -> {
                        BotDashboardSection(
                            settings = settings,
                            students = students,
                            messageLogs = messageLogs,
                            onNavigateToTab = { target -> currentSection = target },
                            onTestConnection = {
                                val token = settings?.telegramBotToken?.trim() ?: ""
                                if (token.isBlank()) {
                                    viewModel.showMessage("لطفاً ابتدا توکن ربات را در تب تنظیمات ربات وارد نمایید.")
                                } else {
                                    viewModel.testTelegramBot(token) { _, msg ->
                                        viewModel.showMessage(msg)
                                    }
                                }
                            }
                        )
                    }

                    BotTabSection.STUDENTS -> {
                        BotStudentsSection(
                            settings = settings,
                            students = students,
                            isCheckingUpdates = isCheckingTelegram,
                            onCheckUpdates = {
                                viewModel.checkTelegramUpdates(showFeedback = true)
                            },
                            onUpdateStudent = { student ->
                                viewModel.updateStudent(student) { _, _ -> }
                            },
                            onDisconnectStudent = { student ->
                                viewModel.disconnectStudentTelegram(student.id)
                            },
                            onSendMessage = { student ->
                                directMessageStudent = student
                            }
                        )
                    }

                    BotTabSection.MESSAGES -> {
                        BotMessagesSection(
                            settings = settings,
                            templates = templates,
                            onUpdateTemplate = { template ->
                                viewModel.updateTemplate(template)
                            }
                        )
                    }

                    BotTabSection.BUTTONS -> {
                        BotButtonsSection(
                            settings = settings,
                            onSaveSettings = { updated ->
                                viewModel.updateSettings(updated) {}
                            }
                        )
                    }

                    BotTabSection.REMINDERS -> {
                        val allReminders by viewModel.reminders.collectAsState()
                        BotRemindersSection(
                            settings = settings,
                            students = students,
                            reminders = allReminders,
                            templates = templates,
                            onUpdateSettings = { updated ->
                                viewModel.updateSettings(updated) {}
                            },
                            onScheduleTestReminder = { student, delayMinutes, exactHour, exactMinute, templateType, amount ->
                                viewModel.scheduleAutomaticTelegramTest(
                                    student = student,
                                    delayMinutes = delayMinutes,
                                    exactHour = exactHour,
                                    exactMinute = exactMinute,
                                    templateType = templateType,
                                    customAmount = amount
                                )
                            },
                            onCancelReminder = { reminderId ->
                                viewModel.cancelReminder(reminderId)
                            }
                        )
                    }

                    BotTabSection.LOGS -> {
                        BotLogsSection(
                            messageLogs = messageLogs
                        )
                    }

                    BotTabSection.SETTINGS -> {
                        BotSettingsSection(
                            settings = settings,
                            onSaveSettings = { updated ->
                                viewModel.updateSettings(updated) {}
                            },
                            onTestConnection = { token, callback ->
                                viewModel.testTelegramBot(token, callback)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Direct Send Message Dialog if triggered
    if (directMessageStudent != null) {
        val student = directMessageStudent!!
        val botUsername = settings?.telegramBotUsername ?: ""
        val deepLink = if (botUsername.isNotBlank()) "https://t.me/${botUsername.removePrefix("@")}?start=${student.id}" else ""

        SendMessageDialog(
            student = student,
            installment = null,
            templates = templates,
            settings = settings,
            telegramDeepLink = deepLink,
            onDismiss = { directMessageStudent = null },
            onSendMessage = { text, channel ->
                viewModel.sendMessageToStudent(
                    student = student,
                    messageText = text,
                    channel = channel
                ) { _ -> }
                directMessageStudent = null
            }
        )
    }
}
