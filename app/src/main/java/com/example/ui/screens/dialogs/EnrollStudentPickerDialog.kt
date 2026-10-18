package com.example.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.Student

/**
 * Small first step for the «ثبت‌نام» shortcut: pick which student is being
 * enrolled, then the full enrolment form opens with that student preselected.
 */
@Composable
fun EnrollStudentPickerDialog(
    students: List<Student>,
    onDismiss: () -> Unit,
    onPick: (Student) -> Unit
) {
    com.example.ui.components.AppFormDialog(
        title = "انتخاب هنرجو",
        subtitle = "برای ثبت‌نام، هنرجو را انتخاب کنید",
        icon = Icons.Default.MenuBook,
        onDismiss = onDismiss,
        confirmText = "بستن",
        onConfirm = onDismiss,
        dismissText = "انصراف",
        testTag = "enroll_picker_dialog"
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .testTag("enroll_picker_list")
            ) {
                items(students, key = { it.id }) { st ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(st) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(st.fullName, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "${st.studentCode} · ${st.phone}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        Spacer(Modifier.padding(2.dp))
    }
}
