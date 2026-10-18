package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Student
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY createdAt DESC")
    fun getAllStudents(): Flow<List<Student>>

    @Query("SELECT * FROM students WHERE id = :id LIMIT 1")
    suspend fun getStudentById(id: String): Student?

    @Query("SELECT * FROM students WHERE telegramChatId = :chatId AND telegramEnabled = 1 LIMIT 1")
    suspend fun getStudentByTelegramChatId(chatId: String): Student?

    @Query("SELECT * FROM students")
    suspend fun getAllStudentsDirect(): List<Student>

    @Query("SELECT * FROM pending_student_deletions ORDER BY createdAt ASC")
    fun getPendingDeletions(): Flow<List<com.example.data.model.PendingStudentDeletion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudentDeletion(deletion: com.example.data.model.PendingStudentDeletion)

    @Query("DELETE FROM pending_student_deletions WHERE studentId = :id")
    suspend fun clearStudentDeletion(id: String)

    @Query("SELECT * FROM students WHERE telegramLinkToken = :token LIMIT 1")
    suspend fun getStudentByTelegramToken(token: String): Student?

    @Query("SELECT * FROM students WHERE firstName LIKE '%' || :query || '%' OR lastName LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR nationalCode LIKE '%' || :query || '%' OR studentCode LIKE '%' || :query || '%'")
    fun searchStudents(query: String): Flow<List<Student>>

    @Query("SELECT COUNT(*) FROM students")
    fun getStudentsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM students")
    suspend fun getStudentsCountNow(): Int

    /** Largest running number already used in codes of the given year. */
    @Query("SELECT MAX(studentCode) FROM students WHERE studentCode LIKE :yearPrefix || '-%'")
    suspend fun getMaxStudentCodeForYear(yearPrefix: String): String?

    @Query("SELECT studentCode FROM students")
    suspend fun getAllStudentCodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: Student)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudents(students: List<Student>)

    @Update
    suspend fun updateStudent(student: Student)

    @Delete
    suspend fun deleteStudent(student: Student)

    @Query("DELETE FROM students WHERE id = :id")
    suspend fun deleteStudentById(id: String)

    @Query("DELETE FROM students WHERE isSampleData = 1")
    suspend fun deleteSampleStudents()

    @Query("DELETE FROM students")
    suspend fun clearAll()
}
