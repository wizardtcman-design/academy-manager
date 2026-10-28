package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.MessageLog
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageLogDao {
    @Query("SELECT * FROM message_logs ORDER BY sentAtEpoch DESC")
    fun getAllLogs(): Flow<List<MessageLog>>

    @Query("SELECT * FROM message_logs WHERE studentId = :studentId ORDER BY sentAtEpoch DESC")
    fun getLogsForStudent(studentId: String): Flow<List<MessageLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: MessageLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<MessageLog>)

    @Delete
    suspend fun deleteLog(log: MessageLog)

    @Query("DELETE FROM message_logs")
    suspend fun clearAll()

}
