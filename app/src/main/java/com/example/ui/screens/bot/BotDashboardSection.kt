package com.example.ui.screens.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.MessageLog
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealSecondary

@Composable
fun BotDashboardSection(
    settings: AcademySettings?,
    students: List<Student>,
    messageLogs: List<MessageLog>,
    onNavigateToTab: (BotTabSection) -> Unit,
    onTestConnection: () -> Unit
) {
    val isConfigured = settings?.isTelegramConfigured == true
    val isEnabled = settings?.telegramEnabled == true
    val botUsername = settings?.telegramBotUsername?.trim() ?: ""

    val connectedStudentsCount = remember(students) {
        students.count { it.isTelegramConnected }
    }
    val unconnectedStudentsCount = remember(students) {
        students.size - connectedStudentsCount
    }

    val todayShamsi = remember { PersianDateUtil.currentShamsiString() }
    val todayLogs = remember(messageLogs, todayShamsi) {
        messageLogs.filter { it.sentAtShamsi.startsWith(todayShamsi) }
    }
    val sentTodayCount = todayLogs.size
    val successCount = remember(messageLogs) {
        messageLogs.count { it.status == "SENT" }
    }
    val failedCount = remember(messageLogs) {
        messageLogs.count { it.status == "FAILED" }
    }
    val lastActivity = remember(messageLogs) {
        val latest = messageLogs.maxByOrNull { it.sentAtEpoch }
        if (latest != null) {
            "${latest.sentAtShamsi} (${latest.studentName.ifBlank { "سیستم" }})"
        } else {
            "هنوز فعالیتی ثبت نشده است"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Connection Status Hero Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_status_hero_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    !isConfigured -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    !isEnabled -> AmberTertiary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                !isConfigured -> StatusError
                                !isEnabled -> AmberTertiary
                                else -> StatusSuccess
                            },
                            modifier = Modifier.size(14.dp)
                        ) {}

                        Text(
                            text = when {
                                !isConfigured -> "ربات تلگرام پیکربندی نشده است"
                                !isEnabled -> "ربات تلگرام غیرفعال است"
                                else -> "ربات تلگرام فعال و آماده به کار"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isConfigured && isEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isConfigured && isEnabled) StatusSuccess else StatusError
                            )
                            Text(
                                text = if (isConfigured && isEnabled) "آنلاین" else "آفلاین",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "آیدی ربات تلگرام:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (botUsername.isNotBlank()) "@$botUsername" else "هنوز تعیین نشده",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Button(
                        onClick = onTestConnection,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("bot_dashboard_test_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تست اتصال", fontSize = 12.sp)
                    }
                }
            }
        }

        // 2. Overview Stats (Grid 2x2 & KPIs)
        Text(
            text = "آمار و وضعیت ارتباطات",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "هنرجویان متصل",
                value = "$connectedStudentsCount نفر",
                subtitle = "دارای شناسه تلگرام",
                icon = Icons.Default.Link,
                accentColor = StatusSuccess,
                onClick = { onNavigateToTab(BotTabSection.STUDENTS) }
            )

            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "غیرمتصل به ربات",
                value = "$unconnectedStudentsCount نفر",
                subtitle = "نیازمند ارسال لینک",
                icon = Icons.Default.LinkOff,
                accentColor = AmberTertiary,
                onClick = { onNavigateToTab(BotTabSection.STUDENTS) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "پیام‌های امروز",
                value = "$sentTodayCount پیام",
                subtitle = "ارسال‌های خودکار و دستی",
                icon = Icons.Default.Send,
                accentColor = IndigoPrimary,
                onClick = { onNavigateToTab(BotTabSection.LOGS) }
            )

            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "پیام‌های موفق",
                value = "$successCount پیام",
                subtitle = "تحویل شده در تلگرام",
                icon = Icons.Default.CheckCircle,
                accentColor = TealSecondary,
                onClick = { onNavigateToTab(BotTabSection.LOGS) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "پیام‌های ناموفق",
                value = "$failedCount خطا",
                subtitle = "نیاز به بررسی",
                icon = Icons.Default.Error,
                accentColor = StatusError,
                onClick = { onNavigateToTab(BotTabSection.LOGS) }
            )

            BotStatCard(
                modifier = Modifier.weight(1f),
                title = "یادآوری‌های فعال",
                value = "۵ بازه زمانی",
                subtitle = "۷ روز، ۳ روز، سررسید و...",
                icon = Icons.Default.Alarm,
                accentColor = AmberTertiary,
                onClick = { onNavigateToTab(BotTabSection.REMINDERS) }
            )
        }

        // 3. Last Activity Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "آخرین فعالیت ثبت‌شده ربات",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lastActivity,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 4. Quick Actions
        Text(
            text = "دسترسی سریع به بخش‌های ربات",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onNavigateToTab(BotTabSection.STUDENTS) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("هنرجویان", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = { onNavigateToTab(BotTabSection.MESSAGES) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("پیام‌ها", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = { onNavigateToTab(BotTabSection.SETTINGS) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("تنظیمات", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun BotStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}
