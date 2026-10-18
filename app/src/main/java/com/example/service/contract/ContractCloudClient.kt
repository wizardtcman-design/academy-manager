package com.example.service.contract

import android.util.Log
import com.example.data.model.AcademySettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin HTTPS client for the contract part of the academy Worker.
 * The bearer key is the academy's own API secret (entered in Settings), never a
 * key baked into the APK.
 */
class ContractCloudClient(
    private val baseUrl: String,
    private val apiSecret: String
) {
    private val tag = "ContractCloud"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result<T>(val ok: Boolean, val data: T? = null, val error: String = "", val code: Int = 0)

    private fun endpoint(path: String): String = baseUrl.trim().removeSuffix("/") + path

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && apiSecret.isNotBlank()

    /** Health probe used by the settings screen: does the Worker answer and see D1/R2? */
    fun probe(): Result<JSONObject> {
        if (!isConfigured()) return Result(false, error = "آدرس سرور یا کلید API تنظیم نشده است.")
        return try {
            val req = Request.Builder()
                .url(endpoint("/api/contracts/health"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (res.isSuccessful && json?.optBoolean("success") == true) {
                    Result(true, json.optJSONObject("data") ?: JSONObject())
                } else {
                    Result(false, error = json?.optString("message").orEmpty().ifBlank { "HTTP ${res.code}" }, code = res.code)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "probe failed: ${e.message}")
            Result(false, error = e.message ?: "خطای شبکه")
        }
    }

    fun uploadContract(
        contractId: String,
        enrollmentId: String,
        tokenHash: String,
        publicToken: String,
        existsRemotely: Boolean,
        expiresAtEpoch: Long,
        studentId: String,
        enrollment: JSONObject,
        pdf: File,
        forceRenew: Boolean
    ): Result<JSONObject> {
        if (!isConfigured()) return Result(false, error = "اتصال به سرور قرارداد تنظیم نشده است.")
        return try {
            val meta = JSONObject()
                .put("contractId", contractId)
                .put("enrollmentId", enrollmentId)
                .put("tokenHash", tokenHash)
                .put("publicToken", if (existsRemotely.not()) publicToken else "")
                .put("expiresAtEpoch", expiresAtEpoch)
                .put("studentId", studentId)
                .put("enrollment", enrollment)
                .put("forceRenew", forceRenew)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", meta.toString())
                .addFormDataPart(
                    "file",
                    pdf.name,
                    pdf.asRequestBody("application/pdf".toMediaType())
                )
                .build()

            val req = Request.Builder()
                .url(endpoint("/api/contracts"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .post(body)
                .build()

            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (res.isSuccessful && json?.optBoolean("success") == true) {
                    Result(true, json.optJSONObject("data") ?: JSONObject())
                } else {
                    Result(false, error = json?.optString("message").orEmpty().ifBlank { "HTTP ${res.code}" }, code = res.code)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "upload failed: ${e.message}")
            Result(false, error = e.message ?: "خطای شبکه")
        }
    }

    fun getContract(remoteId: String): Result<JSONObject> {
        if (!isConfigured()) return Result(false, error = "اتصال تنظیم نشده است.")
        return try {
            val req = Request.Builder()
                .url(endpoint("/api/contracts/$remoteId"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (res.isSuccessful && json?.optBoolean("success") == true) {
                    Result(true, json.optJSONObject("data") ?: JSONObject())
                } else {
                    Result(false, error = json?.optString("message").orEmpty().ifBlank { "HTTP ${res.code}" }, code = res.code)
                }
            }
        } catch (e: Exception) {
            Result(false, error = e.message ?: "خطای شبکه")
        }
    }

    /** Downloads the signed PDF into the given file (only when the contract is signed). */
    fun downloadSignedPdf(remoteId: String, target: File): Result<File> {
        if (!isConfigured()) return Result(false, error = "اتصال تنظیم نشده است.")
        return try {
            val req = Request.Builder()
                .url(endpoint("/api/contracts/$remoteId/pdf?signed=1"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val text = res.body?.string().orEmpty()
                    return Result(false, error = "HTTP ${res.code} ${text.take(120)}", code = res.code)
                }
                val bytes = res.body?.bytes() ?: return Result(false, error = "فایل خالی")
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                Result(true, target)
            }
        } catch (e: Exception) {
            Result(false, error = e.message ?: "خطای دانلود")
        }
    }

    /** Revokes a link so it can no longer be opened (status stays signed if signed). */
    fun revoke(remoteId: String): Result<JSONObject> {
        if (!isConfigured()) return Result(false, error = "اتصال تنظیم نشده است.")
        return try {
            val req = Request.Builder()
                .url(endpoint("/api/contracts/$remoteId/revoke"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string() ?: ""
                val json = runCatching { JSONObject(text) }.getOrNull()
                Result(res.isSuccessful && json?.optBoolean("success") == true,
                    json?.optJSONObject("data"), json?.optString("message").orEmpty(), res.code)
            }
        } catch (e: Exception) {
            Result(false, error = e.message ?: "خطای شبکه")
        }
    }

    companion object {
        fun fromSettings(settings: AcademySettings?): ContractCloudClient =
            ContractCloudClient(
                baseUrl = settings?.telegramWorkerUrl.orEmpty(),
                apiSecret = settings?.telegramApiSecret.orEmpty()
            )
    }
}
