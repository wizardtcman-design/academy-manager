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

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (studentToEdit == null) "ثبت هنرجوی جدید" else "ویرایش مشخصات هنرجو",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // First name
                OutlinedTextField(
                    value = firstName,
                    onValueChange = {
                        firstName = it
                        firstNameError = false
                    },
                    label = { Text("نام *") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    isError = firstNameError,
                    supportingText = if (firstNameError) { { Text("نام الزامی است") } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("student_firstname_input")
                )

                // Last name
                OutlinedTextField(
                    value = lastName,
                    onValueChange = {
                        lastName = it
                        lastNameError = false
                    },
                    label = { Text("نام خانوادگی *") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    singleLine = true,
                    isError = lastNameError,
                    supportingText = if (lastNameError) { { Text("نام خانوادگی الزامی است") } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("student_lastname_input")
                )

                // Phone
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        phoneError = false
                    },
                    label = { Text("شماره موبایل *") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                    singleLine = true,
                    isError = phoneError,
                    supportingText = if (phoneError) { { Text(phoneErrorText) } } else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("student_phone_input")
                )

                // National Code (Optional)
                OutlinedTextField(
                    value = nationalCode,
                    onValueChange = { if (it.length <= 10) { nationalCode = it; nationalCodeError = false } },
                    label = { Text("کد ملی (اختیاری)") },
                    isError = nationalCodeError,
                    supportingText = if (nationalCodeError) { { Text("کد ملی وارد شده معتبر نیست") } } else null,
                    leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("توضیحات و سوابق (اختیاری)") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
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
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("save_student_button")
            ) {
                Text(if (studentToEdit == null) "ثبت هنرجو" else "ذخیره تغییرات")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("انصراف")
            }
        }
    )
}
