package com.example.ui.screens.bot

import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.MessageTemplate
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealSecondary

data class MessageTypeItem(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val defaultText: String,
    val emoji: String,
    val availableVariables: List<Pair<String, String>> // tag to description
)

val standardMessageTypes = listOf(
    MessageTypeItem(
        id = "tmpl_registration",
        type = "REGISTRATION",
        title = "پیام خوش‌آمدگویی و ثبت‌نام (Welcome Message)",
        description = "هنگام ثبت‌نام هنرجو در دوره جدید به صورت خودکار ارسال می‌شود.",
        defaultText = "هنرجوی گرامی {studentName}\nثبت‌نام شما در دوره {courseName} با موفقیت انجام شد. از همراهی شما در {academyName} سپاسگزاریم.\nکد رهگیری ثبت‌نام: {receiptNumber}",
        emoji = "👋",
        availableVariables = listOf(
            "{studentName}" to "نام و نام‌خانوادگی هنرجو",
            "{courseName}" to "عنوان دوره آموزشی",
            "{academyName}" to "نام آموزشگاه",
            "{receiptNumber}" to "کد رهگیری ثبت‌نام"
        )
    ),
    MessageTypeItem(
        id = "tmpl_3_days",
        type = "REMINDER_3_DAYS",
        title = "یادآوری موعد قسط (Payment Reminder)",
        description = "چند روز قبل از موعد سررسید قسط برای هنرجو ارسال می‌شود.",
        defaultText = "سلام {studentName} گرامی\nیادآوری می‌شود موعد قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان در تاریخ {dueDate} می‌باشد.\nبا سپاس - {academyName}",
        emoji = "⏰",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{installmentNumber}" to "شماره قسط",
            "{amount}" to "مبلغ قسط",
            "{dueDate}" to "تاریخ سررسید",
            "{academyName}" to "نام آموزشگاه"
        )
    ),
    MessageTypeItem(
        id = "tmpl_due_day",
        type = "REMINDER_DUE_DAY",
        title = "سررسید قسط امروز (Due Today)",
        description = "در صبح روز سررسید قسط جهت واریز وجه ارسال می‌گردد.",
        defaultText = "سلام {studentName} گرامی\nامروز تاریخ سررسید قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان است. لطفاً نسبت به پرداخت و ثبت فیش اقدام فرمایید.\n{academyName}",
        emoji = "🚨",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{installmentNumber}" to "شماره قسط",
            "{amount}" to "مبلغ قسط",
            "{academyName}" to "نام آموزشگاه"
        )
    ),
    MessageTypeItem(
        id = "tmpl_overdue",
        type = "OVERDUE",
        title = "اخطار معوقه و دیرکرد (Overdue Payment)",
        description = "پس از گذشت موعد سررسید قسط به عنوان هشدار ارسال می‌گردد.",
        defaultText = "هنرجوی گرامی {studentName}\nقسط شماره {installmentNumber} دوره {courseName} به مبلغ {remainingDebt} تومان معوق شده است. خواهشمند است جهت تعیین وضعیت پرداخت با مدیریت آموزشگاه تماس حاصل فرمایید.\n{academyName}",
        emoji = "⚠️",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{installmentNumber}" to "شماره قسط",
            "{remainingDebt}" to "مبلغ معوقه",
            "{academyName}" to "نام آموزشگاه"
        )
    ),
    MessageTypeItem(
        id = "tmpl_payment_success",
        type = "PAYMENT_SUCCESS",
        title = "رسید و تایید دریافت پرداخت (Payment Receipt)",
        description = "بلافاصله پس از ثبت هر پرداخت یا قسط در سامانه ارسال می‌گردد.",
        defaultText = "هنرجوی گرامی {studentName}\nمبلغ {paymentAmount} تومان بابت شهریه دوره {courseName} دریافت شد.\nکد پیگیری تراکنش: {receiptNumber}\nمانده بدهی شما: {remainingDebt} تومان.\nبا تشکر - {academyName}",
        emoji = "🧾",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{paymentAmount}" to "مبلغ واریزی",
            "{receiptNumber}" to "کد پیگیری",
            "{remainingDebt}" to "مانده بدهی",
            "{academyName}" to "نام آموزشگاه"
        )
    ),
    MessageTypeItem(
        id = "tmpl_settlement",
        type = "FULL_SETTLEMENT",
        title = "تسویه حساب کامل دوره (Full Settlement)",
        description = "هنگام پرداخت آخرین قسط و صفر شدن مانده بدهی هنرجو ارسال می‌شود.",
        defaultText = "هنرجوی گرامی {studentName}\nشهریه دوره آموزشی {courseName} به طور کامل تسویه گردید. صمیمانه برای شما آرزوی موفقیت و پیشرفت روزافزون داریم.\n{academyName}",
        emoji = "🎓",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{academyName}" to "نام آموزشگاه"
        )
    ),
    MessageTypeItem(
        id = "tmpl_custom",
        type = "CUSTOM",
        title = "پیام سفارشی و عمومی (Custom Message)",
        description = "جهت اطلاع‌رسانی تعطیلی کلاس‌ها، رویدادها یا تغییر ساعات آموزشی.",
        defaultText = "هنرجوی گرامی {studentName}\nاطلاعیه آموزشی: به اطلاع می‌رساند کلیه کلاس‌های دوره {courseName} در تاریخ {dueDate} برگزار می‌گردد.\nبا احترام - {academyName}",
        emoji = "📢",
        availableVariables = listOf(
            "{studentName}" to "نام هنرجو",
            "{courseName}" to "نام دوره",
            "{dueDate}" to "تاریخ",
            "{academyName}" to "نام آموزشگاه"
        )
    )
)

