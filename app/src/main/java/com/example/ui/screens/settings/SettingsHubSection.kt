package com.example.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademySettings
import com.example.data.model.User
import com.example.data.util.PersianDateUtil
import com.example.ui.components.IconTile
import com.example.ui.components.SegmentedTabs

/**
 * The former "More" tab, rebuilt as a grouped hub on top of Settings.
 * Nothing was dropped: every item that used to live in that tab is here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsHubSection(
    user: User?,
    settings: AcademySettings?,
    versionName: String,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onNavigateToCourses: () -> Unit,
    onNavigateToAuditLogs: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onOpenInstallmentFormula: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onLogout: () -> Unit,
    onTestNotification: () -> Unit
) {
    // Profile card
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            Color.Transparent
                        )
                    )
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.fullName ?: "مدیر آموزشگاه",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = settings?.managerUsername ?: "admin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "نسخه $versionName",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // Quick access tiles
    Text(
        text = "دسترسی سریع",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3
    ) {
        QuickTile("آموزشگاه", Icons.Default.School, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onSelectTab(1) }
        QuickTile("بانک و کارت", Icons.Default.CreditCard, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { onSelectTab(3) }
        QuickTile("قالب پیام", Icons.Default.Message, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f)) { onNavigateToReminders() }
        QuickTile("دوره‌ها", Icons.Default.FormatListNumbered, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onNavigateToCourses() }
        QuickTile("ربات تلگرام", Icons.Default.Share, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { onOpenIntegrations() }
        QuickTile("امنیت", Icons.Default.Security, Color(0xFF34D399), Modifier.weight(1f)) { onSelectTab(4) }
    }

    Spacer(Modifier.height(16.dp))

    // Backup / system tile also kept reachable from the hub
    HubCard(
        title = "داده‌ها، پشتیبان و سیستم",
        icon = Icons.Default.Storage,
        accent = MaterialTheme.colorScheme.tertiary
    ) {
        HubRow("پشتیبان‌گیری و خروجی JSON", "نسخه پشتیبان کامل از هنرجویان و تراکنش‌ها", Icons.Default.Backup, MaterialTheme.colorScheme.tertiary) { onOpenBackup() }
        HubRow("تست اعلان اندروید", "اطمینان از کارکرد یادآوری‌ها و نوتیفیکیشن‌ها", Icons.Default.NotificationsActive, Color(0xFFFBBF24)) { onTestNotification() }
        HubRow("تقویم جلالی", "${PersianDateUtil.getDayOfWeekPersian()} ${PersianDateUtil.currentShamsiFullString()}", Icons.Default.CalendarMonth, MaterialTheme.colorScheme.secondary) { onOpenCalendar() }
        HubRow("درباره برنامه", "نسخه $versionName · تقویم جلالی و قوانین مالی", Icons.Default.Info, MaterialTheme.colorScheme.primary, last = true) { onOpenAbout() }
    }

    Spacer(Modifier.height(14.dp))

    HubCard(
        title = "مدیریت و امنیت",
        icon = Icons.Default.Security,
        accent = MaterialTheme.colorScheme.primary
    ) {
        HubRow("تاریخچه تغییرات (Audit Log)", "ثبت خودکار افزودن‌ها، ویرایش‌ها و حذف‌ها", Icons.Default.History, Color(0xFF34D399)) { onNavigateToAuditLogs() }
        HubRow("فرمول محاسبه اقساط", "محاسبه بر اساس تاریخ قسط اول با تقویم خورشیدی", Icons.Default.AccountBalance, Color(0xFFFBBF24)) { onOpenInstallmentFormula() }
        HubRow("تغییر رمز عبور مدیر", "بروزرسانی امن رمز ورود به سامانه", Icons.Default.Lock, MaterialTheme.colorScheme.secondary) { onOpenChangePassword() }
        HubRow("خروج از حساب کاربری", "پایان نشست مدیریتی", Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.error, last = true, destructive = true) { onLogout() }
    }

    Spacer(Modifier.height(16.dp))

    Text(
        text = "جزئیات تنظیمات",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(8.dp))
    SegmentedTabs(
        labels = listOf("ظاهر", "آموزشگاه", "پیام‌ها", "امنیت", "داده‌ها"),
        selectedIndex = selectedTab,
        onSelect = onSelectTab,
        icons = listOf(
            Icons.Default.Palette,
            Icons.Default.School,
            Icons.Default.Notifications,
            Icons.Default.Security,
            Icons.Default.Storage
        )
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun QuickTile(
    title: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            tint.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconTile(icon, tint, size = 32)
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HubCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.16f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun HubRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    last: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, if (destructive) MaterialTheme.colorScheme.error else tint, size = 34)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 12.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
        }
    }
}
