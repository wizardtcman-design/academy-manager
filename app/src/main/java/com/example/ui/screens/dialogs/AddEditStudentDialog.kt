package com.example.ui.screens.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.Student

@Composable
fun AddEditStudentDialog(
    studentToEdit: Student? = null,
    onDismiss: () -> Unit,
    onConfirm: (firstName: String, lastName: String, phone: String, nationalCode: String, notes: String) -> Unit
) {
    var firstName by remember { mutableStateOf(studentToEdit?.firstName ?: "") }
    var lastName by remember { mutableStateOf(studentToEdit?.lastName ?: "") }
    var phone by remember { mutableStateOf(studentToEdit?.phone ?: "") }
    var nationalCode by remember { mutableStateOf(studentToEdit?.nationalCode ?: "") }
    var notes by remember { mutableStateOf(studentToEdit?.notes ?: "") }

    var firstNameError by remember { mutableStateOf(false) }
    var lastNameError by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf(false) }
    var phoneErrorText by remember { mutableStateOf("شماره موبایل الزامی است") }
    var nationalCodeError by remember { mutableStateOf(false) }

    val isEditing = studentToEdit != null

    fun submit() {
        var hasError = false
        if (firstName.isBlank()) {
            firstNameError = true
            hasError = true
        }
        if (lastName.isBlank()) {
            lastNameError = true
            hasError = true
        }
        if (phone.isBlank()) {
            phoneError = true
            phoneErrorText = "شماره موبایل الزامی است"
            hasError = true
        } else if (!com.example.data.util.ValidationUtil.isValidMobile(phone)) {
            phoneError = true
            phoneErrorText = "شماره موبایل معتبر نیست (مثال: 09121234567)"
            hasError = true
        }
        if (nationalCode.isNotBlank() && !com.example.data.util.ValidationUtil.isValidNationalCode(nationalCode)) {
            nationalCodeError = true
            hasError = true
        }
        if (!hasError) {
            onConfirm(
                firstName,
                lastName,
                com.example.data.util.ValidationUtil.normalizePhone(phone),
                nationalCode.trim(),
                notes
            )
        }
    }

    com.example.ui.components.AppFormDialog(
        title = if (isEditing) "ویرایش مشخصات هنرجو" else "ثبت هنرجوی جدید",
        subtitle = if (isEditing) "تغییر اطلاعات شناسنامه‌ای و تماس" else "مشخصات اصلی هنرجو را کامل کنید",
        icon = Icons.Default.PersonAdd,
        onDismiss = onDismiss,
        confirmText = if (isEditing) "ذخیره تغییرات" else "ثبت هنرجو",
        onConfirm = { submit() },
        maxHeight = 720,
        testTag = "student_form_dialog"
    ) {
        com.example.ui.components.FormSection(
            title = "هویت هنرجو",
            icon = Icons.Default.Badge
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.example.ui.components.FormTextField(
                    value = firstName,
                    onValueChange = { firstName = it; firstNameError = false },
                    label = "نام *",
                    leadingIcon = Icons.Default.Person,
                    isError = firstNameError,
                    supportingText = if (firstNameError) "نام الزامی است" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                    testTag = "student_firstname_input"
                )
                com.example.ui.components.FormTextField(
                    value = lastName,
                    onValueChange = { lastName = it; lastNameError = false },
                    label = "نام خانوادگی *",
                    leadingIcon = Icons.Default.Person,
                    isError = lastNameError,
                    supportingText = if (lastNameError) "نام خانوادگی الزامی است" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                    testTag = "student_lastname_input"
                )
            }
        }

        com.example.ui.components.FormSection(
            title = "تماس و شناسنامه",
            icon = Icons.Default.Phone,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            com.example.ui.components.FormTextField(
                value = phone,
                onValueChange = { phone = it; phoneError = false },
                label = "شماره موبایل *",
                leadingIcon = Icons.Default.Phone,
                isError = phoneError,
                supportingText = if (phoneError) phoneErrorText else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.weight(1f),
                testTag = "student_phone_input"
            )
            com.example.ui.components.FormTextField(
                value = nationalCode,
                onValueChange = {
                    if (it.length <= 10) {
                        nationalCode = it
                        nationalCodeError = false
                    }
                },
                label = "کد ملی (اختیاری)",
                leadingIcon = Icons.Default.Badge,
                isError = nationalCodeError,
                supportingText = if (nationalCodeError) "کد ملی وارد شده معتبر نیست" else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )
        }

        com.example.ui.components.FormSection(
            title = "توضیحات و سوابق",
            icon = Icons.Default.Description,
            accent = MaterialTheme.colorScheme.secondary
        ) {
            com.example.ui.components.FormTextField(
                value = notes,
                onValueChange = { notes = it },
                label = "توضیحات و سوابق (اختیاری)",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                leadingIcon = null
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
