package com.example.ui.screens.bot

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MessageLog
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealSecondary

@Composable
fun BotLogsSection(
    messageLogs: List<MessageLog>
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, SENT, FAILED
    var selectedLogForDetails by remember { mutableStateOf<MessageLog?>(null) }

    val filteredLogs = remember(messageLogs, searchQuery, selectedFilter) {
        messageLogs.filter { log ->
            val matchesFilter = when (selectedFilter) {
                "SENT" -> log.status == "SENT"
                "FAILED" -> log.status == "FAILED"
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                    log.studentName.contains(searchQuery, ignoreCase = true) ||
                    log.messageText.contains(searchQuery, ignoreCase = true) ||
                    log.channel.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesSearch
        }.sortedByDescending { it.sentAtEpoch }
    }

    val totalCount = messageLogs.size
    val sentCount = remember(messageLogs) { messageLogs.count { it.status == "SENT" } }
    val failedCount = remember(messageLogs) { messageLogs.count { it.status == "FAILED" } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_logs_search_input"),
            placeholder = { Text("جستجو در لاگ‌ها (نام هنرجو، متن پیام)...") },
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

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text("همه ($totalCount)") },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )

            FilterChip(
                selected = selectedFilter == "SENT",
                onClick = { selectedFilter = "SENT" },
                label = { Text("موفق ($sentCount)") },
                shape = RoundedCornerShape(10.dp),
                leadingIcon = {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusSuccess)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StatusSuccess.copy(alpha = 0.2f)
                )
            )

            FilterChip(
                selected = selectedFilter == "FAILED",
                onClick = { selectedFilter = "FAILED" },
                label = { Text("ناموفق ($failedCount)") },
                shape = RoundedCornerShape(10.dp),
                leadingIcon = {
                    Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(16.dp), tint = StatusError)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = StatusError.copy(alpha = 0.2f)
                )
            )
        }

        // List of logs
        if (filteredLogs.isEmpty()) {
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
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "هیچ گزارش پیامی در این دسته‌بندی وجود ندارد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            filteredLogs.forEach { log ->
                MessageLogCard(
                    log = log,
                    onClick = { selectedLogForDetails = log }
                )
            }
        }
    }

    // Detail dialog
    if (selectedLogForDetails != null) {
        val log = selectedLogForDetails!!
        AlertDialog(
            onDismissRequest = { selectedLogForDetails = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (log.status == "SENT") Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (log.status == "SENT") StatusSuccess else StatusError
                    )
                    Text(
                        text = "جزئیات لاگ پیام",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "هنرجوی دریافت‌کننده: ${log.studentName.ifBlank { "سیستم / عمومی" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "زمان ارسال: ${log.sentAtShamsi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "کانال: ${log.channel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = IndigoPrimary
                    )

                    if (log.status == "FAILED" && log.errorMessage.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = StatusError.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "علت خطا:",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusError
                                )
                                Text(
                                    text = log.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusError
                                )
                            }
                        }
                    }

                    Text(
                        text = "متن پیام ارسال شده:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = log.messageText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLogForDetails = null }) {
                    Text("بستن")
                }
            }
        )
    }
}

@Composable
private fun MessageLogCard(
    log: MessageLog,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("bot_log_item_${log.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Student Name, Channel, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (log.status == "SENT") StatusSuccess.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (log.status == "SENT") Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (log.status == "SENT") StatusSuccess else StatusError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = log.studentName.ifBlank { "هنرجوی آموزشگاه" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = log.sentAtShamsi,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (log.status == "SENT") StatusSuccess.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = when (log.status) {
                            "SENT" -> "موفق"
                            "FAILED" -> "ناموفق"
                            else -> "در انتظار"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (log.status == "SENT") StatusSuccess else StatusError,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Message text snippet
            Text(
                text = log.messageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            // Error Reason if failed
            if (log.status == "FAILED" && log.errorMessage.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StatusError.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "علت خطا: ${log.errorMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusError,
                        modifier = Modifier.padding(6.dp),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
