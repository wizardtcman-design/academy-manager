package com.example.data.util

import com.example.data.model.Installment
import com.example.data.model.Payment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4: the payment-sync contract between the app and the Worker.
 * Pure JVM (org.json from testImplementation) — same pattern as WorkerBotApiTest.
 */
class PaymentSyncPayloadsTest {

    private fun payment(over: Payment.() -> Payment = { this }) = Payment(
        id = "pay-1",
        transactionId = "TRX-1404-0001",
        studentId = "stu-1",
        studentName = "دانا محمدی",
        installmentId = "ins-2",
        installmentNumber = 2,
        amount = 1_000_000L,
        paymentDateShamsi = "۱۴۰۴/۰۵/۱۲",
        paymentTime = "14:30",
        paymentMethod = "TRANSFER",
        destinationAccountTitle = "حساب پیش‌فرض آموزشگاه",
        trackingNumber = "TRK-99881"
    ).over()

    // ---------- push payload ----------

    @Test
    fun `payment payload carries the app receipt record verbatim`() {
        val json = PaymentSyncPayloads.paymentsJson(listOf(payment()))
        val arr = json.getJSONArray("payments")
        assertEquals(1, arr.length())
        val p = arr.getJSONObject(0)
        assertEquals("pay-1", p.getString("id"))
        assertEquals("TRX-1404-0001", p.getString("transactionId"))
        assertEquals("stu-1", p.getString("studentId"))
        assertEquals("ins-2", p.getString("installmentId"))
        assertEquals(2, p.getInt("installmentNumber"))
        assertEquals(1_000_000L, p.getLong("amount"))
        assertEquals("۱۴۰۴/۰۵/۱۲", p.getString("paymentDateShamsi"))
        assertEquals("TRANSFER", p.getString("paymentMethod"))
        assertEquals("TRK-99881", p.getString("trackingNumber"))
        assertFalse(p.getBoolean("isReversed"))
    }

    @Test
    fun `reversal state travels with the same payment id`() {
        val json = PaymentSyncPayloads.paymentsJson(listOf(payment { copy(isReversed = true, reversalReason = "برگشت وجه") }))
        val p = json.getJSONArray("payments").getJSONObject(0)
        assertTrue(p.getBoolean("isReversed"))
        assertEquals("برگشت وجه", p.getString("reversalReason"))
        // same id: the worker keys the receipt by payment id, never creates a second one
        assertEquals("pay-1", p.getString("id"))
    }

    @Test
    fun `installment payload carries the manager payment method`() {
        val inst = Installment(
            id = "ins-2", studentId = "stu-1", installmentNumber = 2, totalInstallments = 3,
            courseTitle = "ICDL", amount = 1_500_000L, paidAmount = 0L, remainingAmount = 1_500_000L,
            dueDateShamsi = "۱۴۰۴/۰۸/۰۱", paymentMethod = "ONLINE"
        )
        val json = PaymentSyncPayloads.installmentJson(inst)
        assertEquals("ONLINE", json.getString("payment_method"))
        assertEquals(0, json.getInt("is_paid"))
        assertEquals(1_500_000L, json.getLong("remaining_amount"))
        assertEquals("ICDL", json.getString("course_title"))
    }

    // ---------- confirmed-online pull ----------

    @Test
    fun `confirmed online order maps to a local payment with ONLINE method and ref`() {
        val p = PaymentSyncPayloads.onlinePaymentFromTxn(
            orderId = "a1b2c3d4-e5f6-4789-9abc-def012345678",
            studentId = "stu-1",
            installmentId = "ins-2",
            amount = 1_500_000L,
            ref = "REF-OK-1",
            verifiedAtEpoch = 1_754_000_000_000,
            existingPaymentIds = emptySet()
        )
        assertNotNull(p)
        assertEquals("a1b2c3d4-e5f6-4789-9abc-def012345678", p!!.id) // orderId IS the payment id
        assertEquals("ONLINE", p.paymentMethod)
        assertEquals("REF-OK-1", p.trackingNumber)
        assertEquals(1_500_000L, p.amount)
        assertTrue(p.notes.contains("تأییدشده"))
    }

