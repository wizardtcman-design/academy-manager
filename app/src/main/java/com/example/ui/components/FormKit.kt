package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shared, modern shell for every data-entry dialog: a gradient header band with
 * an icon tile, a title and a subtitle, then grouped cards with their own
 * colored headers, and a footer with full-width cancel / confirm buttons.
 *
 * Dialogs built from this all look the same, which is what makes the app feel
 * consistent no matter where the user opens a form.
 */
@Composable
fun AppFormDialog(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String = "انصراف",
    maxHeight: Int = 720,
    testTag: String = "form_dialog",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = maxHeight.dp)
                .clip(RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header band
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "بستن",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Scrollable body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )

                // Footer, always pinned outside the scrolling body: a user who
                // scrolled to the bottom must never lose the way out.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(dismissText, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier
                            .weight(1.45f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(confirmText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * A bordered card inside a dialog, with a small colored header (icon + title).
 * Fields are grouped here instead of floating on a plain background.
 */
@Composable
fun FormSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    fontSize = 13.sp
                )
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/** Field styling shared by every form: rounded, softly filled, clear focus ring. */
@Composable
fun FormTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
)

/**
 * Single-line text field with the shared form look.
 *
 * The caption is drawn *above* the box instead of floating inside it: a
 * floating label and the typed value are both small and sit on top of each
 * other, which made phone and national code look identical. With the caption
 * outside, every field always shows a visible name, the value stays large and
 * the boxes keep an identical height.
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String = ""
) {
    // The caller's modifier (e.g. Modifier.weight in a Row) must win over
    // fillMaxWidth: applying both made a side-by-side field take the whole row
    // and push its neighbour out of the dialog.
    Column(modifier = modifier) {
        FieldCaption(label = label, required = label.contains("*"), isError = isError)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("—", fontSize = 13.sp) },
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, modifier = Modifier.size(19.dp)) }
            },
            trailingIcon = trailing,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            isError = isError,
            textStyle = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textDirection = TextDirection.Ltr
            ),
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(14.dp),
            colors = FormTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
        )
        if (!supportingText.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = supportingText,
                fontSize = 10.5.sp,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Small caption above a field, with a red * for required fields. */
@Composable
fun FieldCaption(label: String, required: Boolean = false, isError: Boolean = false) {
    @Composable
    fun Caption() {
        val clean = label.replace("*", "").trim()
        BasicText(
            text = buildAnnotatedString {
                append(clean)
                if (required) {
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    ) { append(" *") }
                }
            },
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            ),
            modifier = Modifier.padding(start = 4.dp)
        )
    }
    Caption()
}
