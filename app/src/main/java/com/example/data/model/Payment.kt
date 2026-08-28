package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "payments")
data class Payment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val transactionId: String = "", // e.g. TRX-1403-0001
    val paymentCode: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val enrollmentId: String = "",
    val installmentId: String = "",
    val installmentNumber: Int = 1,
    val amount: Long = 0L,
    val paymentDateShamsi: String = "",
    val paymentDateEpoch: Long = System.currentTimeMillis(),
    val paymentTime: String = "12:00",
    val paymentMethod: String = "TRANSFER", // TRANSFER (کارت‌به‌کارت), POS (کارتخوان), CASH (نقدی), BANK_DEPOSIT (واریز بانکی), OTHER (سایر)
    val destinationAccountId: String = "",
    val destinationAccountTitle: String = "",
    val trackingNumber: String = "",
    val receiptImage: String = "",
    val notes: String = "",
    val recordedBy: String = "مدیر سیستم",
    val isReversed: Boolean = false,
    val reversalReason: String = "",
    val reversedAtEpoch: Long = 0L,
    val reversedBy: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "transactionId" to transactionId,
        "paymentCode" to paymentCode,
        "studentId" to studentId,
        "studentName" to studentName,
        "enrollmentId" to enrollmentId,
        "installmentId" to installmentId,
        "installmentNumber" to installmentNumber,
        "amount" to amount,
        "paymentDateShamsi" to paymentDateShamsi,
        "paymentDateEpoch" to paymentDateEpoch,
        "paymentTime" to paymentTime,
        "paymentMethod" to paymentMethod,
        "destinationAccountId" to destinationAccountId,
        "destinationAccountTitle" to destinationAccountTitle,
        "trackingNumber" to trackingNumber,
        "receiptImage" to receiptImage,
        "notes" to notes,
        "recordedBy" to recordedBy,
        "isReversed" to isReversed,
        "reversalReason" to reversalReason,
        "reversedAtEpoch" to reversedAtEpoch,
        "reversedBy" to reversedBy,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Payment {
            return Payment(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                transactionId = map["transactionId"] as? String ?: "",
                paymentCode = map["paymentCode"] as? String ?: "",
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                enrollmentId = map["enrollmentId"] as? String ?: "",
                installmentId = map["installmentId"] as? String ?: "",
                installmentNumber = (map["installmentNumber"] as? Number)?.toInt() ?: 1,
                amount = (map["amount"] as? Number)?.toLong() ?: 0L,
                paymentDateShamsi = map["paymentDateShamsi"] as? String ?: "",
                paymentDateEpoch = (map["paymentDateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                paymentTime = map["paymentTime"] as? String ?: "12:00",
                paymentMethod = map["paymentMethod"] as? String ?: "TRANSFER",
                destinationAccountId = map["destinationAccountId"] as? String ?: "",
                destinationAccountTitle = map["destinationAccountTitle"] as? String ?: "",
                trackingNumber = map["trackingNumber"] as? String ?: "",
                receiptImage = map["receiptImage"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                recordedBy = map["recordedBy"] as? String ?: "مدیر سیستم",
                isReversed = map["isReversed"] as? Boolean ?: false,
                reversalReason = map["reversalReason"] as? String ?: "",
                reversedAtEpoch = (map["reversedAtEpoch"] as? Number)?.toLong() ?: 0L,
                reversedBy = map["reversedBy"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
