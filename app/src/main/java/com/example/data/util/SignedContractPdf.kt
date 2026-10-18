package com.example.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.model.AcademySettings
import com.example.data.model.Contract
import com.example.data.model.Enrollment
import com.example.data.model.Student
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Puts the student's finger signature + the academy seal on the ORIGINAL contract
 * PDF (same file, same pages) and writes one final copy. The result records the
 * SHA-256 of the original so the signed file is provably derived from it.
 */
object SignedContractPdf {

    data class Output(
        val file: File,
        val originalSha256: String,
        val signedSha256: String
    )

    fun render(
        context: Context,
        originalPdf: File,
        signaturePng: ByteArray,
        contract: Contract,
        enrollment: Enrollment,
        student: Student?,
        settings: AcademySettings?
    ): Output {
        val signature = BitmapFactory.decodeByteArray(signaturePng, 0, signaturePng.size)
            ?: throw IllegalArgumentException("فایل امضا خوانده نشد.")

        val parcel = android.os.ParcelFileDescriptor.open(
            originalPdf,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY
        )
        val reader = android.graphics.pdf.PdfRenderer(parcel)
        val outDoc = PdfDocument()
        try {
            for (index in 0 until reader.pageCount) {
                val page = reader.openPage(index)
                val info = PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                val outPage = outDoc.startPage(info)
                outPage.canvas.drawColor(Color.WHITE)
                val layer = android.graphics.Bitmap.createBitmap(
                    page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888
                )
                layer.eraseColor(Color.WHITE)
                page.render(layer, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                outPage.canvas.drawBitmap(layer, 0f, 0f, null)
                layer.recycle()
                if (index == reader.pageCount - 1) {
                    drawReceiptBlock(
                        outPage.canvas,
                        page.width,
                        page.height,
                        signature,
                        contract,
                        enrollment,
                        student,
                        settings
                    )
                }
                outDoc.finishPage(outPage)
                page.close()
            }
        } finally {
            reader.close()
        }

        val dir = File(context.filesDir, "contracts_signed").apply { mkdirs() }
        val target = File(dir, "Contract_${contract.id.take(8)}_signed.pdf")
        FileOutputStream(target).use { outDoc.writeTo(it) }
        outDoc.close()

        return Output(
            file = target,
            originalSha256 = if (contract.pdfSha256.isNotBlank()) contract.pdfSha256 else sha256(originalPdf),
            signedSha256 = sha256(target)
        )
    }

    private fun drawReceiptBlock(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        signature: Bitmap,
        contract: Contract,
        enrollment: Enrollment,
        student: Student?,
        settings: AcademySettings?
    ) {
        val k = canvas.density.toFloat().coerceAtLeast(1f)
        val margin = 40f
        val scale = pageWidth / (595f * k)
        val unit = k * scale

        val blockHeight = 190f * unit
        val top = pageHeight - blockHeight - (24f * unit)
        if (top < 0) return

        val border = Paint().apply {
            color = Color.rgb(148, 163, 184)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * unit
            isAntiAlias = true
        }
        val fill = Paint().apply { color = Color.rgb(248, 250, 252) }
        val rect = RectF(margin * unit, top, pageWidth - margin * unit, pageHeight - 24f * unit)
        canvas.drawRoundRect(rect, 8f * unit, 8f * unit, fill)
        canvas.drawRoundRect(rect, 8f * unit, 8f * unit, border)

        val titlePaint = Paint().apply {
            color = Color.rgb(13, 148, 136)
            textSize = 12f * unit
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.rgb(51, 65, 85)
            textSize = 8.5f * unit
            isAntiAlias = true
        }
        val smallPaint = Paint().apply {
            color = Color.rgb(100, 116, 139)
            textSize = 7f * unit
            isAntiAlias = true
        }

        val rtl = { text: String, x: Float, baseline: Float, paint: Paint ->
            val bidi = android.text.BidiFormatter.getInstance().unicodeWrap(
                text,
                android.text.TextDirectionHeuristics.RTL
            )
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(bidi, x, baseline, paint)
        }

        rtl(
            PersianDateUtil.toPersianDigits("رسید امضای دیجیتال — قرارداد ${enrollment.contractNumber.ifBlank { contract.id.take(8) }}"),
            rect.left + 12f * unit,
            top + 18f * unit,
            titlePaint
        )

        val verifiedPhone = PersianDateUtil.toLatinDigits(student?.phone.orEmpty())
        val verifiedNational = PersianDateUtil.toLatinDigits(student?.nationalCode.orEmpty())
        rtl(
            PersianDateUtil.toPersianDigits(
                "امضاکننده: ${student?.fullName ?: enrollment.studentName} · کد هنرجو: ${student?.studentCode.orEmpty()}"
            ),
            rect.right - 12f * unit,
            top + 36f * unit,
            bodyPaint
        )
        rtl(
            PersianDateUtil.toPersianDigits("زمان امضا: ${contract.signedAtShamsi}"),
            rect.right - 12f * unit,
            top + 50f * unit,
            bodyPaint
        )
        rtl(
            "شماره تماس و کد ملی واردشده با سوابق آموزشگاه تطبیق داده شد (تأیید غیررسمی، جایگزین احراز هویت رسمی نیست).",
            rect.right - 12f * unit,
            top + 64f * unit,
            smallPaint
        )
        rtl(
            "phone=${verifiedPhone.take(4)}***${verifiedPhone.takeLast(2)}  national=***${verifiedNational.takeLast(2)}",
            rect.left + 12f * unit,
            top + 64f * unit,
            smallPaint
        )

        // Signature image
        val sigBox = RectF(rect.left + 12f * unit, top + 72f * unit, rect.left + 150f * unit, top + 150f * unit)
        canvas.drawRoundRect(sigBox, 6f * unit, 6f * unit, border)
        val fitted = fitRect(sigBox, signature.width.toFloat(), signature.height.toFloat(), 6f * unit)
        canvas.drawBitmap(signature, null, fitted, Paint().apply { isFilterBitmap = true })

        // Academy seal
        val sealBox = RectF(rect.right - 150f * unit, top + 72f * unit, rect.right - 12f * unit, top + 150f * unit)
        canvas.drawRoundRect(sealBox, 6f * unit, 6f * unit, border)
        val seal = PdfDocumentHelper.loadImage(settings?.sealImagePath.orEmpty())
        val sig = PdfDocumentHelper.loadImage(settings?.signatureImagePath.orEmpty())
        var cursor = sealBox.left + 6f * unit
        val halfW = (sealBox.width() - 12f * unit) / 2f
        seal?.let {
            val slot = RectF(cursor, sealBox.top + 6f * unit, cursor + halfW, sealBox.bottom - 6f * unit)
            canvas.drawBitmap(it, null, fitRect(slot, it.width.toFloat(), it.height.toFloat()), Paint().apply { isFilterBitmap = true })
            cursor += halfW
        }
        sig?.let {
            val slot = RectF(cursor, sealBox.top + 18f * unit, cursor + halfW, sealBox.bottom - 6f * unit)
            canvas.drawBitmap(it, null, fitRect(slot, it.width.toFloat(), it.height.toFloat()), Paint().apply { isFilterBitmap = true })
        }

        val hashLine = "orig sha256: ${contract.pdfSha256.take(24)}…"
        rtl(hashLine, rect.left + 12f * unit, rect.bottom - 10f * unit, smallPaint)
        rtl(
            PersianDateUtil.toPersianDigits("امضای مسئول آموزشگاه: ${settings?.signerName.orEmpty()} ${settings?.signerTitle.orEmpty()}"),
            rect.right - 12f * unit,
            rect.bottom - 10f * unit,
            smallPaint
        )
    }

    private fun fitRect(box: RectF, w: Float, h: Float, padding: Float = 0f): RectF {
        val innerW = (box.width() - padding * 2).coerceAtLeast(1f)
        val innerH = (box.height() - padding * 2).coerceAtLeast(1f)
        val scale = minOf(innerW / w, innerH / h)
        val nw = w * scale
        val nh = h * scale
        val cx = box.centerX()
        val cy = box.centerY()
        return RectF(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }
}
