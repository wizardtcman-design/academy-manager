package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "academy_notifications")
data class AcademyNotification(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val message: String = "",
    val type: String = "INFO", // INFO, WARNING, SUCCESS, INSTALLMENT_DUE, PAYMENT_RECEIVED
    val dateShamsi: String = "",
    val dateEpoch: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val targetScreen: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "message" to message,
        "type" to type,
        "dateShamsi" to dateShamsi,
        "dateEpoch" to dateEpoch,
        "isRead" to isRead,
        "targetScreen" to targetScreen
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): AcademyNotification {
            return AcademyNotification(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                title = map["title"] as? String ?: "",
                message = map["message"] as? String ?: "",
                type = map["type"] as? String ?: "INFO",
                dateShamsi = map["dateShamsi"] as? String ?: "",
                dateEpoch = (map["dateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                isRead = map["isRead"] as? Boolean ?: false,
                targetScreen = map["targetScreen"] as? String ?: ""
            )
        }
    }
}
