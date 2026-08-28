package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "message_logs")
data class MessageLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val studentId: String = "",
    val studentName: String = "",
    val channel: String = "TELEGRAM", // TELEGRAM, RUBIKA, SMS, WHATSAPP, MANUAL
    val messageText: String = "",
    val sentAtShamsi: String = "",
    val sentAtEpoch: Long = System.currentTimeMillis(),
    val status: String = "SENT", // SENT, FAILED, PENDING, MANUAL_COPIED, NOT_LINKED
    val errorMessage: String = "",
    val messageId: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "studentId" to studentId,
        "studentName" to studentName,
        "channel" to channel,
        "messageText" to messageText,
        "sentAtShamsi" to sentAtShamsi,
        "sentAtEpoch" to sentAtEpoch,
        "status" to status,
        "errorMessage" to errorMessage,
        "messageId" to messageId
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): MessageLog {
            return MessageLog(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                channel = map["channel"] as? String ?: "TELEGRAM",
                messageText = map["messageText"] as? String ?: "",
                sentAtShamsi = map["sentAtShamsi"] as? String ?: "",
                sentAtEpoch = (map["sentAtEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = map["status"] as? String ?: "SENT",
                errorMessage = map["errorMessage"] as? String ?: "",
                messageId = map["messageId"] as? String ?: ""
            )
        }
    }
}
