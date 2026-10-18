package com.example.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a verified APK to Android's own package installer.
 *
 * The app never installs anything silently: it only starts the official system
 * installer, which shows its normal confirmation screens. On Android 8+ the
 * user must also grant "install unknown apps" to this app the first time; when
 * that is missing we send them straight to that toggle instead of failing.
 *
 * The APK is shared as a `content://` URI minted by [FileProvider] -- never a
 * `file://` URI, which Android forbids across apps since Nougat and would make
 * the installer fail. The read grant is put both on the intent flags and on its
 * [ClipData], which is what many installer builds actually inspect.
 */
object ApkInstaller {

    /** The file provider authority declared in the manifest. */
    private fun authority(ctx: Context) = "${ctx.packageName}.fileprovider"

    /** True when this app is allowed to request a package install. */
    fun canInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (_: Exception) {
        false
    }

    /** Opens the system setting that lets the user allow installs from this app. */
    fun openInstallPermissionSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            // Some devices name this screen differently; fall back to the list
            // of apps allowed to install unknown apps.
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Outcome of an install attempt, so the caller can say exactly what happened. */
    enum class Result { STARTED, NO_PERMISSION, NO_INSTALLER, FAILED }

    /**
     * Launches the system installer for [apk]. Returns a precise result so the
     * UI can guide the user instead of leaving them stuck or closing the app.
     */
    fun install(ctx: Context, apk: File): Result {
        if (!apk.exists()) return Result.FAILED
        if (!canInstall(ctx)) return Result.NO_PERMISSION

        return try {
            val uri: Uri = FileProvider.getUriForFile(ctx, authority(ctx), apk)

            // REQUEST_INSTALL_PACKAGES with a content:// URI is the only intent
            // Android 8+ guarantees to route to the package installer. A plain
            // ACTION_VIEW is resolved by every registered viewer, and some
            // devices hand it to a file manager that just opens the APK zip
            // again -> the user taps «نصب» and nothing happens.
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, ctx.packageName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // The receiving package must be granted the URI explicitly: some
            // ROMs (MIUI/ColorOS) ignore FLAG_GRANT_READ_URI_PERMISSION alone.
            val targets = ctx.packageManager
                .queryIntentActivities(installIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            val official = targets.firstOrNull {
                it.activityInfo.packageName == "com.google.android.packageinstaller" ||
                    it.activityInfo.packageName == "com.android.packageinstaller" ||
                    it.activityInfo.packageName == "com.android.packageinstaller"
            } ?: targets.firstOrNull()
                ?: return Result.NO_INSTALLER

            val start = Intent(installIntent).apply {
                setClassName(official.activityInfo.packageName, official.activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Older ROMs also look at this list for the URI grant.
                clipData = ClipData.newRawUri("apk", uri)
            }
            ctx.grantUriPermission(
                official.activityInfo.packageName, uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            ctx.startActivity(start)
            Result.STARTED
        } catch (_: android.content.ActivityNotFoundException) {
            Result.NO_INSTALLER
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
