package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One digital contract per enrollment. `enrollmentId` is UNIQUE in the database,
 * so re-sending a link, re-signing or updating a status can never create a
 * second visible contract for the same enrollment.
 *
 * Statuses: DRAFT (built in the app) -> ISSUED (uploaded, remoteId known) ->
 * OPENED / SIGNED / EXPIRED / REVOKED / FAILED.
 */
@Entity(
    tableName = "contracts",
    indices = [
        Index(value = ["enrollmentId"], unique = true),
        Index(value = ["studentId"]),
        Index(value = ["publicTokenHash"], unique = true)
    ]
)
data class Contract(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val enrollmentId: String = "",
    val studentId: String = "",
    val studentName: String = "",
    val courseId: String = "",
    val courseTitle: String = "",
    val status: String = "DRAFT",
    /** Random 32-byte token in URL-safe base64; the server stores only its SHA-256. */
    val publicToken: String = "",
    val publicTokenHash: String = "",
    val signUrl: String = "",
    val issuedAtEpoch: Long = 0L,
    val expiresAtEpoch: Long = 0L,
    val openedAtEpoch: Long = 0L,
    val signedAtEpoch: Long = 0L,
    val signedAtShamsi: String = "",
    val remoteId: String = "",
    val remoteKey: String = "",
    val localPdfPath: String = "",
    val localSignedPdfPath: String = "",
    /** SHA-256 of the unsigned PDF, so the signed copy is provably the same file. */
    val pdfSha256: String = "",
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isSigned: Boolean get() = status == "SIGNED" || signedAtEpoch > 0L

    val statusFa: String
        get() = when (status.uppercase()) {
            "DRAFT" -> "پیش‌نویس"
            "ISSUED" -> "لینک صادر شد"
            "OPENED" -> "مشاهده شد"
            "SIGNED" -> "امضاشده"
            "EXPIRED" -> "منقضی"
            "REVOKED" -> "لغو شده"
            "FAILED" -> "خطا"
            else -> status
        }

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "enrollmentId" to enrollmentId,
        "studentId" to studentId,
        "status" to status,
        "remoteId" to remoteId,
        "issuedAtEpoch" to issuedAtEpoch,
        "signedAtEpoch" to signedAtEpoch,
        "updatedAt" to updatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): Contract = Contract(
            id = map["id"] as? String ?: android.util.Base64.encodeToString(
                java.util.UUID.randomUUID().toString().toByteArray(), android.util.Base64.NO_WRAP
            ),
            enrollmentId = map["enrollmentId"] as? String ?: "",
            studentId = map["studentId"] as? String ?: "",
            studentName = map["studentName"] as? String ?: "",
            courseId = map["courseId"] as? String ?: "",
            courseTitle = map["courseTitle"] as? String ?: "",
            status = map["status"] as? String ?: "DRAFT",
            publicToken = map["publicToken"] as? String ?: "",
            publicTokenHash = map["publicTokenHash"] as? String ?: "",
            signUrl = map["signUrl"] as? String ?: "",
            issuedAtEpoch = (map["issuedAtEpoch"] as? Number)?.toLong() ?: 0L,
            expiresAtEpoch = (map["expiresAtEpoch"] as? Number)?.toLong() ?: 0L,
            openedAtEpoch = (map["openedAtEpoch"] as? Number)?.toLong() ?: 0L,
            signedAtEpoch = (map["signedAtEpoch"] as? Number)?.toLong() ?: 0L,
            signedAtShamsi = map["signedAtShamsi"] as? String ?: "",
            remoteId = map["remoteId"] as? String ?: "",
            remoteKey = map["remoteKey"] as? String ?: "",
            localPdfPath = map["localPdfPath"] as? String ?: "",
            localSignedPdfPath = map["localSignedPdfPath"] as? String ?: "",
            pdfSha256 = map["pdfSha256"] as? String ?: "",
            lastError = map["lastError"] as? String ?: "",
            createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )
    }
}
