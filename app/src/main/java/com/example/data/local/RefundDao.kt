package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Refund
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundDao {
    @Query("SELECT * FROM refunds ORDER BY dateEpoch DESC, createdAt DESC")
    fun getAllRefunds(): Flow<List<Refund>>

    @Query("SELECT * FROM refunds WHERE id = :id LIMIT 1")
    suspend fun getRefundById(id: String): Refund?

    @Query("SELECT * FROM refunds WHERE studentId = :studentId ORDER BY dateEpoch DESC")
    fun getRefundsForStudent(studentId: String): Flow<List<Refund>>

    @Query("SELECT * FROM refunds WHERE enrollmentId = :enrollmentId ORDER BY dateEpoch DESC")
    fun getRefundsForEnrollment(enrollmentId: String): Flow<List<Refund>>

    @Query("SELECT SUM(amount) FROM refunds WHERE dateEpoch >= :startEpoch AND dateEpoch <= :endEpoch")
    fun getRefundsSumInRange(startEpoch: Long, endEpoch: Long): Flow<Long?>

    @Query("SELECT SUM(amount) FROM refunds")
    fun getTotalRefundsSum(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefund(refund: Refund)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefunds(refunds: List<Refund>)

    @Update
    suspend fun updateRefund(refund: Refund)

    @Delete
    suspend fun deleteRefund(refund: Refund)

    @Query("DELETE FROM refunds WHERE isSampleData = 1")
    suspend fun deleteSampleRefunds()
}
