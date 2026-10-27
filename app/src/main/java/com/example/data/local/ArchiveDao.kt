package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ArchiveCourse
import com.example.data.model.ArchiveStudent
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveDao {
    // --- Archive Courses ---
    @Query("SELECT * FROM archive_courses ORDER BY createdAt DESC")
    fun getAllArchiveCourses(): Flow<List<ArchiveCourse>>

    @Query("SELECT * FROM archive_courses WHERE id = :id LIMIT 1")
    suspend fun getArchiveCourseById(id: String): ArchiveCourse?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchiveCourse(course: ArchiveCourse)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchiveCourses(courses: List<ArchiveCourse>)

    @Update
    suspend fun updateArchiveCourse(course: ArchiveCourse)

    @Delete
    suspend fun deleteArchiveCourse(course: ArchiveCourse)

    // --- Archive Students ---
    @Query("SELECT * FROM archive_students ORDER BY createdAt DESC")
    fun getAllArchiveStudents(): Flow<List<ArchiveStudent>>

    @Query("SELECT * FROM archive_students")
    suspend fun getArchiveStudentsDirect(): List<ArchiveStudent>

    @Query("SELECT * FROM archive_students WHERE archiveCourseId = :courseId ORDER BY createdAt DESC")
    fun getArchiveStudentsForCourse(courseId: String): Flow<List<ArchiveStudent>>

    @Query("SELECT * FROM archive_students WHERE archiveCourseId = :courseId ORDER BY createdAt DESC")
    suspend fun getArchiveStudentsForCourseDirect(courseId: String): List<ArchiveStudent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchiveStudent(student: ArchiveStudent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchiveStudents(students: List<ArchiveStudent>)

    @Update
    suspend fun updateArchiveStudent(student: ArchiveStudent)

    @Delete
    suspend fun deleteArchiveStudent(student: ArchiveStudent)

    @Query("DELETE FROM archive_students WHERE archiveCourseId = :courseId")
    suspend fun deleteArchiveStudentsForCourse(courseId: String)
}
