package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.AcademyNotification
import com.example.data.model.AcademySettings
import com.example.data.model.AuditLog
import com.example.data.model.BankAccount
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Expense
import com.example.data.model.ExpenseCategory
import com.example.data.model.Installment
import com.example.data.model.MessageLog
import com.example.data.model.MessageTemplate
import com.example.data.model.Payment
import com.example.data.model.Refund
import com.example.data.model.Reminder
import com.example.data.model.Student

@Database(
    entities = [
        Student::class,
        Course::class,
        AcademySettings::class,
        AuditLog::class,
        Enrollment::class,
        Installment::class,
        Payment::class,
        AcademyNotification::class,
        Reminder::class,
        BankAccount::class,
        MessageTemplate::class,
        MessageLog::class,
        Expense::class,
        Refund::class,
        ExpenseCategory::class,
        com.example.data.model.PendingDeletion::class,
        com.example.data.model.PendingStudentDeletion::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun courseDao(): CourseDao
    abstract fun settingsDao(): AcademySettingsDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun enrollmentDao(): EnrollmentDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun paymentDao(): PaymentDao
    abstract fun notificationDao(): AcademyNotificationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun bankAccountDao(): BankAccountDao
    abstract fun messageTemplateDao(): MessageTemplateDao
    abstract fun messageLogDao(): MessageLogDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun refundDao(): RefundDao
    abstract fun expenseCategoryDao(): ExpenseCategoryDao
    abstract fun pendingDeletionDao(): PendingDeletionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v5 adds deletion tombstones for offline-safe sync. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pending_student_deletions` (
                        `studentId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`studentId`))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `pending_deletions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL)"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_deletions_entityType_entityId` " +
                        "ON `pending_deletions` (`entityType`, `entityId`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "academy_manager_database"
                )
                    // Real migrations only. Never wipe the academy data silently:
                    // a missing migration is a build-time bug, not a user problem.
                    .addMigrations(MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
