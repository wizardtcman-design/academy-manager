package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.util.PersianDateUtil

private val monthNames = listOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
)

/**
 * Reads "1405/07/26" (Persian or Latin digits) into a JalaliDate, falling back
 * to today so an empty or half-typed value still opens on a sensible month.
 */
fun parseJalaliOrToday(text: String): PersianDateUtil.JalaliDate {
    val parts = PersianDateUtil.toLatinDigits(text.trim())
        .split('/', '-')
        .mapNotNull { it.trim().toIntOrNull() }
    if (parts.size == 3 && parts[0] > 1200 && parts[1] in 1..12) {
        val maxDay = PersianDateUtil.getDaysInMonth(parts[0], parts[1])
        return PersianDateUtil.JalaliDate(parts[0], parts[1], parts[2].coerceIn(1, maxDay))
    }
    return PersianDateUtil.currentJalali()
}

/**
 * A read-only Jalali date field: tapping it opens a picker with month, year and
 * day, so the user never has to type "1405/07/26" by hand.
 */
@Composable
fun JalaliDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }

    // Enabling the field but intercepting the click keeps the ripple while
    // still guaranteeing no keyboard can appear.
    OutlinedTextField(
        value = PersianDateUtil.toPersianDigits(value),
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label, fontSize = 12.5.sp) },
        leadingIcon = {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "انتخاب تاریخ")
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledLeadingIconColor = MaterialTheme.colorScheme.primary,
            disabledTrailingIconColor = MaterialTheme.colorScheme.primary
        ),
        modifier = modifier.fillMaxWidth()
    )

    if (open) {
        JalaliDatePickerDialog(
            initial = parseJalaliOrToday(value),
            onDismiss = { open = false },
            onPick = {
                onValueChange(it.format())
                open = false
            }
        )
    }
}

/** Month + year nav and a day grid, all in the Jalali calendar. */
@Composable
fun JalaliDatePickerDialog(
    initial: PersianDateUtil.JalaliDate = PersianDateUtil.currentJalali(),
    onDismiss: () -> Unit,
    onPick: (PersianDateUtil.JalaliDate) -> Unit
) {
    var year by remember { mutableIntStateOf(initial.year) }
    var month by remember { mutableIntStateOf(initial.month) }
    var day by remember { mutableIntStateOf(initial.day) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "انتخاب تاریخ (شمسی)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Month + year selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepBox(
                        modifier = Modifier.weight(1.6f),
                        title = monthNames[month - 1],
                        subtitle = "ماه",
                        onPrevious = { month = if (month == 1) 12 else month - 1 },
                        onNext = { month = if (month == 12) 1 else month + 1 }
                    )
                    StepBox(
                        modifier = Modifier.weight(1.3f),
                        title = PersianDateUtil.toPersianDigits(year.toString()),
                        subtitle = "سال",
                        onPrevious = { year -= 1 },
                        onNext = { year += 1 }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "روز",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        val today = PersianDateUtil.currentJalali()
                        year = today.year
                        month = today.month
                        day = today.day
                    }) {
                        Text("امروز", style = MaterialTheme.typography.labelMedium)
                    }
                }

                val maxDay = PersianDateUtil.getDaysInMonth(year, month)
                if (day > maxDay) day = maxDay
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(6) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (col in 0 until 7) {
                                val d = row * 7 + col + 1
                                if (d <= maxDay) {
                                    val selected = d == day
                                    androidx.compose.material3.Surface(
                                        onClick = { day = d },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                        border = if (selected) null else BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        androidx.compose.foundation.layout.Box(
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = PersianDateUtil.toPersianDigits(d.toString()),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "انتخاب: ${PersianDateUtil.toPersianDigits(day.toString())} " +
                            "${monthNames[month - 1]} ${PersianDateUtil.toPersianDigits(year.toString())}",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPick(PersianDateUtil.JalaliDate(year, month, day)) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("تأیید تاریخ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
private fun StepBox(
    title: String,
    subtitle: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "قبلی", modifier = Modifier.size(18.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.height(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "بعدی", modifier = Modifier.size(18.dp))
            }
        }
    }
}
