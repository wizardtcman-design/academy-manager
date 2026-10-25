package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.data.model.Installment
import com.example.data.model.Student
import com.example.service.messaging.BotCloudBridge
import com.example.service.messaging.WorkerMessaging
import kotlinx.coroutines.launch

/**
 * Stage 6 — «تنظیمات پیام‌رسانی سرور (روبات)»: the third tab of the
 * messages settings. Everything here talks to the WORKER
 * (/api/messaging/*) with the academy's own credentials; the worker is
 * the single source of truth for reminder/receipt/welcome texts and the
 * per-event auto/manual/channel policy.
 *
 * Honest channel states: SMS is shown as «پیکربندی‌نشده» until a real
 * panel is connected — the UI never claims a real SMS was sent, and the
 * worker refuses real SMS sends by construction in this stage.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ServerMessagingSection(
    students: List<Student>,
    installments: List<Installment>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { BotCloudBridge(context) }

    var config by remember { mutableStateOf<WorkerMessaging.MessagingConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var workerConfigured by remember { mutableStateOf(true) }

    // editor state: which event is open, its draft template/policy
    var selectedEventKey by remember { mutableStateOf("INSTALLMENT_REMINDER") }
    var draftTemplate by remember { mutableStateOf("") }
    var draftChannel by remember { mutableStateOf("telegram") }
    var draftAuto by remember { mutableStateOf(true) }
    var draftManual by remember { mutableStateOf(true) }
    var draftOverdueEnabled by remember { mutableStateOf(true) }
    var draftHour by remember { mutableStateOf("10") }
    var draftMinute by remember { mutableStateOf("0") }
    var draftTz by remember { mutableStateOf("Asia/Tehran") }
    var previewHtml by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // reminder manager state
    var selectedStudentId by remember { mutableStateOf("") }
    var reminderRows by remember { mutableStateOf<List<WorkerMessaging.ReminderRow>>(emptyList()) }
    var stoppedMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var remindersLoading by remember { mutableStateOf(false) }

    fun selectEventAndDraft(cfgValue: WorkerMessaging.MessagingConfig, key: String) {
        selectedEventKey = key
        selectEvent(cfgValue, key)?.let { (t, ch, a, m) ->
            draftTemplate = t; draftChannel = ch; draftAuto = a; draftManual = m
        }
        previewHtml = ""
    }

    fun loadConfig() {
        scope.launch {
            loading = true
            loadError = ""
            val cfg = bridge.pullServerMessagingConfig()
            if (cfg == null) {
                workerConfigured = bridge.pullRemindersView().first // any response means configured
                loadError = if (!workerConfigured)
                    "اتصال به سرور ربات پیکربندی نشده است؛ ابتدا از بخش ربات، سرور و کلید API را تنظیم کنید."
                else
                    "دریافت تنظیمات از سرور ناموفق بود؛ اتصال اینترنت یا آدرس سرور را بررسی کنید."
            } else {
                config = cfg
                draftOverdueEnabled = cfg.overdueDailyEnabled
                draftHour = cfg.sendHour.toString()
                draftMinute = cfg.sendMinute.toString()
                draftTz = cfg.timezone
                selectEvent(cfg, selectedEventKey)?.let { (t, ch, a, m) ->
                    draftTemplate = t; draftChannel = ch; draftAuto = a; draftManual = m
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadConfig() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "این تنظیمات روی سرور (ربات تلگرام) اعمال می‌شوند: متن پیام‌های خودکار، کانال مجاز هر پیام، یادآوری روزانه اقساط معوق و ارسال دستی. هر آموزشگاه تنظیمات مستقل خود را دارد.",
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
            OutlinedButton(onClick = { loadConfig() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("تلاش دوباره")
            }
            return@Column
        }

        val cfg = config!!

        // ---- 1) Real channel status ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("وضعیت واقعی کانال‌ها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                ChannelStatusRow("تلگرام", cfg.channelStatus.telegramConfigured, cfg.channelStatus.telegramDetail)
                ChannelStatusRow("پیامک", cfg.channelStatus.smsConfigured, cfg.channelStatus.smsDetail)
                ChannelStatusRow("درگاه پرداخت آنلاین (مخفی)", cfg.channelStatus.gatewayConfigured, cfg.channelStatus.gatewayDetail)
            }
        }

        // ---- 2) Send time (same scheduler keys the worker uses) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ساعت ارسال یادآوری‌ها (به وقت آموزشگاه)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftHour,
                        onValueChange = { draftHour = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("ساعت (۰-۲۳)") },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl)
                    )
                    OutlinedTextField(
                        value = draftMinute,
                        onValueChange = { draftMinute = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("دقیقه") },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl)
                    )
                    OutlinedTextField(
                        value = draftTz,
                        onValueChange = { draftTz = it },
                        label = { Text("منطقه زمانی") },
                        modifier = Modifier.weight(1.6f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl)
                    )
                }
            }
        }

        // ---- 3) Daily overdue reminder (the user-approved defaults) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("یادآوری روزانه قسط معوق", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = draftOverdueEnabled, onCheckedChange = { draftOverdueEnabled = it })
                }
                Text(
                    text = "پس از سررسید، هر روز تا ثبت پرداخت واقعی یا توقف توسط مدیر ادامه می‌یابد. اولین یادآوری یک روز پس از سررسید است و زمان‌بندی ۷/۴/۱ روز قبل و روز سررسید دست‌نخورده باقی می‌ماند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 4) Event picker + editor ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("پیام‌ها و قالب‌ها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cfg.events.forEach { ev ->
                        FilterChip(
                            selected = selectedEventKey == ev.key,
                            onClick = { selectEventAndDraft(cfg, ev.key) },
                            label = { Text(shortEventLabel(ev.key), style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                val current = cfg.events.firstOrNull { it.key == selectedEventKey }
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    if (current.isOverridden) {
                        Text(
                            "قالب سفارشی ذخیره‌شده — بازگردانی به پیش‌فرض ممکن است.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    // policy chips
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ارسال خودکار", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(4.dp))
                            Switch(checked = draftAuto, onCheckedChange = { draftAuto = it }, modifier = Modifier.height(28.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ارسال دستی", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(4.dp))
                            Switch(checked = draftManual, onCheckedChange = { draftManual = it }, modifier = Modifier.height(28.dp))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChannelChip("telegram", "تلگرام", draftChannel) { draftChannel = it }
                        ChannelChip("sms", "پیامک (غیرفعال)", draftChannel) { draftChannel = it }
                        ChannelChip("both", "هر دو", draftChannel) { draftChannel = it }
                    }
                    if (draftChannel == "sms" || draftChannel == "both") {
                        Text(
                            "⚠️ کانال پیامک هنوز پیکربندی نشده است؛ تا اتصال پنل پیامکی، فقط تلگرام واقعاً ارسال می‌شود.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // variables help
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        current.variables.forEach { v ->
                            AssistChip(
                                onClick = { draftTemplate += "{${v.name}}" },
                                label = { Text("{${v.name}}", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draftTemplate,
                        onValueChange = { draftTemplate = it },
                        label = { Text("متن قالب") },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    previewing = true
                                    val payload = org.json.JSONObject()
                                        .put("eventType", selectedEventKey)
                                        .put("template", draftTemplate)
                                    val (ok, body) = bridge.previewServerTemplate(payload)
                                    previewHtml = if (ok) {
                                        val p = WorkerMessaging.parsePreview(body)
                                        if (p.valid) p.telegramHtml else "خطاهای قالب:\n" + p.errors.joinToString("\n")
                                    } else WorkerMessaging.parsePreview(body).errors.joinToString("\n")
                                    previewing = false
                                }
                            },
                            enabled = !previewing
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("پیش‌نمایش (اطلاعات نمونه)")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val (ok, body) = bridge.resetServerTemplate(selectedEventKey)
                                    if (ok) {
                                        Toast.makeText(context, "قالب به پیش‌فرض بازگردانده شد.", Toast.LENGTH_SHORT).show()
                                        val fresh = bridge.pullServerMessagingConfig()
                                        if (fresh != null) { config = fresh; selectEventAndDraft(fresh, selectedEventKey) }
                                    } else Toast.makeText(context, "بازگردانی ناموفق بود.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("بازگردانی به پیش‌فرض")
                        }
                    }
                    if (previewHtml.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                previewHtml.replace(Regex("<[^>]+>"), ""),
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ---- 5) Save all ----
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    val hour = draftHour.toIntOrNull()?.coerceIn(0, 23)
                    val minute = draftMinute.toIntOrNull()?.coerceIn(0, 59)
                    if (hour == null || minute == null) {
                        Toast.makeText(context, "ساعت ارسال معتبر نیست.", Toast.LENGTH_SHORT).show()
                        saving = false
                        return@launch
                    }
                    val payload = WorkerMessaging.configPatch()
                        .event(selectedEventKey, auto = draftAuto, manual = draftManual, channel = draftChannel, template = draftTemplate)
                        .overdueDaily(enabled = draftOverdueEnabled)
                        .sendTime(hour = hour, minute = minute, tz = draftTz)
                    val (ok, body) = bridge.pushServerMessagingConfig(payload.toJson())
                    if (ok) {
                        Toast.makeText(context, "تنظیمات پیام‌رسانی روی سرور ذخیره شد.", Toast.LENGTH_SHORT).show()
                        config = bridge.pullServerMessagingConfig() ?: config
                    } else {
                        val errors = WorkerMessaging.parseConfigErrors(body)
                        Toast.makeText(context, errors.firstOrNull() ?: "ذخیره ناموفق بود.", Toast.LENGTH_LONG).show()
                    }
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (saving) "در حال ذخیره…" else "ذخیره تنظیمات روی سرور")
        }

        // ---- 6) Per-installment reminder manager (stop/resume) ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مدیریت یادآوری هر قسط", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "توقف فقط همین قسط را متوقف می‌کند؛ اقساط دیگر و سایر پیام‌ها ادامه دارند. پس از ثبت پرداخت واقعی، یادآوری‌ها خودکار متوقف می‌شوند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StudentDropdown(
                    students = students,
                    selectedId = selectedStudentId,
                    onSelect = { sid ->
                        selectedStudentId = sid
                        remindersLoading = true
                        scope.launch {
                            val (ok, body) = bridge.pullRemindersView(studentId = sid)
                            val view = if (ok) WorkerMessaging.parseRemindersView(body) else null
                            reminderRows = view?.rows ?: emptyList()
                            stoppedMap = view?.stoppedInstallments ?: emptyMap()
                            remindersLoading = false
                        }
                    }
                )
                if (remindersLoading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
                // group rows per installment
                val byInstallment = reminderRows.groupBy { it.installmentId }
                byInstallment.forEach { (instId, rows) ->
                    val inst = installments.firstOrNull { it.id == instId }
                    val stopped = stoppedMap.containsKey(instId)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "قسط ${inst?.installmentNumber ?: "?"} — ${inst?.courseTitle ?: ""}",
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (stopped) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("توقف‌شده", style = MaterialTheme.typography.labelSmall) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    )
                                }
                            }
                            rows.sortedBy { it.triggerOffset }.take(8).forEach { row ->
                                Text(
                                    "${WorkerMessaging.reminderRowLabel(row)} — ${WorkerMessaging.statusLabelFa(row.status)}" +
                                        if (row.error.isNotBlank()) " (${row.error.take(40)})" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!stopped) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            val (ok, _) = bridge.stopInstallmentReminders(instId, "توقف از پنل مدیریت")
                                            if (ok) {
                                                stoppedMap = stoppedMap + (instId to "توقف از پنل مدیریت")
                                                Toast.makeText(context, "یادآوری این قسط متوقف شد (فقط همین قسط).", Toast.LENGTH_SHORT).show()
                                            } else Toast.makeText(context, "توقف ناموفق بود.", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("توقف یادآوری این قسط")
                                    }
                                } else {
                                    Button(onClick = {
                                        scope.launch {
                                            val (ok, _) = bridge.resumeInstallmentReminders(instId)
                                            if (ok) {
                                                stoppedMap = stoppedMap - instId
                                                Toast.makeText(context, "یادآوری این قسط از سر گرفته شد.", Toast.LENGTH_SHORT).show()
                                                val (ok2, body2) = bridge.pullRemindersView(studentId = selectedStudentId)
                                                if (ok2) WorkerMessaging.parseRemindersView(body2)?.let {
                                                    reminderRows = it.rows; stoppedMap = it.stoppedInstallments
                                                }
                                            } else Toast.makeText(context, "ازسرگیری ناموفق بود.", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Text("ازسرگیری")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelStatusRow(title: String, configured: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (configured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.width(96.dp)
        ) {
            Text(
                if (configured) "فعال" else "غیرفعال",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (configured) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelChip(value: String, label: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudentDropdown(
    students: List<Student>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = students.firstOrNull { it.id == selectedId }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected?.fullName ?: (if (selectedId.isBlank()) "انتخاب هنرجو…" else ""),
            onValueChange = {},
            readOnly = true,
            label = { Text("هنرجو") },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Rtl)
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            students.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s.fullName) },
                    onClick = {
                        expanded = false
                        onSelect(s.id)
                    }
                )
            }
        }
    }
}

/** `menuAnchor()` is a member of ExposedDropdownMenuBoxScope — used inline above. */

private fun selectEvent(
    cfg: WorkerMessaging.MessagingConfig,
    key: String
): Quadruple? {
    val ev = cfg.events.firstOrNull { it.key == key } ?: return null
    return Quadruple(ev.effectiveTemplate, ev.channel, ev.auto, ev.manual)
}

private data class Quadruple(val first: String, val second: String, val third: Boolean, val fourth: Boolean)

private fun shortEventLabel(key: String): String = when (key) {
    "WELCOME" -> "خوشامدگویی ثبت‌نام"
    "CONNECT_WELCOME" -> "خوشامدگویی اتصال"
    "INSTALLMENT_REMINDER" -> "یادآوری اقساط"
    "OVERDUE_DAILY" -> "یادآوری روزانه معوق"
    "PAYMENT_RECEIPT" -> "رسید پرداخت"
    "REVERSAL_NOTICE" -> "برگشت پرداخت"
    "RECEIPT_REJECTED" -> "رد مدرک پرداخت"
    "CLAIM_ACK" -> "دریافت مدرک"
    "CONTRACT_DELIVERED" -> "تحویل قرارداد"
    else -> key
}
