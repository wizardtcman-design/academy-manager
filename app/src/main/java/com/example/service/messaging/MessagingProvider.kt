package com.example.service.messaging

import com.example.data.model.MessageLog
import com.example.data.model.Student

sealed class MessageSendResult {
    data class Success(val messageId: String, val note: String = "ارسال موفق") : MessageSendResult()
    data class Failed(val error: String) : MessageSendResult()
    data class NotConnected(val reason: String) : MessageSendResult()
    data class ManualActionRequired(val preparedText: String, val intentUri: String? = null) : MessageSendResult()
}

interface MessagingProvider {
    val channelName: String
    val isEnabled: Boolean
    val isConfigured: Boolean

    suspend fun sendMessage(
        student: Student,
        messageText: String
    ): MessageSendResult
}
