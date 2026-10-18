package com.example.ui.screens.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AcademySettings
import com.example.data.util.PdfDocumentHelper
import com.example.service.contract.ContractCloudClient
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Letterhead for the printed contract: academy seal + manager signature, plus a
 * live probe of the Cloudflare contract endpoint (Worker + D1 + R2).
 *
 * Images are copied into app-private storage and only their path is stored, so
 * they never travel to Firestore or into the APK.
 */
@Composable
fun ContractLetterheadSection(
    viewModel: MainViewModel,
    settings: AcademySettings?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sealPath by remember(settings) { mutableStateOf(settings?.sealImagePath.orEmpty()) }
    var signaturePath by remember(settings) { mutableStateOf(settings?.signatureImagePath.orEmpty()) }
    var signerName by remember(settings) { mutableStateOf(settings?.signerName.orEmpty()) }
    var signerTitle by remember(settings) { mutableStateOf(settings?.signerTitle.orEmpty().ifBlank { "مدیر آموزشگاه" }) }

    var probeState by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    fun save(seal: String, signature: String, name: String, title: String) {
        val current = (settings ?: AcademySettings()).copy(
            sealImagePath = seal,
            signatureImagePath = signature,
            signerName = name.trim(),
            signerTitle = title.trim().ifBlank { "مدیر آموزشگاه" }
        )
        viewModel.updateSettings(current) { ok ->
            if (ok) viewModel.showMessage("مهر و امضای قرارداد ذخیره شد.")
        }
    }

    fun importImage(uri: Uri, onDone: (String) -> Unit) {
        scope.launch {
            val path = withContext(Dispatchers.IO) { copyImageToPrivateStorage(context, uri) }
            if (path == null) {
                viewModel.showMessage("خواندن تصویر ناموفق بود. یک تصویر PNG/JPG انتخاب کنید.")
            } else {
                onDone(path)
            }
        }
    }

    val sealPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri) { path ->
            sealPath = path
            save(path, signaturePath, signerName, signerTitle)
        }
    }
    val signaturePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri) { path ->
            signaturePath = path
            save(sealPath, path, signerName, signerTitle)
        }
    }

    // Warning required by the purchase checklist: an unsigned contract is not valid
    val missing = buildList {
        if (sealPath.isBlank()) add("مهر آموزشگاه")
        if (signaturePath.isBlank()) add("امضای مسئول آموزشگاه")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("مهر، امضا و سربخش قرارداد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "این تصاویر خودکار روی قرارداد چاپ می‌شوند و فقط روی گوشی شما ذخیره می‌شوند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (missing.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "توجه: ${missing.joinToString(" و ")} تنظیم نشده؛ قرارداد بدون آن چاپ می‌شود و قابل امضا تلقی نمی‌شود.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImageSlot(
                    title = "مهر آموزشگاه",
                    path = sealPath,
                    modifier = Modifier.weight(1f),
                    pick = { sealPicker.launch("image/*") },
                    clear = {
                        sealPath = ""
                        save("", signaturePath, signerName, signerTitle)
                    }
                )
                ImageSlot(
                    title = "امضای مسئول",
                    path = signaturePath,
                    modifier = Modifier.weight(1f),
                    pick = { signaturePicker.launch("image/*") },
                    clear = {
                        signaturePath = ""
                        save(sealPath, "", signerName, signerTitle)
                    }
                )
            }

            OutlinedTextField(
                value = signerName,
                onValueChange = { signerName = it },
                label = { Text("نام و نام خانوادگی مسئول آموزشگاه", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = signerTitle,
                onValueChange = { signerTitle = it },
                label = { Text("سمت (مثلاً مدیر آموزشگاه)", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { save(sealPath, signaturePath, signerName, signerTitle) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("ذخیره مهر و امضا")
                }
                OutlinedButton(
                    onClick = {
                        probing = true
                        probeState = null
                        scope.launch {
                            val client = ContractCloudClient.fromSettings(settings)
                            val res = withContext(Dispatchers.IO) { client.probe() }
                            probing = false
                            probeState = if (res.ok) {
                                val d = res.data
                                "اتصال برقرار است · Worker: بله · D1: ${if (d?.optBoolean("d1", false) == true) "متصل" else "غیرفعال"} · R2: ${if (d?.optBoolean("r2", false) == true) "متصل" else "غیرفعال"} · نسخه: ${d?.optString("version").orEmpty()}"
                            } else {
                                "اتصال ناموفق: ${res.error}"
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (probing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("تست Cloudflare", fontSize = 12.sp)
                }
            }

            probeState?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("اتصال برقرار")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Text(
                "آدرس سرور و کلید API همان مقادیری است که در «پیام‌ها ← اتصال Cloudflare» وارد می‌کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImageSlot(
    title: String,
    path: String,
    modifier: Modifier = Modifier,
    pick: () -> Unit,
    clear: () -> Unit
) {
    val bitmap = remember(path) { if (path.isBlank()) null else PdfDocumentHelper.loadImage(path, 600) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(84.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("تصویری انتخاب نشده", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) {
                Text("انتخاب", fontSize = 12.sp)
            }
            if (path.isNotBlank()) {
                OutlinedButton(onClick = clear) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Copies a picked image into filesDir/letterhead and returns its absolute path. */
internal fun copyImageToPrivateStorage(context: Context, uri: Uri): String? {
    return try {
        val decoded: Bitmap = context.contentResolver.openInputStream(uri).use { input ->
            input ?: return null
            BitmapFactory.decodeStream(input) ?: return null
        }
        val maxSide = 1200
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        val dir = File(context.filesDir, "letterhead").apply { mkdirs() }
        val target = File(dir, "img_${System.currentTimeMillis()}.png")
        target.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.PNG, 95, out) }
        target.absolutePath
    } catch (e: Exception) {
        null
    }
}
