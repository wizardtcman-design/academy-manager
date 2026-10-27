package com.example.ui.screens.courses

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Course
import com.example.data.model.Enrollment
import com.example.data.util.CourseAccount
import com.example.data.util.CourseStatusLabels
import com.example.data.util.PersianDateUtil
import com.example.ui.components.SegmentedTabs
import com.example.ui.components.StatCardPro
import com.example.ui.components.StatusBadge
import com.example.ui.screens.dialogs.EnrollStudentDialog
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MainViewModel

/**
 * Full management screen for ONE course: header with the three independent
 * status groups + capacity, then four sections — هنرجویان / حساب دوره /
 * مدارک / بررسی پایان. All numbers come from the live flows and the pure
 * CourseAccount math; nothing is cached or mocked.
 */
@Composable
fun CourseDetailsScreen(
    viewModel: MainViewModel,
    courseId: String,
    currencyUnit: String,
    onClose: () -> Unit
) {
    val courses by viewModel.courses.collectAsState()
    val allEnrollments by viewModel.enrollments.collectAsState()
    val installments by viewModel.installments.collectAsState()
    val payments by viewModel.payments.collectAsState()
    val refunds by viewModel.refunds.collectAsState()
    val students by viewModel.students.collectAsState()
    val completionState by viewModel.courseCompletion.collectAsState()

    val course = courses.firstOrNull { it.id == courseId }

    // Dialog state
    var selectedTab by remember { mutableIntStateOf(0) }
    var showEnrollDialog by remember { mutableStateOf(false) }
    var showOverrideConfirm by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var showOpenConfirm by remember { mutableStateOf(false) }
    var showHoldingEndDialog by remember { mutableStateOf(false) }
    var showReadyConfirm by remember { mutableStateOf(false) }
    var showFinalizeConfirm by remember { mutableStateOf(false) }
    var finalizeDoubleConfirm by remember { mutableStateOf(false) }

    // Pending enrollment payload kept around so the capacity-override retry
    // can re-run the exact same registration with allowOverCapacity = true.
    var pendingEnrollment by remember {
        mutableStateOf<PendingEnrollmentPayload?>(null)
    }

    val enrollments = remember(allEnrollments, courseId) {
        allEnrollments.filter { it.courseId == courseId }
            .sortedByDescending { it.registrationDateEpoch }
    }

    val account = remember(course, allEnrollments, installments, payments, refunds) {
        if (course == null) null
        else CourseAccount.build(course, allEnrollments, installments, payments, refunds)
    }

    val validEnrollments = enrollments.filter { it.status != "CANCELLED" }
    val isCapacityFull = (course?.capacity ?: 0) > 0 && validEnrollments.size >= (course?.capacity ?: 0)
    val isClosed = course?.enrollmentStatus == Course.ENROLLMENT_CLOSED

    // The completion review runs when the «بررسی پایان» tab is opened and
    // re-runs whenever a status changed after a manager action.
    LaunchedEffect(selectedTab, course?.finalStatus, course?.enrollmentStatus, course?.holdingStatus) {
        if (selectedTab == 3 && course != null) {
            viewModel.checkCourseCompletion(course.id)
        }
    }

    if (course == null) {
        CourseDetailsMissingScreen(onClose = onClose)
        return
    }

    Scaffold(
        bottomBar = {
            if (selectedTab == 0) {
                com.example.ui.components.PinnedActionBar(
                    title = "افزودن هنرجو به این دوره",
                    icon = Icons.Default.PersonAdd,
                    onClick = { showEnrollDialog = true },
                    testTag = "fab_enroll_in_course"
                )
            }
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
                    IconButton(onClick = onClose, modifier = Modifier.testTag("course_details_back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "جزئیات دوره",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ---- Header band: title + start info + three status rows ----
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        )
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                            Color.Transparent
                                        )
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.School,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                val startLine = buildString {
                                    if (course.academicYear.isNotBlank()) {
                                        append("سال آموزشی ${PersianDateUtil.toPersianDigits(course.academicYear)}")
                                    }
                                    if (course.startShamsi.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("شروع ${PersianDateUtil.toPersianDigits(course.startShamsi)}")
                                    }
                                    if (course.startTime.isNotBlank()) {
                                        append(" - ${PersianDateUtil.toPersianDigits(course.startTime)}")
                                    }
                                }
                                if (startLine.isNotBlank()) {
                                    Text(
                                        text = startLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Group 1: پذیرش
                            StatusRow(
                                "پذیرش",
                                CourseStatusLabels.enrollmentStatusLabel(course.enrollmentStatus),
                                when (course.enrollmentStatus) {
                                    Course.ENROLLMENT_OPEN -> StatusSuccess
                                    Course.ENROLLMENT_CAPACITY_FULL -> StatusWarning
                                    else -> StatusError
                                }
                            )
                            // Group 2: برگزاری
                            StatusRow(
                                "برگزاری",
                                CourseStatusLabels.holdingStatusLabel(course.holdingStatus),
                                if (course.holdingStatus == Course.HOLDING_ONGOING) StatusInfo
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Group 3: وضعیت نهایی
                            StatusRow(
                                "وضعیت نهایی",
                                CourseStatusLabels.finalStatusLabel(course.finalStatus),
                                when (course.finalStatus) {
                                    Course.FINAL_READY -> StatusInfo
                                    Course.FINAL_FINALIZED -> StatusSuccess
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            // Derived follow-up flag (never a stored field)
                            // account is guaranteed non-null here (course != null).
                            val followingUp = course.finalStatus != Course.FINAL_FINALIZED &&
                                (account!!.installmentUnpaidCount > 0 ||
                                    account.certificatePendingCount > 0 ||
                                    account.activeCount > 0)
                            if (followingUp) {
                                StatusRow("پیگیری", "در حال پیگیری تعهدات", StatusWarning, outlined = true)
                            }
                        }
                    }
                }
            }

            // ---- Capacity card ----
            item {
                if (course.capacity > 0) {
                    StatCardPro(
                        title = if (isCapacityFull) "ظرفیت تکمیل است" else "ظرفیت دوره",
                        value = "${PersianDateUtil.toPersianDigits(validEnrollments.size.toString())} از ${PersianDateUtil.toPersianDigits(course.capacity.toString())}",
                        icon = Icons.Default.People,
                        tint = if (isCapacityFull) StatusWarning else MaterialTheme.colorScheme.primary,
                        progress = validEnrollments.size.toFloat() / course.capacity.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    StatCardPro(
                        title = "ظرفیت دوره",
                        value = "بدون محدودیت (${PersianDateUtil.toPersianDigits(validEnrollments.size.toString())} ثبت‌نام)",
                        icon = Icons.Default.People,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- Tabs ----
            item {
                SegmentedTabs(
                    labels = listOf("هنرجویان", "حساب دوره", "مدارک", "بررسی پایان"),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }

            // Inline warning for enroll-blocked states (students tab)
            if (selectedTab == 0 && (isClosed || isCapacityFull || course.finalStatus == Course.FINAL_FINALIZED)) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = StatusWarning.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusWarning.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = when {
                                course.finalStatus == Course.FINAL_FINALIZED ->
                                    "این دوره پایان کامل تأیید شده و ثبت‌نام جدید نمی‌پذیرد."
                                isClosed ->
                                    "ثبت‌نام این دوره توسط مدیر بسته شده است؛ برای ثبت‌نام جدید ابتدا آن را بازگشایی کنید."
                                else ->
                                    "ظرفیت این دوره تکمیل است؛ ثبت‌نام جدید فقط با ثبت‌نام استثنایی (سرریز ظرفیت) ممکن است."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = StatusWarning,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> StudentsTab(
                    enrollments = enrollments,
                    currencyUnit = currencyUnit
                )

                1 -> AccountTab(
                    acc = account!!, // non-null whenever course != null
                    installments = installments,
                    currencyUnit = currencyUnit
                )

                2 -> CertificatesTab(
                    enrollments = enrollments,
                    onSetStatus = { enrollmentId, status -> viewModel.setCertificateStatus(enrollmentId, status) }
                )

                3 -> CompletionTab(
                    course = course,
                    completionState = completionState,
                    isValidCourse = completionState.courseId == course.id,
                    onMarkReady = { showReadyConfirm = true },
                    onFinalize = { showFinalizeConfirm = true },
                    onCloseEnrollment = { showCloseConfirm = true },
                    onOpenEnrollment = { showOpenConfirm = true },
                    onHoldingEnd = { showHoldingEndDialog = true }
                )
            }
        }
    }

    // ---- Enroll dialog (pre-selected to this course) ----
    if (showEnrollDialog) {
        EnrollStudentDialog(
            students = students,
            courses = listOf(course),
            initialCourse = course,
            existingEnrollments = allEnrollments,
            onDismiss = { showEnrollDialog = false },
            onEnroll = { student, crs, startDate, discount, count, amounts, firstDate, firstPaidNow, notes ->
                showEnrollDialog = false
                pendingEnrollment = PendingEnrollmentPayload(
                    student = student,
                    course = crs,
                    startDateShamsi = startDate,
                    discount = discount,
                    installmentCount = count,
                    customAmounts = amounts,
                    firstInstallmentDateShamsi = firstDate,
                    firstInstallmentPaidNow = firstPaidNow,
                    notes = notes
                )
                viewModel.enrollStudent(
                    student = student,
                    course = crs,
                    startDateShamsi = startDate,
                    discount = discount,
                    installmentCount = count,
                    customInstallmentAmounts = amounts,
                    firstInstallmentDateShamsi = firstDate,
                    firstInstallmentPaidNow = firstPaidNow,
                    notes = notes,
                    allowOverCapacity = false
                ) { ok, msg ->
                    // Capacity is the ONLY failure that offers the explicit
                    // manager override — never any other error.
                    if (!ok && msg?.contains("ظرفیت") == true) {
                        showOverrideConfirm = true
                    }
                }
            }
        )
    }

    if (showOverrideConfirm) {
        AlertDialog(
            onDismissRequest = { showOverrideConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("ثبت‌نام استثنایی", fontWeight = FontWeight.Bold) },
            text = {
                Text("ثبت‌نام استثنایی با سرریز ظرفیت؟ ظرفیت تغییر نمی‌کند و این ثبت‌نام با نشان «ثبت‌نام استثنایی» ثبت می‌شود.")
            },
            confirmButton = {
                Button(onClick = {
                    showOverrideConfirm = false
                    val p = pendingEnrollment
                    if (p != null) {
                        viewModel.enrollStudent(
                            student = p.student,
                            course = p.course,
                            startDateShamsi = p.startDateShamsi,
                            discount = p.discount,
                            installmentCount = p.installmentCount,
                            customInstallmentAmounts = p.customAmounts,
                            firstInstallmentDateShamsi = p.firstInstallmentDateShamsi,
                            firstInstallmentPaidNow = p.firstInstallmentPaidNow,
                            notes = p.notes,
                            allowOverCapacity = true
                        ) { _, _ -> }
                        pendingEnrollment = null
                    }
                }) { Text("تأیید ثبت‌نام استثنایی") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverrideConfirm = false
                    pendingEnrollment = null
                }) { Text("انصراف") }
            }
        )
    }

    // ---- Enrollment open/close confirms ----
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("بستن ثبت‌نام", fontWeight = FontWeight.Bold) },
            text = { Text("ثبت‌نام جدید برای این دوره متوقف می‌شود؛ اقساط، مدارک و داده‌های مالی دست‌نخورده می‌مانند.") },
            confirmButton = {
                Button(onClick = {
                    showCloseConfirm = false
                    viewModel.closeEnrollmentManually(course.id)
                }) { Text("بستن ثبت‌نام") }
            },
            dismissButton = { TextButton(onClick = { showCloseConfirm = false }) { Text("انصراف") } }
        )
    }

    if (showOpenConfirm) {
        AlertDialog(
            onDismissRequest = { showOpenConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("بازگشایی ثبت‌نام", fontWeight = FontWeight.Bold) },
            text = { Text("ثبت‌نام دوباره باز می‌شود. اگر ظرفیت پر باشد، وضعیت «ظرفیت تکمیل» برمی‌گردد.") },
            confirmButton = {
                Button(onClick = {
                    showOpenConfirm = false
                    viewModel.openEnrollment(course.id)
                }) { Text("بازگشایی") }
            },
            dismissButton = { TextButton(onClick = { showOpenConfirm = false }) { Text("انصراف") } }
        )
    }

    // ---- Holding end (پایان برگزاری) with Jalali end date ----
    if (showHoldingEndDialog) {
        HoldingEndDialog(
            course = course,
            onDismiss = { showHoldingEndDialog = false },
            onConfirm = { endShamsi ->
                showHoldingEndDialog = false
                viewModel.setCourseHoldingEnded(
                    courseId = course.id,
                    endShamsi = endShamsi,
                    endEpoch = PersianDateUtil.parseShamsiToEpoch(endShamsi)
                )
            }
        )
    }

    // ---- Finalize workflow: gate 1 (mark READY) ----
    if (showReadyConfirm) {
        AlertDialog(
            onDismissRequest = { showReadyConfirm = false },
            shape = RoundedCornerShape(20.dp),
            title = { Text("بررسی و آماده‌سازی پایان", fontWeight = FontWeight.Bold) },
            text = { Text("وضعیت دوره به «آماده بررسی پایان» تغییر می‌کند. اگر مانده تعهدی وجود داشته باشد، آماده‌سازی رد می‌شود.") },
            confirmButton = {
                Button(onClick = {
                    showReadyConfirm = false
                    viewModel.markCourseReady(course.id)
                }) { Text("آماده‌سازی پایان") }
            },
            dismissButton = { TextButton(onClick = { showReadyConfirm = false }) { Text("انصراف") } }
        )
    }

    // ---- Finalize workflow: gate 2 (FINALIZED) with double confirm ----
    if (showFinalizeConfirm) {
        if (!finalizeDoubleConfirm) {
            AlertDialog(
                onDismissRequest = { showFinalizeConfirm = false; finalizeDoubleConfirm = false },
                shape = RoundedCornerShape(20.dp),
                title = { Text("تأیید پایان کامل دوره", fontWeight = FontWeight.Bold) },
                text = { Text("این اقدام وضعیت دوره را به «پایان کامل تأییدشده» تغییر می‌دهد و ثبت‌نام جدید را برای همیشه می‌بندد. مطمئن هستید؟") },
                confirmButton = {
                    Button(onClick = { finalizeDoubleConfirm = true }) { Text("بله، ادامه") }
                },
                dismissButton = {
                    TextButton(onClick = { showFinalizeConfirm = false }) { Text("انصراف") }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showFinalizeConfirm = false; finalizeDoubleConfirm = false },
                shape = RoundedCornerShape(20.dp),
                title = { Text("تأیید نهایی", fontWeight = FontWeight.Bold) },
                text = { Text("پایان کامل دوره برگشت‌پذیر نیست؛ اطلاعات و سوابق مالی آن حفظ می‌شوند. تأیید می‌کنید؟") },
                confirmButton = {
                    Button(onClick = {
                        showFinalizeConfirm = false
                        finalizeDoubleConfirm = false
                        viewModel.finalizeCourse(course.id)
                    }) { Text("تأیید پایان کامل") }
                },
                dismissButton = {
                    TextButton(onClick = { showFinalizeConfirm = false; finalizeDoubleConfirm = false }) { Text("انصراف") }
                }
            )
        }
    }
}

/** One labeled status row inside the header (پذیرش / برگزاری / وضعیت نهایی). */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    color: Color,
    outlined: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(70.dp)
        )
        StatusBadge(value, color, outlined = outlined)
    }
}

