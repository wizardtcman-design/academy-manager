package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "expense_categories")
data class ExpenseCategory(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val iconName: String = "Category",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "iconName" to iconName,
        "isDefault" to isDefault,
        "createdAt" to createdAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ExpenseCategory {
            return ExpenseCategory(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                name = map["name"] as? String ?: "",
                iconName = map["iconName"] as? String ?: "Category",
                isDefault = map["isDefault"] as? Boolean ?: false,
                createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            )
        }
    }
}
