package com.example.data.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.AcademySettings
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.model.Installment
import com.example.data.model.Payment
import com.example.data.model.Student
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfDocumentHelper {

    private const val PAGE_WIDTH = 595 // Standard A4 width in PostScript points (72 dpi)
    private const val PAGE_HEIGHT = 842 // Standard A4 height in PostScript points

    /**
     * Generates a complete, printable, official PDF receipt for a payment.
     */
    fun generatePaymentReceiptPdf(
        context: Context,
        payment: Payment,
        installment: Installment? = null,
        student: Student? = null,
        course: Course? = null,
        settings: AcademySettings? = null
    ): File {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        val academyName = settings?.academyName?.ifEmpty { "آموزشگاه کامپیوتر پیشگامان" } ?: "آموزشگاه کامپیوتر پیشگامان"
        val academyPhone = settings?.phone ?: "۰۲۱-۸۸۸۸۹۹۹۹"
        val academyAddress = settings?.address ?: ""
        val currencyUnit = settings?.currencyUnit ?: "تومان"

        val methodTitle = when (payment.paymentMethod) {
            "TRANSFER" -> "کارت به کارت"
            "POS" -> "دستگاه پوز (کارتخوان)"
            "CASH" -> "نقدی"
            "BANK_DEPOSIT" -> "واریز بانکی"
            else -> "سایر روش‌ها"
        }

        // Background
        canvas.drawColor(Color.WHITE)

        // Outer Border & Card
        val borderPaint = Paint().apply {
            color = Color.rgb(226, 232, 240) // Slate 200
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val outerRect = RectF(24f, 24f, PAGE_WIDTH - 24f, PAGE_HEIGHT - 24f)
        canvas.drawRoundRect(outerRect, 16f, 16f, borderPaint)

        // Header Background Banner
        val headerPaint = Paint().apply {
            color = Color.rgb(238, 242, 255) // Indigo 50
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val headerRect = RectF(24f, 24f, PAGE_WIDTH - 24f, 120f)
        canvas.drawRoundRect(headerRect, 16f, 16f, headerPaint)
        // fill bottom curve of header banner
        canvas.drawRect(24f, 80f, PAGE_WIDTH - 24f, 120f, headerPaint)

        // Header Text Paints
        val brandPaint = Paint().apply {
            color = Color.rgb(79, 70, 229) // Indigo 600
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subHeaderPaint = Paint().apply {
            color = Color.rgb(71, 85, 105) // Slate 600
            textSize = 12f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val docTitlePaint = Paint().apply {
            color = Color.rgb(30, 41, 59) // Slate 800
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        // Draw Header Content
        canvas.drawText(academyName, PAGE_WIDTH / 2f, 55f, brandPaint)
        canvas.drawText("رسید رسمی دریافت وجه شهریه و اقساط", PAGE_WIDTH / 2f, 80f, docTitlePaint)
        canvas.drawText("تلفن: $academyPhone", PAGE_WIDTH / 2f, 102f, subHeaderPaint)

        // Receipt Meta Box (Date, Time, Receipt No)
        val metaBoxPaint = Paint().apply {
            color = Color.rgb(248, 250, 252) // Slate 50
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(40f, 132f, PAGE_WIDTH - 40f, 180f), 10f, 10f, metaBoxPaint)
        canvas.drawRoundRect(RectF(40f, 132f, PAGE_WIDTH - 40f, 180f), 10f, 10f, borderPaint)

        val metaTextPaint = Paint().apply {
            color = Color.rgb(51, 65, 85)
            textSize = 10.5f
            isAntiAlias = true
        }
        val metaTextBoldPaint = Paint().apply {
            color = Color.rgb(15, 23, 42)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        // Meta row
        drawRtlRow(canvas, "شماره رسید / تراکنش:", payment.transactionId, 48f, PAGE_WIDTH - 48f, 153f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "تاریخ و ساعت ثبت:", "${payment.paymentDateShamsi} - ${payment.paymentTime}", 48f, PAGE_WIDTH - 48f, 171f, metaTextPaint, metaTextBoldPaint)

        // Student & Course Info Section
        var currentY = 205f
        drawSectionHeader(canvas, "مشخصات هنرجو و دوره آموزشی", currentY)
        currentY += 20f

        val studentBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 75f)
        canvas.drawRoundRect(studentBox, 10f, 10f, metaBoxPaint)
        canvas.drawRoundRect(studentBox, 10f, 10f, borderPaint)

        val stName = payment.studentName.ifEmpty { "${student?.firstName ?: ""} ${student?.lastName ?: ""}".trim() }
        val stCode = student?.studentCode ?: "---"
        val stPhone = student?.phone ?: "---"
        val cTitle = course?.title ?: installment?.courseTitle ?: "دوره آموزشی"

        drawRtlRow(canvas, "نام و نام خانوادگی:", stName, 52f, PAGE_WIDTH - 52f, currentY + 22f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "کد هنرجویی / موبایل:", "$stCode | $stPhone", 52f, PAGE_WIDTH - 52f, currentY + 44f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "عنوان دوره ثبت‌نامی:", cTitle, 52f, PAGE_WIDTH - 52f, currentY + 66f, metaTextPaint, metaTextBoldPaint)

        // Financial & Installment Details Section
        currentY += 95f
        drawSectionHeader(canvas, "جزئیات مالی و وضعیت قسط", currentY)
        currentY += 20f

        val finBoxHeight = 150f
        val finBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + finBoxHeight)
        canvas.drawRoundRect(finBox, 10f, 10f, metaBoxPaint)
        canvas.drawRoundRect(finBox, 10f, 10f, borderPaint)

        val instNumberStr = "قسط شماره ${PersianDateUtil.toPersianDigits(payment.installmentNumber.toString())}"
        val paidAmountStr = PersianDateUtil.formatCurrency(payment.amount, currencyUnit)
        val instTotalStr = if (installment != null) PersianDateUtil.formatCurrency(installment.amount, currencyUnit) else "---"
        val instRemainingStr = if (installment != null) PersianDateUtil.formatCurrency(installment.remainingAmount, currencyUnit) else "---"

        val highlightPaint = Paint().apply {
            color = Color.rgb(16, 185, 129) // Emerald 600
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        drawRtlRow(canvas, "مورد پرداخت:", instNumberStr, 52f, PAGE_WIDTH - 52f, currentY + 24f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "مبلغ واریزی این رسید:", paidAmountStr, 52f, PAGE_WIDTH - 52f, currentY + 48f, metaTextPaint, highlightPaint)
        drawRtlRow(canvas, "مبلغ کل این قسط:", instTotalStr, 52f, PAGE_WIDTH - 52f, currentY + 72f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "مانده بدهی این قسط:", instRemainingStr, 52f, PAGE_WIDTH - 52f, currentY + 96f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "روش پرداخت:", methodTitle, 52f, PAGE_WIDTH - 52f, currentY + 120f, metaTextPaint, metaTextBoldPaint)

        val destAcc = if (payment.destinationAccountTitle.isNotBlank()) payment.destinationAccountTitle else "صندوق/حساب اصلی آموزشگاه"
        drawRtlRow(canvas, "حساب / کارت مقصد:", destAcc, 52f, PAGE_WIDTH - 52f, currentY + 142f, metaTextPaint, metaTextBoldPaint)

        // Tracking & Notes Section
        currentY += finBoxHeight + 20f
        if (payment.trackingNumber.isNotBlank() || payment.notes.isNotBlank()) {
            drawSectionHeader(canvas, "توضیحات و شناسه پیگیری", currentY)
            currentY += 18f
            val noteBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 45f)
            canvas.drawRoundRect(noteBox, 10f, 10f, metaBoxPaint)
            canvas.drawRoundRect(noteBox, 10f, 10f, borderPaint)

            if (payment.trackingNumber.isNotBlank()) {
                drawRtlRow(canvas, "کد رهگیری بانکی:", payment.trackingNumber, 52f, PAGE_WIDTH - 52f, currentY + 20f, metaTextPaint, metaTextBoldPaint)
            }
            if (payment.notes.isNotBlank()) {
                drawRtlRow(canvas, "توضیحات تکمیلی:", payment.notes, 52f, PAGE_WIDTH - 52f, currentY + 38f, metaTextPaint, metaTextPaint)
            }
            currentY += 60f
        } else {
            currentY += 10f
        }

        // Terms and Info Note
        val dashedPaint = Paint().apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
            isAntiAlias = true
        }
        canvas.drawLine(40f, currentY, PAGE_WIDTH - 40f, currentY, dashedPaint)
        currentY += 18f

        val noticePaint = Paint().apply {
            color = Color.rgb(100, 116, 139)
            textSize = 9.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText("• این رسید به صورت سیستمی صادر شده و معتبر می‌باشد.", PAGE_WIDTH - 44f, currentY, noticePaint)
        canvas.drawText("• لطفا رسیدهای پرداختی خود را تا پایان دوره و صدور گواهینامه نزد خود نگه دارید.", PAGE_WIDTH - 44f, currentY + 15f, noticePaint)

        // Signatures and Stamp Box
        currentY += 45f
        val stampBoxWidth = 220f
        val stampBoxHeight = 85f

        // Academy Stamp
        val academyStampRect = RectF(44f, currentY, 44f + stampBoxWidth, currentY + stampBoxHeight)
        canvas.drawRoundRect(academyStampRect, 10f, 10f, metaBoxPaint)
        canvas.drawRoundRect(academyStampRect, 10f, 10f, borderPaint)

        val stampTitlePaint = Paint().apply {
            color = Color.rgb(71, 85, 105)
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("محل مهر و امضای امور مالی آموزشگاه", 44f + stampBoxWidth / 2f, currentY + 22f, stampTitlePaint)

        // Student Signature
        val studentStampRect = RectF(PAGE_WIDTH - 44f - stampBoxWidth, currentY, PAGE_WIDTH - 44f, currentY + stampBoxHeight)
        canvas.drawRoundRect(studentStampRect, 10f, 10f, metaBoxPaint)
        canvas.drawRoundRect(studentStampRect, 10f, 10f, borderPaint)
        canvas.drawText("امضا و تایید هنرجو / پرداخت‌کننده", PAGE_WIDTH - 44f - stampBoxWidth / 2f, currentY + 22f, stampTitlePaint)

        // Footer Address
        if (academyAddress.isNotBlank()) {
            val footerPaint = Paint().apply {
                color = Color.rgb(148, 163, 184)
                textSize = 9f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("نشانی: $academyAddress", PAGE_WIDTH / 2f, PAGE_HEIGHT - 35f, footerPaint)
        }

        pdfDoc.finishPage(page)

        // Write to cache file
        val outputDir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val outputFile = File(outputDir, "Receipt_${payment.transactionId}.pdf")
        FileOutputStream(outputFile).use { out ->
            pdfDoc.writeTo(out)
        }
        pdfDoc.close()

        return outputFile
    }

    /**
     * Generates a complete, printable, official PDF enrollment sheet / contract.
     */
    fun generateEnrollmentContractPdf(
        context: Context,
        enrollment: Enrollment,
        student: Student? = null,
        course: Course? = null,
        installments: List<Installment>,
        settings: AcademySettings? = null
    ): File {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        val academyName = settings?.academyName?.ifEmpty { "آموزشگاه کامپیوتر پیشگامان" } ?: "آموزشگاه کامپیوتر پیشگامان"
        val academyPhone = settings?.phone ?: "۰۲۱-۸۸۸۸۹۹۹۹"
        val academyAddress = settings?.address ?: ""
        val currencyUnit = settings?.currencyUnit ?: "تومان"

        canvas.drawColor(Color.WHITE)

        // Outer Border
        val borderPaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val outerRect = RectF(24f, 24f, PAGE_WIDTH - 24f, PAGE_HEIGHT - 24f)
        canvas.drawRoundRect(outerRect, 16f, 16f, borderPaint)

        // Header Background Banner
        val headerPaint = Paint().apply {
            color = Color.rgb(240, 253, 250) // Teal 50
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val headerRect = RectF(24f, 24f, PAGE_WIDTH - 24f, 115f)
        canvas.drawRoundRect(headerRect, 16f, 16f, headerPaint)
        canvas.drawRect(24f, 75f, PAGE_WIDTH - 24f, 115f, headerPaint)

        val brandPaint = Paint().apply {
            color = Color.rgb(13, 148, 136) // Teal 600
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val docTitlePaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subHeaderPaint = Paint().apply {
            color = Color.rgb(71, 85, 105)
            textSize = 11f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        canvas.drawText(academyName, PAGE_WIDTH / 2f, 52f, brandPaint)
        canvas.drawText("برگه قرارداد و ثبت‌نام دوره آموزشی", PAGE_WIDTH / 2f, 77f, docTitlePaint)
        canvas.drawText("شماره ثبت‌نام: ${enrollment.id.take(8).uppercase()} | تاریخ: ${enrollment.registrationDateShamsi}", PAGE_WIDTH / 2f, 98f, subHeaderPaint)

        val metaBoxPaint = Paint().apply {
            color = Color.rgb(248, 250, 252)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val metaTextPaint = Paint().apply {
            color = Color.rgb(51, 65, 85)
            textSize = 10f
            isAntiAlias = true
        }
        val metaTextBoldPaint = Paint().apply {
            color = Color.rgb(15, 23, 42)
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        // Student Info Box
        var currentY = 130f
        drawSectionHeader(canvas, "۱. مشخصات هنرجو", currentY)
        currentY += 18f

        val studentBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 60f)
        canvas.drawRoundRect(studentBox, 8f, 8f, metaBoxPaint)
        canvas.drawRoundRect(studentBox, 8f, 8f, borderPaint)

        val stFullName = enrollment.studentName.ifEmpty { "${student?.firstName ?: ""} ${student?.lastName ?: ""}".trim() }
        val stCode = student?.studentCode ?: "---"
        val stNatCode = student?.nationalCode ?: "---"
        val stPhone = student?.phone ?: "---"

        drawRtlRow(canvas, "نام و نام خانوادگی:", stFullName, 50f, PAGE_WIDTH - 50f, currentY + 20f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "کد هنرجویی / کدملی:", "$stCode | $stNatCode", 50f, PAGE_WIDTH - 50f, currentY + 38f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "شماره تماس:", stPhone, 50f, PAGE_WIDTH - 50f, currentY + 54f, metaTextPaint, metaTextBoldPaint)

        // Course Info Box
        currentY += 75f
        drawSectionHeader(canvas, "۲. مشخصات دوره آموزشی", currentY)
        currentY += 18f

        val courseBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 60f)
        canvas.drawRoundRect(courseBox, 8f, 8f, metaBoxPaint)
        canvas.drawRoundRect(courseBox, 8f, 8f, borderPaint)

        val cTitle = enrollment.courseTitle.ifEmpty { course?.title ?: "دوره تخصصی" }
        val cStart = enrollment.startDateShamsi.ifEmpty { "---" }
        val cEnd = enrollment.endDateShamsi.ifEmpty { "طبق تقویم آموزشی" }
        val cDuration = course?.durationMonths?.let { "$it ماه" } ?: "---"

        drawRtlRow(canvas, "عنوان دوره:", cTitle, 50f, PAGE_WIDTH - 50f, currentY + 20f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "تاریخ شروع / پایان:", "$cStart الی $cEnd", 50f, PAGE_WIDTH - 50f, currentY + 38f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "مدت آموزش:", cDuration, 50f, PAGE_WIDTH - 50f, currentY + 54f, metaTextPaint, metaTextBoldPaint)

        // Tuition and Discount Box
        currentY += 75f
        drawSectionHeader(canvas, "۳. شهریه و شرایط مالی", currentY)
        currentY += 18f

        val finBox = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 65f)
        canvas.drawRoundRect(finBox, 8f, 8f, metaBoxPaint)
        canvas.drawRoundRect(finBox, 8f, 8f, borderPaint)

        val baseFeeStr = PersianDateUtil.formatCurrency(enrollment.totalFee, currencyUnit)
        val discountStr = if (enrollment.discount > 0) PersianDateUtil.formatCurrency(enrollment.discount, currencyUnit) else "بدون تخفیف"
        val finalFeeStr = PersianDateUtil.formatCurrency(enrollment.finalFee, currencyUnit)
        val instCountStr = "${PersianDateUtil.toPersianDigits(enrollment.installmentCount.toString())} قسط"

        drawRtlRow(canvas, "شهریه مصوب دوره:", baseFeeStr, 50f, PAGE_WIDTH - 50f, currentY + 20f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "تخفیف اعطایی:", discountStr, 50f, PAGE_WIDTH - 50f, currentY + 38f, metaTextPaint, metaTextBoldPaint)
        drawRtlRow(canvas, "شهریه نهایی و تعداد اقساط:", "$finalFeeStr ($instCountStr)", 50f, PAGE_WIDTH - 50f, currentY + 56f, metaTextPaint, metaTextBoldPaint)

        // Installment Schedule Table
        currentY += 80f
        drawSectionHeader(canvas, "۴. جدول زمان‌بندی اقساط شهریه", currentY)
        currentY += 16f

        // Table Header
        val tableHeaderPaint = Paint().apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val thRect = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + 22f)
        canvas.drawRect(thRect, tableHeaderPaint)
        canvas.drawRect(thRect, borderPaint)

        val thTextPaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        // Columns: شماره قسط (40..110), مبلغ (110..220), سررسید (220..330), پرداختی (330..430), وضعیت (430..PAGE_WIDTH-40)
        canvas.drawText("شماره قسط", PAGE_WIDTH - 75f, currentY + 15f, thTextPaint)
        canvas.drawText("مبلغ قسط ($currencyUnit)", PAGE_WIDTH - 170f, currentY + 15f, thTextPaint)
        canvas.drawText("تاریخ سررسید", PAGE_WIDTH - 275f, currentY + 15f, thTextPaint)
        canvas.drawText("مبلغ پرداخت‌شده", PAGE_WIDTH - 380f, currentY + 15f, thTextPaint)
        canvas.drawText("وضعیت تسویه", PAGE_WIDTH - 485f, currentY + 15f, thTextPaint)

        currentY += 22f
        val sortedInstallments = installments.sortedBy { it.installmentNumber }
        val cellTextPaint = Paint().apply {
            color = Color.rgb(51, 65, 85)
            textSize = 9f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val rowHeight = 20f
        sortedInstallments.take(8).forEachIndexed { index, inst ->
            val rowRect = RectF(40f, currentY, PAGE_WIDTH - 40f, currentY + rowHeight)
            if (index % 2 == 1) {
                val zebraPaint = Paint().apply { color = Color.rgb(248, 250, 252); style = Paint.Style.FILL }
                canvas.drawRect(rowRect, zebraPaint)
            }
            canvas.drawRect(rowRect, borderPaint)

            val numStr = "قسط ${PersianDateUtil.toPersianDigits(inst.installmentNumber.toString())}"
            val amtStr = PersianDateUtil.toPersianDigits(PersianDateUtil.formatCurrency(inst.amount, ""))
            val dueStr = inst.dueDateShamsi
            val paidStr = PersianDateUtil.toPersianDigits(PersianDateUtil.formatCurrency(inst.paidAmount, ""))
            val statusStr = when (inst.status) {
                "PAID" -> "تسویه شده"
                "PARTIAL_PAID" -> "پرداخت ناقص"
                "OVERDUE" -> "معوقه"
                "CANCELLED" -> "لغو شده"
                else -> "در انتظار پرداخت"
            }

            canvas.drawText(numStr, PAGE_WIDTH - 75f, currentY + 14f, cellTextPaint)
            canvas.drawText(amtStr, PAGE_WIDTH - 170f, currentY + 14f, cellTextPaint)
            canvas.drawText(dueStr, PAGE_WIDTH - 275f, currentY + 14f, cellTextPaint)
            canvas.drawText(paidStr, PAGE_WIDTH - 380f, currentY + 14f, cellTextPaint)
            canvas.drawText(statusStr, PAGE_WIDTH - 485f, currentY + 14f, cellTextPaint)

            currentY += rowHeight
        }

        // Terms and Conditions Section
        currentY += 16f
        val termsPaint = Paint().apply {
            color = Color.rgb(100, 116, 139)
            textSize = 8.5f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText("مقررات: هنرجو متعهد می‌گردد اقساط فوق را در مواعد مقرر واریز نماید. انصراف پس از شروع کلاس تابع ضوابط انصرافی آموزشگاه می‌باشد.", PAGE_WIDTH - 44f, currentY, termsPaint)
        canvas.drawText("این برگه به عنوان قرارداد آموزشی بین هنرجو و آموزشگاه معتبر بوده و یک نسخه نزد طرفین نگهداری می‌شود.", PAGE_WIDTH - 44f, currentY + 13f, termsPaint)

        // Signatures
        currentY += 35f
        val stampBoxWidth = 210f
        val stampBoxHeight = 70f

        val academyStampRect = RectF(44f, currentY, 44f + stampBoxWidth, currentY + stampBoxHeight)
        canvas.drawRoundRect(academyStampRect, 8f, 8f, metaBoxPaint)
        canvas.drawRoundRect(academyStampRect, 8f, 8f, borderPaint)

        val stampTitlePaint = Paint().apply {
            color = Color.rgb(71, 85, 105)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("محل مهر و امضای مدیریت آموزشگاه", 44f + stampBoxWidth / 2f, currentY + 20f, stampTitlePaint)

        val studentStampRect = RectF(PAGE_WIDTH - 44f - stampBoxWidth, currentY, PAGE_WIDTH - 44f, currentY + stampBoxHeight)
        canvas.drawRoundRect(studentStampRect, 8f, 8f, metaBoxPaint)
        canvas.drawRoundRect(studentStampRect, 8f, 8f, borderPaint)
        canvas.drawText("محل امضا و اثر انگشت هنرجو", PAGE_WIDTH - 44f - stampBoxWidth / 2f, currentY + 20f, stampTitlePaint)

        // Footer Address
        if (academyAddress.isNotBlank()) {
            val footerPaint = Paint().apply {
                color = Color.rgb(148, 163, 184)
                textSize = 8.5f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("نشانی آموزشگاه: $academyAddress | تلفن: $academyPhone", PAGE_WIDTH / 2f, PAGE_HEIGHT - 32f, footerPaint)
        }

        pdfDoc.finishPage(page)

        val outputDir = File(context.cacheDir, "contracts").apply { mkdirs() }
        val outputFile = File(outputDir, "Contract_${enrollment.id.take(8)}.pdf")
        FileOutputStream(outputFile).use { out ->
            pdfDoc.writeTo(out)
        }
        pdfDoc.close()

        return outputFile
    }

    /**
     * Shares the generated PDF file via standard Android Intent
     */
    fun sharePdfFile(context: Context, pdfFile: File, title: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(intent, "ارسال و اشتراک‌گذاری $title"))
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در اشتراک‌گذاری فایل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Prints or opens the PDF using system PrintManager or View Intent
     */
    fun printOrOpenPdf(context: Context, pdfFile: File, jobName: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager != null) {
                val printAdapter = object : android.print.PrintDocumentAdapter() {
                    override fun onLayout(
                        oldAttributes: PrintAttributes?,
                        newAttributes: PrintAttributes?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: LayoutResultCallback?,
                        extras: android.os.Bundle?
                    ) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback?.onLayoutCancelled()
                            return
                        }
                        val info = android.print.PrintDocumentInfo.Builder("$jobName.pdf")
                            .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(1)
                            .build()
                        callback?.onLayoutFinished(info, true)
                    }

                    override fun onWrite(
                        pages: Array<out android.print.PageRange>?,
                        destination: android.os.ParcelFileDescriptor?,
                        cancellationSignal: android.os.CancellationSignal?,
                        callback: WriteResultCallback?
                    ) {
                        try {
                            FileInputStream(pdfFile).use { input ->
                                FileOutputStream(destination?.fileDescriptor).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                        } catch (e: Exception) {
                            callback?.onWriteFailed(e.message)
                        }
                    }
                }

                val attributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf_res", "PDF Resolution", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()

                printManager.print(jobName, printAdapter, attributes)
            } else {
                // Fallback to view intent
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(viewIntent, "مشاهده و چاپ فایل PDF"))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "خطا در چاپ PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves a copy of the PDF into the Downloads directory or external public storage
     */
    fun savePdfToDownloads(context: Context, pdfFile: File, destinationFileName: String): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val destFile = File(downloadsDir, destinationFileName)
            FileInputStream(pdfFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "فایل با موفقیت در پوشه Downloads ذخیره شد.", Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "فایل در حافظه موقت برنامه ذخیره شد.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun drawSectionHeader(canvas: Canvas, title: String, y: Float) {
        val paint = Paint().apply {
            color = Color.rgb(15, 23, 42) // Slate 900
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText(title, PAGE_WIDTH - 40f, y, paint)
    }

    private fun drawRtlRow(
        canvas: Canvas,
        label: String,
        value: String,
        leftX: Float,
        rightX: Float,
        y: Float,
        labelPaint: Paint,
        valuePaint: Paint
    ) {
        val rtlLabelPaint = Paint(labelPaint).apply { textAlign = Paint.Align.RIGHT }
        val ltrValuePaint = Paint(valuePaint).apply { textAlign = Paint.Align.LEFT }

        canvas.drawText(label, rightX, y, rtlLabelPaint)
        canvas.drawText(value, leftX, y, ltrValuePaint)
    }
}
