package com.example.ui.screens.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (oldPass: String, newPass: String) -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }


    com.example.ui.components.AppFormDialog(
        title = "تغییر رمز عبور",
        subtitle = "رمز فعلی و رمز تازهٔ مدیر را وارد کنید",
        icon = Icons.Default.Lock,
        onDismiss = onDismiss,
        confirmText = "تغییر رمز",
        onConfirm = {
            if (newPassword.length < 6) {
                errorText = "رمز عبور جدید باید حداقل ۶ کاراکتر باشد."
            } else if (newPassword != confirmPassword) {
                errorText = "تکرار رمز عبور با رمز جدید مطابقت ندارد."
            } else {
                onConfirm(oldPassword, newPassword)
            }
        },
        maxHeight = 680
    ) {
        com.example.ui.components.FormSection(title = "رمز عبور", icon = Icons.Default.Lock) {
            OutlinedTextField(
                value = oldPassword,
                onValueChange = { oldPassword = it; errorText = null },
                label = { Text("رمز عبور فعلی") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(14.dp),
                colors = com.example.ui.components.FormTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it; errorText = null },
                label = { Text("رمز عبور جدید (حداقل ۶ کاراکتر)") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(14.dp),
                colors = com.example.ui.components.FormTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorText = null },
                label = { Text("تکرار رمز عبور جدید") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                colors = com.example.ui.components.FormTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            if (errorText != null) {
                Text(
                    text = errorText!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
