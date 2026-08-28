package com.example.ui.screens.bot

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealSecondary

val defaultInteractiveButtons = listOf(
    BotInteractiveButton(
        id = "btn_financial_status",
        defaultTitle = "💰 وضعیت مالی",
        customTitle = "💰 وضعیت مالی",
        iconEmoji = "💰",
        isEnabled = true,
        description = "هنرجو با لمس این دکمه خلاصه شهریه کل دوره، کل مبالغ پرداختی و مانده بدهی جاری را دریافت می‌کند.",
        sampleResponse = "📊 وضعیت مالی شما:\n• کل شهریه دوره‌ها: ۵,۰۰۰,۰۰۰ تومان\n• کل پرداختی‌ها: ۳,۰۰۰,۰۰۰ تومان\n• مانده بدهی فعلی: ۲,۰۰۰,۰۰۰ تومان"
    ),
    BotInteractiveButton(
        id = "btn_my_installments",
        defaultTitle = "📅 اقساط من",
        customTitle = "📅 اقساط من",
        iconEmoji = "📅",
        isEnabled = true,
        description = "ارسال جدول اقساط با ذکر شماره قسط، مبلغ، تاریخ سررسید و وضعیت پرداخت شده یا معوق.",
        sampleResponse = "📅 لیست اقساط شما:\n۱. قسط ۱: ۱,۵۰۰,۰۰۰ تومان (پرداخت شده ✓)\n۲. قسط ۲: ۱,۵۰۰,۰۰۰ تومان (سررسید: ۱۴۰۴/۰۶/۱۵ - در انتظار ⏰)\n۳. قسط ۳: ۲,۰۰۰,۰۰۰ تومان (سررسید: ۱۴۰۴/۰۷/۱۵)"
    ),
    BotInteractiveButton(
        id = "btn_my_receipts",
        defaultTitle = "🧾 رسیدهای من",
        customTitle = "🧾 رسیدهای من",
        iconEmoji = "🧾",
        isEnabled = true,
        description = "مشاهده رسیدهای پرداخت ثبت شده در سامانه همراه با شماره پیگیری و تاریخ ثبت تراکنش.",
        sampleResponse = "🧾 رسیدهای پرداخت ثبت‌شده:\n• رسید TRX-9812: مبلغ ۱,۵۰۰,۰۰۰ تومان (تاریخ ۱۴۰۴/۰۵/۱۵ - کارت به کارت)\n• رسید TRX-7611: مبلغ ۱,۵۰۰,۰۰۰ تومان (تاریخ ۱۴۰۴/۰۴/۱۵ - پوز آموزشگاه)"
    ),
    BotInteractiveButton(
        id = "btn_my_contract",
        defaultTitle = "📄 قرارداد من",
        customTitle = "📄 قرارداد من",
        iconEmoji = "📄",
        isEnabled = true,
        description = "ارسال مشخصات دوره آموزشی ثبت‌نام شده، تاریخ شروع، روزها و ساعت برگزاری و وضعیت عضویت.",
        sampleResponse = "📄 مشخصات قرارداد آموزشی:\n• عنوان دوره: پایتون پیشرفته و هوش مصنوعی\n• وضعیت: هنرجوی فعال\n• مدت دوره: ۳ ماهه (۳۶ ساعت)"
    ),
    BotInteractiveButton(
        id = "btn_contact_academy",
        defaultTitle = "📞 تماس با آموزشگاه",
        customTitle = "📞 تماس با آموزشگاه",
        iconEmoji = "📞",
        isEnabled = true,
        description = "ارسال شماره تماس‌های پشتیبانی، نشانی، ساعات کاری و موقعیت مکانی آموزشگاه برای هنرجو.",
        sampleResponse = "📞 راه‌های ارتباط با آموزشگاه:\n• تلفن: ۰۲۱-۸۸۸۸۹۹۹۹\n• آدرس: تهران، میدان انقلاب، خیابان کارگر شمالی\n• ساعات کاری: همه‌روزه از ساعت ۹ الی ۲۰"
    ),
    BotInteractiveButton(
        id = "btn_refresh_info",
        defaultTitle = "🔄 بروزرسانی اطلاعات",
        customTitle = "🔄 بروزرسانی اطلاعات",
        iconEmoji = "🔄",
        isEnabled = true,
        description = "استعلام و به‌روزرسانی زنده آخرین وضعیت حساب، بدهی و مشخصات هنرجو از سامانه.",
        sampleResponse = "🔄 اطلاعات حساب کاربری شما به‌روزرسانی شد ✓\n• نام: علی رضایی\n• دوره‌های فعال: ۱ دوره\n• وضعیت بدهی: تسویه شده"
    )
)

@Composable
fun BotButtonsSection(
    settings: AcademySettings?,
    onSaveSettings: (AcademySettings) -> Unit = {}
) {
    val context = LocalContext.current
    val buttonsState = remember {
        mutableStateListOf<BotInteractiveButton>().apply {
            addAll(defaultInteractiveButtons)
        }
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
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "در این بخش می‌توانید کلیدهای کیبورد شیشه‌ای منوی اصلی ربات تلگرام را فعال/غیرفعال کرده و عنوان هر دکمه را مطابق با نیاز خود شخصی‌سازی کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Live Keyboard Layout Preview
        Text(
            text = "پیش‌نمایش کیبورد تلگرام هنرجو:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val activeButtons = buttonsState.filter { it.isEnabled }
                if (activeButtons.isEmpty()) {
                    Text(
                        text = "هیچ دکمه‌ای فعال نیست.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    // Render in 2 columns
                    activeButtons.chunked(2).forEach { rowBtns ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowBtns.forEach { btn ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 1.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp, horizontal = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = btn.customTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            if (rowBtns.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // List of all 5 interactive buttons
        buttonsState.forEachIndexed { index, btn ->
            BotButtonItemCard(
                button = btn,
                onTitleChange = { newTitle ->
                    buttonsState[index] = btn.copy(customTitle = newTitle)
                },
                onToggleEnabled = { enabled ->
                    buttonsState[index] = btn.copy(isEnabled = enabled)
                },
                onSave = {
                    Toast.makeText(context, "تنظیمات کلید «${btn.customTitle}» ذخیره شد.", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun BotButtonItemCard(
    button: BotInteractiveButton,
    onTitleChange: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    var title by remember(button.customTitle) { mutableStateOf(button.customTitle) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bot_button_card_${button.id}"),
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = button.iconEmoji, fontSize = 22.sp)
                    Column {
                        Text(
                            text = button.customTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (button.isEnabled) "فعال در کیبورد ربات" else "غیرفعال",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (button.isEnabled) StatusSuccess else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Switch(
                    checked = button.isEnabled,
                    onCheckedChange = {
                        onToggleEnabled(it)
                    },
                    modifier = Modifier.testTag("switch_${button.id}")
                )
            }

            Text(
                text = button.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Edit Title field
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    onTitleChange(it)
                },
                label = { Text("عنوان نمایشی دکمه") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_${button.id}"),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            // Simulated response preview
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "نمونه پاسخ خودکار ربات پس از فشرده شدن دکمه:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = button.sampleResponse,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Save
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ذخیره تنظیمات کلید")
            }
        }
    }
}
