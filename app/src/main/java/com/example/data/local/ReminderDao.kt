package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY scheduledTimeEpoch ASC")
    fun getAllReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY scheduledTimeEpoch ASC")
    fun getPendingReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE installmentId = :installmentId ORDER BY scheduledTimeEpoch ASC")
    fun getRemindersForInstallment(installmentId: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: String): Reminder?

    @Query("SELECT * FROM reminders WHERE installmentId = :installmentId AND triggerDaysOffset = :offset LIMIT 1")
    suspend fun getReminderForInstallmentAndOffset(installmentId: String, offset: Int): Reminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: Reminder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(reminders: List<Reminder>)

    @Update
    suspend fun updateReminder(reminder: Reminder)

    @Query("UPDATE reminders SET status = :status, errorMessage = :error, sendTimeShamsi = :sendTime, isCompleted = :isCompleted WHERE id = :id")
    suspend fun updateReminderStatus(id: String, status: String, sendTime: String, error: String, isCompleted: Boolean)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: String)

    @Query("DELETE FROM reminders WHERE installmentId = :installmentId")
    suspend fun deleteRemindersForInstallment(installmentId: String)

    /**
     * Stage 5: after a REAL payment, every still-SCHEDULED reminder of the
     * installment is cancelled (kept for history, never fired again). Status
     * is preserved instead of hard-deleting so the manager keeps an audit
     * trail and pushAll stops re-publishing them to the worker.
     */
    @Query("UPDATE reminders SET status = 'CANCELLED', errorMessage = :error, isCompleted = 1 WHERE installmentId = :installmentId AND status = 'SCHEDULED'")
    suspend fun cancelScheduledForInstallment(installmentId: String, error: String): Int

    // --- Test-data cleanup (temporary admin tool) ---
    @Query("DELETE FROM reminders WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: String)

    @Query("SELECT COUNT(*) FROM reminders WHERE studentId = :studentId")
    suspend fun countForStudent(studentId: String): Int
}
