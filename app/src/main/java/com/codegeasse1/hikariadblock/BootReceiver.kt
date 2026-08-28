package com.codegeasse1.hikariadblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codegeasse1.hikariadblock.data.Preferences
import com.codegeasse1.hikariadblock.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val autostart = Preferences.isAutostartEnabled(context)
            if (autostart && VpnController.isPrepared(context)) {
                VpnController.begin(context)
            }
        }
    }
}
