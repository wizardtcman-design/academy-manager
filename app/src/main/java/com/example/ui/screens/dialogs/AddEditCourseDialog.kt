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
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.model.Course
import com.example.data.util.PersianDateUtil

@Composable
fun AddEditCourseDialog(
    courseToEdit: Course? = null,
    currencyUnit: String = "تومان",
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        durationMonths: Int,
        tuitionFee: Long,
        installmentCount: Int,
        description: String,
        isActive: Boolean
    ) -> Unit
) {
    var title by remember { mutableStateOf(courseToEdit?.title ?: "") }
    var durationMonthsText by remember { mutableStateOf(courseToEdit?.durationMonths?.toString() ?: "3") }
    var tuitionFeeText by remember { mutableStateOf(courseToEdit?.tuitionFee?.toString() ?: "4500000") }
    var installmentCountText by remember { mutableStateOf(courseToEdit?.installmentCount?.toString() ?: "3") }
    var description by remember { mutableStateOf(courseToEdit?.description ?: "") }
    var isActive by remember { mutableStateOf(courseToEdit?.isActive ?: true) }

    var titleError by remember { mutableStateOf(false) }
    var feeError by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (courseToEdit == null) "تعریف دوره آموزشی جدید" else "ویرایش دوره آموزشی",
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
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        titleError = false
                    },
                    label = { Text("نام دوره آموزشی *") },
                    leadingIcon = { Icon(Icons.Default.School, contentDescription = null) },
                    singleLine = true,
                    isError = titleError,
                    supportingText = if (titleError) { { Text("نام دوره الزامی است") } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("course_title_input")
                )

                // Tuition Fee
                OutlinedTextField(
                    value = tuitionFeeText,
                    onValueChange = {
                        tuitionFeeText = it.filter { ch -> ch.isDigit() }
                        feeError = false
                    },
                    label = { Text("شهریه دوره ($currencyUnit) *") },
                    leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                    singleLine = true,
                    isError = feeError,
                    supportingText = {
                        val feeNum = tuitionFeeText.toLongOrNull() ?: 0L
                        if (feeNum > 0) {
                            Text(PersianDateUtil.formatCurrency(feeNum, currencyUnit))
                        } else if (feeError) {
                            Text("شهریه باید بزرگتر از صفر باشد")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("course_fee_input")
                )

                // Duration Months (Default: 3 months)
                OutlinedTextField(
                    value = durationMonthsText,
                    onValueChange = { durationMonthsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("مدت دوره (ماه) - پیش‌فرض ۳ ماه") },
                    leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Installment Count (Default: 3 installments)
                OutlinedTextField(
                    value = installmentCountText,
                    onValueChange = { installmentCountText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("تعداد اقساط شهریه - پیش‌فرض ۳ قسط") },
                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("توضیحات و سرفصل‌ها (اختیاری)") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Active toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "وضعیت دوره (فعال و در حال ثبت‌نام)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isActive,
                        onCheckedChange = { isActive = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    var hasError = false
                    if (title.isBlank()) {
                        titleError = true
                        hasError = true
                    }
                    val fee = tuitionFeeText.toLongOrNull() ?: 0L
                    if (fee <= 0) {
                        feeError = true
                        hasError = true
                    }
                    val duration = durationMonthsText.toIntOrNull() ?: 3
                    val installments = installmentCountText.toIntOrNull() ?: 3

                    if (!hasError) {
                        onConfirm(title, duration, fee, installments, description, isActive)
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("save_course_button")
            ) {
                Text(if (courseToEdit == null) "ایجاد دوره" else "ذخیره تغییرات")
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
