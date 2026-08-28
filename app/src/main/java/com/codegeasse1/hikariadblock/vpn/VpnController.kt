package com.codegeasse1.hikariadblock.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnController {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(running: Boolean) {
        _running.value = running
    }

    fun syncRunning() {
        _running.value = HikariVpnService.running
    }

    fun isPrepared(context: Context): Boolean = VpnService.prepare(context) == null

    fun start(context: Context): Intent? {
        val intent = VpnService.prepare(context)
        if (intent != null) return intent
        begin(context)
        return null
    }

    fun begin(context: Context) {
        val i = Intent(context, HikariVpnService::class.java).setAction(HikariVpnService.ACTION_START)
        context.startForegroundService(i)
    }

    fun stop(context: Context) {
        HikariVpnService.requestStop()
        runCatching {
            context.startService(Intent(context, HikariVpnService::class.java).setAction(HikariVpnService.ACTION_STOP))
        }
        context.stopService(Intent(context, HikariVpnService::class.java))
    }
}
