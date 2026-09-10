package com.codegeasse1.hikariadblock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.utils.LocaleHelper
import com.codegeasse1.hikariadblock.service.AdBlockVpnService
import com.codegeasse1.hikariadblock.service.IptablesManager
import com.codegeasse1.hikariadblock.service.RootProxyService
import com.codegeasse1.hikariadblock.service.ShizukuManager
import com.codegeasse1.hikariadblock.service.ShizukuProxyService
import com.codegeasse1.hikariadblock.utils.AppScope
import com.codegeasse1.hikariadblock.ui.HikariAdBlockApp
import com.codegeasse1.hikariadblock.ui.theme.HikariTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.getKoin

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_VPN = "extra_start_vpn"
        const val EXTRA_SHOW_VPN_CONFLICT_DIALOG = "extra_show_vpn_conflict_dialog"
        const val ACTION_TOGGLE_SHORTCUT = "com.codegeasse1.hikariadblock.ACTION_TOGGLE_SHORTCUT"
    }

    private var widgetIntentHandled = false
    private val _showVpnConflictDialog = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed regardless — notification is optional but nice to have
        continueVpnToggle()
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply saved locale for pre-API 33 devices
        val appPrefs = AppPreferences(newBase)
        val savedLang = runBlocking { appPrefs.appLanguage.first() }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, savedLang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply hide-from-recents preference
        val appPrefsInit = AppPreferences(this)
        val hideFromRecents = runBlocking { appPrefsInit.hideFromRecents.first() }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.appTasks.firstOrNull()?.setExcludeFromRecents(hideFromRecents)

        enableEdgeToEdge()
        setContent {
            val appPrefs: AppPreferences = getKoin().get()
            val themeMode by appPrefs.themeMode.collectAsState(initial = AppPreferences.THEME_SYSTEM)
            val accentColor by appPrefs.accentColor.collectAsState(initial = AppPreferences.ACCENT_GREEN)

            val isDark = when (themeMode) {
                AppPreferences.THEME_DARK -> true
                AppPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            // Update status bar icons when theme changes
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            HikariTheme(themeMode = themeMode, accentColor = accentColor) {
                HikariAdBlockApp(
                    showVpnConflictDialog = _showVpnConflictDialog.value,
                    onDismissVpnConflictDialog = { _showVpnConflictDialog.value = false },
                    onShowVpnConflictDialog = { _showVpnConflictDialog.value = true },
                    onRequestVpnPermission = { handleVpnToggle() }
                )
            }
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG, false) == true) {
            _showVpnConflictDialog.value = true
            intent.removeExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG)
        }
        handleWidgetIntent(intent)
        handleShortcutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        retryPendingShizukuStart()
    }

    /**
     * If a Shizuku enable was requested but the foreground-service start was
     * blocked (Android 12+ background-start restriction) while the grant
     * dialog was on screen, retry now that we are back in the foreground.
     */
    private fun retryPendingShizukuStart() {
        if (!ShizukuManager.pendingEnable) return
        if (ShizukuProxyService.isRunning) {
            ShizukuManager.pendingEnable = false
            return
        }
        val appPrefs: AppPreferences = getKoin().get()
        lifecycleScope.launch(Dispatchers.IO) {
            // The user may have switched away from Shizuku mode while the grant
            // dialog was up — don't start it then.
            if (appPrefs.routingMode.first() != AppPreferences.ROUTING_MODE_SHIZUKU) {
                ShizukuManager.pendingEnable = false
                return@launch
            }
            if (!ShizukuManager.isBinderAlive() || !ShizukuManager.hasPermission()) return@launch
            withContext(Dispatchers.Main) {
                if (ShizukuProxyService.start(this@MainActivity)) {
                    ShizukuManager.pendingEnable = false
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG, false)) {
            _showVpnConflictDialog.value = true
            intent.removeExtra(EXTRA_SHOW_VPN_CONFLICT_DIALOG)
        }
        widgetIntentHandled = false
        handleWidgetIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (!widgetIntentHandled && intent?.getBooleanExtra(EXTRA_START_VPN, false) == true) {
            widgetIntentHandled = true
            val appPrefs: AppPreferences = getKoin().get()
            val routingMode = runBlocking { appPrefs.routingMode.first() }

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (!RootProxyService.isRunning) handleVpnToggle()
            } else if (routingMode == AppPreferences.ROUTING_MODE_SHIZUKU) {
                if (!ShizukuProxyService.isRunning) handleVpnToggle()
            } else {
                if (!AdBlockVpnService.isRunning) handleVpnToggle()
            }
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_TOGGLE_SHORTCUT) {
            val appPrefs: AppPreferences = getKoin().get()
            val routingMode = runBlocking { appPrefs.routingMode.first() }

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (RootProxyService.isRunning) {
                    RootProxyService.stop(this)
                } else {
                    handleVpnToggle()
                }
            } else if (routingMode == AppPreferences.ROUTING_MODE_SHIZUKU) {
                if (ShizukuProxyService.isRunning) {
                    ShizukuProxyService.stop(this)
                } else {
                    handleVpnToggle()
                }
            } else {
                if (AdBlockVpnService.isRunning) {
                    val stopIntent = Intent(this, AdBlockVpnService::class.java).apply {
                        action = AdBlockVpnService.ACTION_STOP
                    }
                    startService(stopIntent)
                } else {
                    handleVpnToggle()
                }
            }
            // Clear the action so it doesn't re-trigger
            intent.action = null
        }
    }

    private fun handleVpnToggle() {
        // Check notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        continueVpnToggle()
    }

    private fun continueVpnToggle() {
        val appPrefs: AppPreferences = getKoin().get()
        lifecycleScope.launch(Dispatchers.IO) {
            val routingMode = appPrefs.routingMode.first()

            if (routingMode == AppPreferences.ROUTING_MODE_ROOT) {
                if (IptablesManager.isRootAvailable()) {
                    withContext(Dispatchers.Main) {
                        RootProxyService.start(this@MainActivity)
                    }
                } else {
                    // If root is lost, fallback to Direct mode and request VPN permission
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    withContext(Dispatchers.Main) {
                        requestVpnPermission()
                    }
                }
            } else if (routingMode == AppPreferences.ROUTING_MODE_SHIZUKU) {
                if (!ShizukuManager.isBinderAlive()) {
                    // Shizuku not running — fallback to Direct mode and request VPN permission
                    appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                    withContext(Dispatchers.Main) {
                        requestVpnPermission()
                    }
                } else if (ShizukuManager.hasPermission()) {
                    ShizukuManager.pendingEnable = true
                    withContext(Dispatchers.Main) {
                        if (ShizukuProxyService.start(this@MainActivity)) {
                            ShizukuManager.pendingEnable = false
                        }
                    }
                } else {
                    // Grant can outlive this Activity (the Shizuku app dialog
                    // may background/destroy us), so wait on the application
                    // scope, and poll checkSelfPermission rather than relying
                    // solely on the result callback (Shevery bug).
                    //
                    // Create the foreground service *before* showing the grant
                    // dialog: the dialog backgrounds the app, and Android 12+
                    // blocks starting a foreground service from the background,
                    // which silently left the mode off after the user tapped
                    // Allow. The service waits for the grant before applying
                    // rules, and onResume() retries if the start was refused.
                    ShizukuManager.pendingEnable = true
                    withContext(Dispatchers.Main) {
                        ShizukuProxyService.start(this@MainActivity)
                    }
                    val activity = this@MainActivity
                    AppScope.scope.launch {
                        val granted = ShizukuManager.requestPermissionAndWait()
                        if (granted) {
                            // Nudge the (already started) service in case it
                            // gave up waiting; retrying from the app context is
                            // fine now that the FGS is alive.
                            if (ShizukuProxyService.start(activity.applicationContext)) {
                                ShizukuManager.pendingEnable = false
                            }
                        } else {
                            // Permission denied — fallback to Direct mode and request VPN permission
                            ShizukuManager.pendingEnable = false
                            appPrefs.setRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
                            ShizukuProxyService.stop(activity.applicationContext)
                            withContext(Dispatchers.Main) {
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    requestVpnPermission()
                                }
                            }
                        }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    requestVpnPermission()
                }
            }
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already have permission
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}