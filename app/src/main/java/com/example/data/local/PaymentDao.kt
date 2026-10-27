package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Payment
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY paymentDateEpoch DESC, createdAt DESC")
    fun getAllPayments(): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE isReversed = 0 ORDER BY paymentDateEpoch DESC, createdAt DESC")
    fun getValidPayments(): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE studentId = :studentId ORDER BY paymentDateEpoch DESC")
    fun getPaymentsForStudent(studentId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE installmentId = :installmentId AND isReversed = 0 ORDER BY paymentDateEpoch ASC")
    fun getPaymentsForInstallment(installmentId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE destinationAccountId = :accountId AND isReversed = 0 ORDER BY paymentDateEpoch DESC")
    fun getPaymentsForAccount(accountId: String): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE id = :id LIMIT 1")
    suspend fun getPaymentById(id: String): Payment?

    @Query("SELECT SUM(amount) FROM payments WHERE isReversed = 0 AND paymentDateEpoch >= :startOfDayEpoch AND paymentDateEpoch <= :endOfDayEpoch")
    fun getTodayReceiptsSum(startOfDayEpoch: Long, endOfDayEpoch: Long): Flow<Long?>

    @Query("SELECT SUM(amount) FROM payments WHERE isReversed = 0 AND paymentDateEpoch >= :startEpoch AND paymentDateEpoch <= :endEpoch")
    fun getReceiptsSumInRange(startEpoch: Long, endEpoch: Long): Flow<Long?>

    @Query("SELECT SUM(amount) FROM payments WHERE isReversed = 0")
    fun getTotalReceiptsSum(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: Payment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayments(payments: List<Payment>)

    @Update
    suspend fun updatePayment(payment: Payment)

    @Delete
    suspend fun deletePayment(payment: Payment)

    @Query("DELETE FROM payments WHERE isSampleData = 1")
    suspend fun deleteSamplePayments()

    // --- Test-data cleanup (temporary admin tool) ---
    @Query("DELETE FROM payments WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: String)

    @Query("SELECT COUNT(*) FROM payments WHERE studentId = :studentId")
    suspend fun countForStudent(studentId: String): Int
}
