package com.example.service.messaging

import com.example.data.model.AcademySettings
import com.example.data.model.Student

class RubikaProvider(
    private var settings: AcademySettings?
) : MessagingProvider {

    override val channelName: String = "RUBIKA"

    override val isEnabled: Boolean
        get() = settings?.rubikaEnabled == true

    override val isConfigured: Boolean
        get() = false // Real official Bot API is currently closed/pending token registration

    fun updateSettings(newSettings: AcademySettings?) {
        this.settings = newSettings
    }

    override suspend fun sendMessage(
        student: Student,
        messageText: String
    ): MessageSendResult {
        // Transparent non-fake provider
        return MessageSendResult.NotConnected(
            "سرویس ربات پیام‌رسان روبیکا نیازمند API رسمی است - در حال حاضر ارسال دستی یا پیامک فعال می‌باشد."
        )
    }
}
