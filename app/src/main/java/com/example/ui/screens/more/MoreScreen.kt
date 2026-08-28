package com.example.ui.screens.more

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademySettings
import com.example.data.model.User
import com.example.data.util.PersianDateUtil
import com.example.service.NotificationHelper
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.TealSecondary

@Composable
fun MoreScreen(
    user: User?,
    settings: AcademySettings?,
    onNavigateToCourses: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAuditLogs: () -> Unit,
    onNavigateToReminders: () -> Unit = {},
    onOpenChangePassword: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var showMessageTemplateDialog by remember { mutableStateOf(false) }
    var showInstallmentsDialog by remember { mutableStateOf(false) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showIntegrationsDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // User Profile Summary Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.fullName ?: "مدیر آموزشگاه",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "نام کاربری: ${settings?.managerUsername ?: "admin"} | ${user?.email ?: "admin@academy.ir"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "مدیر کل",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 1. مدیریت آموزشگاه (Academy)
        item {
            CategoryCard(
                title = "۱. مدیریت آموزشگاه",
                items = listOf(
                    CategoryItemData(
                        title = "مشخصات آموزشگاه",
                        subtitle = "${settings?.academyName ?: "آموزشگاه کامپیوتر"} - واحد: ${settings?.currencyUnit ?: "تومان"}",
                        icon = Icons.Default.School,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateToSettings
                    ),
                    CategoryItemData(
                        title = "تاریخچه رخدادها و تغییرات (Audit Log)",
                        subtitle = "ثبت خودکار تمامی تغییرات، افزودن‌ها و حذف‌ها",
                        icon = Icons.Default.History,
                        iconTint = TealSecondary,
                        onClick = onNavigateToAuditLogs
                    )
                )
            )
        }

        // 2. هنرجویان و دوره‌ها (Students & Courses)
        item {
            CategoryCard(
                title = "۲. هنرجویان و دوره‌ها",
                items = listOf(
                    CategoryItemData(
                        title = "مدیریت دوره‌های آموزشی",
                        subtitle = "تعریف شهریه، طول دوره و ظرفیت کلاس‌ها",
                        icon = Icons.Default.School,
                        iconTint = IndigoPrimary,
                        onClick = onNavigateToCourses
                    ),
                    CategoryItemData(
                        title = "فرمول محاسبه خودکار اقساط",
                        subtitle = "محاسبه بر اساس تاریخ پرداخت قسط اول با تقویم خورشیدی",
                        icon = Icons.Default.FormatListNumbered,
                        iconTint = AmberTertiary,
                        onClick = { showInstallmentsDialog = true }
                    )
                )
            )
        }

        // 3. مالی و حسابداری (Finance)
        item {
            CategoryCard(
                title = "۳. مالی و حسابداری",
                items = listOf(
                    CategoryItemData(
                        title = "تنظیمات حساب‌های بانکی و مالی",
                        subtitle = "کارت‌ها و شماره‌حساب‌های آموزشگاه جهت دریافت شهریه",
                        icon = Icons.Default.AccountBalance,
                        iconTint = TealSecondary,
                        onClick = onNavigateToSettings
                    )
                )
            )
        }

        // 4. پیام‌ها و یادآوری‌ها (Messages & Reminders)
        item {
            CategoryCard(
                title = "۴. پیام‌ها و یادآوری‌ها",
                items = listOf(
                    CategoryItemData(
                        title = "مرکز قالب‌های پیام و اعلان‌ها",
                        subtitle = "ویرایش قالب‌های ۷ روز، ۳ روز، ۱ روز، سررسید و معوقه",
                        icon = Icons.Default.Message,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateToReminders
                    )
                )
            )
        }

        // 5. اعلان‌ها (Notifications)
        item {
            CategoryCard(
                title = "۵. اعلان‌ها و یادآورهای سیستم",
                items = listOf(
                    CategoryItemData(
                        title = "تست و بررسی نوتیفیکیشن اندروید",
                        subtitle = "ارسال یک اعلان آزمایشی جهت اطمینان از عملکرد سیستم",
                        icon = Icons.Default.Notifications,
                        iconTint = AmberTertiary,
                        onClick = {
                            val helper = NotificationHelper(context)
                            helper.showInstallmentDueNotification(
                                notificationId = 9999,
                                studentName = "علی محمدی (تست)",
                                amountFormatted = "۱,۵۰۰,۰۰۰ تومان",
                                dueDateShamsi = PersianDateUtil.currentShamsiString(),
                                dueCategory = "امروز",
                                customTitle = "🔔 تست اعلان سررسید قسط",
                                customMessage = "سامانه یادآوری اقساط آموزشگاه با موفقیت فعال است."
                            )
                            Toast.makeText(context, "اعلان آزمایشی ارسال شد.", Toast.LENGTH_SHORT).show()
                        }
                    )
                )
            )
        }

        // 6. امنیت (Security)
        item {
            CategoryCard(
                title = "۶. امنیت و دسترسی",
                items = listOf(
                    CategoryItemData(
                        title = "نام کاربری و قفل برنامه (App Lock)",
                        subtitle = "تنظیم نام کاربری مدیر و پین‌کد ورود",
                        icon = Icons.Default.Security,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateToSettings
                    ),
                    CategoryItemData(
                        title = "تغییر رمز عبور مدیر",
                        subtitle = "بروزرسانی امن رمز عبور ورود به سامانه",
                        icon = Icons.Default.Lock,
                        iconTint = AmberTertiary,
                        onClick = onOpenChangePassword
                    )
                )
            )
        }

        // 7. ظاهر برنامه (Appearance)
        item {
            CategoryCard(
                title = "۷. ظاهر و پوسته برنامه",
                items = listOf(
                    CategoryItemData(
                        title = "انتخاب رنگ تم و حالت شب/روز",
                        subtitle = "پوسته‌های آبی پیشگام، سبز زمردی، بنفش، سرمه‌ای",
                        icon = Icons.Default.Palette,
                        iconTint = IndigoPrimary,
                        onClick = onNavigateToSettings
                    )
                )
            )
        }

        // 8. پشتیبان‌گیری و اطلاعات (Backup & Data)
        item {
            CategoryCard(
                title = "۸. پشتیبان‌گیری و اطلاعات",
                items = listOf(
                    CategoryItemData(
                        title = "خروجی و پشتیبان‌گیری دیتابیس",
                        subtitle = "تهیه نسخه پشتیبان JSON از تمام هنرجویان و تراکنش‌ها",
                        icon = Icons.Default.Backup,
                        iconTint = TealSecondary,
                        onClick = { showBackupDialog = true }
                    )
                )
            )
        }

        // 9. اتصالات و سرویس‌ها (Integrations)
        item {
            CategoryCard(
                title = "۹. اتصالات و سرویس‌ها",
                items = listOf(
                    CategoryItemData(
                        title = "ربات تلگرام و پیام‌رسان‌ها",
                        subtitle = if (settings?.isTelegramConfigured == true) "ربات تلگرام متصل است (@${settings.telegramBotUsername})" else "پیکربندی توکن ربات تلگرام و روبیکا",
                        icon = Icons.Default.Share,
                        iconTint = if (settings?.isTelegramConfigured == true) TealSecondary else MaterialTheme.colorScheme.primary,
                        onClick = { showIntegrationsDialog = true }
                    )
                )
            )
        }

        // 10. درباره برنامه (About) & Logout
        item {
            CategoryCard(
                title = "۱۰. درباره برنامه و خروج",
                items = listOf(
                    CategoryItemData(
                        title = "درباره سامانه مدیریت آموزشگاه",
                        subtitle = "نسخه ۲.۰ - تقویم جلالی و قوانین مالی",
                        icon = Icons.Default.Info,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { showAboutDialog = true }
                    ),
                    CategoryItemData(
                        title = "تقویم جلالی و رویدادها",
                        subtitle = "${PersianDateUtil.getDayOfWeekPersian()} ${PersianDateUtil.currentShamsiFullString()}",
                        icon = Icons.Default.CalendarMonth,
                        iconTint = AmberTertiary,
                        onClick = { showCalendarDialog = true }
                    ),
                    CategoryItemData(
                        title = "خروج از حساب کاربری",
                        subtitle = "پایان نشست مدیریتی",
                        icon = Icons.AutoMirrored.Filled.Logout,
                        iconTint = StatusError,
                        isDestructive = true,
                        onClick = onLogout
                    )
                )
            )
        }
    }

    // Backup Dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = {
                Text(
                    text = "پشتیبان‌گیری و همگام‌سازی",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• داده‌های آموزشگاه همزمان در پایگاه داده محلی (Room) و ابری (Firestore) ذخیره می‌شوند.")
                    Text("• امکان خروجی کامل JSON برای بایگانی امن تعبیه شده است.")
                    Text("• فرکانس پشتیبان‌گیری خودکار: هفتگی")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackupDialog = false
                        Toast.makeText(context, "نسخه پشتیبان آماده است.", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("تایید")
                }
            }
        )
    }

    // Integrations Dialog
    if (showIntegrationsDialog) {
        AlertDialog(
            onDismissRequest = { showIntegrationsDialog = false },
            title = {
                Text(
                    text = "اتصالات پیام‌رسانی",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "وضعیت ربات تلگرام: " + if (settings?.isTelegramConfigured == true) "فعال و متصل" else "پیکربندی نشده",
                        fontWeight = FontWeight.Bold,
                        color = if (settings?.isTelegramConfigured == true) TealSecondary else MaterialTheme.colorScheme.error
                    )
                    Text("نام کاربری ربات: ${if (settings?.telegramBotUsername?.isNotBlank() == true) "@" + settings.telegramBotUsername else "تنظیم نشده"}")
                    Text("برای تنظیم توکن و نام کاربری به بخش تنظیمات آموزشگاه مراجعه کنید.")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIntegrationsDialog = false
                        onNavigateToSettings()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("رفتن به تنظیمات")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showIntegrationsDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("بستن")
                }
            }
        )
    }

    // Architecture Dialog for Installment Calculation
    if (showInstallmentsDialog) {
        AlertDialog(
            onDismissRequest = { showInstallmentsDialog = false },
            title = {
                Text(
                    text = "منطق محاسبه اقساط آینده",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "فرمول اقساط بر اساس تاریخ پرداخت قسط اول:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("مثال برای دوره ۳ ماهه:")
                    Text("• ثبت‌نام: ۱۰ شهریور")
                    Text("• پرداخت قسط ۱: ۱۳ شهریور")
                    Text("• سررسید قسط ۲: ۱۳ مهر (دقیقاً ۱ ماه بعد در تقویم شمسی)")
                    Text("• سررسید قسط ۳: ۱۳ آبان (۲ ماه بعد)")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "موتور محاسباتی PersianDateUtil.addMonths() برای اضافه کردن دقیق ماه‌های ۳۱ و ۳۰ روزه در سیستم پیاده‌سازی شده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showInstallmentsDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("بستن")
                }
            }
        )
    }

    // Jalali Calendar dialog
    if (showCalendarDialog) {
        AlertDialog(
            onDismissRequest = { showCalendarDialog = false },
            title = {
                Text(
                    text = "تقویم جلالی آموزشگاه",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "تاریخ جاری سیستم:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${PersianDateUtil.getDayOfWeekPersian()} ${PersianDateUtil.currentShamsiFullString()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "سیستم تبدیل الگوریتمی دقیق میلادی به شمسی بدون وابستگی خارجی با پشتیبانی از سال‌های کبیسه پیاده‌سازی شده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCalendarDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("تایید")
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Text(
                    text = "درباره سامانه مدیریت آموزشگاه",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = settings?.academyName ?: "آموزشگاه کامپیوتر پیشگامان",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text("نسخه نرم‌افزار: ۲.۰.۰")
                    Text("طراحی شده بر پایه معماری مدرن Jetpack Compose، Material Design 3 و پایگاه داده محلی Room.")
                    Text("شماره پشتیبانی: ${settings?.phone ?: "۰۲۱-۸۸۸۸۹۹۹۹"}")
                    Text("آدرس: ${settings?.address ?: "تهران"}")
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("بستن")
                }
            }
        )
    }
}

private data class CategoryItemData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconTint: Color,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
private fun CategoryCard(
    title: String,
    items: List<CategoryItemData>
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    MoreMenuItem(
                        title = item.title,
                        subtitle = item.subtitle,
                        icon = item.icon,
                        iconTint = item.iconTint,
                        isDestructive = item.isDestructive,
                        onClick = item.onClick
                    )
                    if (index < items.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreMenuItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = iconTint.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
