package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Installment
import kotlinx.coroutines.flow.Flow

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installments ORDER BY dueDateEpoch ASC")
    fun getAllInstallments(): Flow<List<Installment>>

    @Query("SELECT * FROM installments WHERE studentId = :studentId ORDER BY installmentNumber ASC, dueDateEpoch ASC")
    fun getInstallmentsForStudent(studentId: String): Flow<List<Installment>>

    @Query("SELECT * FROM installments WHERE enrollmentId = :enrollmentId ORDER BY installmentNumber ASC")
    fun getInstallmentsForEnrollment(enrollmentId: String): Flow<List<Installment>>

    @Query("SELECT * FROM installments WHERE enrollmentId = :enrollmentId ORDER BY installmentNumber")
    suspend fun getInstallmentsForEnrollmentDirect(enrollmentId: String): List<Installment>

    @Query("SELECT * FROM installments WHERE studentId = :studentId ORDER BY dueDateEpoch")
    suspend fun getInstallmentsForStudentDirect(studentId: String): List<Installment>

    @Query("SELECT * FROM installments WHERE courseId = :courseId ORDER BY installmentNumber ASC")
    suspend fun getInstallmentsForCourseDirect(courseId: String): List<Installment>

    @Query("SELECT * FROM installments WHERE id = :id LIMIT 1")
    suspend fun getInstallmentById(id: String): Installment?

    @Query("SELECT COUNT(*) FROM installments WHERE status != 'PAID' AND dueDateEpoch <= :endOfDayEpoch AND dueDateEpoch >= :startOfDayEpoch")
    fun getDueTodayInstallmentsCount(startOfDayEpoch: Long, endOfDayEpoch: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM installments WHERE status != 'PAID' AND dueDateEpoch <= :endOfDayEpoch")
    fun getDueInstallmentsCount(endOfDayEpoch: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM installments WHERE status = 'OVERDUE' OR (status != 'PAID' AND dueDateEpoch < :startOfDayEpoch)")
    fun getOverdueInstallmentsCount(startOfDayEpoch: Long): Flow<Int>

    // Bug fix: CANCELLED installments are no longer counted as debt (the
    // dashboard is the only consumer, and it never pre-filters them itself).
    @Query("SELECT SUM(amount - paidAmount) FROM installments WHERE status != 'PAID' AND status != 'CANCELLED'")
    fun getTotalPendingDebt(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallment(installment: Installment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallments(installments: List<Installment>)

    @Update
    suspend fun updateInstallment(installment: Installment)

    @Delete
    suspend fun deleteInstallment(installment: Installment)

    @Query("DELETE FROM installments WHERE enrollmentId = :enrollmentId")
    suspend fun deleteInstallmentsByEnrollment(enrollmentId: String)

    @Query("DELETE FROM installments WHERE isSampleData = 1")
    suspend fun deleteSampleInstallments()

    // --- Test-data cleanup (temporary admin tool) ---
    @Query("DELETE FROM installments WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: String)

    @Query("SELECT COUNT(*) FROM installments WHERE studentId = :studentId")
    suspend fun countForStudent(studentId: String): Int
}
