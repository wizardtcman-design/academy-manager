package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "audit_logs")
data class AuditLog(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val entityType: String = "", // STUDENT, COURSE, ENROLLMENT, INSTALLMENT, PAYMENT, BANK_ACCOUNT, SETTINGS
    val entityId: String = "",
    val actionType: String = "", // CREATE, UPDATE, DELETE, REVERSE, CORRECTION
    val previousValue: String = "",
    val newValue: String = "",
    val reason: String = "",
    val changedBy: String = "مدیر آموزشگاه",
    val timestampShamsi: String = "",
    val timestampEpoch: Long = System.currentTimeMillis(),
    val description: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "entityType" to entityType,
        "entityId" to entityId,
        "actionType" to actionType,
        "previousValue" to previousValue,
        "newValue" to newValue,
        "reason" to reason,
        "changedBy" to changedBy,
        "timestampShamsi" to timestampShamsi,
        "timestampEpoch" to timestampEpoch,
        "description" to description
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): AuditLog {
            return AuditLog(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                entityType = map["entityType"] as? String ?: "",
                entityId = map["entityId"] as? String ?: "",
                actionType = map["actionType"] as? String ?: "",
                previousValue = map["previousValue"] as? String ?: "",
                newValue = map["newValue"] as? String ?: "",
                reason = map["reason"] as? String ?: "",
                changedBy = map["changedBy"] as? String ?: "مدیر آموزشگاه",
                timestampShamsi = map["timestampShamsi"] as? String ?: "",
                timestampEpoch = (map["timestampEpoch"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                description = map["description"] as? String ?: ""
            )
        }
    }
}
