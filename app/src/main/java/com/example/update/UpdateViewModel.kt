package com.example.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Holds the state of the update screen: the current check result, the download
 * progress, and the verified file once it is ready. All work is done on the
 * view model so it survives rotation while the screen is open.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    /** Which phase the screen is in. */
    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val update: UpdateInfo? = null,
        val percent: Int = 0,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val message: String? = null,
        val readyFile: File? = null,
        /** Set once a fresh download finished so the screen launches the installer. */
        val autoInstallPending: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Checks GitHub (force = bypass the interval) and updates the screen. */
    fun check(force: Boolean = true) {
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(phase = Phase.CHECKING, message = null)
        viewModelScope.launch {
            val latest = UpdateManager.checkForUpdate(ctx, force = force)
            _state.value = if (latest == null && UpdateManager.lastCheckFailed()) {
                _state.value.copy(
                    phase = Phase.ERROR, update = null,
                    message = "بررسی نسخهٔ تازه انجام نشد. اینترنت را چک کنید و دوباره بزنید."
                )
            } else if (latest == null) {
                _state.value.copy(phase = Phase.UP_TO_DATE, update = null)
            } else {
                // Point at a file we may already have from a previous attempt.
                val existing = ApkDownloader.fileFor(ctx, latest.version)
                    .takeIf { it.exists() && ApkDownloader.verifyApk(ctx, it) == null }
                if (existing != null) {
                    _state.value.copy(
                        phase = Phase.READY, update = latest, readyFile = existing,
                        percent = 100
                    )
                } else {
                    _state.value.copy(phase = Phase.AVAILABLE, update = latest)
                }
            }
        }
    }

    /** Downloads the APK for the current update, showing real progress. */
    fun download() {
        val info = _state.value.update ?: return
        if (downloadJob?.isActive == true) return
        val ctx = getApplication<Application>()
        _state.value = _state.value.copy(
            phase = Phase.DOWNLOADING, percent = 0,
            downloadedBytes = 0L, totalBytes = info.apkSize, message = null
        )
        downloadJob = viewModelScope.launch {
            val result = ApkDownloader.download(ctx, info) { done, total, percent ->
                _state.value = _state.value.copy(
                    downloadedBytes = done,
                    totalBytes = if (total > 0) total else info.apkSize,
                    percent = if (percent < 0) 0 else percent
                )
            }
            _state.value = when (result) {
                is ApkDownloader.Result.Success ->
                    _state.value.copy(
                        phase = Phase.READY,
                        readyFile = result.file,
                        percent = 100,
                        autoInstallPending = true
                    )
                is ApkDownloader.Result.Cancelled ->
                    _state.value.copy(phase = Phase.AVAILABLE, percent = 0, autoInstallPending = false)
                is ApkDownloader.Result.Failed ->
                    _state.value.copy(phase = Phase.ERROR, message = result.message, autoInstallPending = false)
            }
        }
    }


    /** Clears the one-shot auto-install flag after the screen has acted on it. */
    fun consumeAutoInstall() {
        if (_state.value.autoInstallPending) {
            _state.value = _state.value.copy(autoInstallPending = false)
        }
    }

    /** Cancels an in-flight download. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (_state.value.phase == Phase.DOWNLOADING) {
            _state.value = _state.value.copy(phase = Phase.AVAILABLE, percent = 0)
        }
    }

    /** Remembers that the user does not want this version, then ends the check. */
    fun skip() {
        val ctx = getApplication<Application>()
        _state.value.update?.let { UpdatePrefs.setSkippedVersion(ctx, it.version) }
        _state.value = _state.value.copy(phase = Phase.IDLE, message = null)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
    }
}
