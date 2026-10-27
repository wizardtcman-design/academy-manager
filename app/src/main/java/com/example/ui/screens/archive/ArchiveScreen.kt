package com.example.ui.screens.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.ArchiveCourse
import com.example.data.model.ArchiveStudent
import com.example.data.util.ArchiveMath
import com.example.data.util.PersianDateUtil
import com.example.ui.components.StatusBadge
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MainViewModel

/**
 * بایگانی دوره‌ها و هنرجویان قدیمی: historical courses that predate the live
 * system. Their money is ISOLATED — received amounts here never merge with
 * installments/debt/reminders, and unknown amounts are counted as unknown,
 * never as zero.
 */
@Composable
fun ArchiveScreen(
    viewModel: MainViewModel,
    currencyUnit: String,
    onBack: () -> Unit
) {
    val archiveCourses by viewModel.allArchiveCourses.collectAsState()
    val archiveStudents by viewModel.allArchiveStudents.collectAsState()

    var selectedCourse by remember { mutableStateOf<ArchiveCourse?>(null) }

    // Second level: the students editor of one archive course.
    if (selectedCourse != null) {
        ArchiveStudentsEditor(
            viewModel = viewModel,
            course = selectedCourse!!,
            students = archiveStudents.filter { it.archiveCourseId == selectedCourse!!.id },
            currencyUnit = currencyUnit,
            onBack = { selectedCourse = null }
        )
        return
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var courseToEdit by remember { mutableStateOf<ArchiveCourse?>(null) }
    var courseToDelete by remember { mutableStateOf<ArchiveCourse?>(null) }

    // Whole-archive totals (known sums + unknown occurrences).
    val grandTotals = remember(archiveStudents) { ArchiveMath.totalsFor(archiveStudents) }

    Scaffold(
        bottomBar = {
            com.example.ui.components.PinnedActionBar(
                title = "ثبت دوره تاریخی",
                icon = Icons.Default.Add,
                onClick = { showAddDialog = true },
                testTag = "fab_add_archive_course"
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("archive_back_button")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "بایگانی دوره‌ها",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ثبت دوره‌های گذشته و دریافتی‌های قطعی آن‌ها",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Totals card — the archive's own money, never mixed with the live ledger.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "دریافتی قطعی بایگانی",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = PersianDateUtil.formatCurrency(grandTotals.knownReceivedTotal, currencyUnit),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (grandTotals.unknownReceivedCount > 0) {
                            Text(
                                text = "${PersianDateUtil.toPersianDigits(grandTotals.unknownReceivedCount.toString())} هنرجو با مبلغ نامشخص",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusWarning
                            )
                        }
                        Text(
                            text = "این مبالغ با پرداخت‌های سیستم فعلی جمع می‌شوند و هر پرداخت فقط یک‌بار شمرده می‌شود.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (archiveCourses.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Archive,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Text(
                                text = "بایگانی خالی است؛ دوره‌های قدیمی آموزشگاه را همین‌جا ثبت کنید.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(archiveCourses, key = { it.id }) { ac ->
                    val students = archiveStudents.filter { it.archiveCourseId == ac.id }
                    val totals = remember(students) { ArchiveMath.totalsFor(students) }
                    ArchiveCourseCard(
                        course = ac,
                        studentCount = totals.studentCount,
                        knownReceived = totals.knownReceivedTotal,
                        unknownCount = totals.unknownReceivedCount,
                        currencyUnit = currencyUnit,
                        onOpen = { selectedCourse = ac },
                        onEdit = { courseToEdit = ac },
                        onDelete = { courseToDelete = ac }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditArchiveCourseDialog(
            courseToEdit = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { title, year, start, notes ->
                viewModel.addArchiveCourse(title, year, start, notes)
                showAddDialog = false
            }
        )
    }

    if (courseToEdit != null) {
        AddEditArchiveCourseDialog(
            courseToEdit = courseToEdit,
            onDismiss = { courseToEdit = null },
            onConfirm = { title, year, start, notes ->
                viewModel.updateArchiveCourse(
                    courseToEdit!!.copy(title = title, academicYear = year, startShamsi = start, notes = notes)
                )
                courseToEdit = null
            }
        )
    }

    if (courseToDelete != null) {
        AlertDialog(
            onDismissRequest = { courseToDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("حذف دوره تاریخی", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "با حذف «${courseToDelete!!.title}» همهٔ ${PersianDateUtil.toPersianDigits(
                        archiveStudents.count { it.archiveCourseId == courseToDelete!!.id }.toString()
                    )} هنرجوی ثبت‌شدهٔ آن نیز از بایگانی حذف می‌شود. این کار برگشت‌پذیر نیست."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteArchiveCourse(courseToDelete!!)
                        courseToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("حذف دوره و هنرجویان") }
            },
            dismissButton = {
                TextButton(onClick = { courseToDelete = null }) { Text("انصراف") }
            }
        )
    }
}

@Composable
private fun ArchiveCourseCard(
    course: ArchiveCourse,
    studentCount: Int,
    knownReceived: Long,
    unknownCount: Int,
    currencyUnit: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("archive_course_card_${course.id}")
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val metaLine = buildString {
                        if (course.academicYear.isNotBlank()) {
                            append("سال آموزشی ${PersianDateUtil.toPersianDigits(course.academicYear)}")
                        }
                        if (course.startShamsi.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("شروع ${PersianDateUtil.toPersianDigits(course.startShamsi)}")
                        }
                    }
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "ویرایش دوره تاریخی", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف دوره تاریخی", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Groups,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${PersianDateUtil.toPersianDigits(studentCount.toString())} هنرجو",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (unknownCount > 0) {
                    StatusBadge(
                        "${PersianDateUtil.toPersianDigits(unknownCount.toString())} مبالغ نامشخص",
                        StatusWarning
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                    Icons.Default.Payments,
                    contentDescription = null,
                    tint = StatusSuccess,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "دریافتی قطعی: ${PersianDateUtil.formatCurrency(knownReceived, currencyUnit)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusSuccess
                )
            }
        }
    }
}

