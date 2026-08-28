package com.codegeasse1.hikariadblock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.codegeasse1.hikariadblock.data.Blocklist
import com.codegeasse1.hikariadblock.data.Preferences
import com.codegeasse1.hikariadblock.ui.HikariApp
import com.codegeasse1.hikariadblock.ui.theme.HikariAdBlockTheme
import com.codegeasse1.hikariadblock.util.Updater
import com.codegeasse1.hikariadblock.vpn.RootHosts
import com.codegeasse1.hikariadblock.vpn.VpnController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var updateVersion by mutableStateOf<String?>(null)
    private var alwaysOnDialog by mutableStateOf(false)
    private var stopCheckJob: Job? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            VpnController.begin(this)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        VpnController.syncRunning()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            if (Preferences.rootModeOnce(this@MainActivity)) {
                RootHosts.isActive()
            }
        }
        checkUpdate()
        setContent {
            val theme by Preferences.themeFlow(this@MainActivity).collectAsStateWithLifecycle(initialValue = "system")
            val darkTheme = when (theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            HikariAdBlockTheme(darkTheme = darkTheme) {
                HikariApp(
                    onToggle = { on -> toggle(on) },
                    onCheckUpdate = { checkUpdate() }
                )
            }
            updateVersion?.let { latest ->
                AlertDialog(
                    onDismissRequest = { updateVersion = null },
                    title = { Text("New update available") },
                    text = { Text("Hikari AdBlock v$latest is available.\n\nOpen GitHub to download the new APK.") },
                    confirmButton = {
                        TextButton(onClick = {
                            openUrl(Updater.REPO_URL + "/releases/latest")
                            updateVersion = null
                        }) { Text("Update") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            openUrl(Updater.REPO_URL)
                            updateVersion = null
                        }) { Text("GitHub") }
                    }
                )
            }
            if (alwaysOnDialog) {
                AlertDialog(
                    onDismissRequest = { alwaysOnDialog = false },
                    title = { Text("VPN won't stop") },
                    text = {
                        Text(
                            "Hikari AdBlock's VPN is still running even though you turned it off.\n\nTap \"Force stop\" to close the app so the VPN disconnects immediately — you can simply reopen the app afterwards."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            alwaysOnDialog = false
                            VpnController.stop(this@MainActivity)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }) { Text("Force stop") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            alwaysOnDialog = false
                            openVpnSettings()
                        }) { Text("VPN settings") }
                    }
                )
            }
        }
    }

    private fun checkUpdate() {
        lifecycleScope.launch {
            val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                .getOrNull() ?: "1.0.0"
            val latest = Updater.latestVersion()
            if (latest != null && Updater.isNewer(latest, current)) {
                updateVersion = latest
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun toggle(on: Boolean) {
        if (on) {
            stopCheckJob?.cancel()
            lifecycleScope.launch {
                val useRoot = Preferences.rootModeOnce(this@MainActivity) && RootHosts.isRootAvailable()
                if (useRoot) {
                    VpnController.stop(this@MainActivity)
                    Blocklist.loadIfNeeded(this@MainActivity)
                    RootHosts.apply(this@MainActivity)
                        .onFailure { startVpn() }
                } else {
                    startVpn()
                }
            }
        } else {
            VpnController.stop(this)
            if (RootHosts.active.value) {
                lifecycleScope.launch {
                    RootHosts.restore(this@MainActivity)
                }
            }
            stopCheckJob?.cancel()
            stopCheckJob = lifecycleScope.launch {
                var stuck = true
                for (i in 0 until 25) {
                    if (!VpnController.running.value) { stuck = false; break }
                    delay(100)
                }
                if (stuck) {
                    VpnController.stop(this@MainActivity)
                    for (i in 0 until 25) {
                        if (!VpnController.running.value) { stuck = false; break }
                        delay(100)
                    }
                }
                if (stuck) {
                    alwaysOnDialog = true
                }
            }
        }
    }

    private fun openVpnSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }
    }

    private fun startVpn() {
        VpnController.stop(this)
        val intent = VpnController.start(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        }
    }
}
