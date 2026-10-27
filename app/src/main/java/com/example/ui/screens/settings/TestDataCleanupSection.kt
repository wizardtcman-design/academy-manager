package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Student
import com.example.ui.viewmodel.MainViewModel

/**
 * TEMPORARY admin tool: «پاک‌سازی کامل اطلاعات آزمایشی».
 *
 * Lets the academy owner run the whole registration → connect → contract →
 * sign cycle again from scratch: explicit test students are selected by id,
 * a combined (server + device) preview is shown, an explicit confirmation is
 * required, and the result report verifies that nothing remains. Academy
 * settings, courses, bank accounts, templates and every other student are
 * never touched.
 */
@Composable
fun TestDataCleanupSection(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val busy by viewModel.testDataCleanupBusy.collectAsState()

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showPicker by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var showPreview by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var confirmChecked by remember { mutableStateOf(false) }
    var resultOk by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "پاک‌سازی کامل اطلاعات آزمایشی (ابزار موقت مدیر):",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "برای تست دوبارهٔ کل فرایند ثبت‌نام، اتصال تلگرام و قرارداد با یک کاربر آزمایشی جدید: هنرجوهای آزمایشی را صریحاً انتخاب کنید؛ پیش‌نمایش دقیق رکوردها نشان داده می‌شود و پس از تأیید، داده‌های همان هنرجوها از سرور، فایربیس و دستگاه حذف می‌شود. تنظیمات آموزشگاه، دوره‌ها، حساب‌ها و سایر هنرجوها دست‌نخورده می‌مانند و لینک‌های قبلی غیرفعال می‌شوند.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.weight(1.2f),
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (selectedIds.isEmpty()) "انتخاب هنرجوی آزمایشی" else "انتخاب‌شده: ${selectedIds.size} هنرجو",
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = {
                        viewModel.previewTestDataCleanup(selectedIds.toList()) { ok, text ->
                            previewText = text
                            showPreview = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && selectedIds.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("بررسی پیش از پاک‌سازی", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        confirmChecked = false
                        showConfirm = true
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && selectedIds.isNotEmpty(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال پاک‌سازی...", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("پاک‌سازی کامل اطلاعات آزمایشی", fontSize = 11.sp)
                    }
                }
            }
        }
    }

    if (showPicker) {
        StudentPickerForCleanupDialog(
            viewModel = viewModel,
            selectedIds = selectedIds,
            onDismiss = { showPicker = false },
            onConfirm = { ids ->
                selectedIds = ids
                showPicker = false
            }
        )
    }

    if (showPreview) {
        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("پیش‌نمایش پاک‌سازی", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(previewText, fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showPreview = false }) { Text("متوجه شدم") }
            }
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showConfirm = false },
            title = {
                Text(
                    "پاک‌سازی کامل اطلاعات آزمایشی؟",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "برای ${selectedIds.size} هنرجوی انتخاب‌شده، تمام پروفایل، ثبت‌نام، اقساط، پرداخت‌ها، قراردادها، توکن‌ها و لینک‌ها، اتصال تلگرام، فایل‌های PDF و گزارش‌های مرتبط از سرور، فایربیس و دستگاه حذف می‌شود. این عملیات بازگشت‌پذیر نیست.",
                        fontSize = 12.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmChecked, onCheckedChange = { confirmChecked = it })
                        Text("می‌دانم این عملیات بازگشت‌پذیر نیست.", fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        val ids = selectedIds.toList()
                        viewModel.runTestDataCleanup(ids) { ok, text ->
                            resultOk = ok
                            resultText = text
                            if (ok) selectedIds = emptySet()
                            showResult = true
                        }
                    },
                    enabled = confirmChecked && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("پاک‌سازی کن") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirm = false }) { Text("انصراف") }
            }
        )
    }

    if (showResult) {
        AlertDialog(
            onDismissRequest = { showResult = false },
            title = {
                Text(
                    if (resultOk) "گزارش پاک‌سازی" else "پاک‌سازی کامل نشد",
                    fontWeight = FontWeight.Bold,
                    color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(resultText, fontSize = 12.sp, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showResult = false }) { Text("بستن") }
            }
        )
    }
}

@Composable
private fun StudentPickerForCleanupDialog(
    viewModel: MainViewModel,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val students by viewModel.students.collectAsState()
    var search by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(selectedIds) }
    val filtered = students.filter {
        search.isBlank() || it.fullName.contains(search) || it.studentCode.contains(search) || it.phone.contains(search)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب هنرجوهای آزمایشی", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "فقط هنرجوهایی را انتخاب کنید که قطعاً آزمایشی هستند. انتخاب با شناسهٔ یکتای هنرجو انجام می‌شود، نه نام یا شماره تلفن.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("جست‌وجو (نام / کد / تلفن)", fontSize = 12.sp) },
                    singleLine = true
                )
                if (filtered.isEmpty()) {
                    Text("هنرجویی برای نمایش نیست.", fontSize = 12.sp)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(filtered, key = { it.id }) { student ->
                            CleanupStudentRow(
                                student = student,
                                checked = student.id in selection,
                                onToggle = {
                                    selection = if (student.id in selection) selection - student.id
                                    else selection + student.id
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selection) },
                enabled = selection.isNotEmpty()
            ) { Text("تأیید انتخاب (${selection.size})") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
private fun CleanupStudentRow(student: Student, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(student.fullName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                buildString {
                    append("کد: ${student.studentCode.ifBlank { "—" }}")
                    if (student.phone.isNotBlank()) append("  ·  ${student.phone}")
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider()
}
