package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AcademySettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AcademySettingsDao {
    @Query("SELECT * FROM academy_settings WHERE id = 'current_settings' LIMIT 1")
    fun getSettings(): Flow<AcademySettings?>

    @Query("SELECT * FROM academy_settings WHERE id = 'current_settings' LIMIT 1")
    suspend fun getSettingsDirect(): AcademySettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: AcademySettings)
}
