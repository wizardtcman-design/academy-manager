package com.example

import com.example.service.contract.ContractCloudClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * A stored contract is only accepted when it is a COMPLETE PDF whose SHA-256
 * equals the one recorded when it was signed. Everything else must be refused;
 * a cut-off download can never be saved as a valid contract.
 */
class ContractDownloadVerifyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun completePdf(): ByteArray {
        val body = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n"
        val pad = ByteArray(3000) { (it % 251).toByte() }
        return body.toByteArray(Charsets.ISO_8859_1) + pad + "\nstartxref\n0\n%%EOF\n".toByteArray()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun completePdfWithMatchingHashIsAccepted() {
        val bytes = completePdf()
        val file = write("ok.pdf", bytes)
        assertNull(ContractCloudClient.verifyDownload(file, sha(bytes)))
    }

    @Test
    fun truncatedPdfIsRejected() {
        val bytes = completePdf()
        val file = write("cut.pdf", bytes.copyOf(bytes.size / 2))
        val problem = ContractCloudClient.verifyDownload(file, sha(bytes))
        assertTrue(problem != null && problem.contains("ناقص"))
    }

    @Test
    fun corruptedPdfIsRejectedByHash() {
        val bytes = completePdf()
        val file = write("bad.pdf", bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertEquals("فایل با نسخه ثبت‌شده مطابقت ندارد", ContractCloudClient.verifyDownload(file, sha(bytes)))
    }

    @Test
    fun emptyFileIsRejected() {
        val file = write("empty.pdf", ByteArray(0))
        assertEquals("فایل خالی است", ContractCloudClient.verifyDownload(file, sha(completePdf())))
    }

    @Test
    fun pdfWithoutTrailerIsRejectedEvenWithoutHash() {
        val file = write("head.pdf", "%PDF-1.7 only".toByteArray())
        assertTrue(ContractCloudClient.verifyDownload(file, "").orEmpty().contains("ناقص"))
    }
}
