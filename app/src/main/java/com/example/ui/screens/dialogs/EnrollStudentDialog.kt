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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
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
    onCreateCourse: () -> Unit = {},
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

    com.example.ui.components.AppFormDialog(
        title = "ثبت‌نام در دوره",
        subtitle = "هنرجو، دوره، اقساط و تاریخ‌ها را مشخص کنید",
        icon = Icons.Default.MenuBook,
        onDismiss = onDismiss,
        confirmText = "تأیید و ثبت‌نام",
        onConfirm = {
            val st = selectedStudent
            val crs = selectedCourse
            if (st != null && crs != null && isSumValid) {
                onEnroll(
                    st,
                    crs,
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
        confirmEnabled = selectedStudent != null && selectedCourse != null && isSumValid,
        maxHeight = 760,
        testTag = "enroll_student_dialog"
    ) {
        com.example.ui.components.FormSection(title = "هنرجو و دوره", icon = Icons.Default.Person) {
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
                        shape = RoundedCornerShape(14.dp),
                        colors = com.example.ui.components.FormTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = studentDropdownExpanded,
                        onDismissRequest = { studentDropdownExpanded = false }
                    ) {
                        students.forEach { st ->
                            DropdownMenuItem(
                                text = { Text("${st.fullName} (${st.studentCode}) - ${st.phone}") },
                                onClick = {
                                    selectedStudent = st
                                    studentDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${initialStudent.fullName} (${initialStudent.studentCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (courses.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "هنوز هیچ دوره‌ای تعریف نشده است.",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "اول دوره را بسازید؛ بعد از ذخیره، همین‌جا برای ثبت‌نام انتخاب می‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onCreateCourse, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تعریف دورهٔ جدید")
                        }
                    }
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = courseDropdownExpanded,
                    onExpandedChange = { courseDropdownExpanded = !courseDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedCourse?.let {
                            "${it.title} (${PersianDateUtil.formatCurrency(it.tuitionFee)})"
                        } ?: "انتخاب دوره آموزشی",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("دوره آموزشی") },
                        leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = courseDropdownExpanded) },
                        shape = RoundedCornerShape(14.dp),
                        colors = com.example.ui.components.FormTextFieldColors(),
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
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("+ تعریف دورهٔ جدید", color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                courseDropdownExpanded = false
                                onCreateCourse()
                            }
                        )
                    }
                }
                TextButton(onClick = onCreateCourse, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("دوره‌ای در لیست نیست؟ دورهٔ تازه بسازید")
                }
            }
        }

        com.example.ui.components.FormSection(
            title = "تاریخ و تخفیف",
            icon = Icons.Default.CalendarMonth,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.example.ui.components.JalaliDateField(
                    value = startDateShamsi,
                    onValueChange = { startDateShamsi = it },
                    label = "تاریخ شروع دوره",
                    modifier = Modifier.weight(1f)
                )
                com.example.ui.components.FormTextField(
                    value = discountText,
                    onValueChange = { discountText = it },
                    label = "تخفیف (تومان)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            com.example.ui.components.JalaliDateField(
                value = firstInstallmentDateShamsi,
                onValueChange = { firstInstallmentDateShamsi = it },
                label = "سررسید قسط اول"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "قسط اول هم‌اکنون پرداخت شد",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Switch(checked = firstInstallmentPaidNow, onCheckedChange = { firstInstallmentPaidNow = it })
            }
        }

        com.example.ui.components.FormSection(
            title = "شهریه و اقساط",
            icon = Icons.Default.Payments,
            accent = MaterialTheme.colorScheme.secondary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("شهریه مصوب:", style = MaterialTheme.typography.bodySmall)
                Text(PersianDateUtil.formatCurrency(baseTuition), fontWeight = FontWeight.Bold)
            }
            if (discount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("تخفیف:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text(
                        "- ${PersianDateUtil.formatCurrency(discount)}",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("قابل پرداخت:", style = MaterialTheme.typography.bodySmall)
                Text(
                    PersianDateUtil.formatCurrency(finalFee),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            // Installment count: one compact, equal-width control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("تعداد اقساط", style = MaterialTheme.typography.bodySmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (installmentCount > 1) installmentCount -= 1 },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "کمتر", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = PersianDateUtil.toPersianDigits(installmentCount.toString()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = { if (installmentCount < 12) installmentCount += 1 },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "بیشتر", modifier = Modifier.size(18.dp))
                    }
                }
            }

            customAmounts.forEachIndexed { index, amount ->
                val dateParts = firstInstallmentDateShamsi.split("/").mapNotNull { it.toIntOrNull() }
                val jalali = if (dateParts.size == 3) {
                    PersianDateUtil.JalaliDate(dateParts[0], dateParts[1], dateParts[2]).addMonths(index).format()
                } else "ماه ${index + 1}"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.width(96.dp)) {
                        Text(
                            text = "قسط ${PersianDateUtil.toPersianDigits((index + 1).toString())}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = PersianDateUtil.toPersianDigits(jalali),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = amount.toString(),
                        onValueChange = { newText ->
                            customAmounts[index] = newText.toLongOrNull() ?: 0L
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = com.example.ui.components.FormTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!isSumValid) {
                Text(
                    text = "جمع اقساط (${PersianDateUtil.formatCurrency(currentSum)}) با شهریه کل " +
                        "(${PersianDateUtil.formatCurrency(finalFee)}) برابر نیست.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        com.example.ui.components.FormSection(
            title = "یادداشت",
            icon = Icons.Default.Description
        ) {
            com.example.ui.components.FormTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "توضیحات ثبت‌نام (اختیاری)",
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
