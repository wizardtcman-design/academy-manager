package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Historical student of an [ArchiveCourse]. Money fields are nullable ON PURPOSE:
 * null means نامشخص (unknown). An unknown amount is NEVER assumed zero and NEVER
 * counted as income or debt — the archive is isolated from the live financial
 * systems (installments/debt/reminders).
 */
@Entity(
    tableName = "archive_students",
    indices = [Index("archiveCourseId")]
)
data class ArchiveStudent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val archiveCourseId: String = "",
    val fullName: String = "",
    val mobile: String = "",
    // null = نامشخص (never counted as tuition)
    val tuitionAmount: Long? = null,
    // null = نامشخص (never counted as income)
    val receivedAmount: Long? = null,
    // Free-text payment dates, e.g. "۱۴۰۲/۰۷ و ۱۴۰۲/۱۰".
    val paymentDatesNote: String = "",
    val notes: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Derived single source of truth: true when any money amount is unknown.
     * Stored nowhere — a stored flag could drift from the real amounts.
     */
    val hasUnknownInfo: Boolean
        get() = tuitionAmount == null || receivedAmount == null
    fun toMap(): Map<String, Any> {
        // Nullable Longs are only put into the map when present; an absent key
        // decodes back to null (Firestore returns nothing for null numbers).
        val map = mutableMapOf<String, Any>(
            "id" to id,
            "archiveCourseId" to archiveCourseId,
            "fullName" to fullName,
            "mobile" to mobile,
            "paymentDatesNote" to paymentDatesNote,
            "notes" to notes,
            "isSampleData" to isSampleData,
            "createdAt" to createdAt
        )
        tuitionAmount?.let { map["tuitionAmount"] = it }
        receivedAmount?.let { map["receivedAmount"] = it }
        return map
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): ArchiveStudent {
            return ArchiveStudent(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                archiveCourseId = map["archiveCourseId"] as? String ?: "",
                fullName = map["fullName"] as? String ?: "",
                mobile = map["mobile"] as? String ?: "",
                tuitionAmount = (map["tuitionAmount"] as? Number)?.toLong(),
                receivedAmount = (map["receivedAmount"] as? Number)?.toLong(),
                paymentDatesNote = map["paymentDatesNote"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
