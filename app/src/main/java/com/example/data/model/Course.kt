package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val durationMonths: Int = 3,
    val tuitionFee: Long = 0L,
    val installmentCount: Int = 3,
    val description: String = "",
    val isActive: Boolean = true,
    val isSampleData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    // Academic year (سال آموزشی), e.g. "1404". Display-only.
    val academicYear: String = "",
    // Exact start of the course. epoch == 0 means "not registered yet".
    val startShamsi: String = "",
    val startTime: String = "",
    val startEpoch: Long = 0L,
    // Optional end of the course (0 = no end registered).
    val endShamsi: String = "",
    val endTime: String = "",
    val endEpoch: Long = 0L,
    // Capacity: 0 = بدون محدودیت (unlimited), >0 enforced by the repository.
    val capacity: Int = 0,
    // THREE SEPARATE status groups — never mixed, never derived from each other.
    // enrollmentStatus: OPEN / CAPACITY_FULL / CLOSED (manual manager decision).
    val enrollmentStatus: String = ENROLLMENT_OPEN,
    // holdingStatus: ONGOING / ENDED (course finished being held).
    val holdingStatus: String = HOLDING_ONGOING,
    // finalStatus: INCOMPLETE / READY / FINALIZED (explicit finalize workflow only).
    val finalStatus: String = FINAL_INCOMPLETE,
    // Timestamp of the manual/holding end (0 = not ended).
    val endedAtShamsi: String = "",
    val endedAtEpoch: Long = 0L,
    // LWW timestamp for cloud sync of the mutable statuses.
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "durationMonths" to durationMonths,
        "tuitionFee" to tuitionFee,
        "installmentCount" to installmentCount,
        "description" to description,
        "isActive" to isActive,
        "isSampleData" to isSampleData,
        "createdAt" to createdAt,
        "academicYear" to academicYear,
        "startShamsi" to startShamsi,
        "startTime" to startTime,
        "startEpoch" to startEpoch,
        "endShamsi" to endShamsi,
        "endTime" to endTime,
        "endEpoch" to endEpoch,
        "capacity" to capacity,
        "enrollmentStatus" to enrollmentStatus,
        "holdingStatus" to holdingStatus,
        "finalStatus" to finalStatus,
        "endedAtShamsi" to endedAtShamsi,
        "endedAtEpoch" to endedAtEpoch,
        "updatedAt" to updatedAt
    )

    companion object {
        // enrollmentStatus values
        const val ENROLLMENT_OPEN = "OPEN"
        const val ENROLLMENT_CAPACITY_FULL = "CAPACITY_FULL"
        const val ENROLLMENT_CLOSED = "CLOSED"
        // holdingStatus values
        const val HOLDING_ONGOING = "ONGOING"
        const val HOLDING_ENDED = "ENDED"
        // finalStatus values
        const val FINAL_INCOMPLETE = "INCOMPLETE"
        const val FINAL_READY = "READY"
        const val FINAL_FINALIZED = "FINALIZED"

        fun fromMap(map: Map<String, Any?>): Course {
            return Course(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                title = map["title"] as? String ?: "",
                durationMonths = (map["durationMonths"] as? Number)?.toInt() ?: 3,
                tuitionFee = (map["tuitionFee"] as? Number)?.toLong() ?: 0L,
                installmentCount = (map["installmentCount"] as? Number)?.toInt() ?: 3,
                description = map["description"] as? String ?: "",
                isActive = map["isActive"] as? Boolean ?: true,
                isSampleData = map["isSampleData"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                academicYear = map["academicYear"] as? String ?: "",
                startShamsi = map["startShamsi"] as? String ?: "",
                startTime = map["startTime"] as? String ?: "",
                startEpoch = (map["startEpoch"] as? Number)?.toLong() ?: 0L,
                endShamsi = map["endShamsi"] as? String ?: "",
                endTime = map["endTime"] as? String ?: "",
                endEpoch = (map["endEpoch"] as? Number)?.toLong() ?: 0L,
                capacity = (map["capacity"] as? Number)?.toInt() ?: 0,
                enrollmentStatus = map["enrollmentStatus"] as? String ?: ENROLLMENT_OPEN,
                holdingStatus = map["holdingStatus"] as? String ?: HOLDING_ONGOING,
                finalStatus = map["finalStatus"] as? String ?: FINAL_INCOMPLETE,
                endedAtShamsi = map["endedAtShamsi"] as? String ?: "",
                endedAtEpoch = (map["endedAtEpoch"] as? Number)?.toLong() ?: 0L,
                updatedAt = (map["updatedAt"] as? Number)?.toLong()
                    ?: ((map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis())
            )
        }
    }
}
