package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val expenseCode: String = "", // e.g. EXP-1405-000001
    val title: String = "",
    val amount: Long = 0L,
    val dateShamsi: String = "",
    val dateEpoch: Long = System.currentTimeMillis(),
    val category: String = "سایر", // اجاره, برق, آب, گاز, اینترنت, حقوق, تجهیزات, تعمیرات, تبلیغات, لوازم مصرفی, نرم‌افزار, حمل‌ونقل, سایر
    val paymentMethod: String = "TRANSFER", // CASH (صندوق نقدی), POS (کارتخوان), TRANSFER (کارت به کارت), BANK_DEPOSIT (حساب بانکی), OTHER (سایر)
    val sourceAccountId: String = "",
    val sourceAccountTitle: String = "",
    val trackingNumber: String = "",
    val notes: String = "",
    val receiptImage: String = "",
    val recordedBy: String = "مدیر سیستم",
    val isDeleted: Boolean = false,
    val deletedAt: Long = 0L,
    val deleteReason: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "expenseCode" to expenseCode,
        "title" to title,
        "amount" to amount,
        "dateShamsi" to dateShamsi,
        "dateEpoch" to dateEpoch,
        "category" to category,
        "paymentMethod" to paymentMethod,
        "sourceAccountId" to sourceAccountId,
        "sourceAccountTitle" to sourceAccountTitle,
        "trackingNumber" to trackingNumber,
        "notes" to notes,
        "receiptImage" to receiptImage,
        "recordedBy" to recordedBy,
        "isDeleted" to isDeleted,
        "deletedAt" to deletedAt,
        "deleteReason" to deleteReason,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            "اجاره",
            "برق",
            "آب",
            "گاز",
            "اینترنت",
            "حقوق",
            "تجهیزات",
            "تعمیرات",
            "تبلیغات",
            "لوازم مصرفی",
            "نرم‌افزار",
            "حمل‌ونقل",
            "سایر"
        )

        fun fromMap(map: Map<String, Any?>): Expense {
            return Expense(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                expenseCode = map["expenseCode"] as? String ?: "",
                title = map["title"] as? String ?: "",
                amount = (map["amount"] as? Number)?.toLong() ?: 0L,
                dateShamsi = map["dateShamsi"] as? String ?: "",
                dateEpoch = (map["dateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                category = map["category"] as? String ?: "سایر",
                paymentMethod = map["paymentMethod"] as? String ?: "TRANSFER",
                sourceAccountId = map["sourceAccountId"] as? String ?: "",
                sourceAccountTitle = map["sourceAccountTitle"] as? String ?: "",
                trackingNumber = map["trackingNumber"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                receiptImage = map["receiptImage"] as? String ?: "",
                recordedBy = map["recordedBy"] as? String ?: "مدیر سیستم",
                isDeleted = map["isDeleted"] as? Boolean ?: false,
                deletedAt = (map["deletedAt"] as? Number)?.toLong() ?: 0L,
                deleteReason = map["deleteReason"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
