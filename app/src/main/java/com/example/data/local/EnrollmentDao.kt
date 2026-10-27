package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Enrollment
import kotlinx.coroutines.flow.Flow

@Dao
interface EnrollmentDao {
    @Query("SELECT * FROM enrollments ORDER BY createdAt DESC")
    fun getAllEnrollments(): Flow<List<Enrollment>>

    @Query("SELECT * FROM enrollments WHERE studentId = :studentId ORDER BY createdAt DESC")
    fun getEnrollmentsForStudent(studentId: String): Flow<List<Enrollment>>

    @Query("SELECT * FROM enrollments WHERE id = :id LIMIT 1")
    suspend fun getEnrollmentById(id: String): Enrollment?

    @Query("SELECT * FROM enrollments WHERE studentId = :studentId AND courseId = :courseId ORDER BY registrationDateEpoch DESC")
    suspend fun getEnrollmentsForStudentAndCourse(studentId: String, courseId: String): List<Enrollment>

    @Query("SELECT * FROM enrollments WHERE courseId = :courseId")
    suspend fun getEnrollmentsForCourse(courseId: String): List<Enrollment>

    @Query("SELECT * FROM enrollments WHERE courseId = :courseId ORDER BY createdAt ASC")
    fun getEnrollmentsForCourseFlow(courseId: String): Flow<List<Enrollment>>

    /** Capacity counter: every non-cancelled enrollment occupies one seat. */
    @Query("SELECT COUNT(*) FROM enrollments WHERE courseId = :courseId AND status != 'CANCELLED'")
    suspend fun countValidEnrollments(courseId: String): Int

    @Query("SELECT COUNT(*) FROM enrollments WHERE courseId = :courseId AND status != 'CANCELLED'")
    fun getValidEnrollmentCountFlow(courseId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM enrollments")
    fun getEnrollmentsCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrollment(enrollment: Enrollment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnrollments(enrollments: List<Enrollment>)

    @Update
    suspend fun updateEnrollment(enrollment: Enrollment)

    @Delete
    suspend fun deleteEnrollment(enrollment: Enrollment)

    @Query("DELETE FROM enrollments WHERE id = :id")
    suspend fun deleteEnrollmentById(id: String)

    @Query("DELETE FROM enrollments WHERE isSampleData = 1")
    suspend fun deleteSampleEnrollments()

    // --- Test-data cleanup (temporary admin tool) ---
    @Query("DELETE FROM enrollments WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: String)

    @Query("SELECT COUNT(*) FROM enrollments WHERE studentId = :studentId")
    suspend fun countForStudent(studentId: String): Int
}
