package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.service.messaging.BotCloudBridge
import com.example.service.messaging.WorkerMessaging
import kotlinx.coroutines.launch

/**
 * Stage 9 — «پنل پیامکی»: the manager picks the ACTIVE SMS provider and
 * fills ONLY that provider's fields (from its official documentation).
 *
 * Security (mirrors the worker contract):
 *  - secret values are typed once and stored server-side per academy;
 *    the screen shows «ذخیره شده» instead of the value and resends a
 *    secret ONLY when the manager types a new one (blank = keep);
 *  - switching providers never deletes the other panel's stored fields
 *    nor any message history;
 *  - «آزمایش اتصال» calls the provider's FREE endpoint only — it can
 *    never send a (billable) SMS;
 *  - with no real credentials entered nothing is sent anywhere: the
 *    worker keeps SMS honestly UNCONFIGURED.
 *
 * RyeSMS is listed for completeness with an honest «در انتظار مستندات
 * رسمی» state: the panel publishes no public web-service documentation,
 * so its adapter refuses sending until the docs exist.
 */
@Composable
fun SmsPanelSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { BotCloudBridge(context) }

    var config by remember { mutableStateOf<WorkerMessaging.SmsPanelConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var workerConfigured by remember { mutableStateOf(true) }

    // editor state
    var selectedProvider by remember { mutableStateOf("") }   // "" = غیرفعال
    var draftFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf<Boolean?>(null) }

    fun applyConfig(cfg: WorkerMessaging.SmsPanelConfig) {
        config = cfg
        selectedProvider = cfg.activeProvider
        // drafts start empty: blank fields mean «keep stored» server-side
        draftFields = emptyMap()
        testResult = ""
        testOk = null
    }

    fun load() {
        scope.launch {
            loading = true
            loadError = ""
            val (ok, body) = bridge.pullSmsPanelConfig()
            if (!ok) {
                config = null
                workerConfigured = bridge.pullRemindersView().first
                loadError = if (!workerConfigured)
                    "اتصال به سرور ربات پیکربندی نشده است؛ ابتدا از بخش ربات، سرور و کلید API را تنظیم کنید."
                else
                    "دریافت تنظیمات پنل پیامکی از سرور ناموفق بود؛ اتصال اینترنت یا آدرس سرور را بررسی کنید."
            } else {
                WorkerMessaging.parseSmsPanelConfig(body)?.let { applyConfig(it) }
                    ?: run { loadError = "پاسخ سرور قابل تفسیر نبود."; config = null }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "انتخاب پنل ارسال پیامک (یادآوری اقساط، رسیدها و اطلاع‌رسانی‌ها وقتی کانال پیامک در تنظیمات پیام‌ها مجاز باشد). کلیدها فقط روی سرور اختصاصی خودتان ذخیره می‌شوند و هرگز در پاسخ‌ها یا لاگ‌ها نمایش داده نمی‌شوند.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp)) }
            return@Column
        }
        if (config == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = loadError,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            OutlinedButton(onClick = { load() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("تلاش دوباره")
            }
            return@Column
        }

        val cfg = config!!

        // ---- current status (honest, no secrets) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (cfg.statusConfigured) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (cfg.statusConfigured) Icons.Default.CloudDone else Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = cfg.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cfg.statusConfigured) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // ---- provider selection ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ارائه‌دهنده فعال:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedProvider.isBlank(),
                        onClick = { selectedProvider = "" },
                        label = { Text("غیرفعال", style = MaterialTheme.typography.bodySmall) }
                    )
                    cfg.providers.forEach { p ->
                        FilterChip(
                            selected = selectedProvider == p.id,
                            onClick = {
                                selectedProvider = p.id
                                testResult = ""
                                testOk = null
                            },
                            label = {
                                Text(
                                    if (p.docsPending) "${p.label} (در انتظار مستندات)"
                                    else p.label,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }
                }

                // ---- fields of the selected provider ----
                val provider = cfg.providers.firstOrNull { it.id == selectedProvider }
                if (provider != null) {
                    if (provider.docsPending) {
                        Text(
                            text = "⚠️ مستندات رسمی وب‌سرویس این پنل به‌صورت عمومی منتشر نشده است (فقط داخل پنل کاربری). تا تکمیل مستندات، ذخیره تنظیمات و ارسال پیامک از این پنل ممکن نیست.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    provider.fields.forEach { f ->
                        val stored = provider.settings[f.key] ?: ""
                        val isSecret = f.kind == "secret"
                        OutlinedTextField(
                            value = draftFields[f.key] ?: "",
                            onValueChange = { draftFields = draftFields + (f.key to it) },
                            label = { Text(f.label + if (isSecret && stored == "1") " (ذخیره شده)" else "") },
                            supportingText = {
                                val t = buildString {
                                    if (isSecret && stored == "1") append("مقدار قبلی روی سرور ذخیره است؛ خالی بگذارید تا تغییر نکند. ")
                                    if (f.hint.isNotBlank()) append(f.hint)
                                }
                                if (t.isNotBlank()) Text(t, style = MaterialTheme.typography.labelSmall)
                            },
                            visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (selectedProvider.isBlank()) {
                    Text(
                        text = "با انتخاب «غیرفعال»، ارسال پیامک متوقف می‌شود اما سوابق پیامک‌ها و مقادیر ذخیره‌شده همهٔ پنل‌ها بدون تغییر باقی می‌مانند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ---- actions ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                saving = true
                                val fieldsForSave: Map<String, String> = if (selectedProvider.isBlank()) emptyMap()
                                else provider?.fields?.mapNotNull { f ->
                                    val v = draftFields[f.key] ?: ""
                                    if (v.isNotBlank()) f.key to v else null
                                }?.toMap() ?: emptyMap()
                                val (ok, body) = bridge.pushSmsPanelConfig(selectedProvider, fieldsForSave)
                                saving = false
                                if (ok) {
                                    Toast.makeText(context, "تنظیمات پنل پیامکی ذخیره شد.", Toast.LENGTH_SHORT).show()
                                    load()
                                } else {
                                    // server validation errors are shown verbatim (no secrets in them)
                                    Toast.makeText(context, body.take(180), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !saving && (!selectedProvider.isBlank() || cfg.activeProvider.isNotBlank()),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (selectedProvider.isBlank()) "غیرفعال‌سازی پیامک" else "ذخیره تنظیمات")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                testing = true
                                testResult = ""
                                val (ok, body) = bridge.testSmsPanelConnection()
                                testing = false
                                val parsed = WorkerMessaging.parseSmsConnectionTest(body)
                                testOk = parsed.ok
                                testResult = if (parsed.message.isNotBlank()) parsed.message else body.take(180)
                            }
                        },
                        enabled = !testing && cfg.statusConfigured,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("آزمایش اتصال")
                    }
                }

                if (testResult.isNotBlank()) {
                    Text(
                        text = (if (testOk == true) "✅ " else "⚠️ ") + testResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testOk == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "دکمهٔ «آزمایش اتصال» فقط اعتبار کلید و اعتبار مالی پنل را با نقطهٔ پایانی رسمی و بدون‌هزینه بررسی می‌کند و هرگز پیامکی ارسال نمی‌کند. آزمایش ارسال واقعی پس از وارد‌کردن اطلاعات واقعی و با اجازهٔ صریح شما انجام می‌شود.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- official docs links (verified sources) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "مستندات رسمی بررسی‌شده:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                AssistChip(
                    onClick = { },
                    label = { Text("SMS.ir — sms.ir/rest-api", style = MaterialTheme.typography.bodySmall) }
                )
                Text(
                    text = "رایس‌اس‌ام‌اس — مستندات وب‌سرویس به‌صورت عمومی منتشر نشده است (rysesms.ir).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
