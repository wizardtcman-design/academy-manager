package com.example.service.messaging

import android.content.Context
import com.example.data.local.MessageLogDao
import com.example.data.local.MessageTemplateDao
import com.example.data.model.AcademySettings
import com.example.data.model.MessageLog
import com.example.data.model.MessageTemplate
import com.example.data.model.Student
import com.example.data.util.FinanceUtil
import com.example.data.util.PersianDateUtil
import java.util.UUID

class MessagingManager(
    private val context: Context,
    private val messageTemplateDao: MessageTemplateDao,
    private val messageLogDao: MessageLogDao,
    private var currentSettings: AcademySettings?
) {
    val telegramProvider = TelegramProvider(context, currentSettings)
    val rubikaProvider = RubikaProvider(currentSettings)
    val manualProvider = ManualMessageProvider(context)

    fun updateSettings(settings: AcademySettings?) {
        this.currentSettings = settings
        telegramProvider.updateSettings(settings)
        rubikaProvider.updateSettings(settings)
    }

    /**
     * Sends message via specified channel (or auto selects Telegram if connected)
     */
    suspend fun sendMessage(
        student: Student,
        messageText: String,
        targetChannel: String = "AUTO"
    ): Pair<MessageSendResult, MessageLog> {
        val channelToUse = when (targetChannel) {
            "TELEGRAM" -> "TELEGRAM"
            "RUBIKA" -> "RUBIKA"
            "MANUAL" -> "MANUAL"
            else -> if (student.isTelegramConnected && telegramProvider.isEnabled) "TELEGRAM" else "MANUAL"
        }

        val result = when (channelToUse) {
            "TELEGRAM" -> telegramProvider.sendMessage(student, messageText)
            "RUBIKA" -> rubikaProvider.sendMessage(student, messageText)
            else -> manualProvider.sendMessage(student, messageText)
        }

        val nowEpoch = System.currentTimeMillis()
        val nowShamsi = PersianDateUtil.currentShamsiString()

        val log = when (result) {
            is MessageSendResult.Success -> MessageLog(
                id = UUID.randomUUID().toString(),
                studentId = student.id,
                studentName = student.fullName,
                channel = channelToUse,
                messageText = messageText,
                sentAtShamsi = nowShamsi,
                sentAtEpoch = nowEpoch,
                status = "SENT",
                errorMessage = "",
                messageId = result.messageId
            )
            is MessageSendResult.Failed -> MessageLog(
                id = UUID.randomUUID().toString(),
                studentId = student.id,
                studentName = student.fullName,
                channel = channelToUse,
                messageText = messageText,
                sentAtShamsi = nowShamsi,
                sentAtEpoch = nowEpoch,
                status = "FAILED",
                errorMessage = result.error,
                messageId = ""
            )
            is MessageSendResult.NotConnected -> MessageLog(
                id = UUID.randomUUID().toString(),
                studentId = student.id,
                studentName = student.fullName,
                channel = channelToUse,
                messageText = messageText,
                sentAtShamsi = nowShamsi,
                sentAtEpoch = nowEpoch,
                status = "NOT_LINKED",
                errorMessage = result.reason,
                messageId = ""
            )
            is MessageSendResult.ManualActionRequired -> MessageLog(
                id = UUID.randomUUID().toString(),
                studentId = student.id,
                studentName = student.fullName,
                channel = "MANUAL",
                messageText = messageText,
                sentAtShamsi = nowShamsi,
                sentAtEpoch = nowEpoch,
                status = "MANUAL_COPIED",
                errorMessage = "",
                messageId = ""
            )
        }

        messageLogDao.insertLog(log)
        return Pair(result, log)
    }

    /**
     * Resolves message template by type and fills student/installment details
     */
    suspend fun prepareMessage(
        templateType: String,
        student: Student,
        courseTitle: String = "",
        installmentNumber: Int = 1,
        installmentAmount: Long = 0L,
        paidAmount: Long = 0L,
        remainingDebt: Long = 0L,
        dueDateShamsi: String = ""
    ): String {
        val template = messageTemplateDao.getTemplateByType(templateType)
        val rawText = template?.templateText ?: MessageTemplate.getDefaultTemplates().find { it.type == templateType }?.templateText ?: ""

        return FinanceUtil.fillMessageTemplate(
            templateText = rawText,
            student = student,
            courseTitle = courseTitle,
            installmentNumber = installmentNumber,
            installmentAmount = installmentAmount,
            paidAmount = paidAmount,
            remainingDebt = remainingDebt,
            dueDateShamsi = dueDateShamsi,
            settings = currentSettings
        )
    }
}
