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
import java.security.MessageDigest
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
        forceRenew: Boolean,
        sealFile: File? = null,
        signatureFile: File? = null,
        signerName: String = "",
        signerTitle: String = ""
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
                .put("signerName", signerName)
                .put("signerTitle", signerTitle)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", meta.toString())
                .addFormDataPart(
                    "file",
                    pdf.name,
                    pdf.asRequestBody("application/pdf".toMediaType())
                )
                .apply {
                    if (sealFile != null && sealFile.exists()) {
                        addFormDataPart("seal", sealFile.name, sealFile.asRequestBody("image/png".toMediaType()))
                    }
                    if (signatureFile != null && signatureFile.exists()) {
                        addFormDataPart("signature", signatureFile.name, signatureFile.asRequestBody("image/png".toMediaType()))
                    }
                }
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

    /**
     * Secure per-student download: the server checks that the contract BELONGS
     * to this student and that the file hash matches the one recorded when it
     * was signed. The file is written to a temporary name first and only moved
     * into place when it is a complete PDF, so a cut-off download never looks
     * like a saved contract.
     */
    fun downloadContractForStudent(
        studentId: String,
        contractId: String,
        signed: Boolean,
        target: File,
        expectedSha256: String = ""
    ): Result<File> {
        if (!isConfigured()) return Result(false, error = "اتصال تنظیم نشده است.")
        val kind = if (signed) "signed-pdf" else "pdf"
        val partial = File(target.parentFile, target.name + ".part")
        return try {
            val req = Request.Builder()
                .url(endpoint("/api/students/${java.net.URLEncoder.encode(studentId, "UTF-8")}" +
                    "/contracts/${java.net.URLEncoder.encode(contractId, "UTF-8")}/$kind"))
                .addHeader("Authorization", "Bearer $apiSecret")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val text = res.body?.string().orEmpty()
                    return Result(false, error = "HTTP ${res.code} ${text.take(120)}", code = res.code)
                }
                val expected = res.header("Content-Length")?.toLongOrNull() ?: -1L
                partial.parentFile?.mkdirs()
                partial.outputStream().use { out ->
                    res.body?.byteStream()?.copyTo(out) ?: return Result(false, error = "فایل خالی")
                }
                val size = partial.length()
                if (expected >= 0 && size != expected) {
                    partial.delete()
                    return Result(false, error = "دانلود ناقص است ($size از $expected بایت)", code = 0)
                }
                val headSha = res.header("X-Content-SHA256")?.trim()?.lowercase().orEmpty()
                verifyDownload(
                    partial,
                    expectedSha256 = expectedSha256.ifBlank { headSha }
                )?.let { problem ->
                    partial.delete()
                    return Result(false, error = problem, code = 0)
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                Result(true, target)
            }
        } catch (e: Exception) {
            partial.delete()
            Log.w(tag, "download failed: ${e.message}")
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
        /**
         * Null when the file is a complete PDF whose SHA-256 matches the
         * recorded one; otherwise a Persian reason. A partial file fails
         * BOTH checks: it has no %%EOF trailer and a different hash.
         */
        fun verifyDownload(file: File, expectedSha256: String): String? {
            if (!file.exists() || file.length() == 0L) return "فایل خالی است"
            val size = file.length()
            val head = file.inputStream().use { input -> ByteArray(5).also { input.read(it) } }
            if (!head.contentEquals("%PDF-".toByteArray())) return "فایل PDF معتبر نیست"
            val tailSize = minOf(1024L, size).toInt()
            val tailBytes = java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(size - tailSize)
                ByteArray(tailSize).also { raf.readFully(it) }
            }
            if (!String(tailBytes, Charsets.ISO_8859_1).contains("%%EOF")) {
                return "فایل ناقص است (پایان PDF پیدا نشد)"
            }
            val wanted = expectedSha256.trim().lowercase()
            if (wanted.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha != wanted) return "فایل با نسخه ثبت‌شده مطابقت ندارد"
            }
            return null
        }
    
        fun fromSettings(settings: AcademySettings?): ContractCloudClient =
            ContractCloudClient(
                baseUrl = settings?.telegramWorkerUrl.orEmpty(),
                apiSecret = settings?.telegramApiSecret.orEmpty()
            )
    }
}