/** Simple holder type for the retryable (capacity-override) enrollment. */
private data class PendingEnrollmentPayload(
    val student: com.example.data.model.Student,
    val course: Course,
    val startDateShamsi: String,
    val discount: Long,
    val installmentCount: Int,
    val customAmounts: List<Long>,
    val firstInstallmentDateShamsi: String,
    val firstInstallmentPaidNow: Boolean,
    val notes: String
)

@Composable
private fun CourseDetailsMissingScreen(onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Default.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(42.dp)
            )
            Text("این دوره پیدا نشد؛ ممکن است حذف شده باشد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClose) { Text("بازگشت") }
        }
    }
}

/** «ثبت پایان برگزاری» dialog with a Jalali end-date field. */
@Composable
private fun HoldingEndDialog(
    course: Course,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var endShamsi by remember { mutableStateOf(course.endShamsi.ifBlank { PersianDateUtil.currentShamsiString() }) }
    com.example.ui.components.AppFormDialog(
        title = "ثبت پایان برگزاری",
        subtitle = "تاریخ پایان کلاس‌های این دوره را ثبت کنید (اقساط و مدارک زنده می‌مانند)",
        icon = Icons.Default.CalendarMonth,
        onDismiss = onDismiss,
        confirmText = "ثبت پایان برگزاری",
        onConfirm = { onConfirm(endShamsi) }
    ) {
        com.example.ui.components.JalaliDateField(
            value = endShamsi,
            onValueChange = { endShamsi = it },
            label = "تاریخ پایان برگزاری"
        )
    }
}

