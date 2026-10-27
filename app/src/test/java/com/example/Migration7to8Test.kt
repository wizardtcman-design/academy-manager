package com.example

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Course
import com.example.data.model.Enrollment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Scenario 16 — the REAL Room migration 7 → 8.
 *
 * A v7 database is built BY HAND in a file-backed SQLite database:
 * every CREATE statement is taken verbatim from the Room-generated v8 schema
 * (read from an in-memory database at runtime), minus exactly the v8 additions:
 *   * `courses` loses the 14 columns of MIGRATION_7_8,
 *   * `enrollments` loses certificateStatus / enrolledViaOverride / updatedAt,
 *   * the two archive tables and their index are absent,
 *   * every other table and every pre-v8 index is identical.
 * Rows are inserted as v7-era data and `user_version` is set to 7.
 *
 * Opening the file with Room + the real migration chain must pass Room's full
 * schema validation (the actual proof that the migration SQL matches the
 * entities) and preserve all old values while giving the new columns their
 * documented defaults (capacity 0 = unlimited, OPEN / ONGOING / INCOMPLETE,
 * updatedAt backfilled from createdAt, certificate PENDING, no override).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration7to8Test {

    companion object {
        private const val DB_NAME = "migration_test.db"

        // Columns MIGRATION_7_8 adds to `courses` (order = migration order).
        private val V8_COURSE_COLUMNS = setOf(
            "academicYear", "startShamsi", "startTime", "endShamsi", "endTime",
            "enrollmentStatus", "holdingStatus", "finalStatus", "endedAtShamsi",
            "startEpoch", "endEpoch", "endedAtEpoch", "updatedAt", "capacity"
        )

        // Columns MIGRATION_7_8 adds to `enrollments`.
        private val V8_ENROLLMENT_COLUMNS = setOf(
            "certificateStatus", "enrolledViaOverride", "updatedAt"
        )

        // v8 additions that must NOT exist in a v7 schema.
        private val V8_NEW_TABLES = setOf("archive_courses", "archive_students")
        private val V8_NEW_INDEXES = setOf("index_archive_students_archiveCourseId")

        /** Strips the given columns from a Room-generated `CREATE TABLE` statement. */
        fun stripColumns(createTableSql: String, removedColumns: Set<String>): String {
            val open = createTableSql.indexOf('(')
            val close = createTableSql.lastIndexOf(')')
            require(open in 0 until close) { "Unexpected CREATE statement: $createTableSql" }
            val head = createTableSql.substring(0, open + 1)
            val tail = createTableSql.substring(close)
            val body = createTableSql.substring(open + 1, close)

            // Split on top-level commas (parens of PRIMARY KEY(...) must not split).
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            body.forEachIndexed { index, ch ->
                when (ch) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        parts.add(body.substring(start, index))
                        start = index + 1
                    }
                }
            }
            parts.add(body.substring(start))

            val kept = parts.map { it.trim() }.filter { part ->
                val upper = part.uppercase()
                val isTableConstraint = upper.startsWith("PRIMARY") || upper.startsWith("FOREIGN") ||
                    upper.startsWith("UNIQUE") || upper.startsWith("CHECK") || upper.startsWith("CONSTRAINT")
                if (isTableConstraint) {
                    true
                } else {
                    val columnName = part.removePrefix("`").substringBefore('`').trim()
                    columnName !in removedColumns
                }
            }
            return head + kept.joinToString(", ") + tail
        }
    }

    private lateinit var context: Context
    private var roomDb: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        roomDb?.close()
        roomDb = null
        deleteDbFiles()
    }

    private fun deleteDbFiles() {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            File(context.getDatabasePath(DB_NAME).path + suffix).delete()
        }
    }

    @Test
    fun scenario16_migration_7_to_8_preservesData() = runBlocking {
        val dbFile = context.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()

        // ------------------------------------------------------------------
        // 1. Derive the CURRENT v8 Room-generated schema from a throwaway
        //    in-memory database (tables + indexes, verbatim).
        // ------------------------------------------------------------------
        val v8 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val statements = mutableListOf<Triple<String, String, String>>() // (type, name, sql)
        val cursor: Cursor = v8.openHelper.writableDatabase.query(
            "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' AND name != 'room_master_table'"
        )
        cursor.use { c ->
            val typeIdx = c.getColumnIndexOrThrow("type")
            val nameIdx = c.getColumnIndexOrThrow("name")
            val sqlIdx = c.getColumnIndexOrThrow("sql")
            while (c.moveToNext()) {
                statements.add(Triple(c.getString(typeIdx), c.getString(nameIdx), c.getString(sqlIdx)))
            }
        }
        v8.close()
        assertTrue("v8 schema must contain tables", statements.any { it.first == "table" })

        // ------------------------------------------------------------------
        // 2. Transform v8 → v7: drop the archive additions and the new columns.
        // ------------------------------------------------------------------
        val v7Tables = statements
            .filter { it.first == "table" && it.second !in V8_NEW_TABLES }
            .map { (type, name, sql) ->
                when (name) {
                    "courses" -> Triple(type, name, stripColumns(sql, V8_COURSE_COLUMNS))
                    "enrollments" -> Triple(type, name, stripColumns(sql, V8_ENROLLMENT_COLUMNS))
                    else -> Triple(type, name, sql)
                }
            }
        val v7Indexes = statements.filter { it.first == "index" && it.second !in V8_NEW_INDEXES }

        // The v7 courses DDL must genuinely lack the new columns.
        val coursesDdl = v7Tables.first { it.second == "courses" }.third
        V8_COURSE_COLUMNS.forEach { column ->
            assertTrue("v7 DDL must not contain the v8 column `$column`", !coursesDdl.contains("`$column`"))
        }
        val enrollmentsDdl = v7Tables.first { it.second == "enrollments" }.third
        V8_ENROLLMENT_COLUMNS.forEach { column ->
            assertTrue("v7 DDL must not contain the v8 column `$column`", !enrollmentsDdl.contains("`$column`"))
        }

        // ------------------------------------------------------------------
        // 3. Build the v7 database by hand and insert v7-era rows.
        // ------------------------------------------------------------------
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile.path, null)
        v7Tables.forEach { (_, _, sql) -> sqlite.execSQL(sql) }   // tables first …
        v7Indexes.forEach { (_, _, sql) -> sqlite.execSQL(sql) }  // … then indexes

        // One v7-era student (students table is unchanged by v8).
        sqlite.execSQL(
            """INSERT INTO students (
                    id, studentCode, firstName, lastName, phone, nationalCode, address, notes,
                    registrationDateShamsi, registrationDateEpoch, status,
                    telegramEnabled, telegramChatId, telegramUsername, telegramLinkedAt,
                    telegramLinkToken, telegramTokenExpiryEpoch, isActive, isSampleData,
                    createdAt, updatedAt)
               VALUES ('std_v7', 'ST-1403-0001', 'هنرجو', 'قدیمی', '09120000000', '', '', '',
                       '1403/06/01', 1000000, 'ACTIVE',
                       0, '', '', 0,
                       '', 0, 1, 0,
                       1000000, 1000000)"""
        )
        // One v7-era course (no academicYear/start/capacity/status columns yet).
        sqlite.execSQL(
            """INSERT INTO courses (
                    id, title, durationMonths, tuitionFee, installmentCount, description,
                    isActive, isSampleData, createdAt)
               VALUES ('crs_v7', 'دوره قدیمی مهاجرت', 3, 4000000, 3, 'توضیح قدیمی',
                       1, 0, 1234567890)"""
        )
        // One v7-era enrollment (contractNumber already exists from v6; no certificate columns).
        sqlite.execSQL(
            """INSERT INTO enrollments (
                    id, studentId, studentName, courseId, courseTitle,
                    registrationDateShamsi, registrationDateEpoch, startDateShamsi, endDateShamsi,
                    totalFee, discount, finalFee, installmentCount,
                    firstInstallmentPaidOnDate, firstInstallmentDateShamsi, firstInstallmentDateEpoch,
                    status, notes, contractNumber, isSampleData, createdAt)
               VALUES ('enr_v7', 'std_v7', 'هنرجو قدیمی', 'crs_v7', 'دوره قدیمی مهاجرت',
                       '1403/06/01', 1000000, '1403/06/01', '1403/09/01',
                       3000000, 200000, 2800000, 3,
                       0, '1403/06/01', 1000000,
                       'ACTIVE', 'یادداشت قدیمی', 'C-1403-0001', 0, 1000000)"""
        )
        sqlite.execSQL("PRAGMA user_version = 7")
        sqlite.close()

        // ------------------------------------------------------------------
        // 4. Open through Room with the REAL migration chain.
        //    The first query runs MIGRATION_7_8 and Room's full schema
        //    validation — any mismatch throws IllegalStateException here.
        // ------------------------------------------------------------------
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .addMigrations(
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8
            )
            .allowMainThreadQueries()
            .build()
            .also { roomDb = it }

        // --- course: old fields intact, new fields = documented defaults ---
        val course = migrated.courseDao().getCourseById("crs_v7")
        assertTrue("دوره قدیمی باید پس از مهاجرت موجود باشد", course != null)
        course!!
        assertEquals("عنوان قدیمی حفظ شود", "دوره قدیمی مهاجرت", course.title)
        assertEquals("شهریه قدیمی حفظ شود", 4_000_000L, course.tuitionFee)
        assertEquals("توضیحات قدیمی حفظ شود", "توضیح قدیمی", course.description)
        assertEquals(3, course.durationMonths)
        assertEquals(3, course.installmentCount)
        assertTrue(course.isActive)
        assertEquals(1234567890L, course.createdAt)
        // v8 defaults
        assertEquals("ظرفیت پیش‌فرض ۰ یعنی بدون محدودیت", 0, course.capacity)
        assertEquals(Course.ENROLLMENT_OPEN, course.enrollmentStatus)
        assertEquals(Course.HOLDING_ONGOING, course.holdingStatus)
        assertEquals(Course.FINAL_INCOMPLETE, course.finalStatus)
        assertEquals("", course.academicYear)
        assertEquals("", course.startShamsi)
        assertEquals("", course.startTime)
        assertEquals(0L, course.startEpoch)
        assertEquals(0L, course.endEpoch)
        assertEquals("", course.endedAtShamsi)
        assertEquals(0L, course.endedAtEpoch)
        assertEquals(
            "updatedAt باید از createdAt پر شده باشد (backfill همگام‌سازی)",
            course.createdAt,
            course.updatedAt
        )

        // --- enrollment: certificate PENDING, no override, backfilled updatedAt ---
        val enrollment = migrated.enrollmentDao().getEnrollmentById("enr_v7")
        assertTrue("ثبت‌نام قدیمی باید پس از مهاجرت موجود باشد", enrollment != null)
        enrollment!!
        assertEquals("enr_v7", enrollment.id)
        assertEquals("std_v7", enrollment.studentId)
        assertEquals("crs_v7", enrollment.courseId)
        assertEquals("هنرجو قدیمی", enrollment.studentName)
        assertEquals(2_800_000L, enrollment.finalFee)
        assertEquals(200_000L, enrollment.discount)
        assertEquals("ACTIVE", enrollment.status)
        assertEquals("C-1403-0001", enrollment.contractNumber)
        assertEquals("وضعیت مدرک پیش‌فرض باید در انتظار باشد", Enrollment.CERTIFICATE_PENDING, enrollment.certificateStatus)
        assertEquals("هیچ ثبت‌نام قدیمی سرریز ظرفیت نبوده", false, enrollment.enrolledViaOverride)
        assertEquals(
            "updatedAt ثبت‌نام هم باید از createdAt پر شود",
            enrollment.createdAt,
            enrollment.updatedAt
        )

        // --- student survived untouched ---
        val student = migrated.studentDao().getStudentById("std_v7")
        assertTrue("هنرجوی قدیمی باید باقی بماند", student != null)
        assertEquals("هنرجو قدیمی", student!!.fullName)
        assertEquals("ACTIVE", student.status)

        // --- the new archive tables exist but are EMPTY ---
        assertTrue("بایگانی پس از مهاجرت باید خالی باشد", migrated.archiveDao().getAllArchiveCourses().first().isEmpty())
        assertTrue("بایگانی هنرجویان پس از مهاجرت باید خالی باشد", migrated.archiveDao().getArchiveStudentsDirect().isEmpty())

        // --- the migrated file really reached version 8 ---
        val versionCursor = migrated.openHelper.writableDatabase.query("PRAGMA user_version")
        versionCursor.use { c ->
            c.moveToFirst()
            assertEquals("نسخه پایگاه‌داده باید ۸ شود", 8, c.getInt(0))
        }
    }
}
