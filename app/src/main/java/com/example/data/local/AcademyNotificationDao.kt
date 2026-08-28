package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AcademyNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface AcademyNotificationDao {
    @Query("SELECT * FROM academy_notifications ORDER BY dateEpoch DESC")
    fun getAllNotifications(): Flow<List<AcademyNotification>>

    @Query("SELECT COUNT(*) FROM academy_notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: AcademyNotification)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<AcademyNotification>)

    @Update
    suspend fun updateNotification(notification: AcademyNotification)

    @Query("UPDATE academy_notifications SET isRead = 1")
    suspend fun markAllAsRead()
}
