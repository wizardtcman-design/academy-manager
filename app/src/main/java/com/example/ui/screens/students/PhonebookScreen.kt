package com.example.ui.screens.students

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Student
import com.example.data.util.PersianDateUtil
import com.example.ui.screens.dialogs.AddEditStudentDialog
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusSuccess
import com.example.ui.viewmodel.MainViewModel

/**
 * دفترچه تلفن مرکزی: the SAME Student entity (no separate contact store) used
 * as a central, searchable phonebook. Calling / SMS are plain system intents.
 * NO group-SMS feature here — explicitly out of scope. A contact may be
 * enrolled in several courses; the phonebook is independent of enrollment.
 */
@Composable
fun PhonebookScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val students by viewModel.students.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<Student?>(null) }

    // Local search over name / family / phone / national code. The shared
    // students flow (repo search) is filtered in-memory to keep the phonebook
    // search independent from the Students tab query state.
    val filtered = remember(students, searchQuery) {
        val q = PersianDateUtil.toLatinDigits(searchQuery.trim()).lowercase()
        if (q.isEmpty()) students.sortedBy { it.fullName }
        else students.filter { s ->
            s.fullName.lowercase().contains(q) ||
                PersianDateUtil.toLatinDigits(s.phone).contains(q) ||
                s.nationalCode.contains(q)
        }.sortedBy { it.fullName }
    }

    Scaffold(
        bottomBar = {
            com.example.ui.components.PinnedActionBar(
                title = "مخاطب جدید",
                icon = Icons.Default.PersonAdd,
                subtitle = PersianDateUtil.toPersianDigits(students.size.toString()),
                onClick = { showAddDialog = true },
                testTag = "fab_add_contact"
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("phonebook_back_button")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "بازگشت",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "دفترچه تلفن",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "دفترچه تلفن مرکزی هنرجویان",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Scope note: the phonebook is separate from course enrollment.
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = StatusInfo.copy(alpha = 0.10f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusInfo.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "این دفترچه از ثبت‌نام دوره جدا است؛ یک مخاطب می‌تواند در چند دوره ثبت‌نام کند.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusInfo,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Search bar (same visual style as the Students screen search)
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("phonebook_search_bar"),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "جستجو",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "جستجوی نام، شماره یا کدملی",
                                            fontSize = 12.5.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 50.dp),
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
                                        Icons.Default.Contacts,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (searchQuery.isBlank()) "هنوز مخاطبی ثبت نشده است؛ با دکمهٔ پایین اولین مخاطب را اضافه کنید."
                                else "مخاطبی با این جستجو پیدا نشد.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { contact ->
                    PhonebookRow(
                        contact = contact,
                        onCall = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}"))
                                )
                            }.onFailure {
                                viewModel.showMessage("اجرای برنامهٔ تماس ممکن نشد.")
                            }
                        },
                        onSms = {
                            runCatching {
                                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.phone}"))
                                context.startActivity(smsIntent)
                            }.onFailure {
                                viewModel.showMessage("اجرای برنامهٔ پیامک ممکن نشد.")
                            }
                        },
                        onOpen = { contactToEdit = contact }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { firstName, lastName, phone, nationalCode, address, notes ->
                viewModel.addStudent(
                    firstName = firstName,
                    lastName = lastName,
                    phone = phone,
                    nationalCode = nationalCode,
                    address = address,
                    notes = notes
                ) { ok, msg ->
                    // The repository dedups by phone/national code — surface its
                    // Persian warning (e.g. duplicate contact) as a snackbar.
                    if (!ok && !msg.isNullOrBlank()) {
                        viewModel.showMessage(msg)
                    }
                }
                showAddDialog = false
            }
        )
    }

    // Editing reuses the EXISTING student edit dialog from the Students screen.
    if (contactToEdit != null) {
        AddEditStudentDialog(
            studentToEdit = contactToEdit,
            onDismiss = { contactToEdit = null },
            onConfirm = { fName, lName, phone, natCode, notes ->
                viewModel.updateStudent(
                    contactToEdit!!.copy(
                        firstName = fName,
                        lastName = lName,
                        phone = phone,
                        nationalCode = natCode,
                        notes = notes
                    )
                ) { _, _ -> }
                contactToEdit = null
            }
        )
    }
}

@Composable
private fun PhonebookRow(
    contact: Student,
    onCall: () -> Unit,
    onSms: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("phonebook_row_${contact.id}")
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = contact.fullName.trim().take(1).ifBlank { "؟" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = PersianDateUtil.toPersianDigits(contact.phone).ifBlank { "بدون شماره" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCall) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "تماس با ${contact.fullName}",
                    tint = StatusSuccess,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onSms) {
                Icon(
                    Icons.Default.Message,
                    contentDescription = "پیامک به ${contact.fullName}",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Manual contact form. Phone is required (system-wide identity); national
 * code / address / notes are optional here, unlike the enrollment-facing
 * student form. Duplicate phone numbers are refused by the repository and
 * its Persian warning is surfaced as a snackbar.
 */
@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        firstName: String,
        lastName: String,
        phone: String,
        nationalCode: String,
        address: String,
        notes: String
    ) -> Unit
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var nationalCode by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    var firstNameError by remember { mutableStateOf(false) }
    var lastNameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }

    com.example.ui.components.AppFormDialog(
        title = "مخاطب جدید",
        subtitle = "افزودن مخاطب به دفترچه تلفن مرکزی",
        icon = Icons.Default.PersonAdd,
        onDismiss = onDismiss,
        confirmText = "ذخیره مخاطب",
        onConfirm = {
            var hasError = false
            if (firstName.isBlank()) {
                firstNameError = true
                hasError = true
            }
            if (lastName.isBlank()) {
                lastNameError = true
                hasError = true
            }
            if (com.example.data.util.ValidationUtil.normalizePhone(phone).isBlank()) {
                phoneError = true
                hasError = true
            }
            if (!hasError) {
                onConfirm(
                    firstName.trim(),
                    lastName.trim(),
                    com.example.data.util.ValidationUtil.normalizePhone(phone),
                    nationalCode.trim(),
                    address.trim(),
                    notes.trim()
                )
            }
        },
        maxHeight = 760,
        testTag = "contact_form_dialog"
    ) {
        com.example.ui.components.FormSection(title = "مشخصات مخاطب", icon = Icons.Default.Contacts) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.example.ui.components.FormTextField(
                    value = firstName,
                    onValueChange = { firstName = it; firstNameError = false },
                    label = "نام *",
                    isError = firstNameError,
                    supportingText = if (firstNameError) "نام الزامی است" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                com.example.ui.components.FormTextField(
                    value = lastName,
                    onValueChange = { lastName = it; lastNameError = false },
                    label = "نام خانوادگی *",
                    isError = lastNameError,
                    supportingText = if (lastNameError) "نام خانوادگی الزامی است" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
            }
            com.example.ui.components.FormTextField(
                value = phone,
                onValueChange = { phone = it; phoneError = false },
                label = "شماره موبایل (۰۹…) *",
                isError = phoneError,
                supportingText = if (phoneError) "شماره موبایل الزامی است" else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next)
            )
            com.example.ui.components.FormTextField(
                value = nationalCode,
                onValueChange = { nationalCode = it },
                label = "کد ملی (اختیاری)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
            )
            com.example.ui.components.FormTextField(
                value = address,
                onValueChange = { address = it },
                label = "نشانی (اختیاری)",
                singleLine = false,
                minLines = 1,
                maxLines = 2
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
