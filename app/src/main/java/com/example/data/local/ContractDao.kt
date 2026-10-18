package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Contract
import kotlinx.coroutines.flow.Flow

@Dao
interface ContractDao {
    @Query("SELECT * FROM contracts ORDER BY createdAt DESC")
    fun getAllContracts(): Flow<List<Contract>>

    @Query("SELECT * FROM contracts WHERE enrollmentId = :enrollmentId LIMIT 1")
    suspend fun getByEnrollment(enrollmentId: String): Contract?

    @Query("SELECT * FROM contracts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Contract?

    @Query("SELECT * FROM contracts WHERE studentId = :studentId ORDER BY createdAt DESC")
    fun getForStudent(studentId: String): Flow<List<Contract>>

    @Query("SELECT * FROM contracts WHERE status IN ('ISSUED','OPENED') AND remoteId != ''")
    suspend fun getPendingRemote(): List<Contract>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContract(contract: Contract): Long

    @Update
    suspend fun updateContract(contract: Contract)

    @Query("UPDATE contracts SET status = :status, openedAtEpoch = :openedAt, updatedAt = :now WHERE id = :id AND openedAtEpoch = 0 AND status IN ('ISSUED')")
    suspend fun markOpened(id: String, status: String, openedAt: Long, now: Long)

    @Query("SELECT * FROM contracts")
    suspend fun getAllContractsList(): List<Contract>

    @Query("DELETE FROM contracts")
    suspend fun clearAll()
}
