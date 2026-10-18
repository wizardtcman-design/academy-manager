package com.example.ui.screens.dialogs

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
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

    val isEditing = courseToEdit != null

    fun submit() {
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
    }

    com.example.ui.components.AppFormDialog(
        title = if (isEditing) "ویرایش دوره" else "تعریف دورهٔ جدید",
        subtitle = "عنوان، شهریه و تعداد اقساط دوره را مشخص کنید",
        icon = Icons.Default.School,
        onDismiss = onDismiss,
        confirmText = if (isEditing) "ذخیره تغییرات" else "ایجاد دوره",
        onConfirm = { submit() },
        maxHeight = 720,
        testTag = "course_form_dialog"
    ) {
        com.example.ui.components.FormSection(title = "مشخصات دوره", icon = Icons.Default.School) {
            com.example.ui.components.FormTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = "نام دورهٔ آموزشی *",
                leadingIcon = Icons.Default.School,
                isError = titleError,
                supportingText = if (titleError) "نام دوره الزامی است" else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                testTag = "course_title_input"
            )
            com.example.ui.components.FormTextField(
                value = description,
                onValueChange = { description = it },
                label = "توضیحات و سرفصل‌ها (اختیاری)",
                singleLine = false,
                minLines = 2,
                maxLines = 3
            )
        }

        com.example.ui.components.FormSection(
            title = "شهریه و اقساط",
            icon = Icons.Default.AttachMoney,
            accent = MaterialTheme.colorScheme.tertiary
        ) {
            com.example.ui.components.FormTextField(
                value = tuitionFeeText,
                onValueChange = { tuitionFeeText = it.filter { ch -> ch.isDigit() }; feeError = false },
                label = "شهریهٔ دوره ($currencyUnit) *",
                leadingIcon = Icons.Default.AttachMoney,
                isError = feeError,
                supportingText = run {
                    val feeNum = tuitionFeeText.toLongOrNull() ?: 0L
                    when {
                        feeNum > 0 -> PersianDateUtil.formatCurrency(feeNum, currencyUnit)
                        feeError -> "شهریه باید بزرگ‌تر از صفر باشد"
                        else -> null
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                testTag = "course_fee_input"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.example.ui.components.FormTextField(
                    value = durationMonthsText,
                    onValueChange = { durationMonthsText = it.filter { ch -> ch.isDigit() } },
                    label = "مدت (ماه)",
                    leadingIcon = Icons.Default.DateRange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                com.example.ui.components.FormTextField(
                    value = installmentCountText,
                    onValueChange = { installmentCountText = it.filter { ch -> ch.isDigit() } },
                    label = "تعداد اقساط",
                    leadingIcon = Icons.Default.FormatListNumbered,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("دوره فعال و در حال ثبت‌نام باشد", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = isActive, onCheckedChange = { isActive = it })
        }
        Spacer(Modifier.height(4.dp))
    }
}
