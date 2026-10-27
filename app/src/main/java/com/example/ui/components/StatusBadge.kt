package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small colored status badge used for the THREE course status groups
 * (پذیرش / برگزاری / وضعیت نهایی) and the derived follow-up flag.
 *
 * `outlined = true` renders a hollow badge: reserved for DERIVED states
 * (e.g. «در حال پیگیری تعهدات») so they never look like stored fields.
 * Only existing palette tokens (StatusSuccess/Warning/Error/Info) are used.
 */
@Composable
fun StatusBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    outlined: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (outlined) Color.Transparent else color.copy(alpha = 0.13f),
        border = if (outlined) BorderStroke(1.dp, color.copy(alpha = 0.65f)) else null
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