// ======================================================================
// Students editor of one archive course
// ======================================================================
@Composable
private fun ArchiveStudentsEditor(
    viewModel: MainViewModel,
    course: ArchiveCourse,
    students: List<ArchiveStudent>,
    currencyUnit: String,
    onBack: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var studentToEdit by remember { mutableStateOf<ArchiveStudent?>(null) }
    var studentToDelete by remember { mutableStateOf<ArchiveStudent?>(null) }

    val totals = remember(students) { ArchiveMath.totalsFor(students) }

    Scaffold(
        bottomBar = {
            com.example.ui.components.PinnedActionBar(
                title = "افزودن هنرجو به این دوره",
                icon = Icons.Default.Add,
                onClick = { showAddDialog = true },
                testTag = "fab_add_archive_student"
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت به بایگانی",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = course.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "هنرجویان تاریخی و دریافتی‌های قطعی",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("تعداد هنرجویان:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                PersianDateUtil.toPersianDigits(totals.studentCount.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("دریافتی قطعی (مبالغ مشخص):", style = MaterialTheme.typography.bodySmall)
                            Text(
                                PersianDateUtil.formatCurrency(totals.knownReceivedTotal, currencyUnit),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                        }
                        if (totals.unknownReceivedCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("مبالغ نامشخص:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${PersianDateUtil.toPersianDigits(totals.unknownReceivedCount.toString())} هنرجو",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusWarning
                                )
                            }
                        }
                    }
                }
            }

            if (students.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Text(
                            text = "هنوز هنرجویی برای این دورهٔ تاریخی ثبت نشده است؛ با دکمهٔ پایین اضافه کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(students, key = { it.id }) { st ->
                    ArchiveStudentRow(
                        student = st,
                        currencyUnit = currencyUnit,
                        onEdit = { studentToEdit = st },
                        onDelete = { studentToDelete = st }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddEditArchiveStudentDialog(
            studentToEdit = null,
            currencyUnit = currencyUnit,
            onDismiss = { showAddDialog = false },
            onConfirm = { fullName, mobile, tuition, received, paymentDates, notes ->
                viewModel.addArchiveStudent(
                    archiveCourseId = course.id,
                    fullName = fullName,
                    mobile = mobile,
                    tuitionAmount = tuition,
                    receivedAmount = received,
                    paymentDatesNote = paymentDates,
                    notes = notes
                )
                showAddDialog = false
            }
        )
    }

    if (studentToEdit != null) {
        AddEditArchiveStudentDialog(
            studentToEdit = studentToEdit,
            currencyUnit = currencyUnit,
            onDismiss = { studentToEdit = null },
            onConfirm = { fullName, mobile, tuition, received, paymentDates, notes ->
                viewModel.updateArchiveStudent(
                    studentToEdit!!.copy(
                        fullName = fullName,
                        mobile = mobile,
                        tuitionAmount = tuition,
                        receivedAmount = received,
                        paymentDatesNote = paymentDates,
                        notes = notes
                    )
                )
                studentToEdit = null
            }
        )
    }

    if (studentToDelete != null) {
        AlertDialog(
            onDismissRequest = { studentToDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("حذف هنرجوی تاریخی", fontWeight = FontWeight.Bold) },
            text = { Text("«${studentToDelete!!.fullName}» از بایگانی این دوره حذف می‌شود.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteArchiveStudent(studentToDelete!!)
                        studentToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { studentToDelete = null }) { Text("انصراف") } }
        )
    }
}

@Composable
private fun ArchiveStudentRow(
    student: ArchiveStudent,
    currencyUnit: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = student.fullName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (student.mobile.isNotBlank()) {
                        Text(
                            text = "موبایل: ${PersianDateUtil.toPersianDigits(student.mobile)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "ویرایش هنرجو", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف هنرجو", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "شهریه: " + (student.tuitionAmount?.let {
                            PersianDateUtil.formatCurrency(it, currencyUnit)
                        } ?: "نامشخص"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (student.tuitionAmount == null) StatusWarning else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "دریافتی: " + (student.receivedAmount?.let {
                            PersianDateUtil.formatCurrency(it, currencyUnit)
                        } ?: "نامشخص"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (student.receivedAmount == null) StatusWarning else StatusSuccess,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            if (student.paymentDatesNote.isNotBlank()) {
                Text(
                    text = "تاریخ پرداخت‌ها: ${student.paymentDatesNote}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (student.notes.isNotBlank()) {
                Text(
                    text = student.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ======================================================================
// Dialogs
// ======================================================================
@Composable
private fun AddEditArchiveCourseDialog(
    courseToEdit: ArchiveCourse?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, academicYear: String, startShamsi: String, notes: String) -> Unit
) {
    var title by remember { mutableStateOf(courseToEdit?.title ?: "") }
    var academicYear by remember { mutableStateOf(courseToEdit?.academicYear ?: "") }
    var startShamsi by remember { mutableStateOf(courseToEdit?.startShamsi ?: "") }
    var notes by remember { mutableStateOf(courseToEdit?.notes ?: "") }
    var titleError by remember { mutableStateOf(false) }
    val isEditing = courseToEdit != null

    com.example.ui.components.AppFormDialog(
        title = if (isEditing) "ویرایش دوره تاریخی" else "ثبت دوره تاریخی",
        subtitle = "دوره‌های گذشته را با دریافتی قطعی هنرجویانشان ثبت کنید",
        icon = Icons.Default.Archive,
        onDismiss = onDismiss,
        confirmText = if (isEditing) "ذخیره تغییرات" else "ثبت در بایگانی",
        onConfirm = {
            if (title.isBlank()) titleError = true
            else onConfirm(title.trim(), academicYear.trim(), startShamsi, notes.trim())
        },
        maxHeight = 640,
        testTag = "archive_course_form_dialog"
    ) {
        com.example.ui.components.FormSection(title = "مشخصات دوره", icon = Icons.Default.School) {
            com.example.ui.components.FormTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = "عنوان دوره *",
                leadingIcon = Icons.Default.School,
                isError = titleError,
                supportingText = if (titleError) "عنوان دوره الزامی است" else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            com.example.ui.components.FormTextField(
                value = academicYear,
                onValueChange = { academicYear = it },
                label = "سال آموزشی (اختیاری) — مثال: ۱۴۰۱",
                leadingIcon = Icons.Default.HistoryEdu,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            com.example.ui.components.JalaliDateField(
                value = startShamsi,
                onValueChange = { startShamsi = it },
                label = "تاریخ شروع (اختیاری)"
            )
            com.example.ui.components.FormTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "توضیحات (اختیاری)",
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
        }
    }
}

/**
 * Archive student form: every money field can be explicitly نامشخص —
 * unknown amounts are stored as null and are never counted as zero.
 */
@Composable
private fun AddEditArchiveStudentDialog(
    studentToEdit: ArchiveStudent?,
    currencyUnit: String,
    onDismiss: () -> Unit,
    onConfirm: (
        fullName: String,
        mobile: String,
        tuitionAmount: Long?,
        receivedAmount: Long?,
        paymentDatesNote: String,
        notes: String
    ) -> Unit
) {
    var fullName by remember { mutableStateOf(studentToEdit?.fullName ?: "") }
    var mobile by remember { mutableStateOf(studentToEdit?.mobile ?: "") }
    var tuitionText by remember { mutableStateOf(studentToEdit?.tuitionAmount?.toString() ?: "") }
    var tuitionUnknown by remember { mutableStateOf(studentToEdit?.tuitionAmount == null) }
    var receivedText by remember { mutableStateOf(studentToEdit?.receivedAmount?.toString() ?: "") }
    var receivedUnknown by remember { mutableStateOf(studentToEdit?.receivedAmount == null) }
    var paymentDates by remember { mutableStateOf(studentToEdit?.paymentDatesNote ?: "") }
    var notes by remember { mutableStateOf(studentToEdit?.notes ?: "") }

    var fullNameError by remember { mutableStateOf(false) }
    var tuitionError by remember { mutableStateOf(false) }
    var receivedError by remember { mutableStateOf(false) }

    val isEditing = studentToEdit != null

    com.example.ui.components.AppFormDialog(
        title = if (isEditing) "ویرایش هنرجوی تاریخی" else "افزودن هنرجوی تاریخی",
        subtitle = "مبالغ نامشخص هرگز صفر فرض نمی‌شوند و از درآمد قطعی کنار گذاشته می‌شوند",
        icon = Icons.Default.Groups,
        onDismiss = onDismiss,
        confirmText = if (isEditing) "ذخیره تغییرات" else "افزودن به بایگانی",
        onConfirm = {
            var hasError = false
            if (fullName.isBlank()) {
                fullNameError = true
                hasError = true
            }
            val tuition = PersianDateUtil.toPlainDigits(tuitionText).toLongOrNull()
            if (!tuitionUnknown && tuition == null) {
                tuitionError = true
                hasError = true
            }
            val received = PersianDateUtil.toPlainDigits(receivedText).toLongOrNull()
            if (!receivedUnknown && received == null) {
                receivedError = true
                hasError = true
            }
            if (!hasError) {
                onConfirm(
                    fullName.trim(),
                    mobile.trim(),
                    if (tuitionUnknown) null else tuition,
                    if (receivedUnknown) null else received,
                    paymentDates.trim(),
                    notes.trim()
                )
            }
        },
        maxHeight = 780,
        testTag = "archive_student_form_dialog"
    ) {
        com.example.ui.components.FormSection(title = "مشخصات هنرجو", icon = Icons.Default.Groups) {
            com.example.ui.components.FormTextField(
                value = fullName,
                onValueChange = { fullName = it; fullNameError = false },
                label = "نام و نام خانوادگی *",
                leadingIcon = Icons.Default.Groups,
                isError = fullNameError,
                supportingText = if (fullNameError) "نام هنرجو الزامی است" else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            com.example.ui.components.FormTextField(
                value = mobile,
                onValueChange = { mobile = it },
                label = "موبایل (اختیاری)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
            )
        }

        com.example.ui.components.FormSection(
            title = "مبالغ (شهریه و دریافتی)",
            icon = Icons.Default.Payments,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            // Tuition: number + «نامشخص» checkbox
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.ui.components.FormNumberField(
                    value = tuitionText,
                    onValueChange = { tuitionText = it; tuitionError = false },
                    label = "شهریه کل ($currencyUnit)",
                    isError = tuitionError,
                    supportingText = if (tuitionError) "مبلغ را وارد کنید یا «نامشخص» را فعال کنید" else null,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                    Checkbox(checked = tuitionUnknown, onCheckedChange = { tuitionUnknown = it; tuitionError = false })
                    Text("نامشخص", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Received: number + «نامشخص» checkbox + helper
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.example.ui.components.FormNumberField(
                    value = receivedText,
                    onValueChange = { receivedText = it; receivedError = false },
                    label = "دریافتی واقعی ($currencyUnit)",
                    isError = receivedError,
                    supportingText = if (receivedError) "مبلغ را وارد کنید یا «نامشخص» را فعال کنید" else null,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
                    Checkbox(checked = receivedUnknown, onCheckedChange = { receivedUnknown = it; receivedError = false })
                    Text("نامشخص", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = "مبلغ نامشخص در درآمد قطعی محاسبه نمی‌شود.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            com.example.ui.components.FormTextField(
                value = paymentDates,
                onValueChange = { paymentDates = it },
                label = "تاریخ پرداخت‌ها (اختیاری) — مثال: ۱۴۰۲/۰۷ و ۱۴۰۲/۱۰",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            com.example.ui.components.FormTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "توضیحات (اختیاری)",
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
        }
    }
}
