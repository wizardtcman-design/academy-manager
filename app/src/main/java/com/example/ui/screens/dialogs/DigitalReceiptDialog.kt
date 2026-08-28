package com.example.ui.screens.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AcademySettings
import com.example.data.model.Payment
import com.example.data.util.PdfDocumentHelper
import com.example.data.util.PersianDateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DigitalReceiptDialog(
    payment: Payment,
    settings: AcademySettings?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val academyName = settings?.academyName?.ifEmpty { "آموزشگاه کامپیوتر" } ?: "آموزشگاه کامپیوتر"
    val academyPhone = settings?.phone ?: ""

    val methodTitle = when (payment.paymentMethod) {
        "TRANSFER" -> "کارت به کارت"
        "POS" -> "دستگاه پوز (کارتخوان)"
        "CASH" -> "نقدی"
        "BANK_DEPOSIT" -> "واریز بانکی"
        else -> "سایر"
    }

    val receiptText = buildString {
        appendLine("═══════════════════════")
        appendLine("      رسید دریافت شهریه      ")
        appendLine("   $academyName   ")
        appendLine("═══════════════════════")
        appendLine("شماره تراکنش: ${payment.transactionId}")
        appendLine("تاریخ و ساعت: ${payment.paymentDateShamsi} - ${payment.paymentTime}")
        appendLine("هنرجو: ${payment.studentName}")
        appendLine("قسط شماره: ${PersianDateUtil.toPersianDigits(payment.installmentNumber.toString())}")
        appendLine("مبلغ واریزی: ${PersianDateUtil.formatCurrency(payment.amount)}")
        appendLine("روش پرداخت: $methodTitle")
        if (payment.destinationAccountTitle.isNotBlank()) {
            appendLine("حساب مقصد: ${payment.destinationAccountTitle}")
        }
        if (payment.trackingNumber.isNotBlank()) {
            appendLine("کد رهگیری/پیگیری: ${payment.trackingNumber}")
        }
        if (payment.notes.isNotBlank()) {
            appendLine("توضیحات: ${payment.notes}")
        }
        appendLine("═══════════════════════")
        if (academyPhone.isNotBlank()) {
            appendLine("تلفن تماس: $academyPhone")
        }
        appendLine("با تشکر از پرداخت شما")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("digital_receipt_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Badge
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "رسید پرداخت الکترونیک",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = academyName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Receipt Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ReceiptRow("کد تراکنش:", payment.transactionId, isBold = true)
                        ReceiptRow("تاریخ و ساعت:", "${payment.paymentDateShamsi} - ${payment.paymentTime}")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                        ReceiptRow("هنرجو:", payment.studentName, isBold = true)
                        ReceiptRow("قسط مربوطه:", "قسط شماره ${PersianDateUtil.toPersianDigits(payment.installmentNumber.toString())}")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

                        ReceiptRow(
                            "مبلغ دریافتی:",
                            PersianDateUtil.formatCurrency(payment.amount),
                            valueColor = MaterialTheme.colorScheme.primary,
                            isBold = true
                        )
                        ReceiptRow("روش پرداخت:", methodTitle)

                        if (payment.destinationAccountTitle.isNotBlank()) {
                            ReceiptRow("حساب مقصد:", payment.destinationAccountTitle)
                        }

                        if (payment.trackingNumber.isNotBlank()) {
                            ReceiptRow("کد رهگیری:", payment.trackingNumber)
                        }

                        if (payment.notes.isNotBlank()) {
                            ReceiptRow("توضیحات:", payment.notes)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                val scope = rememberCoroutineScope()

                // Primary PDF Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val pdfFile = PdfDocumentHelper.generatePaymentReceiptPdf(
                                        context = context,
                                        payment = payment,
                                        settings = settings
                                    )
                                    withContext(Dispatchers.Main) {
                                        PdfDocumentHelper.sharePdfFile(
                                            context = context,
                                            pdfFile = pdfFile,
                                            title = "رسید پرداخت ${payment.transactionId}"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "خطا در ساخت PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اشتراک PDF")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val pdfFile = PdfDocumentHelper.generatePaymentReceiptPdf(
                                        context = context,
                                        payment = payment,
                                        settings = settings
                                    )
                                    withContext(Dispatchers.Main) {
                                        PdfDocumentHelper.printOrOpenPdf(
                                            context = context,
                                            pdfFile = pdfFile,
                                            jobName = "Receipt_${payment.transactionId}"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "خطا در آماده‌سازی چاپ: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("چاپ")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Secondary Row (Save PDF to Downloads & Copy Text)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val pdfFile = PdfDocumentHelper.generatePaymentReceiptPdf(
                                        context = context,
                                        payment = payment,
                                        settings = settings
                                    )
                                    withContext(Dispatchers.Main) {
                                        PdfDocumentHelper.savePdfToDownloads(
                                            context = context,
                                            pdfFile = pdfFile,
                                            destinationFileName = "Receipt_${payment.transactionId}.pdf"
                                        )
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "خطا در ذخیره‌سازی: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ذخیره PDF")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("رسید پرداخت", receiptText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "متن رسید کپی شد.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("کپی متن")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("بستن")
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    isBold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
            color = valueColor
        )
    }
}
