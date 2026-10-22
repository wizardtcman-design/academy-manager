package com.example.data.util

import com.example.data.model.Installment
import com.example.data.model.Payment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 4: pure payload/mapping logic for the payment sync (no Android
 * dependencies) so the JVM unit tests can verify the exact contract the
 * Worker implements:
 *
 *   - a payment is pushed VERBATIM (the app's own receipt record);
 *   - the confirmed-online pull maps each order to a Payment the app
 *     stores exactly once (idempotent by orderId);
 *   - the installment update mirrors the repository's own math
 *     (sum of non-reversed payments -> paid/remaining/status).
 */
object PaymentSyncPayloads {

    /** The app's own payment record, verbatim — the receipt IS this record. */
    fun paymentJson(p: Payment): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("transactionId", p.transactionId)
        put("paymentCode", p.paymentCode)
        put("studentId", p.studentId)
        put("studentName", p.studentName)
        put("enrollmentId", p.enrollmentId)
        put("installmentId", p.installmentId)
        put("installmentNumber", p.installmentNumber)
        put("amount", p.amount)
        put("paymentDateShamsi", p.paymentDateShamsi)
        put("paymentDateEpoch", p.paymentDateEpoch)
        put("paymentTime", p.paymentTime)
        put("paymentMethod", p.paymentMethod)
        put("destinationAccountTitle", p.destinationAccountTitle)
        put("trackingNumber", p.trackingNumber)
        put("notes", p.notes)
        put("isReversed", p.isReversed)
        put("reversalReason", p.reversalReason)
        put("recordedBy", p.recordedBy)
    }

    fun paymentsJson(payments: List<Payment>): JSONObject =
        JSONObject().put("payments", JSONArray().apply { payments.forEach { put(paymentJson(it)) } })

    fun installmentJson(i: Installment): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("student_id", i.studentId)
        put("installment_number", i.installmentNumber)
        put("total_installments", i.totalInstallments)
        put("course_title", i.courseTitle)
        put("amount", i.amount)
        put("paid_amount", i.paidAmount)
        put("remaining_amount", i.remainingAmount)
        put("due_date_shamsi", i.dueDateShamsi)
        put("due_date_epoch", i.dueDateEpoch)
        put("is_paid", if (i.isPaid) 1 else 0)
        put("paid_date_shamsi", i.fullPaidDateShamsi)
        put("payment_method", i.paymentMethod)
        put("updated_at_epoch", System.currentTimeMillis())
    }

    /**
     * Maps one confirmed online order into a local Payment.
     * Returns null when the order was already pulled (idempotent, no duplicate
     * transactions or receipts anywhere).
     */
    fun onlinePaymentFromTxn(
        orderId: String,
        studentId: String,
        installmentId: String,
        amount: Long,
        ref: String,
        verifiedAtEpoch: Long,
        existingPaymentIds: Set<String>,
        paymentMethod: String = "ONLINE",
        source: String = "online"
    ): Payment? {
        if (orderId.isBlank() || amount <= 0) return null
        if (orderId in existingPaymentIds) return null
        val fromClaim = source == "claim"
        return Payment(
            id = orderId, // the payment id IS the receipt id: one receipt per payment everywhere
            transactionId = if (fromClaim) "CLM-" + orderId.take(12).uppercase() else "ONLINE-" + orderId.take(8).uppercase(),
            paymentCode = if (fromClaim) "CLM-" + orderId.take(12).uppercase() else "ONLINE-" + orderId.take(8).uppercase(),
            studentId = studentId,
            enrollmentId = "",
            installmentId = installmentId,
            amount = amount,
            paymentDateEpoch = verifiedAtEpoch,
            paymentTime = "",
            paymentMethod = paymentMethod,
            destinationAccountTitle = if (fromClaim) "کارت به کارت (تأیید مدیریت)" else "پرداخت آنلاین",
            trackingNumber = ref,
            notes = if (fromClaim) "رسید ارسالی هنرجو — تأییدشده توسط مدیریت" else "پرداخت آنلاین تأییدشده توسط درگاه",
            recordedBy = if (fromClaim) "مدیر آموزشگاه (تلگرام)" else "درگاه پرداخت"
        )
    }

    /**
     * Recomputes an installment after applying one more payment, using the
     * SAME rule as AcademyRepository.recordPayment (non-reversed sum).
     */
    fun installmentAfterPayment(
        installment: Installment,
        allInstallmentPayments: List<Payment>
    ): Installment {
        val paidTotal = allInstallmentPayments.filter { !it.isReversed }.sumOf { it.amount }
        val remaining = (installment.amount - paidTotal).coerceAtLeast(0L)
        val status = when {
            paidTotal >= installment.amount -> "PAID"
            paidTotal > 0 -> "PARTIAL_PAID"
            else -> "PENDING"
        }
        return installment.copy(
            paidAmount = paidTotal,
            remainingAmount = remaining,
            status = status,
            fullPaidDateShamsi = if (status == "PAID" && installment.fullPaidDateShamsi.isBlank()) {
                PersianDateUtil.currentShamsiString()
            } else installment.fullPaidDateShamsi,
            fullPaidDateEpoch = if (status == "PAID" && installment.fullPaidDateEpoch == 0L) {
                System.currentTimeMillis()
            } else installment.fullPaidDateEpoch
        )
    }
}
