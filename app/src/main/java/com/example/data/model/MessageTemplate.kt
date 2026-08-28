package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "message_templates")
data class MessageTemplate(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String = "REMINDER_3_DAYS",
    val title: String = "",
    val templateText: String = "",
    val isAutoSendEnabled: Boolean = false,
    val sendTime: String = "10:00",
    val targetChannel: String = "AUTO", // AUTO, TELEGRAM, RUBIKA, WHATSAPP, MANUAL
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "type" to type,
        "title" to title,
        "templateText" to templateText,
        "isAutoSendEnabled" to isAutoSendEnabled,
        "sendTime" to sendTime,
        "targetChannel" to targetChannel,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): MessageTemplate {
            return MessageTemplate(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                type = map["type"] as? String ?: "REMINDER_3_DAYS",
                title = map["title"] as? String ?: "",
                templateText = map["templateText"] as? String ?: "",
                isAutoSendEnabled = map["isAutoSendEnabled"] as? Boolean ?: false,
                sendTime = map["sendTime"] as? String ?: "10:00",
                targetChannel = map["targetChannel"] as? String ?: "AUTO",
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }

        fun getDefaultTemplates(): List<MessageTemplate> {
            return listOf(
                MessageTemplate(
                    id = "tmpl_7_days",
                    type = "REMINDER_7_DAYS",
                    title = "یادآوری ۷ روز قبل از سررسید",
                    templateText = "سلام {studentName} گرامی\nیادآوری می‌شود قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان در تاریخ {dueDate} سررسید می‌شود.\nبا تشکر - {academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "10:00",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_3_days",
                    type = "REMINDER_3_DAYS",
                    title = "یادآوری ۳ روز قبل از سررسید",
                    templateText = "سلام {studentName} گرامی\n۳ روز تا سررسید قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان باقی مانده است ({dueDate}).\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "10:00",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_1_day",
                    type = "REMINDER_1_DAY",
                    title = "یادآوری ۱ روز قبل از سررسید",
                    templateText = "سلام {studentName} گرامی\nفردا تاریخ سررسید قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان می‌باشد.\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "10:00",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_due_day",
                    type = "REMINDER_DUE_DAY",
                    title = "یادآوری روز سررسید قسط",
                    templateText = "سلام {studentName} گرامی\nامروز تاریخ سررسید قسط شماره {installmentNumber} دوره {courseName} به مبلغ {amount} تومان است. لطفاً نسبت به پرداخت اقدام فرمایید.\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "09:00",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_overdue",
                    type = "OVERDUE",
                    title = "پیام بعد از سررسید (دیرکرد و معوقه)",
                    templateText = "هنرجوی گرامی {studentName}\nقسط شماره {installmentNumber} دوره {courseName} به مبلغ {remainingDebt} تومان معوق شده است. خواهشمند است جهت تعیین وضعیت پرداخت با آموزشگاه تماس حاصل فرمایید.\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "11:00",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_payment_success",
                    type = "PAYMENT_SUCCESS",
                    title = "پیام تأیید دریافت پرداخت",
                    templateText = "هنرجوی گرامی {studentName}\nمبلغ {paymentAmount} تومان بابت قسط شماره {installmentNumber} دوره {courseName} دریافت شد (کد پیگیری: {receiptNumber}). مانده بدهی: {remainingDebt} تومان.\nبا تشکر - {academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "بلافاصله",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_settlement",
                    type = "FULL_SETTLEMENT",
                    title = "پیام تسویه حساب کامل",
                    templateText = "هنرجوی گرامی {studentName}\nشهریه دوره آموزشی {courseName} به طور کامل تسویه گردید. با آرزوی موفقیت روزافزون برای شما.\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "بلافاصله",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_registration",
                    type = "REGISTRATION",
                    title = "پیام خوش‌آمدگویی و ثبت‌نام در دوره",
                    templateText = "هنرجوی گرامی {studentName}\nثبت‌نام شما در دوره {courseName} با موفقیت انجام شد. از همراهی شما سپاسگزاریم.\n{academyName}",
                    isAutoSendEnabled = true,
                    sendTime = "بلافاصله",
                    targetChannel = "AUTO"
                ),
                MessageTemplate(
                    id = "tmpl_course_end",
                    type = "COURSE_END",
                    title = "پیام اتمام دوره آموزشی",
                    templateText = "هنرجوی گرامی {studentName}\nدوره آموزشی {courseName} به پایان رسید. جهت دریافت گواهی پایان دوره به دفتر آموزشگاه مراجعه فرمایید.\n{academyName}",
                    isAutoSendEnabled = false,
                    sendTime = "10:00",
                    targetChannel = "AUTO"
                )
            )
        }
    }
}

