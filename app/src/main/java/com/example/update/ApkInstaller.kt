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
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                // Grant read access to the installer through every channel the
                // receiving app might look at.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("apk", uri)
            }

            // Try to launch the installer. A missing handler throws
            // ActivityNotFoundException (distinct from a general failure), so
            // the UI can tell the user the device has no installer.
            try {
                ctx.startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                return Result.NO_INSTALLER
            }
            Result.STARTED
        } catch (_: Exception) {
            Result.FAILED
        }
    }
}
