package com.example.ui.screens.update

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.update.ApkDownloader
import com.example.update.ApkInstaller
import com.example.update.UpdateManager
import com.example.update.UpdateViewModel

/** Jalali publication date, e.g. "۱۴۰۵/۰۷/۲۶". */
private fun publishedDate(ms: Long): String {
    return try {
        val local = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        val j = com.example.data.util.PersianDateUtil.gregorianToJalali(
            local.year, local.monthValue, local.dayOfMonth
        )
        j.formatPersianDigits()
    } catch (_: Exception) {
        ""
    }
}

/**
 * In-app updates: reads the newest official release from GitHub, shows its
 * Persian changelog, downloads the APK with progress and hands it to Android's
 * installer after verifying its signature matches this very app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vm: UpdateViewModel = viewModel()
    val state by vm.state.collectAsState()
    var installError by remember { mutableStateOf<String?>(null) }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val info = vm.state.value.update
        if (granted && info != null) UpdateManager.notifyNow(ctx, info)
    }
    LaunchedEffect(state.update?.version) {
        val info = state.update ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            UpdateManager.notifyNow(ctx, info)
        }
    }

    LaunchedEffect(Unit) { vm.check(force = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "به‌روزرسانی برنامه",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "بازگشت")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            UpdateCard {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
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
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "مدیریت آموزشگاه",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "نسخهٔ نصب‌شده: ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            when (state.phase) {
                UpdateViewModel.Phase.IDLE, UpdateViewModel.Phase.CHECKING -> {
                    UpdateCard {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("در حال بررسی نسخهٔ تازه…")
                        }
                    }
                }

                UpdateViewModel.Phase.UP_TO_DATE -> {
                    UpdateCard {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "برنامه به‌روز است",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "آخرین نسخهٔ رسمی همین نسخه‌ای است که الان نصب دارید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = { vm.check(force = true) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("بررسی دوباره")
                            }
                        }
                    }
                }

                else -> {
                    val info = state.update
                    if (info != null) {
                        UpdateCard {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "نسخهٔ تازه: ${info.version}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (info.publishedAt > 0) {
                                    Text(
                                        text = "انتشار: ${publishedDate(info.publishedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = info.notesOrDefault(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (info.apkSize > 0) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "حجم فایل: %.1f مگابایت".format(info.apkSize / 1_048_576.0),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    when (state.phase) {
                        UpdateViewModel.Phase.DOWNLOADING -> UpdateCard {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("در حال دانلود", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(10.dp))
                                if (state.totalBytes > 0) {
                                    LinearProgressIndicator(
                                        progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = buildString {
                                        append("${state.percent}٪")
                                        if (state.totalBytes > 0) {
                                            append(" — %.1f از %.1f مگابایت".format(
                                                state.downloadedBytes / 1_048_576.0,
                                                state.totalBytes / 1_048_576.0
                                            ))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { vm.cancelDownload() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("لغو دانلود") }
                            }
                        }

                        UpdateViewModel.Phase.READY -> UpdateCard {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "آمادهٔ نصب",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "فایل دانلود و امضایش با نسخهٔ رسمی بررسی شد. " +
                                        "با زدن دکمه، نصب‌کنندهٔ اندروید باز می‌شود؛ تأیید کنید. اطلاعات حفظ می‌شود.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                installError?.let {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        installError = null
                                        val file = state.readyFile
                                        val problem = if (file == null) {
                                            "فایل نصب پیدا نشد؛ دوباره دانلود کنید."
                                        } else {
                                            ApkDownloader.verifyApk(ctx, file)
                                        }
                                        if (problem != null) {
                                            installError = problem
                                        } else when (ApkInstaller.install(ctx, file!!)) {
                                            ApkInstaller.Result.STARTED -> Unit
                                            ApkInstaller.Result.NO_PERMISSION -> {
                                                ApkInstaller.openInstallPermissionSettings(ctx)
                                                installError =
                                                    "برای نصب، اجازهٔ «نصب برنامه‌های ناشناس» را به این برنامه بدهید و دوباره بزنید."
                                            }
                                            ApkInstaller.Result.NO_INSTALLER ->
                                                installError = "نصب‌کنندهٔ برنامه روی این دستگاه پیدا نشد."
                                            ApkInstaller.Result.FAILED ->
                                                installError = "باز کردن نصب‌کننده ممکن نشد؛ دوباره تلاش کنید."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("نصب به‌روزرسانی")
                                }
                            }
                        }

                        UpdateViewModel.Phase.ERROR -> UpdateCard {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "مشکل در دانلود",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = state.message ?: "دانلود کامل نشد.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { vm.download() }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("تلاش دوباره")
                                }
                            }
                        }

                        else -> UpdateCard {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Button(
                                    onClick = { vm.download() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("دانلود و نصب نسخهٔ ${info?.version ?: ""}")
                                }
                                Spacer(Modifier.height(6.dp))
                                TextButton(
                                    onClick = { vm.skip() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("این نسخه را فعلاً نمی‌خواهم") }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Rounded surface used for each block, matching the app's card language. */
@Composable
private fun UpdateCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        content()
    }
}
