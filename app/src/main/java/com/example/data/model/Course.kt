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
    val createdAt: Long = System.currentTimeMillis()
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
        "createdAt" to createdAt
    )

    companion object {
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
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
