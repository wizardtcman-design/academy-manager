package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "installments")
data class Installment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val enrollmentId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val courseId: String = "",
    val courseTitle: String = "",
    val installmentNumber: Int = 1,
    val totalInstallments: Int = 3,
    val amount: Long = 0L,
    val dueDateShamsi: String = "",
    val dueDateEpoch: Long = 0L,
    val paidAmount: Long = 0L,
    val remainingAmount: Long = 0L,
    val fullPaidDateShamsi: String = "",
    val fullPaidDateEpoch: Long = 0L,
    val status: String = "PENDING", // PENDING (پرداخت نشده), NEAR_DUE (نزدیک سررسید), DUE_TODAY (سررسید امروز), PARTIAL_PAID (پرداخت ناقص), PAID (پرداخت شده), OVERDUE (دیرکرد), CANCELLED (لغو شده)
    val notes: String = "",
    /** Manager-chosen method: "" | ONLINE | TRANSFER | POS | CASH | BANK_DEPOSIT (Stage 4). */
    val paymentMethod: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isPaid: Boolean
        get() = status == "PAID" || (amount > 0 && paidAmount >= amount)

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "enrollmentId" to enrollmentId,
        "studentId" to studentId,
        "studentName" to studentName,
        "courseId" to courseId,
        "courseTitle" to courseTitle,
        "installmentNumber" to installmentNumber,
        "totalInstallments" to totalInstallments,
        "amount" to amount,
        "dueDateShamsi" to dueDateShamsi,
        "dueDateEpoch" to dueDateEpoch,
        "paidAmount" to paidAmount,
        "remainingAmount" to remainingAmount,
        "fullPaidDateShamsi" to fullPaidDateShamsi,
        "fullPaidDateEpoch" to fullPaidDateEpoch,
        "status" to status,
        "notes" to notes,
        "paymentMethod" to paymentMethod,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Installment {
            val amount = (map["amount"] as? Number)?.toLong() ?: 0L
            val paidAmount = (map["paidAmount"] as? Number)?.toLong() ?: 0L
            val remaining = (map["remainingAmount"] as? Number)?.toLong() ?: (amount - paidAmount)
            return Installment(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                enrollmentId = map["enrollmentId"] as? String ?: "",
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                courseId = map["courseId"] as? String ?: "",
                courseTitle = map["courseTitle"] as? String ?: "",
                installmentNumber = (map["installmentNumber"] as? Number)?.toInt() ?: 1,
                totalInstallments = (map["totalInstallments"] as? Number)?.toInt() ?: 3,
                amount = amount,
                dueDateShamsi = map["dueDateShamsi"] as? String ?: "",
                dueDateEpoch = (map["dueDateEpoch"] as? Number)?.toLong() ?: 0L,
                paidAmount = paidAmount,
                remainingAmount = remaining,
                fullPaidDateShamsi = map["fullPaidDateShamsi"] as? String ?: "",
                fullPaidDateEpoch = (map["fullPaidDateEpoch"] as? Number)?.toLong() ?: 0L,
                status = map["status"] as? String ?: "PENDING",
                notes = map["notes"] as? String ?: "",
                paymentMethod = map["paymentMethod"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
