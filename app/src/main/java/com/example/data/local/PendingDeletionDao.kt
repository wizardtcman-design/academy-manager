package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.PendingDeletion
import com.example.data.model.PendingStudentDeletion
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDeletionDao {
    @Query("SELECT * FROM pending_deletions ORDER BY createdAt ASC")
    fun getAll(): Flow<List<PendingDeletion>>

    @Query("SELECT * FROM pending_deletions WHERE entityType = :type ORDER BY createdAt ASC")
    fun getByType(type: String): Flow<List<PendingDeletion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deletion: PendingDeletion)

    @Query("DELETE FROM pending_deletions WHERE entityType = :type AND entityId = :id")
    suspend fun clear(type: String, id: String)

    @Query("SELECT * FROM pending_student_deletions ORDER BY createdAt ASC")
    fun getPendingDeletions(): Flow<List<PendingStudentDeletion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudentDeletion(deletion: PendingStudentDeletion)

    @Query("DELETE FROM pending_student_deletions WHERE studentId = :id")
    suspend fun clearStudentDeletion(id: String)
}
