package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tombstone for a record deleted locally while the cloud could not be reached.
 * It is pushed on the next successful sync and then cleared, so a deleted
 * student does not come back after a pull.
 */
@Entity(
    tableName = "pending_deletions",
    indices = [Index(value = ["entityType", "entityId"], unique = true)]
)
data class PendingDeletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Thin row used when only the student id is needed. */
@Entity(tableName = "pending_student_deletions")
data class PendingStudentDeletion(
    @PrimaryKey val studentId: String,
    val createdAt: Long = System.currentTimeMillis()
)
