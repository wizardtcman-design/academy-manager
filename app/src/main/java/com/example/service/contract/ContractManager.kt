package com.example.service.contract

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.Contract
import com.example.data.model.Enrollment
import com.example.data.util.PdfDocumentHelper
import com.example.data.util.PersianDateUtil
import com.example.data.util.SecureToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Owns the digital contract of every enrollment.
 *
 * Rules enforced here (and backed by the DB UNIQUE index on enrollmentId):
 * - one contract row per enrollment, forever;
 * - issuing again after expiry rotates the token *inside* the same contract;
 * - a signed contract is never re-issued and its PDF is never rebuilt;
 * - files live under app-private storage, never in the cloud bundle.
 */
class ContractManager(private val context: Context) {

    private val tag = "ContractManager"
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.contractDao()
    private val lock = Mutex()

    data class IssueResult(
        val ok: Boolean,
        val contract: Contract? = null,
        val message: String = "",
        val cloudError: String = ""
    )

    private val dateFmt = SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US)

    /** Server is always called by the app; the local row is the source of truth for ids. */
    private suspend fun contractNumberFor(enrollment: Enrollment): String {
        if (enrollment.contractNumber.isNotBlank()) return enrollment.contractNumber
        val sequence = dao.getAllContractsList().size + 1
        val year = PersianDateUtil.currentJalali().year
        return "CN-$year-${sequence.toString().padStart(4, '0')}"
    }

    /**
     * Returns the single contract of the enrollment, creating it locally when
     * missing. Nothing is sent to Cloudflare from here.
     */
    suspend fun localContract(enrollment: Enrollment): Contract = withContext(Dispatchers.IO) {
        lock.withLock {
            dao.getByEnrollment(enrollment.id) ?: run {
                val number = contractNumberFor(enrollment)
                val updatedEnrollment = enrollment.copy(contractNumber = number)
                if (enrollment.contractNumber != number) {
                    db.enrollmentDao().updateEnrollment(updatedEnrollment)
                }
                val newContract = Contract(
                    enrollmentId = enrollment.id,
                    studentId = enrollment.studentId,
                    studentName = enrollment.studentName,
                    courseId = enrollment.courseId,
                    courseTitle = enrollment.courseTitle,
                    status = "DRAFT",
                    createdAt = System.currentTimeMillis()
                )
                dao.insertContract(newContract)
                // insert(IGNORE) returns -1 when another caller won the race
                dao.getByEnrollment(enrollment.id) ?: newContract
            }
        }
    }

    /** Builds the unsigned PDF and records its hash on the contract row. */
    suspend fun ensureLocalPdf(enrollment: Enrollment): File = withContext(Dispatchers.IO) {
        val contract = localContract(enrollment)
        val existing = contract.localPdfPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (existing != null && existing.exists() && existing.length() > 0 && contract.pdfSha256.isNotBlank()) {
            return@withContext existing
        }
        val file = buildUnsignedPdf(enrollment)
        val hash = SecureToken.fileSha256(file)
        dao.updateContract(
            contract.copy(
                localPdfPath = file.absolutePath,
                pdfSha256 = hash,
                updatedAt = System.currentTimeMillis()
            )
        )
        file
    }

    private suspend fun buildUnsignedPdf(enrollment: Enrollment): File {
        val student = db.studentDao().getStudentById(enrollment.studentId)
        val course = db.courseDao().getCourseById(enrollment.courseId)
        val settings = db.settingsDao().getSettingsDirect()
        val installments = db.installmentDao().getInstallmentsForEnrollmentDirect(enrollment.id).ifEmpty { db.installmentDao().getInstallmentsForStudentDirect(enrollment.studentId) }
        return PdfDocumentHelper.generateEnrollmentContractPdf(
            context = context,
            enrollment = enrollment,
            student = student,
            course = course,
            installments = installments,
            settings = settings
        )
    }

    /**
     * Uploads (or re-uses) the contract on the Worker and returns a fresh signing
     * link. Re-calling for an unsigned, unexpired contract returns the same link.
     */
    suspend fun issueLink(
        enrollment: Enrollment,
        validityDays: Long = 30,
        forceRenew: Boolean = false
    ): IssueResult = withContext(Dispatchers.IO) {
        val settings = db.settingsDao().getSettingsDirect()
        val client = ContractCloudClient.fromSettings(settings)
        val cfg = settings
        if (!client.isConfigured()) {
            return@withContext IssueResult(
                ok = false,
                message = "برای ساخت لینک امضا ابتدا آدرس سرور Cloudflare و کلید API را در تنظیمات وارد کنید."
            )
        }

        val contract = localContract(enrollment)
        if (contract.isSigned) {
            return@withContext IssueResult(false, contract, "این قرارداد قبلاً امضا شده است.")
        }

        val pdf = ensureLocalPdf(enrollment)
        val now = System.currentTimeMillis()
        val stillValid = !forceRenew && contract.signUrl.isNotBlank() &&
            contract.expiresAtEpoch > now && contract.publicToken.isNotBlank()

        if (stillValid) {
            return@withContext IssueResult(true, contract, "لینک قبلی هنوز معتبر است.")
        }

        // New token only when the old one is expired/absent: the URL changes,
        // the contract does not.
        val token = SecureToken.newToken()
        val hash = SecureToken.sha256Hex(token)
        val expires = now + TimeUnit.DAYS.toMillis(validityDays)

        val student = db.studentDao().getStudentById(enrollment.studentId)
        val payload = enrollmentPayload(enrollment, student?.phone.orEmpty(), student?.nationalCode.orEmpty())
        val studentObj = JSONObject()
            .put("fullName", student?.fullName ?: enrollment.studentName)
            .put("studentCode", student?.studentCode.orEmpty())
            .put("phone", student?.phone.orEmpty())
            .put("nationalCode", student?.nationalCode.orEmpty())

        val res = client.uploadContract(
            contractId = contract.id,
            enrollmentId = enrollment.id,
            tokenHash = hash,
            publicToken = token,
            existsRemotely = contract.remoteId.isNotBlank(),
            expiresAtEpoch = expires,
            studentId = enrollment.studentId,
            enrollment = JSONObject().put("info", payload).put("student", studentObj),
            pdf = pdf,
            forceRenew = forceRenew || contract.expiresAtEpoch > 0,
            sealFile = cfg?.sealImagePath?.takeIf { it.isNotBlank() }?.let(::File),
            signatureFile = cfg?.signatureImagePath?.takeIf { it.isNotBlank() }?.let(::File),
            signerName = cfg?.signerName.orEmpty(),
            signerTitle = cfg?.signerTitle.orEmpty()
        )

        if (!res.ok || res.data == null) {
            dao.updateContract(
                contract.copy(
                    status = "FAILED",
                    lastError = res.error,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext IssueResult(false, contract, "آپلود قرارداد روی سرور ناموفق بود: ${res.error}", res.error)
        }

        val data = res.data
        val remoteId = data.optString("id")
        val signUrl = data.optString("signUrl").ifBlank { "${settings?.telegramWorkerUrl.orEmpty().trimEnd('/')}/sign?s=$token" }
        val remoteStatus = data.optString("status", "ISSUED")
        val updated = contract.copy(
            status = if (remoteStatus == "OPENED") "OPENED" else "ISSUED",
            publicToken = token,
            publicTokenHash = hash,
            signUrl = signUrl,
            issuedAtEpoch = now,
            expiresAtEpoch = data.optLong("expiresAtEpoch", expires),
            remoteId = remoteId,
            remoteKey = data.optString("r2Key"),
            localPdfPath = pdf.absolutePath,
            lastError = "",
            updatedAt = now
        )
        dao.updateContract(updated)
        IssueResult(true, updated, "لینک امضا آماده است.")
    }

    /** Pulls the remote status; when signed, downloads the final PDF once. */
    suspend fun refresh(enrollment: Enrollment): Contract? = withContext(Dispatchers.IO) {
        val contract = dao.getByEnrollment(enrollment.id) ?: return@withContext null
        if (contract.remoteId.isBlank() || contract.isSigned) return@withContext contract
        val client = ContractCloudClient.fromSettings(db.settingsDao().getSettingsDirect())
        val res = client.getContract(contract.remoteId)
        if (!res.ok || res.data == null) return@withContext contract

        val data = res.data
        val remoteStatus = data.optString("status", contract.status)
        var updated = contract.copy(
            openedAtEpoch = if (data.optLong("openedAtEpoch", 0L) > 0) data.optLong("openedAtEpoch") else contract.openedAtEpoch,
            updatedAt = System.currentTimeMillis()
        )
        if (remoteStatus == "SIGNED") {
            val target = File(context.filesDir, "contracts_signed/Contract_${contract.id.take(8)}.pdf")
            val dl = client.downloadSignedPdf(contract.remoteId, target)
            updated = updated.copy(
                status = "SIGNED",
                signedAtEpoch = data.optLong("signedAtEpoch", System.currentTimeMillis()),
                signedAtShamsi = data.optString("signedAtShamsi").ifBlank {
                    PersianDateUtil.currentShamsiString()
                },
                localSignedPdfPath = if (dl.ok && dl.data != null) dl.data.absolutePath else "",
                lastError = if (dl.ok) "" else "دریافت PDF امضاشده ناموفق: ${dl.error}"
            )
        } else if (remoteStatus.isNotBlank() && remoteStatus != "EXPIRED") {
            updated = updated.copy(status = remoteStatus)
        } else if (remoteStatus == "EXPIRED") {
            updated = updated.copy(status = "EXPIRED")
        }
        dao.updateContract(updated)
        updated
    }

    suspend fun revoke(enrollment: Enrollment): IssueResult = withContext(Dispatchers.IO) {
        val contract = dao.getByEnrollment(enrollment.id)
            ?: return@withContext IssueResult(false, message = "قراردادی برای این ثبت‌نام وجود ندارد.")
        if (contract.isSigned) {
            return@withContext IssueResult(false, contract, "قرارداد امضاشده قابل لغو نیست.")
        }
        val client = ContractCloudClient.fromSettings(db.settingsDao().getSettingsDirect())
        if (contract.remoteId.isNotBlank()) client.revoke(contract.remoteId)
        val updated = contract.copy(status = "REVOKED", signUrl = "", updatedAt = System.currentTimeMillis())
        dao.updateContract(updated)
        IssueResult(true, updated, "لینک امضا لغو شد.")
    }

    private suspend fun enrollmentPayload(enrollment: Enrollment, phone: String, nationalCode: String): JSONObject {
        val installments = db.installmentDao().getInstallmentsForEnrollmentDirect(enrollment.id)
        val instArr = JSONArray()
        installments.sortedBy { it.installmentNumber }.forEach { i ->
            instArr.put(
                JSONObject()
                    .put("number", i.installmentNumber)
                    .put("amount", i.amount)
                    .put("paid", i.paidAmount)
                    .put("remaining", i.remainingAmount)
                    .put("dueDate", i.dueDateShamsi)
                    .put("status", if (i.isPaid) "PAID" else "OPEN")
            )
        }
        val settings = db.settingsDao().getSettingsDirect()
        return JSONObject()
            .put("contractNumber", enrollment.contractNumber)
            .put("studentName", enrollment.studentName)
            .put("courseTitle", enrollment.courseTitle)
            .put("startDate", enrollment.startDateShamsi)
            .put("endDate", enrollment.endDateShamsi)
            .put("totalFee", enrollment.totalFee)
            .put("discount", enrollment.discount)
            .put("finalFee", enrollment.finalFee)
            .put("installmentCount", enrollment.installmentCount)
            .put("registrationDate", enrollment.registrationDateShamsi)
            .put("currency", settings?.currencyUnit ?: "تومان")
            .put("academyName", settings?.academyName.orEmpty())
            .put("phone", phone)
            .put("nationalCode", nationalCode)
            .put("installments", instArr)
    }

    companion object {
        /** How long a signing link stays usable. */
        const val LINK_VALIDITY_DAYS = 30L
    }
}
