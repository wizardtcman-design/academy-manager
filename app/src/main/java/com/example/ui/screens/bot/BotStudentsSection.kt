package com.example.ui.screens.bot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess

@Composable
fun BotStudentsSection(
    settings: AcademySettings?,
    students: List<Student>,
    isCheckingUpdates: Boolean = false,
    onCheckUpdates: () -> Unit = {},
    onUpdateStudent: (Student) -> Unit,
    onDisconnectStudent: (Student) -> Unit = {},
    onSendMessage: (Student) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, CONNECTED, UNCONNECTED
    var studentToManage by remember { mutableStateOf<Student?>(null) }
    var studentToDisconnectConfirm by remember { mutableStateOf<Student?>(null) }
    var showLinkDialogForStudent by remember { mutableStateOf<Student?>(null) }

    val botUsername = settings?.telegramBotUsername?.trim()?.removePrefix("@") ?: ""

    val filteredStudents = remember(students, searchQuery, selectedFilter) {
        students.filter { s ->
            val matchesFilter = when (selectedFilter) {
                "CONNECTED" -> s.isTelegramConnected
                "UNCONNECTED" -> !s.isTelegramConnected
                else -> true
            }
            val matchesQuery = searchQuery.isBlank() ||
                    s.fullName.contains(searchQuery, ignoreCase = true) ||
                    s.phone.contains(searchQuery) ||
                    s.studentCode.contains(searchQuery, ignoreCase = true) ||
                    s.telegramChatId.contains(searchQuery) ||
                    s.telegramUsername.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val connectedCount = remember(students) { students.count { it.isTelegramConnected } }
    val unconnectedCount = remember(students) { students.size - connectedCount }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Banner with instant sync button
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "اتصال خودکار تلگرام هنرجویان",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "هنرجو با باز کردن لینک و زدن Start در ربات، فوراً متصل می‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }

                Button(
                    onClick = onCheckUpdates,
                    enabled = !isCheckingUpdates,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("bot_check_updates_btn")
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("بررسی پیام‌ها", fontSize = 11.sp)
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_student_search_input"),
            placeholder = { Text("جستجوی هنرجو با نام، تلفن یا شناسه چت...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "پاک کردن")
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            singleLine = true
        )

        // Filter chips: short labels so three fit on any phone width
        com.example.ui.components.FilterBar(
            options = listOf(
                com.example.ui.components.FilterOption("ALL", "همه (${students.size})"),
                com.example.ui.components.FilterOption("CONNECTED", "متصل ($connectedCount)"),
                com.example.ui.components.FilterOption("UNCONNECTED", "غیرمتصل ($unconnectedCount)")
            ),
            selectedKey = selectedFilter,
            onSelect = { selectedFilter = it },
            groups = listOf(
                com.example.ui.components.FilterGroup(
                    "وضعیت اتصال به ربات",
                    listOf(
                        com.example.ui.components.FilterOption("ALL", "همه (${students.size})"),
                        com.example.ui.components.FilterOption("CONNECTED", "متصل به ربات ($connectedCount)"),
                        com.example.ui.components.FilterOption("UNCONNECTED", "غیرمتصل ($unconnectedCount)")
                    )
                )
            )
        )

        // Student list
        if (filteredStudents.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "هیچ هنرجویی با این مشخصات یافت نشد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            filteredStudents.forEach { student ->
                val deepLink = if (botUsername.isNotBlank()) {
                    "https://t.me/$botUsername?start=${student.id}"
                } else {
                    "https://t.me/?start=${student.id}"
                }

                BotStudentCard(
                    student = student,
                    deepLink = deepLink,
                    onCopyLink = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Telegram Link", deepLink)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "لینک اختصاصی تلگرام ${student.fullName} کپی شد.", Toast.LENGTH_SHORT).show()
                    },
                    onShareLink = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "سلام ${student.fullName} گرامی، جهت دریافت اعلان‌ها و اقساط خود در ربات تلگرام روی لینک زیر کلیک کنید:\n$deepLink"
                            )
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, "ارسال لینک اختصاصی به ${student.fullName}")
                        context.startActivity(shareIntent)
                    },
                    onViewLinkDetails = {
                        showLinkDialogForStudent = student
                    },
                    onManageConnection = {
                        studentToManage = student
                    },
                    onDisconnectRequest = {
                        studentToDisconnectConfirm = student
                    },
                    onSendMessage = {
                        onSendMessage(student)
                    }
                )
            }
        }
    }

    // Dialog for Link Details
    if (showLinkDialogForStudent != null) {
        val student = showLinkDialogForStudent!!
        val deepLink = if (botUsername.isNotBlank()) {
            "https://t.me/$botUsername?start=${student.id}"
        } else {
            "https://t.me/?start=${student.id}"
        }

        AlertDialog(
            onDismissRequest = { showLinkDialogForStudent = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "لینک اختصاصی ${student.fullName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "این پیوند حاوی شناسه یکتای هنرجو (${student.id}) است. با کلیک هنرجو و زدن دکمه Start در تلگرام، اتصال زنده برقرار می‌گردد:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = deepLink,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp
                        )
                    }

                    if (student.isTelegramConnected) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusSuccess.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "وضعیت: متصل به تلگرام 🟢",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusSuccess
                                )
                                Text(
                                    text = "شناسه چت: ${student.telegramChatId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (student.telegramLinkedAt > 0L) {
                                    Text(
                                        text = "تاریخ اتصال: ${PersianDateUtil.fromEpoch(student.telegramLinkedAt).format()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Telegram Deep Link", deepLink)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "لینک با موفقیت کپی شد.", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("کپی لینک")
                        }

                        OutlinedButton(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, "لینک اختصاصی ورود به ربات آموزشگاه:\n$deepLink")
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "اشتراک لینک"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اشتراک‌گذاری")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinkDialogForStudent = null }) {
                    Text("بستن")
                }
            }
        )
    }

    // Confirmation Dialog for Disconnecting Telegram
    if (studentToDisconnectConfirm != null) {
        val student = studentToDisconnectConfirm!!
        AlertDialog(
            onDismissRequest = { studentToDisconnectConfirm = null },
            title = {
                Text(
                    text = "قطع اتصال تلگرام",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusError
                )
            },
            text = {
                Text(
                    text = "آیا از قطع اتصال تلگرام هنرجو «${student.fullName}» (شناسه چت: ${student.telegramChatId}) اطمینان دارید؟",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val disconnected = student.copy(
                            telegramEnabled = false,
                            telegramChatId = "",
                            telegramUsername = "",
                            telegramLinkedAt = 0L,
                            updatedAt = System.currentTimeMillis()
                        )
                        onDisconnectStudent(student)
                        onUpdateStudent(disconnected)
                        studentToDisconnectConfirm = null
                        Toast.makeText(context, "اتصال تلگرام ${student.fullName} قطع شد.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("قطع اتصال")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { studentToDisconnectConfirm = null },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("انصراف")
                }
            }
        )
    }

    // Dialog for Connection Management
    if (studentToManage != null) {
        val student = studentToManage!!
        var isConnectedState by remember(student) { mutableStateOf(student.telegramEnabled) }
        var inputChatId by remember(student) { mutableStateOf(student.telegramChatId) }

        AlertDialog(
            onDismissRequest = { studentToManage = null },
            title = {
                Text(
                    text = "مدیریت اتصال تلگرام ${student.fullName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "وضعیت اتصال به ربات:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(
                            checked = isConnectedState,
                            onCheckedChange = { isConnectedState = it }
                        )
                    }

                    OutlinedTextField(
                        value = inputChatId,
                        onValueChange = { inputChatId = it },
                        label = { Text("شناسه چت تلگرام (Chat ID)") },
                        placeholder = { Text("مثال: 123456789") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    if (student.isTelegramConnected) {
                        OutlinedButton(
                            onClick = {
                                val disconnected = student.copy(
                                    telegramEnabled = false,
                                    telegramChatId = "",
                                    telegramUsername = "",
                                    telegramLinkedAt = 0L,
                                    updatedAt = System.currentTimeMillis()
                                )
                                onDisconnectStudent(student)
                                onUpdateStudent(disconnected)
                                studentToManage = null
                                Toast.makeText(context, "اتصال تلگرام قطع شد.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusError),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("قطع فوری اتصال تلگرام")
                        }
                    }

                    Text(
                        text = "توجه: شناسه چت به صورت خودکار هنگام کلیک هنرجو روی دکمه Start لینک اختصاصی ثبت می‌شود. از این بخش برای ویرایش یا قطع دستی می‌توانید استفاده کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = student.copy(
                            telegramEnabled = isConnectedState,
                            telegramChatId = inputChatId.trim(),
                            telegramLinkedAt = if (isConnectedState && student.telegramLinkedAt == 0L) System.currentTimeMillis() else student.telegramLinkedAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        onUpdateStudent(updated)
                        studentToManage = null
                        Toast.makeText(context, "اطلاعات اتصال تلگرام ذخیره شد.", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("ذخیره تغییرات")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { studentToManage = null },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun BotStudentCard(
    student: Student,
    deepLink: String,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onViewLinkDetails: () -> Unit,
    onManageConnection: () -> Unit,
    onDisconnectRequest: () -> Unit,
    onSendMessage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Student info & Connection Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (student.isTelegramConnected) StatusSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (student.isTelegramConnected) Icons.Default.Link else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (student.isTelegramConnected) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = student.fullName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (student.studentCode.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = student.studentCode,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = student.phone.ifBlank { "بدون تلفن" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Connection badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (student.isTelegramConnected) StatusSuccess.copy(alpha = 0.15f) else AmberTertiary.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (student.isTelegramConnected) StatusSuccess else AmberTertiary)
                        )
                        Text(
                            text = if (student.isTelegramConnected) "متصل به ربات" else "غیرمتصل",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (student.isTelegramConnected) StatusSuccess else AmberTertiary
                        )
                    }
                }
            }

            // Chat ID details and Connection timestamp if connected
            if (student.isTelegramConnected) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StatusSuccess.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "شناسه چت:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = student.telegramChatId,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (student.telegramUsername.isNotBlank()) {
                                    Text(
                                        text = "(@${student.telegramUsername})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (student.telegramLinkedAt > 0L) {
                                Text(
                                    text = "زمان اتصال: ${PersianDateUtil.fromEpoch(student.telegramLinkedAt).format()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "اتصال فعال",
                            tint = StatusSuccess,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Link snippet box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onViewLinkDetails() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = deepLink,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        text = "مشاهده",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyLink,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("کپی لینک", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onShareLink,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("اشتراک", fontSize = 11.sp)
                }

                IconButton(
                    onClick = onManageConnection,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "مدیریت اتصال", tint = MaterialTheme.colorScheme.primary)
                }

                if (student.isTelegramConnected) {
                    IconButton(
                        onClick = onDisconnectRequest,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = "قطع اتصال تلگرام", tint = StatusError)
                    }

                    IconButton(
                        onClick = onSendMessage,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "ارسال پیام", tint = StatusSuccess)
                    }
                }
            }
        }
    }
}
