package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AcademySettings
import com.example.data.util.PersianDateUtil
import com.example.ui.theme.TealSecondary

/** Dialogs moved over from the old "More" tab so nothing is lost. */
@Composable
fun SettingsHubDialogs(
    which: String?,
    settings: AcademySettings?,
    versionName: String,
    onClose: () -> Unit,
    onGoToBotSettings: () -> Unit
) {
    val academyName = settings?.academyName ?: "آموزشگاه کامپیوتر"
    when (which) {
        "backup" -> AlertDialog(
            onDismissRequest = onClose,
            title = { DialogTitle("پشتیبان‌گیری و همگام‌سازی") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• داده‌های آموزشگاه همزمان در پایگاه داده محلی (Room) و ابری (Firestore) ذخیره می‌شوند.")
                    Text("• امکان خروجی کامل JSON برای بایگانی امن تعبیه شده است.")
                    Text("• فرکانس پشتیبان‌گیری خودکار: هفتگی")
                }
            },
            confirmButton = {
                Button(onClick = onClose) { Text("تایید") }
            }
        )

        "integrations" -> AlertDialog(
            onDismissRequest = onClose,
            title = { DialogTitle("اتصالات پیام‌رسانی") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "وضعیت ربات تلگرام: " +
                            if (settings?.isTelegramConfigured == true) "فعال و متصل" else "پیکربندی نشده",
                        fontWeight = FontWeight.Bold,
                        color = if (settings?.isTelegramConfigured == true) TealSecondary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "نام کاربری ربات: " +
                            if (settings?.telegramBotUsername?.isNotBlank() == true) "@${settings.telegramBotUsername}" else "تنظیم نشده"
                    )
                    Text("برای تنظیم توکن و نام کاربری به بخش اتصال ربات تلگرام مراجعه کنید.")
                }
            },
            confirmButton = {
                Button(onClick = { onClose(); onGoToBotSettings() }) { Text("رفتن به تنظیمات ربات") }
            },
            dismissButton = {
                OutlinedButton(onClick = onClose) { Text("بستن") }
            }
        )

        "installments" -> AlertDialog(
            onDismissRequest = onClose,
            title = { DialogTitle("منطق محاسبه اقساط آینده") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("فرمول اقساط بر اساس تاریخ پرداخت قسط اول:", fontWeight = FontWeight.Bold)
                    Text("مثال برای دوره ۳ ماهه:")
                    Text("• ثبت‌نام: ۱۰ شهریور")
                    Text("• پرداخت قسط ۱: ۱۳ شهریور")
                    Text("• سررسید قسط ۲: ۱۳ مهر (دقیقاً ۱ ماه بعد در تقویم شمسی)")
                    Text("• سررسید قسط ۳: ۱۳ آبان (۲ ماه بعد)")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "موتور محاسباتی PersianDateUtil.addMonths() برای اضافه کردن دقیق ماه‌های ۳۱ و ۳۰ روزه استفاده می‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = onClose) { Text("بستن") } }
        )

        "calendar" -> AlertDialog(
            onDismissRequest = onClose,
            title = { DialogTitle("تقویم جلالی آموزشگاه") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تاریخ جاری سیستم:", fontWeight = FontWeight.Bold)
                    Text(
                        text = "${PersianDateUtil.getDayOfWeekPersian()} ${PersianDateUtil.currentShamsiFullString()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "تبدیل الگوریتمی میلادی به شمسی بدون وابستگی خارجی، با پشتیبانی از سال‌های کبیسه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = onClose) { Text("تایید") } }
        )

        "about" -> AlertDialog(
            onDismissRequest = onClose,
            title = { DialogTitle("درباره سامانه مدیریت آموزشگاه") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(academyName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("نسخه نرم‌افزار: $versionName")
                    Text("ساخته‌شده با Jetpack Compose، Material Design 3 و پایگاه داده محلی Room.")
                    Text("شماره پشتیبانی: ${settings?.phone ?: "۰۲۱-۸۸۸۸۹۹۹۹"}")
                    Text("آدرس: ${settings?.address ?: "تهران"}")
                }
            },
            confirmButton = { Button(onClick = onClose) { Text("بستن") } }
        )
    }
}

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
