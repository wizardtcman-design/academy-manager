package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "students")
data class Student(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val studentCode: String = "", // e.g. ST-1403-0001
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val nationalCode: String = "",
    val address: String = "",
    val notes: String = "",
    val registrationDateShamsi: String = "",
    val registrationDateEpoch: Long = System.currentTimeMillis(),
    val status: String = "ACTIVE", // ACTIVE (فعال), FINISHED (پایان دوره), SETTLED (تسویه شده), DEBTOR (بدهکار), WITHDRAWN (انصراف داده)
    val telegramEnabled: Boolean = false,
    val telegramChatId: String = "",
    val telegramUsername: String = "",
    val telegramLinkedAt: Long = 0L,
    val telegramLinkToken: String = "",
    val telegramTokenExpiryEpoch: Long = 0L,
    val isActive: Boolean = true,
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val fullName: String
        get() = "$firstName $lastName".trim()

    val isTelegramConnected: Boolean
        get() = telegramEnabled && telegramChatId.isNotBlank()

    // Firestore map conversion
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "studentCode" to studentCode,
        "firstName" to firstName,
        "lastName" to lastName,
        "phone" to phone,
        "nationalCode" to nationalCode,
        "address" to address,
        "notes" to notes,
        "registrationDateShamsi" to registrationDateShamsi,
        "registrationDateEpoch" to registrationDateEpoch,
        "status" to status,
        "telegramEnabled" to telegramEnabled,
        "telegramChatId" to telegramChatId,
        "telegramUsername" to telegramUsername,
        "telegramLinkedAt" to telegramLinkedAt,
        "telegramLinkToken" to telegramLinkToken,
        "telegramTokenExpiryEpoch" to telegramTokenExpiryEpoch,
        "isActive" to isActive,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Student {
            return Student(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                studentCode = map["studentCode"] as? String ?: "",
                firstName = map["firstName"] as? String ?: "",
                lastName = map["lastName"] as? String ?: "",
                phone = map["phone"] as? String ?: "",
                nationalCode = map["nationalCode"] as? String ?: "",
                address = map["address"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                registrationDateShamsi = map["registrationDateShamsi"] as? String ?: "",
                registrationDateEpoch = (map["registrationDateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = map["status"] as? String ?: "ACTIVE",
                telegramEnabled = map["telegramEnabled"] as? Boolean ?: false,
                telegramChatId = map["telegramChatId"] as? String ?: "",
                telegramUsername = map["telegramUsername"] as? String ?: "",
                telegramLinkedAt = (map["telegramLinkedAt"] as? Number)?.toLong() ?: 0L,
                telegramLinkToken = map["telegramLinkToken"] as? String ?: "",
                telegramTokenExpiryEpoch = (map["telegramTokenExpiryEpoch"] as? Number)?.toLong() ?: 0L,
                isActive = map["isActive"] as? Boolean ?: true,
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
