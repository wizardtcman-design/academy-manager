package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "enrollments")
data class Enrollment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val studentId: String = "",
    val studentName: String = "",
    val courseId: String = "",
    val courseTitle: String = "",
    val registrationDateShamsi: String = "",
    val registrationDateEpoch: Long = System.currentTimeMillis(),
    val startDateShamsi: String = "",
    val endDateShamsi: String = "",
    val totalFee: Long = 0L,
    val discount: Long = 0L,
    val finalFee: Long = 0L,
    val installmentCount: Int = 3,
    val firstInstallmentPaidOnDate: Boolean = false, // If first installment is marked as paid on registration
    val firstInstallmentDateShamsi: String = "",
    val firstInstallmentDateEpoch: Long = System.currentTimeMillis(),
    val status: String = "ACTIVE", // ACTIVE, COMPLETED, CANCELLED
    val notes: String = "",
    val contractNumber: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // Certificate tracking (مدرک): PENDING / ISSUED / DELIVERED / NOT_REQUIRED.
    val certificateStatus: String = CERTIFICATE_PENDING,
    // Explicit manager capacity-override record (true = enrolled over capacity with consent).
    val enrolledViaOverride: Boolean = false,
    // LWW timestamp for cloud sync of mutable fields (certificate status, ...).
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "studentId" to studentId,
        "studentName" to studentName,
        "courseId" to courseId,
        "courseTitle" to courseTitle,
        "registrationDateShamsi" to registrationDateShamsi,
        "registrationDateEpoch" to registrationDateEpoch,
        "startDateShamsi" to startDateShamsi,
        "endDateShamsi" to endDateShamsi,
        "totalFee" to totalFee,
        "discount" to discount,
        "finalFee" to finalFee,
        "installmentCount" to installmentCount,
        "firstInstallmentPaidOnDate" to firstInstallmentPaidOnDate,
        "firstInstallmentDateShamsi" to firstInstallmentDateShamsi,
        "firstInstallmentDateEpoch" to firstInstallmentDateEpoch,
        "status" to status,
        "notes" to notes,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt,
        "certificateStatus" to certificateStatus,
        "enrolledViaOverride" to enrolledViaOverride,
        "updatedAt" to updatedAt
    )

    companion object {
        // Certificate status values
        const val CERTIFICATE_PENDING = "PENDING"
        const val CERTIFICATE_ISSUED = "ISSUED"
        const val CERTIFICATE_DELIVERED = "DELIVERED"
        const val CERTIFICATE_NOT_REQUIRED = "NOT_REQUIRED"

        fun fromMap(map: Map<String, Any?>): Enrollment {
            return Enrollment(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                studentId = map["studentId"] as? String ?: "",
                studentName = map["studentName"] as? String ?: "",
                courseId = map["courseId"] as? String ?: "",
                courseTitle = map["courseTitle"] as? String ?: "",
                registrationDateShamsi = map["registrationDateShamsi"] as? String ?: "",
                registrationDateEpoch = (map["registrationDateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                startDateShamsi = map["startDateShamsi"] as? String ?: "",
                endDateShamsi = map["endDateShamsi"] as? String ?: "",
                totalFee = (map["totalFee"] as? Number)?.toLong() ?: 0L,
                discount = (map["discount"] as? Number)?.toLong() ?: 0L,
                finalFee = (map["finalFee"] as? Number)?.toLong() ?: 0L,
                installmentCount = (map["installmentCount"] as? Number)?.toInt() ?: 3,
                firstInstallmentPaidOnDate = map["firstInstallmentPaidOnDate"] as? Boolean ?: false,
                firstInstallmentDateShamsi = map["firstInstallmentDateShamsi"] as? String ?: "",
                firstInstallmentDateEpoch = (map["firstInstallmentDateEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                status = map["status"] as? String ?: "ACTIVE",
                notes = map["notes"] as? String ?: "",
                contractNumber = map["contractNumber"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                certificateStatus = map["certificateStatus"] as? String ?: CERTIFICATE_PENDING,
                enrolledViaOverride = map["enrolledViaOverride"] as? Boolean ?: false,
                updatedAt = (map["updatedAt"] as? Number)?.toLong()
                    ?: ((map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis())
            )
        }
    }
}