    @Test
    fun `pulling the same order twice never duplicates the payment`() {
        val first = PaymentSyncPayloads.onlinePaymentFromTxn(
            "order-1", "stu-1", "ins-2", 500_000L, "REF-1", 1L, existingPaymentIds = emptySet()
        )
        assertNotNull(first)
        val second = PaymentSyncPayloads.onlinePaymentFromTxn(
            "order-1", "stu-1", "ins-2", 500_000L, "REF-1", 1L, existingPaymentIds = setOf("order-1")
        )
        assertNull(second)
    }

    @Test
    fun `invalid orders are refused`() {
        assertNull(PaymentSyncPayloads.onlinePaymentFromTxn("", "stu-1", "ins-2", 100L, "", 1L, emptySet()))
        assertNull(PaymentSyncPayloads.onlinePaymentFromTxn("order-2", "stu-1", "ins-2", 0L, "", 1L, emptySet()))
    }

    // ---------- installment math (mirrors recordPayment) ----------

    @Test
    fun `installment becomes PAID only when the non-reversed sum covers it`() {
        val inst = Installment(id = "ins-1", amount = 2_000_000L, installmentNumber = 1)
        val partial = PaymentSyncPayloads.installmentAfterPayment(
            inst, listOf(payment { copy(id = "p1", installmentId = "ins-1", amount = 500_000L) })
        )
        assertEquals(500_000L, partial.paidAmount)
        assertEquals(1_500_000L, partial.remainingAmount)
        assertEquals("PARTIAL_PAID", partial.status)
        assertFalse(partial.isPaid)

        val paid = PaymentSyncPayloads.installmentAfterPayment(
            partial, listOf(
                payment { copy(id = "p1", installmentId = "ins-1", amount = 500_000L) },
                payment { copy(id = "p2", installmentId = "ins-1", amount = 1_500_000L) }
            )
        )
        assertEquals("PAID", paid.status)
        assertEquals(0L, paid.remainingAmount)
        assertTrue(paid.isPaid)
        assertTrue(paid.fullPaidDateShamsi.isNotBlank()) // real Shamsi date, never fabricated
    }

    @Test
    fun `reversed payments never count toward the installment`() {
        val inst = Installment(id = "ins-1", amount = 1_000_000L, installmentNumber = 1)
        val after = PaymentSyncPayloads.installmentAfterPayment(
            inst, listOf(
                payment { copy(id = "p1", installmentId = "ins-1", amount = 1_000_000L) },
                payment { copy(id = "p2", installmentId = "ins-1", amount = 1_000_000L, isReversed = true) }
            )
        )
        assertEquals("PAID", after.status)
        assertEquals(1_000_000L, after.paidAmount)
    }

    @Test
    fun `JSON escaping survives Persian text and quotes`() {
        val inst = Installment(id = "ins-9", courseTitle = "دوره «طراحی» وب", studentId = "stu-1")
        val json = PaymentSyncPayloads.installmentJson(inst)
        val back = JSONObject(json.toString())
        assertEquals("دوره «طراحی» وب", back.getString("course_title"))
    }

    // ---------- Stage 5: claim-sourced confirmed payments ----------

    @Test
    fun `claim-sourced payment maps to TRANSFER with manager provenance`() {
        val p = PaymentSyncPayloads.onlinePaymentFromTxn(
            orderId = "clm-abc12345-def",
            studentId = "stu-1",
            installmentId = "ins-2",
            amount = 750_000L,
            ref = "claim-abc",
            verifiedAtEpoch = 1_700_000_000_000L,
            existingPaymentIds = emptySet(),
            paymentMethod = "TRANSFER",
            source = "claim"
        )!!
        assertEquals("clm-abc12345-def", p.id) // the payment id IS the claim payment id (dedupe everywhere)
        assertEquals("TRANSFER", p.paymentMethod)
        assertTrue(p.notes.contains("مدیریت"))
        assertEquals("مدیر آموزشگاه (تلگرام)", p.recordedBy)
    }

    @Test
    fun `default pull stays ONLINE and dedupe by existing ids still applies`() {
        val online = PaymentSyncPayloads.onlinePaymentFromTxn(
            "order-9", "stu-1", "ins-2", 100L, "ref", 1L, emptySet()
        )!!
        assertEquals("ONLINE", online.paymentMethod)
        // existing id -> null (no duplicate transaction/receipt)
        assertNull(PaymentSyncPayloads.onlinePaymentFromTxn(
            "order-9", "stu-1", "ins-2", 100L, "ref", 1L, setOf("order-9"), "TRANSFER", "claim"
        ))
    }
}
