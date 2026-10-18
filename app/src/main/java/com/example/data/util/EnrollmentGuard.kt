package com.example.data.util

import com.example.data.model.Enrollment
import java.util.concurrent.TimeUnit

/**
 * One active enrollment per (student, course). Registering for another course is
 * always allowed; the same course can be taken again only after the cooldown,
 * and the old enrollment plus every payment stay untouched.
 */
object EnrollmentGuard {
    /** Minimum time between two enrollments of the same student in one course. */
    const val REREGISTER_AFTER_DAYS = 183

    private val OPEN_STATUSES = setOf("ACTIVE", "COMPLETED", "FINISHED", "PAUSED")

    enum class Block { NONE, ALREADY_ENROLLED, COURSE_FINISHED, COOLDOWN }

    data class Decision(
        val block: Block,
        val message: String = "",
        val canReregisterAtEpoch: Long = 0L
    ) {
        val allowed: Boolean get() = block == Block.NONE
    }

    fun isOpen(status: String): Boolean = status.uppercase() in OPEN_STATUSES

    fun cooldownEpoch(last: Enrollment): Long =
        last.registrationDateEpoch + TimeUnit.DAYS.toMillis(REREGISTER_AFTER_DAYS.toLong())

    /**
     * @param prior existing enrollments of this student in this course, newest first
     * @param courseEndDateEpoch end of the course (0 when unknown)
     */
    fun check(
        prior: List<Enrollment>,
        courseEndDateEpoch: Long,
        now: Long = System.currentTimeMillis()
    ): Decision {
        val open = prior.firstOrNull { isOpen(it.status) }
        if (open != null) {
            return Decision(
                Block.ALREADY_ENROLLED,
                "این هنرجو از قبل در این دوره ثبت‌نام شده است. برای ثبت‌نام دوباره در همین دوره، ابتدا دوره قبلی باید حداقل ۶ ماه از تاریخ ثبت‌نام آن گذشته باشد."
            )
        }
        if (prior.isEmpty()) return Decision(Block.NONE)

        if (courseEndDateEpoch > 0 && now < courseEndDateEpoch) {
            return Decision(
                Block.COURSE_FINISHED,
                "دوره قبلی این هنرجو هنوز به پایان نرسیده است و ثبت‌نام دوباره در همین دوره مجاز نیست."
            )
        }

        val last = prior.maxByOrNull { it.registrationDateEpoch }!!
        val allowedAt = cooldownEpoch(last)
        if (now < allowedAt) {
            return Decision(
                Block.COOLDOWN,
                "ثبت‌نام دوباره در این دوره از تاریخ ${PersianDateUtil.formatEpochToShamsi(allowedAt)} امکان‌پذیر است (حداقل ${REREGISTER_AFTER_DAYS / 30} ماه پس از ثبت‌نام قبلی).",
                allowedAt
            )
        }
        return Decision(Block.NONE)
    }
}
