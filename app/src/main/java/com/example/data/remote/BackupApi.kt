package com.example.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 7 — the app-side model of the Worker's SERVER-SIDE daily backup.
 *
 * The snapshot payload itself never leaves the Worker's D1; this API only
 * ever sees metadata (status, sizes, row counts, error text) so the manager
 * can see when the last healthy backup ran and retry a failed one.
 *
 * Kept 100% JVM-pure so the parsing and the Persian summaries are
 * unit-testable without an Android device.
 */
object BackupApi {

    // ------------------------------------------------------------------
    // /api/backup/status response
    // ------------------------------------------------------------------

    data class BackupRun(
        val id: String,
        val trigger: String,        // AUTO | MANUAL
        val outcome: String,        // RUNNING | SUCCESS | FAILED
        val error: String,
        val startedAtEpoch: Long,
        val durationMs: Long
    )

    data class HealthyBackup(
        val id: String,
        val createdAtEpoch: Long,
        val totalBytes: Long,
        val storedBytes: Long,
        val filesIncluded: Boolean
    )

    data class HistoryItem(
        val id: String,
        val kind: String,
        val status: String,
        val createdAtEpoch: Long,
        val totalBytes: Long,
        val filesIncluded: Boolean,
        val error: String
    )

    data class BackupStatus(
        val lastRun: BackupRun?,
        val lastHealthy: HealthyBackup?,
        val healthyCount: Int,
        val history: List<HistoryItem>,
        val retentionDays: Int,
        val includeFiles: Boolean
    ) {
        /** True when a verified snapshot exists and is recent enough (<= 2 retention periods). */
        fun hasFreshHealthyCopy(nowEpoch: Long): Boolean {
            val last = lastHealthy ?: return false
            val maxAge = 2L * retentionDays.coerceAtLeast(1) * 24 * 60 * 60 * 1000
            return nowEpoch - last.createdAtEpoch <= maxAge
        }
    }

    fun parseStatusResponse(body: String): BackupStatus? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!json.optBoolean("success", false)) return null
        val data = json.optJSONObject("data") ?: return null

        val lastRun = data.optJSONObject("lastRun")?.let { run ->
            BackupRun(
                id = run.optString("id", ""),
                trigger = run.optString("trigger", ""),
                outcome = run.optString("outcome", ""),
                error = run.optString("error", "").trim(),
                startedAtEpoch = run.optLong("started_at_epoch", 0L),
                durationMs = run.optLong("duration_ms", 0L)
            )
        }

        val lastHealthy = data.optJSONObject("lastHealthy")?.let { h ->
            HealthyBackup(
                id = h.optString("id", ""),
                createdAtEpoch = h.optLong("created_at_epoch", 0L),
                totalBytes = h.optLong("total_bytes", 0L),
                storedBytes = h.optLong("stored_bytes", 0L),
                filesIncluded = h.optInt("files_included", 0) == 1
            )
        }

        val history = mutableListOf<HistoryItem>()
        val historyArray: JSONArray? = data.optJSONArray("history")
        if (historyArray != null) {
            for (i in 0 until historyArray.length()) {
                val item = historyArray.optJSONObject(i) ?: continue
                history.add(
                    HistoryItem(
                        id = item.optString("id", ""),
                        kind = item.optString("kind", ""),
                        status = item.optString("status", ""),
                        createdAtEpoch = item.optLong("created_at_epoch", 0L),
                        totalBytes = item.optLong("total_bytes", 0L),
                        filesIncluded = item.optInt("files_included", 0) == 1,
                        error = item.optString("error", "").trim()
                    )
                )
            }
        }

        return BackupStatus(
            lastRun = lastRun,
            lastHealthy = lastHealthy,
            healthyCount = data.optInt("healthyCount", 0),
            history = history,
            retentionDays = data.optInt("retentionDays", 7),
            includeFiles = data.optBoolean("includeFiles", true)
        )
    }

    // ------------------------------------------------------------------
    // /api/backup/run response
    // ------------------------------------------------------------------

    data class RunResult(
        val ok: Boolean,
        val status: String,        // HEALTHY | CORRUPT | FAILED
        val error: String,
        val storedBytes: Long,
        val filesIncluded: Boolean,
        val message: String
    )

    fun parseRunResponse(body: String): RunResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RunResult(false, "FAILED", "پاسخ سرور قابل خواندن نبود.", 0, false, "")
        val data = json.optJSONObject("data") ?: JSONObject()
        return RunResult(
            ok = json.optBoolean("success", false),
            status = data.optString("status", "FAILED"),
            error = data.optString("error", "").trim(),
            storedBytes = data.optLong("storedBytes", 0L),
            filesIncluded = data.optBoolean("filesIncluded", false),
            message = json.optString("message", "").trim()
        )
    }

    // ------------------------------------------------------------------
    // Persian summaries for the management UI
    // ------------------------------------------------------------------

    fun statusSummary(status: BackupStatus, nowEpoch: Long, currencyHint: Boolean = false): String {
        val healthy = status.lastHealthy
        return buildString {
            if (healthy == null) {
                append("هنوز هیچ نسخهٔ سالم پشتیبان روی سرور ثبت نشده است.")
            } else {
                val ageDays = (nowEpoch - healthy.createdAtEpoch) / (24 * 60 * 60 * 1000)
                append("آخرین نسخهٔ سالم: ${com.example.data.util.PersianDateUtil.toPersianDigits(ageDays.toString())} روز پیش")
                append(" · ${formatKb(healthy.storedBytes)} فشرده")
                if (!healthy.filesIncluded) append(" · ⚠️ فایل‌های قرارداد در این نسخه ذخیره نشده‌اند (حجم زیاد)")
            }
            append("\nنسخه‌های سالم نگه‌داری‌شده: ${com.example.data.util.PersianDateUtil.toPersianDigits(status.healthyCount.toString())} از ${com.example.data.util.PersianDateUtil.toPersianDigits(status.retentionDays.toString())}")
            if (!status.includeFiles) append("\n⚠️ پشتیبان‌گیری فایل‌ها روی سرور غیرفعال شده است")
            val lastRun = status.lastRun
            if (lastRun != null && lastRun.outcome != "SUCCESS") {
                append("\n❌ آخرین تلاش ناموفق بود: ${lastRun.error.ifBlank { "خطای نامشخص" }}")
            }
        }
    }

    private fun formatKb(bytes: Long): String {
        return if (bytes >= 1024 * 1024) "${"%.1f".format(bytes / (1024f * 1024f))} مگابایت"
        else "${bytes / 1024} کیلوبایت"
    }
}
