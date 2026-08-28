package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "refunds")
data class Refund(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val refundCode: String = "", // e.g. REF-1405-000001
    val studentId: String = "",
    val studentName: String = "",
    val enrollmentId: String = "",
    val courseTitle: String = "",
    val amount: Long = 0L,
    val dateShamsi: String = "",
    val dateEpoch: Long = System.currentTimeMillis(),
    val paymentMethod: String = "TRANSFER", // CASH, TRANSFER, BANK_DEPOSIT
    val sourceAccountId: String = "",
    val sourceAccountTitle: String = "",
    val trackingNumber: String = "",
    val reason: String = "",
    val recordedBy: String = "مدیر سیستم",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "refundCode" to refundCode,
        "studentId" to studentId,
        "studentName" to studentName,
        "enrollmentId" to enrollmentId,
        "courseTitle" to courseTitle,
        "amount" to amount,
        "dateShamsi" to dateShamsi,
        "dateEpoch" to dateEpoch,
        "paymentMethod" to paymentMethod,
        "sourceAccountId" to sourceAccountId,
        "sourceAccountTitle" to sourceAccountTitle,
        "trackingNumber" to trackingNumber,
        "reason" to reason,
        "recordedBy" to recordedBy,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Refund {
            return Refund(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                refundCode = map["refundCode"] as? String ?: "",
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                enrollmentId = map["enrollmentId"] as? String ?: "",
                courseTitle = map["courseTitle"] as? String ?: "",
                amount = (map["amount"] as? Number)?.toLong() ?: 0L,
                dateShamsi = map["dateShamsi"] as? String ?: "",
                dateEpoch = (map["dateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                paymentMethod = map["paymentMethod"] as? String ?: "TRANSFER",
                sourceAccountId = map["sourceAccountId"] as? String ?: "",
                sourceAccountTitle = map["sourceAccountTitle"] as? String ?: "",
                trackingNumber = map["trackingNumber"] as? String ?: "",
                reason = map["reason"] as? String ?: "",
                recordedBy = map["recordedBy"] as? String ?: "مدیر سیستم",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
