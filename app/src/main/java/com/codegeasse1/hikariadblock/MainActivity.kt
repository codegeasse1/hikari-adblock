package com.codegeasse1.hikariadblock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.codegeasse1.hikariadblock.ui.HikariApp
import com.codegeasse1.hikariadblock.ui.theme.HikariAdBlockTheme
import com.codegeasse1.hikariadblock.vpn.VpnController

class MainActivity : ComponentActivity() {

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
        setContent {
            HikariAdBlockTheme {
                HikariApp(
                    onToggle = { on -> toggle(on) }
                )
            }
        }
    }

    private fun toggle(on: Boolean) {
        if (on) {
            val intent = VpnController.start(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            }
        } else {
            VpnController.stop(this)
        }
    }
}
