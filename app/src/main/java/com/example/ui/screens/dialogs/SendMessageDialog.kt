package com.example.ui.screens.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AcademySettings
import com.example.data.model.Installment
import com.example.data.model.MessageTemplate
import com.example.data.model.Student
import com.example.data.util.FinanceUtil
import com.example.data.util.PersianDateUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendMessageDialog(
    student: Student,
    installment: Installment? = null,
    templates: List<MessageTemplate>,
    settings: AcademySettings?,
    telegramDeepLink: String = "",
    onDismiss: () -> Unit,
    onSendMessage: (text: String, channel: String) -> Unit
) {
    val context = LocalContext.current

    val templateList = if (templates.isNotEmpty()) templates else MessageTemplate.getDefaultTemplates()
    var selectedTemplate by remember { mutableStateOf(templateList.first()) }
    var templateDropdownExpanded by remember { mutableStateOf(false) }

    val channels = listOf(
        "AUTO" to "خودکار (تلگرام / دستی)",
        "TELEGRAM" to "ربات تلگرام",
        "MANUAL" to "ارسال دستی / اشتراک‌گذاری",
        "SMS" to "ارسال پیامک SMS (غیرفعال — پنل پیامکی متصل نیست)"
    )
    var selectedChannel by remember { mutableStateOf(channels.first()) }
    var channelDropdownExpanded by remember { mutableStateOf(false) }
    // Stage 6: one send per dialog session — a double click (the dialog
    // closes asynchronously) can never fire the callback twice, and the
    // SMS choice is honestly marked inactive: no SMS provider exists yet,
    // so selecting it is refused instead of pretending a send happened.
    var sendInFlight by remember { mutableStateOf(false) }

    var messageBody by remember {
        mutableStateOf(
            FinanceUtil.fillMessageTemplate(
                templateText = selectedTemplate.templateText,
                student = student,
                courseTitle = installment?.courseTitle ?: "دوره آموزشی",
                installmentNumber = installment?.installmentNumber ?: 1,
                installmentAmount = installment?.amount ?: 0L,
                paidAmount = installment?.paidAmount ?: 0L,
                remainingDebt = installment?.remainingAmount ?: 0L,
                dueDateShamsi = installment?.dueDateShamsi ?: PersianDateUtil.currentShamsiString(),
                settings = settings
            )
        )
    }

    LaunchedEffect(selectedTemplate) {
        messageBody = FinanceUtil.fillMessageTemplate(
            templateText = selectedTemplate.templateText,
            student = student,
            courseTitle = installment?.courseTitle ?: "دوره آموزشی",
            installmentNumber = installment?.installmentNumber ?: 1,
            installmentAmount = installment?.amount ?: 0L,
            paidAmount = installment?.paidAmount ?: 0L,
            remainingDebt = installment?.remainingAmount ?: 0L,
            dueDateShamsi = installment?.dueDateShamsi ?: PersianDateUtil.currentShamsiString(),
            settings = settings
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("send_message_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Text(
                    text = "ارسال پیام و یادآوری",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Recipient Info & Telegram Status
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("گیرنده:", style = MaterialTheme.typography.bodyMedium)
                            Text("${student.fullName} (${student.phone})", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("وضعیت اتصال تلگرام:", style = MaterialTheme.typography.bodySmall)
                            if (student.isTelegramConnected) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("متصل به ربات", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("هنوز متصل نشده", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (!student.isTelegramConnected && telegramDeepLink.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("لینک اتصال تلگرام", telegramDeepLink)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "لینک اتصال تلگرام کپی شد.", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("کپی لینک اختصاصی اتصال تلگرام برای هنرجو", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Select Template
                ExposedDropdownMenuBox(
                    expanded = templateDropdownExpanded,
                    onExpandedChange = { templateDropdownExpanded = !templateDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedTemplate.title,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("انتخاب الگوی پیام") },
                        leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = templateDropdownExpanded,
                        onDismissRequest = { templateDropdownExpanded = false }
                    ) {
                        templateList.forEach { tmpl ->
                            DropdownMenuItem(
                                text = { Text(tmpl.title) },
                                onClick = {
                                    selectedTemplate = tmpl
                                    templateDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Select Channel
                ExposedDropdownMenuBox(
                    expanded = channelDropdownExpanded,
                    onExpandedChange = { channelDropdownExpanded = !channelDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedChannel.second,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("کانال ارسال") },
                        leadingIcon = { Icon(Icons.Default.Send, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = channelDropdownExpanded,
                        onDismissRequest = { channelDropdownExpanded = false }
                    ) {
                        channels.forEach { ch ->
                            DropdownMenuItem(
                                text = { Text(ch.second) },
                                onClick = {
                                    selectedChannel = ch
                                    channelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Message Text Area
                OutlinedTextField(
                    value = messageBody,
                    onValueChange = { messageBody = it },
                    label = { Text("متن پیام (با جایگذاری متغیرها)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    maxLines = 8
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, messageBody)
                            }
                            context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری پیام"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("اشتراک")
                    }

                    Button(
                        onClick = {
                            if (messageBody.isNotBlank() && !sendInFlight) {
                                if (selectedChannel.first == "SMS") {
                                    android.widget.Toast.makeText(
                                        context,
                                        "پنل پیامکی متصل نیست؛ ارسال پیامک فعلاً امکان‌پذیر نیست.",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@Button
                                }
                                sendInFlight = true
                                onSendMessage(messageBody, selectedChannel.first)
                                onDismiss()
                            }
                        },
                        enabled = messageBody.isNotBlank() && !sendInFlight,
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("confirm_send_message_button")
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ارسال پیام")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("انصراف")
                }
            }
        }
    }
}