// ======================================================================
// Tab 0 — هنرجویان
// ======================================================================
private fun LazyListScope.StudentsTab(
    enrollments: List<Enrollment>,
    currencyUnit: String
) {
    if (enrollments.isEmpty()) {
        item {
            EmptyHintBox("هنوز هیچ هنرجویی در این دوره ثبت‌نام نکرده است؛ با دکمهٔ پایین اولین ثبت‌نام را انجام دهید.")
        }
        return
    }
    item {
        Text(
            text = "${PersianDateUtil.toPersianDigits(enrollments.size.toString())} ثبت‌نام در این دوره",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    items(enrollments, key = { it.id }) { en ->
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
                    Text(
                        text = en.studentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    StatusBadge(
                        when (en.status) {
                            "ACTIVE" -> "فعال"
                            "COMPLETED" -> "تکمیل شده"
                            "CANCELLED" -> "لغو شده"
                            else -> en.status
                        },
                        when (en.status) {
                            "ACTIVE" -> StatusSuccess
                            "COMPLETED" -> StatusInfo
                            else -> StatusError
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "شهریه نهایی: ${PersianDateUtil.formatCurrency(en.finalFee, currencyUnit)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "ثبت‌نام: ${PersianDateUtil.toPersianDigits(en.registrationDateShamsi)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusBadge(
                        "مدرک: ${CourseStatusLabels.certificateStatusLabel(en.certificateStatus)}",
                        when (en.certificateStatus) {
                            Enrollment.CERTIFICATE_PENDING -> StatusWarning
                            Enrollment.CERTIFICATE_ISSUED -> StatusInfo
                            Enrollment.CERTIFICATE_DELIVERED -> StatusSuccess
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (en.enrolledViaOverride) {
                        StatusBadge("ثبت‌نام استثنایی", StatusWarning, outlined = true)
                    }
                }
            }
        }
    }
}

// ======================================================================
// Tab 1 — حساب دوره
// ======================================================================
private fun LazyListScope.AccountTab(
    acc: CourseAccount.CourseAccountReport,
    installments: List<com.example.data.model.Installment>,
    currencyUnit: String
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCardPro(
                    title = "شهریه قراردادشده",
                    value = PersianDateUtil.formatCurrency(acc.tuitionContracted, currencyUnit),
                    icon = Icons.Default.AttachMoney,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatCardPro(
                    title = "دریافتی قطعی",
                    value = PersianDateUtil.formatCurrency(acc.netReceived, currencyUnit),
                    icon = Icons.Default.AccountBalanceWallet,
                    tint = StatusSuccess,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCardPro(
                    title = "باقی‌مانده",
                    value = PersianDateUtil.formatCurrency(acc.outstanding, currencyUnit),
                    icon = Icons.Default.AccountBalanceWallet,
                    tint = if (acc.outstanding > 0) StatusWarning else StatusSuccess,
                    modifier = Modifier.weight(1f)
                )
                StatCardPro(
                    title = "برگشتی‌ها",
                    value = PersianDateUtil.formatCurrency(acc.refunded, currencyUnit),
                    icon = Icons.Default.TrendingDown,
                    tint = StatusError,
                    modifier = Modifier.weight(1f)
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
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "شمارنده‌ها",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                AccountCountRow("ثبت‌نام معتبر (شمارندهٔ ظرفیت)", acc.validEnrollmentCount)
                AccountCountRow("هنرجویان فعال", acc.activeCount)
                AccountCountRow("دوره‌های تکمیل‌شده", acc.completedCount)
                AccountCountRow("ثبت‌نام‌های لغوشده", acc.cancelledCount)
                AccountCountRow("اقساط پرداخت‌نشده", acc.installmentUnpaidCount)
                AccountCountRow("مدارک در انتظار", acc.certificatePendingCount)
                AccountCountRow("مدارک صادرشده", acc.certificateIssuedCount)
                AccountCountRow("مدارک تحویل‌شده", acc.certificateDeliveredCount)
            }
        }
    }

    // Unpaid installment labels — the manager's collection list.
    val unpaid = installments
        .filter { it.courseId == acc.courseId && it.status != "CANCELLED" && it.remainingAmount > 0 }
        .sortedBy { it.dueDateEpoch }
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "اقساط پرداخت‌نشده",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (unpaid.isEmpty()) StatusSuccess else StatusWarning
                )
                if (unpaid.isEmpty()) {
                    Text(
                        text = "همهٔ اقساط این دوره تسویه شده‌اند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    unpaid.forEach { inst ->
                        Text(
                            text = "• ${inst.studentName} – قسط ${PersianDateUtil.toPersianDigits(inst.installmentNumber.toString())} " +
                                "– مانده ${PersianDateUtil.formatCurrency(inst.remainingAmount, currencyUnit)}" +
                                if (inst.dueDateShamsi.isNotBlank()) " – سررسید ${PersianDateUtil.toPersianDigits(inst.dueDateShamsi)}" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCountRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = PersianDateUtil.toPersianDigits(count.toString()),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ======================================================================
// Tab 2 — مدارک
// ======================================================================
private fun LazyListScope.CertificatesTab(
    enrollments: List<Enrollment>,
    onSetStatus: (enrollmentId: String, status: String) -> Unit
) {
    val trackable = enrollments.filter { it.status != "CANCELLED" }
    if (trackable.isEmpty()) {
        item {
            EmptyHintBox("ثبت‌نام فعالی برای صدور مدرک وجود ندارد.")
        }
        return
    }
    item {
        Text(
            text = "چرخهٔ مدرک: در انتظار ← صادر شده ← تحویل شده",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    items(trackable, key = { it.id }) { en ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = en.studentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    StatusBadge(
                        CourseStatusLabels.certificateStatusLabel(en.certificateStatus),
                        when (en.certificateStatus) {
                            Enrollment.CERTIFICATE_PENDING -> StatusWarning
                            Enrollment.CERTIFICATE_ISSUED -> StatusInfo
                            Enrollment.CERTIFICATE_DELIVERED -> StatusSuccess
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (en.certificateStatus) {
                        Enrollment.CERTIFICATE_PENDING -> {
                            Button(
                                onClick = { onSetStatus(en.id, Enrollment.CERTIFICATE_ISSUED) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("انتقال به: صادر شده", maxLines = 1) }
                        }
                        Enrollment.CERTIFICATE_ISSUED -> {
                            Button(
                                onClick = { onSetStatus(en.id, Enrollment.CERTIFICATE_DELIVERED) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) { Text("انتقال به: تحویل شده", maxLines = 1) }
                        }
                        else -> {}
                    }
                    if (en.certificateStatus != Enrollment.CERTIFICATE_NOT_REQUIRED) {
                        OutlinedButton(
                            onClick = { onSetStatus(en.id, Enrollment.CERTIFICATE_NOT_REQUIRED) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("مدرک لازم نیست", maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { onSetStatus(en.id, Enrollment.CERTIFICATE_PENDING) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("بازگشت به «در انتظار»", maxLines = 1) }
                    }
                }
            }
        }
    }
}

// ======================================================================
// Tab 3 — بررسی پایان
// ======================================================================
private fun LazyListScope.CompletionTab(
    course: Course,
    completionState: MainViewModel.CourseCompletionUiState,
    isValidCourse: Boolean,
    onMarkReady: () -> Unit,
    onFinalize: () -> Unit,
    onCloseEnrollment: () -> Unit,
    onOpenEnrollment: () -> Unit,
    onHoldingEnd: () -> Unit
) {
    item {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "چک‌لیست شرایط پایان",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                when {
                    completionState.loading -> {
                        Text(
                            text = "در حال بررسی شرایط پایان…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    completionState.error != null -> {
                        Text(
                            text = completionState.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError
                        )
                    }

                    !isValidCourse || completionState.status == null -> {
                        Text(
                            text = "بررسی پایان در جریان است یا برای این دوره هنوز اجرا نشده است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        val st = completionState.status
                        if (st.isReady &&
                            st.activeStudents.isEmpty() &&
                            st.unpaidInstallments.isEmpty() &&
                            st.missingCertificates.isEmpty()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "شرایط پایان کامل برقرار است",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusSuccess
                                )
                            }
                        } else {
                            CompletionSection("هنرجویان فعال/تعیین‌تکلیف‌نشده", st.activeStudents, StatusWarning)
                            CompletionSection("اقساط پرداخت‌نشده", st.unpaidInstallments, StatusError)
                            CompletionSection("مدارک منتظر", st.missingCertificates, StatusInfo)
                        }
                    }
                }
            }
        }
    }

    // After finalize: history-preservation note
    if (course.finalStatus == Course.FINAL_FINALIZED) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = StatusSuccess.copy(alpha = 0.10f),
                border = androidx.compose.foundation.BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "این دوره پایان کامل تأیید شده است. اطلاعات و سوابق مالی این دوره حفظ می‌شوند و چیزی حذف نمی‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusSuccess,
                    modifier = Modifier.padding(12.dp)
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
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "اقدامات مدیر",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Registration open/close
                if (course.enrollmentStatus == Course.ENROLLMENT_CLOSED) {
                    Button(
                        onClick = onOpenEnrollment,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("بازگشایی ثبت‌نام")
                    }
                } else {
                    OutlinedButton(
                        onClick = onCloseEnrollment,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("بستن ثبت‌نام")
                    }
                }

                // Holding end
                if (course.holdingStatus == Course.HOLDING_ONGOING) {
                    OutlinedButton(
                        onClick = onHoldingEnd,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ثبت پایان برگزاری")
                    }
                }

                // Gate 1: mark READY
                if (course.finalStatus != Course.FINAL_FINALIZED) {
                    OutlinedButton(
                        onClick = onMarkReady,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("بررسی و آماده‌سازی پایان")
                    }
                }

                // Gate 2: finalize (only from READY)
                Button(
                    onClick = onFinalize,
                    enabled = course.finalStatus == Course.FINAL_READY,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (course.finalStatus) {
                            Course.FINAL_FINALIZED -> "پایان کامل تأیید شده است"
                            Course.FINAL_READY -> "تأیید پایان کامل دوره"
                            else -> "تأیید پایان کامل (نیازمند آماده‌سازی)"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionSection(title: String, items: List<String>, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Text(
                text = "$title (${PersianDateUtil.toPersianDigits(items.size.toString())})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
        if (items.isEmpty()) {
            Text(
                text = "موردی وجود ندارد ✓",
                style = MaterialTheme.typography.bodySmall,
                color = StatusSuccess,
                modifier = Modifier.padding(start = 14.dp)
            )
        } else {
            items.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 14.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** Friendly empty-state hint box used by the tabs. */
@Composable
private fun EmptyHintBox(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}
