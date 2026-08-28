package com.example.service.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.data.model.Student
import java.net.URLEncoder

class ManualMessageProvider(
    private val context: Context
) : MessagingProvider {

    override val channelName: String = "MANUAL"
    override val isEnabled: Boolean = true
    override val isConfigured: Boolean = true

    override suspend fun sendMessage(
        student: Student,
        messageText: String
    ): MessageSendResult {
        return MessageSendResult.ManualActionRequired(
            preparedText = messageText
        )
    }

    fun openSms(phone: String, text: String): Intent {
        val uri = Uri.parse("smsto:$phone")
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun openWhatsApp(phone: String, text: String): Intent {
        val cleanPhone = phone.replace("+", "").replace("-", "").replace(" ", "").trim()
        val standardPhone = if (cleanPhone.startsWith("0")) "98" + cleanPhone.substring(1) else cleanPhone
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$standardPhone&text=$encodedText")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun openTelegramDirect(text: String): Intent {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val uri = Uri.parse("https://t.me/share/url?url=&text=$encodedText")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
