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
        com.example.data.model.PendingStudentDeletion::class,
        com.example.data.model.Contract::class
    ],
    version = 6,
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
    abstract fun contractDao(): ContractDao

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

        /**
         * v6 adds the single digital contract per enrollment plus the contract
         * number on the enrollment. Existing rows are kept as-is; numbers are
         * assigned in place on first read so no historical data is rewritten.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `enrollments` ADD COLUMN `contractNumber` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `contracts` (
                        `id` TEXT NOT NULL,
                        `enrollmentId` TEXT NOT NULL,
                        `studentId` TEXT NOT NULL,
                        `studentName` TEXT NOT NULL,
                        `courseId` TEXT NOT NULL,
                        `courseTitle` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `publicToken` TEXT NOT NULL,
                        `publicTokenHash` TEXT NOT NULL,
                        `signUrl` TEXT NOT NULL,
                        `issuedAtEpoch` INTEGER NOT NULL,
                        `expiresAtEpoch` INTEGER NOT NULL,
                        `openedAtEpoch` INTEGER NOT NULL,
                        `signedAtEpoch` INTEGER NOT NULL,
                        `signedAtShamsi` TEXT NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        `remoteKey` TEXT NOT NULL,
                        `localPdfPath` TEXT NOT NULL,
                        `localSignedPdfPath` TEXT NOT NULL,
                        `pdfSha256` TEXT NOT NULL,
                        `lastError` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contracts_enrollmentId` ON `contracts` (`enrollmentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contracts_studentId` ON `contracts` (`studentId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_contracts_publicTokenHash` ON `contracts` (`publicTokenHash`)")
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
