package com.codegeasse1.hikariadblock

import android.app.Application
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.di.appModule
import com.codegeasse1.hikariadblock.worker.DailySummaryScheduler
import com.codegeasse1.hikariadblock.worker.FilterUpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import timber.log.Timber
import timber.log.Timber.DebugTree
import com.codegeasse1.hikariadblock.utils.CrashReportingManager
import com.codegeasse1.hikariadblock.utils.FileLoggingTree


class HikariApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@HikariApp)
            modules(appModule)
        }

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
        
        // Plant File logging tree for all builds to allow log export
        Timber.plant(FileLoggingTree(this))

        // Schedule auto-update for filter lists after Koin is initialized
        val appPreferences: AppPreferences by inject()
        applicationScope.launch {
            // Restore Crash Reporting state dynamically
            val isCrashReportingEnabled = appPreferences.crashReportingEnabled.first()
            CrashReportingManager.toggleSentry(this@HikariApp, isCrashReportingEnabled)

            // Move v6.3.0 single-config users onto the multi-profile schema.
            appPreferences.migrateLegacyWgConfigIfNeeded()

            FilterUpdateScheduler.scheduleFilterUpdate(this@HikariApp, appPreferences)

            // Schedule daily summary only if enabled
            if (appPreferences.dailySummaryEnabled.first()) {
                DailySummaryScheduler.scheduleDailySummary(this@HikariApp)
            }
        }

        // Trusted Wi-Fi networks (#197): auto-pause/resume on SSID change.
        com.codegeasse1.hikariadblock.service.TrustedNetworkManager(this, appPreferences).start()
    }
}