@Composable
fun BotMessagesSection(
    settings: AcademySettings?,
    templates: List<MessageTemplate>,
    onUpdateTemplate: (MessageTemplate) -> Unit
) {
    val academyName = settings?.academyName ?: "آموزشگاه کامپیوتر پیشگامان"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Intro Info Banner
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
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "در این بخش می‌توانید متن الگوهای پیام‌های خودکار ربات تلگرام را ویرایش کنید و پیش‌نمایش ارسال را به صورت زنده مشاهده نمایید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Render each of the 7 standard message cards
        standardMessageTypes.forEach { item ->
            val existingTemplate = templates.find { it.type == item.type || it.id == item.id }
            val isEnabled = existingTemplate?.isAutoSendEnabled ?: true
            val initialText = existingTemplate?.templateText?.ifBlank { item.defaultText } ?: item.defaultText

            MessageTemplateEditorCard(
                item = item,
                initialText = initialText,
                initialEnabled = isEnabled,
                academyName = academyName,
                onSave = { updatedText, updatedEnabled ->
                    val templateToSave = (existingTemplate ?: MessageTemplate(
                        id = item.id,
                        type = item.type,
                        title = item.title
                    )).copy(
                        title = item.title,
                        templateText = updatedText,
                        isAutoSendEnabled = updatedEnabled,
                        updatedAt = System.currentTimeMillis()
                    )
                    onUpdateTemplate(templateToSave)
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageTemplateEditorCard(
    item: MessageTypeItem,
    initialText: String,
    initialEnabled: Boolean,
    academyName: String,
    onSave: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    var text by remember(initialText) { mutableStateOf(initialText) }
    var isEnabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }
    var showPreview by remember { mutableStateOf(true) }

    // Replace variables for sample preview
    val samplePreviewText = remember(text, academyName) {
        text
            .replace("{studentName}", "علی محمدی")
            .replace("{courseName}", "پایتون پیشرفته")
            .replace("{installmentNumber}", "۲")
            .replace("{amount}", "۱,۵۰۰,۰۰۰")
            .replace("{paymentAmount}", "۱,۵۰۰,۰۰۰")
            .replace("{remainingDebt}", "۳,۰۰۰,۰۰۰")
            .replace("{dueDate}", "۱۴۰۴/۰۶/۱۵")
            .replace("{receiptNumber}", "TRX-89241")
            .replace("{academyName}", academyName)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bot_msg_card_${item.type}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Emoji, Title & Switch
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
                    Text(text = item.emoji, fontSize = 22.sp)
                    Column {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { isEnabled = it },
                    modifier = Modifier.testTag("switch_${item.type}")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Editable Text Area
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_${item.type}"),
                label = { Text("متن الگوی پیام") },
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp)
            )

            // Available Variable Chips
            Text(
                text = "متغیرهای قابل استفاده (جهت درج کلیک کنید):",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item.availableVariables.forEach { (tag, desc) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                text = "$text $tag"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Telegram Live Preview Bubble Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
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
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = IndigoPrimary
                            )
                            Text(
                                text = "پیش‌نمایش ظاهر در تلگرام",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = IndigoPrimary
                            )
                        }

                        Text(
                            text = "۱۰:۳۰ ق.ظ ✓✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 2.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 1.dp
                    ) {
                        Text(
                            text = samplePreviewText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }
                }
            }

            // Save Button
            Button(
                onClick = {
                    onSave(text, isEnabled)
                    Toast.makeText(context, "الگوی ${item.title} با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_${item.type}"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("ذخیره تغییرات این الگو")
            }
        }
    }
}
