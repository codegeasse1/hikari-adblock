package com.codegeasse1.hikariadblock

import android.app.Application
import com.codegeasse1.hikariadblock.data.Blocklist
import com.codegeasse1.hikariadblock.data.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HikariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            Blocklist.load(this@HikariApp)
            val wl = Preferences.whitelistOnce(this@HikariApp)
            val cb = Preferences.customBlockedOnce(this@HikariApp)
            Blocklist.applyWhitelist(wl)
            Blocklist.applyCustomBlocked(cb)
        }
        scope.launch {
            while (true) {
                runCatching {
                    val hours = Preferences.autoUpdateHoursOnce(this@HikariApp)
                    if (hours > 0) {
                        val last = Preferences.lastUpdateMillisOnce(this@HikariApp)
                        if (System.currentTimeMillis() - last >= hours * 3600_000L) {
                            val urls = listOf(Preferences.DEFAULT_UPDATE_URL) +
                                Preferences.filterListsOnce(this@HikariApp)
                            Blocklist.refreshFromUrls(this@HikariApp, urls)
                                .onSuccess {
                                    Preferences.setLastUpdate(this@HikariApp, System.currentTimeMillis())
                                }
                        }
                    }
                }
                delay(3600_000L)
            }
        }
    }
}
