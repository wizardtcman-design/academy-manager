package com.example.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Course
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollStudentDialog(
    students: List<Student>,
    courses: List<Course>,
    initialStudent: Student? = null,
    initialCourse: Course? = null,
    onDismiss: () -> Unit,
    onEnroll: (
        student: Student,
        course: Course,
        startDateShamsi: String,
        discount: Long,
        installmentCount: Int,
        customAmounts: List<Long>,
        firstInstallmentDateShamsi: String,
        firstInstallmentPaidNow: Boolean,
        notes: String
    ) -> Unit
) {
    var selectedStudent by remember { mutableStateOf(initialStudent ?: students.firstOrNull()) }
    var selectedCourse by remember { mutableStateOf(initialCourse ?: courses.firstOrNull()) }

    var studentDropdownExpanded by remember { mutableStateOf(false) }
    var courseDropdownExpanded by remember { mutableStateOf(false) }

    val todayShamsi = remember { PersianDateUtil.currentShamsiString() }
    var startDateShamsi by remember { mutableStateOf(todayShamsi) }
    var firstInstallmentDateShamsi by remember { mutableStateOf(todayShamsi) }
    var firstInstallmentPaidNow by remember { mutableStateOf(true) }

    var discountText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val baseTuition = selectedCourse?.tuitionFee ?: 0L
    val discount = discountText.toLongOrNull() ?: 0L
    val finalFee = (baseTuition - discount).coerceAtLeast(0L)

    var installmentCount by remember {
        mutableIntStateOf(selectedCourse?.installmentCount?.coerceIn(1, 12) ?: 3)
    }

    val customAmounts = remember { mutableStateListOf<Long>() }

    // When course or discount or count changes, recalculate default split
    fun recalculateAmounts(count: Int, total: Long) {
        customAmounts.clear()
        if (count <= 0 || total <= 0) return
        val base = total / count
        val rem = total % count
        for (i in 0 until count) {
            customAmounts.add(if (i == 0) base + rem else base)
        }
    }

    LaunchedEffect(selectedCourse, discount, installmentCount) {
        recalculateAmounts(installmentCount, finalFee)
    }

    val currentSum = customAmounts.sum()
    val isSumValid = currentSum == finalFee

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(24.dp))
                .testTag("enroll_student_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ثبت‌نام در دوره آموزشی",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Select Student
                if (initialStudent == null) {
                    ExposedDropdownMenuBox(
                        expanded = studentDropdownExpanded,
                        onExpandedChange = { studentDropdownExpanded = !studentDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedStudent?.let { "${it.fullName} (${it.studentCode})" } ?: "انتخاب هنرجو",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("هنرجو") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = studentDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = studentDropdownExpanded,
                            onDismissRequest = { studentDropdownExpanded = false }
                        ) {
                            students.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("${s.fullName} (${s.studentCode}) - ${s.phone}") },
                                    onClick = {
                                        selectedStudent = s
                                        studentDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "هنرجو: ${initialStudent.fullName} (${initialStudent.studentCode})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 2. Select Course
                ExposedDropdownMenuBox(
                    expanded = courseDropdownExpanded,
                    onExpandedChange = { courseDropdownExpanded = !courseDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedCourse?.let { "${it.title} (${PersianDateUtil.formatCurrency(it.tuitionFee)})" } ?: "انتخاب دوره آموزشی",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("دوره آموزشی") },
                        leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = courseDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = courseDropdownExpanded,
                        onDismissRequest = { courseDropdownExpanded = false }
                    ) {
                        courses.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.title} - ${PersianDateUtil.formatCurrency(c.tuitionFee)} (${c.durationMonths} ماهه)") },
                                onClick = {
                                    selectedCourse = c
                                    installmentCount = c.installmentCount.coerceIn(1, 12)
                                    courseDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = startDateShamsi,
                        onValueChange = { startDateShamsi = it },
                        label = { Text("تاریخ شروع دوره") },
                        leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("تخفیف (تومان)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Financial Summary Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("شهریه مصوب دوره:", style = MaterialTheme.typography.bodyMedium)
                            Text(PersianDateUtil.formatCurrency(baseTuition), fontWeight = FontWeight.Bold)
                        }
                        if (discount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("تخفیف:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                Text("- ${PersianDateUtil.formatCurrency(discount)}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("شهریه نهایی قابل پرداخت:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(PersianDateUtil.formatCurrency(finalFee), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Installments Configuration
                Text(
                    text = "تنظیم و زمان‌بندی اقساط",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("تعداد اقساط:", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { if (installmentCount > 1) installmentCount-- },
                            enabled = installmentCount > 1
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "کاهش")
                        }
                        Text(
                            text = PersianDateUtil.toPersianDigits(installmentCount.toString()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = { if (installmentCount < 12) installmentCount++ },
                            enabled = installmentCount < 12
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "افزایش")
                        }
                    }
                }

                // First Installment Date & Payment Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = firstInstallmentDateShamsi,
                        onValueChange = { firstInstallmentDateShamsi = it },
                        label = { Text("سررسید قسط اول") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "قسط اول هم‌اکنون پرداخت شد",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = firstInstallmentPaidNow,
                        onCheckedChange = { firstInstallmentPaidNow = it }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Installments Breakdown & Custom Amounts
                Text(
                    text = "جدول مبالغ اقساط:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                customAmounts.forEachIndexed { index, amount ->
                    val dateParts = firstInstallmentDateShamsi.split("/").mapNotNull { it.toIntOrNull() }
                    val jalali = if (dateParts.size == 3) {
                        PersianDateUtil.JalaliDate(dateParts[0], dateParts[1], dateParts[2]).addMonths(index).format()
                    } else "ماه ${index + 1}"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "قسط ${index + 1} ($jalali):",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(130.dp)
                        )
                        OutlinedTextField(
                            value = amount.toString(),
                            onValueChange = { newText ->
                                val parsed = newText.toLongOrNull() ?: 0L
                                customAmounts[index] = parsed
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                if (!isSumValid) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "توجه: جمع اقساط (${PersianDateUtil.formatCurrency(currentSum)}) با شهریه کل (${PersianDateUtil.formatCurrency(finalFee)}) برابر نیست!",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("توضیحات و یادداشت ثبت‌نام") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("انصراف")
                    }
                    Button(
                        onClick = {
                            val s = selectedStudent
                            val c = selectedCourse
                            if (s != null && c != null && isSumValid) {
                                onEnroll(
                                    s,
                                    c,
                                    startDateShamsi,
                                    discount,
                                    installmentCount,
                                    customAmounts.toList(),
                                    firstInstallmentDateShamsi,
                                    firstInstallmentPaidNow,
                                    notes
                                )
                            }
                        },
                        enabled = selectedStudent != null && selectedCourse != null && isSumValid,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("submit_enroll_button")
                    ) {
                        Text("تایید و ثبت‌نام")
                    }
                }
            }
        }
    }
}
