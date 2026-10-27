package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.AcademyNotification
import com.example.data.model.AcademySettings
import com.example.data.model.ArchiveCourse
import com.example.data.model.ArchiveStudent
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
        com.example.data.model.Contract::class,
        ArchiveCourse::class,
        ArchiveStudent::class
    ],
    version = 8,
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

    /** Room allows a DAO interface only ONCE per database: both archive
     *  tables are served by this single [ArchiveDao]. */
    abstract fun archiveDao(): ArchiveDao

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

        /**
         * v7 (Stage 4): per-installment payment method (online / card-to-card /
         * POS) plus the gateway and receipt-ordering settings fields.
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `installments` ADD COLUMN `paymentMethod` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `gatewayProvider` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `gatewayMerchantId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `gatewayApiKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `receiptFirstPaymentDelayMinutes` INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `receiptFirstPaymentRequiresContract` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `academy_settings` ADD COLUMN `onlinePaymentsPulledAtEpoch` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 (course management mission): courses gain academic year, exact
         * start/end, capacity and THREE separate status groups; enrollments
         * gain certificate status + capacity-override flag; both gain an
         * `updatedAt` LWW timestamp (backfilled from `createdAt` so legacy
         * rows behave identically in sync). Two new ISOLATED archive tables
         * store historical courses/students with nullable (unknown) money —
         * additive only, nothing is rewritten or deleted.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Courses: text columns (defaults match the Kotlin defaults).
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `academicYear` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `startShamsi` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `startTime` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `endShamsi` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `endTime` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `enrollmentStatus` TEXT NOT NULL DEFAULT 'OPEN'")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `holdingStatus` TEXT NOT NULL DEFAULT 'ONGOING'")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `finalStatus` TEXT NOT NULL DEFAULT 'INCOMPLETE'")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `endedAtShamsi` TEXT NOT NULL DEFAULT ''")
                // Courses: numeric columns (0 = not registered / unlimited capacity).
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `startEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `endEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `endedAtEpoch` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `courses` ADD COLUMN `capacity` INTEGER NOT NULL DEFAULT 0")
                // Legacy courses never had an update timestamp: backfill so LWW sync
                // keeps working exactly as before (createdAt as the tiebreaker).
                db.execSQL("UPDATE `courses` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")

                // Enrollments: certificate tracking + override flag + LWW timestamp.
                db.execSQL("ALTER TABLE `enrollments` ADD COLUMN `certificateStatus` TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE `enrollments` ADD COLUMN `enrolledViaOverride` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `enrollments` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `enrollments` SET `updatedAt` = `createdAt` WHERE `updatedAt` = 0")

                // Isolated archive tables: historical data never feeds the live
                // installments/debt/reminder systems.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `archive_courses` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `academicYear` TEXT NOT NULL,
                        `startShamsi` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `isSampleData` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `archive_students` (
                        `id` TEXT NOT NULL,
                        `archiveCourseId` TEXT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `mobile` TEXT NOT NULL,
                        `tuitionAmount` INTEGER,
                        `receivedAmount` INTEGER,
                        `paymentDatesNote` TEXT NOT NULL,
                        `notes` TEXT NOT NULL,
                        `hasUnknownInfo` INTEGER NOT NULL,
                        `isSampleData` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`))"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_archive_students_archiveCourseId` " +
                        "ON `archive_students` (`archiveCourseId`)"
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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
