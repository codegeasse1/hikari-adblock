package com.codegeasse1.hikariadblock.utils

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.data.remote.AppUpdateChecker
import com.codegeasse1.hikariadblock.data.remote.AppUpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** High-level result of an update check, for the About screen status text. */
enum class UpdateCheckOutcome { IDLE, CHECKING, UP_TO_DATE, FAILED, UPDATE_AVAILABLE }

/** Progress of the in-app update download/install flow. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val downloaded: Long, val total: Long) : InstallState
    data object NeedsPermission : InstallState
    data object Installing : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * App-scoped coordinator for the in-app updater.
 *
 * It is a Koin singleton (not a ViewModel) so that the update dialog can be
 * shown from the app root and the About screen without depending on a
 * navigation-specific ViewModelStoreOwner.
 */
class AppUpdateManager(
    private val context: Context,
    private val checker: AppUpdateChecker,
    private val installer: ApkDownloadInstaller,
    private val prefs: AppPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoCheckStarted = AtomicBoolean(false)
    private val downloadMutex = Mutex()

    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _checkOutcome = MutableStateFlow(UpdateCheckOutcome.IDLE)
    val checkOutcome: StateFlow<UpdateCheckOutcome> = _checkOutcome.asStateFlow()

    private var pendingApk: File? = null

    /**
     * Check GitHub for a newer release. [manual] checks always report their
     * outcome and re-show the dialog even if the version was dismissed
     * previously; the automatic check runs at most once per process and stays
     * quiet when the user already dismissed this version.
     */
    fun checkForUpdates(manual: Boolean) {
        if (!manual && !autoCheckStarted.compareAndSet(false, true)) return
        scope.launch {
            _checkOutcome.value = UpdateCheckOutcome.CHECKING
            val info = checker.fetchLatestRelease()
            if (info == null) {
                _checkOutcome.value = UpdateCheckOutcome.FAILED
                return@launch
            }
            if (!checker.isNewerVersion(info.version)) {
                _checkOutcome.value = UpdateCheckOutcome.UP_TO_DATE
                _updateInfo.value = null
                return@launch
            }
            _checkOutcome.value = UpdateCheckOutcome.UPDATE_AVAILABLE
            val dismissed = prefs.dismissedUpdateVersion.first()
            _updateInfo.value = if (!manual && info.version == dismissed) null else info
        }
    }

    /** Download the release APK and hand it to the system installer. */
    fun downloadAndInstall() {
        val info = _updateInfo.value ?: return
        val apk = info.apk
        if (apk == null) {
            _installState.value = InstallState.Failed("No compatible APK was found in this release.")
            return
        }
        scope.launch {
            downloadMutex.withLock {
                pendingApk = null
                _installState.value = InstallState.Downloading(0L, apk.sizeBytes)
                val file = installer.downloadApk(apk.downloadUrl, info.version) { downloaded, total ->
                    _installState.value = InstallState.Downloading(downloaded, total)
                }
                if (file == null) {
                    _installState.value = InstallState.Failed("Download failed. Check your connection and try again.")
                    return@withLock
                }
                pendingApk = file
                launchInstall(file)
            }
        }
    }

    /** Retry install after the user granted "install unknown apps" permission. */
    fun retryInstall() {
        val file = pendingApk ?: return
        scope.launch { launchInstall(file) }
    }

    private fun launchInstall(file: File) {
        _installState.value = InstallState.Installing
        when (installer.install(file)) {
            InstallOutcome.LAUNCHED -> _installState.value = InstallState.Idle
            InstallOutcome.NEEDS_PERMISSION -> _installState.value = InstallState.NeedsPermission
            InstallOutcome.FAILED -> _installState.value = InstallState.Failed("Could not open the system installer.")
        }
    }

    /** Open the GitHub release page in a browser. */
    fun openReleasePage() {
        val url = _updateInfo.value?.releaseUrl ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Could not open release page")
        }
    }

    /** Close the dialog and remember this version so it is not re-shown. */
    fun dismiss() {
        val version = _updateInfo.value?.version ?: return
        _updateInfo.value = null
        _installState.value = InstallState.Idle
        scope.launch { prefs.setDismissedUpdateVersion(version) }
    }
}
