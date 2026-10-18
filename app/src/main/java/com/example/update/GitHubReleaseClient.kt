package com.example.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reads the newest official release of the app straight from the GitHub
 * Releases API. The APK link is discovered from the release itself -- never
 * hardcoded -- so whichever build CI publishes is the one the app offers.
 *
 * The endpoint for "latest" is used on purpose: it already ignores drafts and
 * pre-releases, so the user is only ever offered a finished, published release.
 */
object GitHubReleaseClient {

    /** Owner/repo of the official source, matches the app's package owner. */
    const val REPO = "wizardtcman-design/academy-manager"

    /**
     * True when the last [fetchLatest] call failed because of the network or an
     * unexpected GitHub answer. The UI uses it to show "بررسی ناموفق بود" and a
     * retry button instead of a misleading "برنامه به‌روز است".
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private const val LATEST_URL =
        "https://api.github.com/repos/$REPO/releases/latest"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches the latest release, or null when there is none, the network is
     * down, or GitHub answers with an unexpected payload. Failures are silent:
     * an update check must never disturb the app.
     */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        lastFailure = null
        try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "academy-manager-update")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    lastFailure = "GitHub پاسخ ${resp.code} داد"
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                parseRelease(JSONObject(body)).also {
                    if (it == null) lastFailure = "پاسخ GitHub قابل خواندن نبود"
                }
            }
        } catch (e: Exception) {
            lastFailure = e.message ?: "شبکه در دسترس نیست"
            null
        }
    }

    /**
     * Fetches the notes of the release whose tag matches [version] (with or
     * without a leading "v"). Used for the one-time "What's New" dialog after
     * the app has been updated. Returns null when the release is not found or
     * the network is unavailable.
     */
    suspend fun fetchNotesForVersion(version: String): String? = withContext(Dispatchers.IO) {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        if (clean.isBlank()) return@withContext null
        for (tag in listOf("v$clean", clean)) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/tags/$tag")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "academy-manager-update")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@withContext null
                        val notes = JSONObject(body).optString("body")
                        if (notes.isNotBlank()) return@withContext notes
                    }
                }
            } catch (_: Exception) {
                // try the next tag form
            }
        }
        null
    }

    /** Turns one GitHub release JSON object into [UpdateInfo], or null. */
    private fun parseRelease(o: JSONObject): UpdateInfo? {
        val tag = o.optString("tag_name").trim()
        if (tag.isBlank()) return null
        val version = tag.removePrefix("v").removePrefix("V")

        val notes = o.optString("body")
        val htmlUrl = o.optString("html_url")
        val publishedAt = parseIso(o.optString("published_at").ifBlank { null })

        // Find the APK asset. Prefer one whose name ends with .apk; a release
        // may also carry a signature or checksum file, so pick carefully.
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        for (i in 0 until (assets?.length() ?: 0)) {
            val a = assets?.optJSONObject(i) ?: continue
            if (a.optString("name").lowercase().endsWith(".apk")) {
                apk = a
                break
            }
        }
        val apkObj = apk ?: return null

        val apkUrl = apkObj.optString("browser_download_url")
        if (apkUrl.isBlank()) return null

        return UpdateInfo(
            tagName = tag,
            version = version,
            notes = notes,
            apkUrl = apkUrl,
            apkName = apkObj.optString("name").ifBlank { "academy-manager.apk" },
            apkSize = apkObj.optLong("size", 0L),
            htmlUrl = htmlUrl,
            publishedAt = publishedAt
        )
    }

    /** Parses an ISO-8601 timestamp to epoch millis; 0 on any problem. */
    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
