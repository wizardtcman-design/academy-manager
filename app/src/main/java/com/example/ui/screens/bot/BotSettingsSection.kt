package com.example.ui.screens.bot

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealSecondary
import kotlinx.coroutines.launch

@Composable
fun BotSettingsSection(
    settings: AcademySettings?,
    onSaveSettings: (AcademySettings) -> Unit,
    onTestConnection: (String, (Boolean, String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var botToken by remember(settings?.telegramBotToken) { mutableStateOf(settings?.telegramBotToken ?: "") }
    var botUsername by remember(settings?.telegramBotUsername) { mutableStateOf(settings?.telegramBotUsername ?: "") }
    var telegramEnabled by remember(settings?.telegramEnabled) { mutableStateOf(settings?.telegramEnabled ?: false) }
    var telegramWorkerUrl by remember(settings?.telegramWorkerUrl) { mutableStateOf(settings?.telegramWorkerUrl ?: "") }
    var telegramApiSecret by remember(settings?.telegramApiSecret) { mutableStateOf(settings?.telegramApiSecret ?: "") }
    var telegramUseWorker by remember(settings?.telegramUseWorker) { mutableStateOf(settings?.telegramUseWorker ?: false) }
    var showToken by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }

    // Sending & Anti-spam settings
    var delaySeconds by remember { mutableIntStateOf(2) }
    var maxRetries by remember { mutableIntStateOf(3) }
    var maxPerHour by remember { mutableIntStateOf(60) }
    var silentHoursEnabled by remember { mutableStateOf(true) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResultDialogText by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Credentials Card (Token & Username & Master Switch)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_settings_credentials_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column {
                            Text(
                                text = "احراز هویت و فعال‌سازی ربات",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "تنظیمات اتصال اختصاصی به API تلگرام",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = telegramEnabled,
                        onCheckedChange = { telegramEnabled = it },
                        modifier = Modifier.testTag("bot_master_switch")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Bot Username
                OutlinedTextField(
                    value = botUsername,
                    onValueChange = { botUsername = it.removePrefix("@").trim() },
                    label = { Text("نام کاربری ربات (بدون @)") },
                    placeholder = { Text("مثال: MyAcademyBot") },
                    leadingIcon = {
                        Text(
                            text = "@",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bot_username_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Bot Token (Direct Mode)
                if (!telegramUseWorker) {
                    OutlinedTextField(
                        value = botToken,
                        onValueChange = { botToken = it.trim() },
                        label = { Text("توکن احراز هویت ربات (Bot Token)") },
                        placeholder = { Text("دریافتی از @BotFather") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "نمایش/مخفی توکن"
                                )
                            }
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_token_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Test Connection Button
                    OutlinedButton(
                        onClick = {
                            val cleanToken = botToken.trim()
                            if (cleanToken.isBlank()) {
                                Toast.makeText(context, "لطفاً ابتدا توکن ربات را وارد کنید.", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isTestingConnection = true
                            onTestConnection(cleanToken) { success, msg ->
                                isTestingConnection = false
                                testResultDialogText = Pair(success, msg)
                                if (success) {
                                    val match = Regex("@([A-Za-z0-9_]+)").find(msg)
                                    if (match != null) {
                                        val detectedUsername = match.groupValues[1]
                                        if (botUsername.isBlank() || botUsername != detectedUsername) {
                                            botUsername = detectedUsername
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isTestingConnection && botToken.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_test_connection_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال بررسی...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست اتصال به سرور تلگرام")
                        }
                    }
                }
            }
        }

        // 1.5. Cloudflare Workers Backend Architecture Card (Recommended)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_cloudflare_backend_card"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (telegramUseWorker) TealSecondary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TealSecondary.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = TealSecondary)
                            }
                        }
                        Column {
                            Text(
                                text = "معماری سرور Cloudflare Worker (امن)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "عدم ارتباط مستقیم اندروید با تلگرام و امنیت کامل توکن",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = telegramUseWorker,
                        onCheckedChange = { telegramUseWorker = it },
                        modifier = Modifier.testTag("bot_worker_mode_switch")
                    )
                }

                if (telegramUseWorker) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    Text(
                        text = "⚡ در این معماری، توکن ربات تلگرام در سرور Cloudflare نگهداری می‌شود و اپلیکیشن از طریق API امن HTTPS و وب‌هوک با آن ارتباط برقرار می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )

                    // Worker URL Input
                    OutlinedTextField(
                        value = telegramWorkerUrl,
                        onValueChange = { telegramWorkerUrl = it.trim() },
                        label = { Text("آدرس Cloudflare Worker (HTTPS Endpoint)") },
                        placeholder = { Text("https://academy-telegram-worker.yourname.workers.dev") },
                        leadingIcon = {
                            Icon(Icons.Default.Send, contentDescription = null, tint = TealSecondary)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_worker_url_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Worker API Secret Key
                    OutlinedTextField(
                        value = telegramApiSecret,
                        onValueChange = { telegramApiSecret = it.trim() },
                        label = { Text("کلید احراز هویت API سرور (API Secret Key)") },
                        placeholder = { Text("کلید امن تعریف‌شده در Cloudflare Secrets") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = TealSecondary)
                        },
                        trailingIcon = {
                            IconButton(onClick = { showSecret = !showSecret }) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "نمایش/مخفی کلید"
                                )
                            }
                        },
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_worker_secret_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Connect the bot: the token goes to the Worker over HTTPS and
                    // only success confirmed by Telegram is reported.
                    var isConnectingBot by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            val cleanUrl = telegramWorkerUrl.trim().removeSuffix("/")
                            val token = botToken.trim()
                            if (cleanUrl.isBlank()) {
                                Toast.makeText(context, "لطفاً آدرس سرور Cloudflare را وارد نمایید.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (token.isBlank()) {
                                Toast.makeText(context, "لطفاً توکن ربات را وارد نمایید.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isConnectingBot = true
                            val provider = com.example.service.messaging.TelegramProvider(context, settings)
                            coroutineScope.launch {
                                val (ok, msg) = provider.connectBotOnWorker(cleanUrl, telegramApiSecret, token)
                                isConnectingBot = false
                                testResultDialogText = Pair(ok, msg)
                            }
                        },
                        enabled = !isConnectingBot && telegramWorkerUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_connect_server_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isConnectingBot) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال اتصال ربات به سرور...")
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اتصال ربات به سرور (فعال‌سازی وبهوک)")
                        }
                    }

                    // Test Worker Connection Button
                    OutlinedButton(
                        onClick = {
                            val cleanUrl = telegramWorkerUrl.trim().removeSuffix("/")
                            if (cleanUrl.isBlank()) {
                                Toast.makeText(context, "لطفاً ابتدا آدرس سرور Cloudflare را وارد نمایید.", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isTestingConnection = true
                            // Use worker connection test via Provider
                            val provider = com.example.service.messaging.TelegramProvider(context, settings)
                            coroutineScope.launch {
                                val (ok, msg) = provider.testCloudflareWorkerConnection(cleanUrl, telegramApiSecret)
                                isTestingConnection = false
                                testResultDialogText = Pair(ok, msg)
                            }
                        },
                        enabled = !isTestingConnection && telegramWorkerUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_test_worker_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال بررسی اتصال سرور...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تست اتصال به Cloudflare Worker")
                        }
                    }

                    // Publish students, installments, financials and reminders to
                    // the Cloudflare Worker so the bot keeps working with the app closed.
                    var isPushingToServer by remember { mutableStateOf(false) }
                    var pushResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

                    OutlinedButton(
                        onClick = {
                            val cleanUrl = telegramWorkerUrl.trim().removeSuffix("/")
                            if (cleanUrl.isBlank() || telegramApiSecret.isBlank()) {
                                Toast.makeText(context, "آدرس سرور و کلید API را کامل کنید.", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            isPushingToServer = true
                            pushResult = null
                            coroutineScope.launch {
                                // Persist current values first so the bridge sees them
                                val current = (settings ?: AcademySettings()).copy(
                                    telegramEnabled = telegramEnabled,
                                    telegramUseWorker = telegramUseWorker,
                                    telegramWorkerUrl = cleanUrl,
                                    telegramApiSecret = telegramApiSecret.trim(),
                                    telegramBotUsername = botUsername,
                                    telegramBotToken = botToken
                                )
                                onSaveSettings(current)
                                kotlinx.coroutines.delay(300)
                                val outcome = com.example.service.messaging.BotCloudBridge(context).pushAll()
                                isPushingToServer = false
                                pushResult = Pair(
                                    outcome.success,
                                    if (outcome.success) {
                                        "✅ سرور آماده است.\n${outcome.message}\n\nاز این پس ربات و یادآوری‌ها حتی وقتی برنامه بسته باشد با سرور Cloudflare کار می‌کنند."
                                    } else outcome.message
                                )
                            }
                        },
                        enabled = !isPushingToServer && telegramWorkerUrl.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("bot_push_to_server_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isPushingToServer) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("در حال ارسال داده‌ها به سرور...")
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("فعال‌سازی ربات ابری (ارسال داده‌ها و یادآوری‌ها)")
                        }
                    }

                    pushResult?.let { (ok, msg) ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) StatusSuccess else StatusError,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 2. Sending Settings Card (Rate & Delays)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = IndigoPrimary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = IndigoPrimary)
                        }
                    }
                    Column {
                        Text(
                            text = "تنظیمات ارسال و سرعت (Sending Settings)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "کنترل فواصل ارسال جهت انطباق با قوانین تلگرام",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "فاصله بین هر ارسال پیام:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$delaySeconds ثانیه تاخیر",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { if (delaySeconds > 1) delaySeconds-- },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("-") }
                        Button(
                            onClick = { if (delaySeconds < 10) delaySeconds++ },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("+") }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "حداکثر دفعات تلاش مجدد در صورت خطا:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$maxRetries بار تلاش",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { if (maxRetries > 1) maxRetries-- },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("-") }
                        Button(
                            onClick = { if (maxRetries < 5) maxRetries++ },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("+") }
                    }
                }
            }
        }

        // 3. Anti-Spam & Quiet Hours Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AmberTertiary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = AmberTertiary)
                        }
                    }
                    Column {
                        Text(
                            text = "تنظیمات ضد اسپم و ساعات سکوت (Anti-Spam)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "جلوگیری از مسدودسازی ربات و مزاحمت در شب",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "سقف مجاز ارسال در هر ساعت:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$maxPerHour پیام در ساعت",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { if (maxPerHour > 20) maxPerHour -= 10 },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("-۱۰") }
                        Button(
                            onClick = { if (maxPerHour < 200) maxPerHour += 10 },
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("+۱۰") }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "حالت بی‌صدا در ساعات شبانه:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "عدم ارسال اعلان صوتی بین ساعت ۲۲:۰۰ الی ۰۸:۰۰",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = silentHoursEnabled,
                        onCheckedChange = { silentHoursEnabled = it }
                    )
                }
            }
        }

        // 4. Save Settings Button
        Button(
            onClick = {
                val current = settings ?: AcademySettings()
                val cleanToken = botToken.trim()
                val cleanUsername = botUsername.removePrefix("@").trim()
                val cleanWorkerUrl = telegramWorkerUrl.trim().removeSuffix("/")
                val cleanApiSecret = telegramApiSecret.trim()
                val updated = current.copy(
                    telegramBotToken = cleanToken,
                    telegramBotUsername = cleanUsername,
                    telegramEnabled = telegramEnabled,
                    telegramWorkerUrl = cleanWorkerUrl,
                    telegramApiSecret = cleanApiSecret,
                    telegramUseWorker = telegramUseWorker,
                    updatedAt = System.currentTimeMillis()
                )
                onSaveSettings(updated)
                Toast.makeText(context, "تنظیمات ربات تلگرام با موفقیت ذخیره شد.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bot_save_all_settings_btn"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("ذخیره کلیه تنظیمات ربات")
        }
    }

    // Connection Test Dialog
    if (testResultDialogText != null) {
        val (isSuccess, message) = testResultDialogText!!
        AlertDialog(
            onDismissRequest = { testResultDialogText = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (isSuccess) StatusSuccess else StatusError
                    )
                    Text(
                        text = if (isSuccess) "اتصال موفق به تلگرام" else "خطا در تست اتصال",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { testResultDialogText = null }) {
                    Text("بستن")
                }
            }
        )
    }
}
