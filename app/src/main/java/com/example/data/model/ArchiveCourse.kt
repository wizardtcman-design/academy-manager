package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Historical course that predates (or lives outside) the live course system.
 * Archive data is ISOLATED from the live installments/debt/reminder systems:
 * it is never merged into the live ledger and never counted by it.
 */
@Entity(tableName = "archive_courses")
data class ArchiveCourse(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    // سال آموزشی, e.g. "1402". May be unknown → "".
    val academicYear: String = "",
    // Optional, may be unknown → "".
    val startShamsi: String = "",
    val notes: String = "",
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "academicYear" to academicYear,
        "startShamsi" to startShamsi,
        "notes" to notes,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ArchiveCourse {
            return ArchiveCourse(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                title = map["title"] as? String ?: "",
                academicYear = map["academicYear"] as? String ?: "",
                startShamsi = map["startShamsi"] as? String ?: "",
                notes = map["notes"] as? String ?: "",
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
