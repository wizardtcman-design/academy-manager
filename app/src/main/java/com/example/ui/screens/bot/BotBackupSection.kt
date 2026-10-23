package com.example.ui.screens.bot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.example.data.model.AcademySettings
import com.example.data.remote.BackupApi
import com.example.data.util.PersianDateUtil
import com.example.service.messaging.BotCloudBridge
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.launch

/**
 * Stage 7 — the manager's view of the SERVER-SIDE daily backup.
 *
 * Shows when the last verified snapshot ran, its size, how many healthy
 * copies are kept, the last failure (with the server's Persian reason) and
 * a manual run/retry button. The snapshot payload itself never leaves the
 * server; restore requires the owner's documented manual runbook.
 */
@Composable
fun BotBackupSection(settings: AcademySettings?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<BackupApi.BackupStatus?>(null) }
    var feedback by remember { mutableStateOf("") }
    var feedbackOk by remember { mutableStateOf(true) }

    val workerConfigured = settings != null &&
            settings.telegramUseWorker &&
            settings.telegramWorkerUrl.isNotBlank() &&
            settings.telegramApiSecret.isNotBlank()

    fun refresh() {
        scope.launch {
            loading = true
            if (!workerConfigured) {
                loading = false
                return@launch
            }
            val (ok, body) = BotCloudBridge(context).fetchBackupStatus(settings!!)
            status = if (ok) BackupApi.parseStatusResponse(body) else null
            if (!ok) feedback = "خواندن وضعیت پشتیبان‌گیری ناموفق بود. کلید API سرور را بررسی کنید."
            loading = false
        }
    }

    LaunchedEffect(workerConfigured) { if (workerConfigured) refresh() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SettingsBackupRestore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "پشتیبان‌گیری خودکار سرور",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "هر شب یک نسخهٔ کامل از داده‌های سرور (هنرجویان، اقساط، مالی و فایل‌های قرارداد) تهیه، صحت‌سنجی و تا ۷ نسخهٔ سالم نگه‌داری می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            when {
                !workerConfigured -> {
                    Text(
                        "برای پشتیبان‌گیری سروری، ابتدا حالت سرور Cloudflare را در همین بخش تنظیم کنید (آدرس سرور و کلید API).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                }
                else -> {
                    val st = status
                    if (st == null) {
                        Text(
                            feedback.ifBlank { "وضعیت پشتیبان‌گیری خوانده نشد." },
                            color = StatusError,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh() }) { Text("تلاش دوباره") }
                    } else {
                        val healthy = st.lastHealthy
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("آخرین نسخهٔ سالم:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (healthy == null) "—"
                                else PersianDateUtil.toPersianDigits(PersianDateUtil.formatEpochToShamsi(healthy.createdAtEpoch)),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نسخه‌های سالم:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${PersianDateUtil.toPersianDigits(st.healthyCount.toString())} از ${PersianDateUtil.toPersianDigits(st.retentionDays.toString())}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        val lastRun = st.lastRun
                        if (lastRun != null && lastRun.outcome == "FAILED") {
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            Text(
                                "❌ آخرین تلاش ناموفق بود: ${lastRun.error.ifBlank { "خطای نامشخص" }}",
                                color = StatusError,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (healthy != null && !healthy.filesIncluded) {
                            Text(
                                "⚠️ در نسخهٔ اخیر، فایل‌های قرارداد به‌دلیل حجم زیاد ذخیره نشده‌اند.",
                                color = StatusWarning,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    running = true
                                    val (ok, body) = BotCloudBridge(context).runServerBackup(settings!!)
                                    val result = BackupApi.parseRunResponse(body)
                                    feedbackOk = ok && result.ok
                                    feedback = when {
                                        ok && result.ok -> "✅ پشتیبان‌گیری انجام و صحت‌سنجی شد (" +
                                                "${result.storedBytes / 1024} کیلوبایت فشرده)."
                                        else -> "پشتیبان‌گیری ناموفق بود: ${result.error.ifBlank { "خطای نامشخص" }}"
                                    }
                                    running = false
                                    refresh()
                                }
                            },
                            enabled = !running
                        ) {
                            if (running) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("اجرای پشتیبان‌گیری همین حالا")
                        }

                        if (feedback.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                feedback,
                                color = if (feedbackOk) StatusSuccess else StatusError,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
