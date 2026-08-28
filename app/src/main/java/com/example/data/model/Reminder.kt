package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val type: String = "INSTALLMENT", // INSTALLMENT, COURSE_END, FOLLOW_UP, GENERAL
    val studentId: String = "",
    val studentName: String = "",
    val installmentId: String = "",
    val targetDateEpoch: Long = 0L,
    val targetDateShamsi: String = "",
    val scheduledTimeEpoch: Long = 0L, // Real exact timestamp for AlarmManager / WorkManager
    val triggerDaysOffset: Int = 0, // -7, -3, -1, 0, 1, 3
    val status: String = "SCHEDULED", // SCHEDULED, SENT, FAILED, CANCELLED
    val isCompleted: Boolean = false,
    val channel: String = "IN_APP", // IN_APP, SMS, WHATSAPP, TELEGRAM
    val messageBody: String = "",
    val sendTimeShamsi: String = "", // e.g. "۲۳ مرداد ساعت ۱۰:۰۰"
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "type" to type,
        "studentId" to studentId,
        "studentName" to studentName,
        "installmentId" to installmentId,
        "targetDateEpoch" to targetDateEpoch,
        "targetDateShamsi" to targetDateShamsi,
        "scheduledTimeEpoch" to scheduledTimeEpoch,
        "triggerDaysOffset" to triggerDaysOffset,
        "status" to status,
        "isCompleted" to isCompleted,
        "channel" to channel,
        "messageBody" to messageBody,
        "sendTimeShamsi" to sendTimeShamsi,
        "errorMessage" to errorMessage,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Reminder {
            return Reminder(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                title = map["title"] as? String ?: "",
                type = map["type"] as? String ?: "INSTALLMENT",
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                installmentId = map["installmentId"] as? String ?: "",
                targetDateEpoch = (map["targetDateEpoch"] as? Number)?.toLong() ?: 0L,
                targetDateShamsi = map["targetDateShamsi"] as? String ?: "",
                scheduledTimeEpoch = (map["scheduledTimeEpoch"] as? Number)?.toLong() ?: 0L,
                triggerDaysOffset = (map["triggerDaysOffset"] as? Number)?.toInt() ?: 0,
                status = map["status"] as? String ?: "SCHEDULED",
                isCompleted = map["isCompleted"] as? Boolean ?: false,
                channel = map["channel"] as? String ?: "IN_APP",
                messageBody = map["messageBody"] as? String ?: "",
                sendTimeShamsi = map["sendTimeShamsi"] as? String ?: "",
                errorMessage = map["errorMessage"] as? String ?: "",
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
