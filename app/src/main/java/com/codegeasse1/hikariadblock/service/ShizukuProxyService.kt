package com.codegeasse1.hikariadblock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.work.OneTimeWorkRequestBuilder
import com.codegeasse1.hikariadblock.MainActivity
import com.codegeasse1.hikariadblock.R
import kotlinx.coroutines.flow.asStateFlow
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import com.codegeasse1.hikariadblock.data.repository.FilterListRepository
import com.codegeasse1.hikariadblock.data.dao.DnsLogDao
import com.codegeasse1.hikariadblock.data.dao.FirewallRuleDao
import com.codegeasse1.hikariadblock.data.dao.CustomDnsRuleDao
import com.codegeasse1.hikariadblock.data.dao.WhitelistDomainDao
import com.codegeasse1.hikariadblock.utils.AppNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
import com.codegeasse1.hikariadblock.utils.startOfDayMillis
import com.codegeasse1.hikariadblock.worker.ShizukuProxyResumeWorker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import org.koin.java.KoinJavaComponent.getKoin
import timber.log.Timber

/**
 * Foreground service for Shizuku mode.
 * Uses Shizuku (shell/ADB privilege) to run iptables and redirect all DNS
 * traffic (port 53) to the local Go engine at 127.0.0.1:15353, instead of
 * using VpnService — so no root is required and the system never shows the
 * VPN status-bar icon.
 *
 * Lifecycle:
 * - onCreate: Initialize Go engine + Koin dependencies
 * - onStartCommand(ACTION_START): Apply iptables rules + start watchdog
 * - onStartCommand(ACTION_STOP): Teardown iptables + stop engine
 * - onDestroy / onTaskRemoved: Teardown iptables (failsafe)
 */
class ShizukuProxyService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 11
        private const val CHANNEL_ID = "hikari_shizuku_proxy_channel"

        const val ACTION_START = "com.codegeasse1.hikariadblock.SHIZUKU_START"
        const val ACTION_STOP = "com.codegeasse1.hikariadblock.SHIZUKU_STOP"
        const val ACTION_RESTART = "com.codegeasse1.hikariadblock.SHIZUKU_RESTART"
        const val ACTION_PAUSE_1H = "com.codegeasse1.hikariadblock.SHIZUKU_PAUSE_1H"
        const val EXTRA_STARTED_FROM_BOOT = "extra_started_from_boot"

        /** Cap on the filter-loading phase. Slow/offline networks must never
         *  leave the UI stuck on "Connecting…" — we proceed with whatever
         *  loaded (or the cached set) once this elapses. */
        private const val FILTER_LOAD_TIMEOUT_MS = 90_000L

        /**
         * How long the service waits for a not-yet-visible Shizuku grant
         * before giving up. Covers a just-granted permission that hasn't
         * propagated yet, so the start doesn't fail spuriously.
         */
        private const val PERMISSION_WAIT_MS = 90_000L

        private val _state = kotlinx.coroutines.flow.MutableStateFlow(VpnState.STOPPED)
        val state: kotlinx.coroutines.flow.StateFlow<VpnState> = _state.asStateFlow()

        val isRunning: Boolean get() = _state.value == VpnState.RUNNING

        @Volatile
        var startTimestamp: Long = 0L
            private set

        private val _iptablesBlocked = kotlinx.coroutines.flow.MutableStateFlow(false)

        /**
         * True when the device/ROM refuses shell-level netfilter access on every
         * backend, so Shizuku mode cannot work here. Surfaced to the UI as an
         * immediate, actionable error instead of an endless "Connecting…".
         * Cleared on a new start attempt, on stop, or when the user dismisses it.
         */
        val iptablesBlocked: kotlinx.coroutines.flow.StateFlow<Boolean> =
            _iptablesBlocked.asStateFlow()

        fun dismissIptablesBlocked() {
            _iptablesBlocked.value = false
        }

        /**
         * Flag shell-level netfilter as blocked from outside the service (e.g.
         * a pre-flight probe run by MainActivity/SettingsViewModel before the
         * service is ever started). The UI reacts to this exactly like the
         * in-service detection, so the user gets the clear error dialog and the
         * one-tap "Switch to Direct (VPN) mode" option instead of an endless
         * "Connecting…".
         */
        fun reportIptablesBlocked() {
            _iptablesBlocked.value = true
        }

        fun start(context: Context): Boolean {
            val intent = Intent(context, ShizukuProxyService::class.java).apply {
                action = ACTION_START
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                // On Android 12+ starting a foreground service from the
                // background throws ForegroundServiceStartNotAllowedException.
                // This happens when the Shizuku grant dialog moved our app to
                // the background before we tried to start. The caller keeps
                // ShizukuManager.pendingEnable set and retries on resume.
                Timber.e(e, "Could not start ShizukuProxyService (blocked from background?)")
                false
            }
        }

        fun stop(context: Context) {
            ShizukuManager.pendingEnable = false
            _iptablesBlocked.value = false
            val intent = Intent(context, ShizukuProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Request a Shizuku restart to apply new settings/filter changes.
         * Only restarts if the service is currently running.
         */
        fun requestRestart(context: Context) {
            val s = _state.value
            if (s == VpnState.RUNNING || s == VpnState.RESTARTING) {
                val intent = Intent(context, ShizukuProxyService::class.java).apply {
                    action = ACTION_RESTART
                }
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null
    private var startWatchdogJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var retryManager = VpnRetryManager(maxRetries = 10, maxDelayMs = 60000L)

    @Volatile
    private var todayBlockedCount: Int = 0
    private lateinit var appPrefs: AppPreferences
    private lateinit var filterRepo: FilterListRepository
    private lateinit var dnsLogDao: DnsLogDao
    private lateinit var firewallRuleDao: FirewallRuleDao
    private lateinit var whitelistDomainDao: WhitelistDomainDao
    private lateinit var customDnsRuleDao: CustomDnsRuleDao
    private lateinit var appNameResolver: AppNameResolver
    private lateinit var goTunnelAdapter: GoTunnelAdapter
    @Volatile
    private var firewallManager: FirewallManager? = null

    // Preserve the displayed uptime across internal restarts (#163) —
    // settings/filter changes restart the proxy but shouldn't reset uptime.
    @Volatile
    private var preserveUptimeOnRestart = false

    // UIDs of whitelisted apps, excluded from the iptables DNS redirect.
    // Kept as a field so the watchdog re-applies the same rules (#150).
    @Volatile
    private var whitelistedUids: List<Int> = emptyList()

    @Volatile
    private var isRecordDnsLogsEnabled = true

    override fun onCreate() {
        super.onCreate()
        val koin = getKoin()
        appPrefs = koin.get()
        filterRepo = koin.get()
        dnsLogDao = koin.get()
        firewallRuleDao = koin.get()
        whitelistDomainDao = koin.get()
        customDnsRuleDao = koin.get()

        serviceScope.launch {
            appPrefs.recordDnsLogs.collect { enabled ->
                isRecordDnsLogsEnabled = enabled
            }
        }

        appNameResolver = AppNameResolver(this)
        goTunnelAdapter = GoTunnelAdapter(
            context = this,
            filterRepo = filterRepo,
            dnsLogDao = dnsLogDao,
            scope = serviceScope,
            appNameResolver = appNameResolver,
            firewallManagerProvider = { firewallManager },
            recordLogProvider = { isRecordDnsLogsEnabled },
        )
        observeLiveRuleChanges()
        Timber.d("ShizukuProxyService created")
    }

    /**
     * Live-refresh the Go engine's in-memory rule structures when
     * whitelist/blacklist/custom-rule/firewall/app-whitelist data changes,
     * so edits apply instantly in Shizuku mode too (no restart needed).
     */
    private fun observeLiveRuleChanges() {
        serviceScope.launch {
            whitelistDomainDao.getAll().collectLatest { filterRepo.loadWhitelist() }
        }
        serviceScope.launch {
            customDnsRuleDao.getAllFlow().collectLatest { filterRepo.loadCustomRules() }
        }
        serviceScope.launch {
            firewallRuleDao.getAll().collectLatest { firewallManager?.loadRules() }
        }
        serviceScope.launch {
            appPrefs.firewallEnabled.collectLatest { enabled ->
                if (enabled && _state.value == VpnState.RUNNING && firewallManager == null) {
                    val fw = FirewallManager(this@ShizukuProxyService, firewallRuleDao)
                    fw.loadRules()
                    firewallManager = fw
                } else if (!enabled) {
                    firewallManager = null
                }
            }
        }
        // App whitelist in Root mode is enforced by iptables UIDs — re-apply
        // automatically on change with a proxy restart (no manual action).
        var lastApplied: Set<String>? = null
        serviceScope.launch {
            appPrefs.whitelistedApps.collectLatest { apps ->
                if (lastApplied == null) {
                    lastApplied = apps
                    return@collectLatest
                }
                if (apps != lastApplied) {
                    lastApplied = apps
                    delay(400)
                    if (_state.value == VpnState.RUNNING) {
                        restartProxy()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedFromBoot = intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) ?: false

        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_1H -> {
                pauseProxy()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartProxy()
                return START_STICKY
            }
            else -> {
                startProxy(startedFromBoot)
                return START_STICKY
            }
        }
    }

    private fun startProxy(startedFromBoot: Boolean = false) {
        if (_state.value == VpnState.RUNNING || _state.value == VpnState.STARTING) {
            Timber.d("ShizukuProxyService already running/starting")
            return
        }
        
        // Fresh attempt — clear any stale "iptables blocked" error so the
        // dialog can surface again if this attempt also hits it.
        _iptablesBlocked.value = false

        _state.value = VpnState.STARTING

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // On boot the su daemon (Magisk/KernelSU) can take well over the
        // normal retry window to come up — allow a much longer budget.
        retryManager = VpnRetryManager(
            maxRetries = if (startedFromBoot) 30 else 10,
            maxDelayMs = 60000L
        )

        // Hard safety net so the UI can never sit on "Connecting…" forever.
        startStartWatchdog(startedFromBoot)

        serviceScope.launch {
            try {
                // 0. Fast-fail pre-flight probe. Callers already probe before
                // starting us, but if we were started by an older path (or the
                // probe raced a ROM that just changed its policy) detect the
                // blocked netfilter here too, on this background thread, before
                // spending a single network call on filters. One cheap command
                // tells us whether every backend is denied.
                if (ShizukuManager.isBinderAlive() &&
                    ShizukuManager.hasPermission() &&
                    ShizukuManager.probeNetfilterBlocked()
                ) {
                    Timber.e("Pre-flight probe: device blocks shell iptables on all backends")
                    stopProxy()
                    showIptablesBlockedNotification()
                    _iptablesBlocked.value = true
                    return@launch
                }

                // 1. Load filters (same as VPN mode). Bounded so a slow or
                // offline network can't leave us stuck in STARTING forever.
                val filterLoadOk = withTimeoutOrNull(FILTER_LOAD_TIMEOUT_MS) {
                    filterRepo.loadWhitelist()
                    filterRepo.loadCustomRules()
                    filterRepo.setYoutubeAdBlocking(appPrefs.youtubeAdBlockEnabled.first())
                    // seedDefaultsIfNeeded() already performs the remote
                    // filter sync — don't fetch it a second time here.
                    filterRepo.seedDefaultsIfNeeded()
                    val result = filterRepo.loadAllEnabledFilters()
                    Timber.d("Filters loaded for Shizuku mode: ${result.getOrDefault(0)} domains")
                    true
                }
                if (filterLoadOk == null) {
                    Timber.w("Filter loading exceeded ${FILTER_LOAD_TIMEOUT_MS}ms — continuing with cached filters")
                }

                // 2. Setup engine parameters
                val protocol = appPrefs.dnsProtocol.first().name
                val primary = appPrefs.upstreamDns.first()
                val fallback = appPrefs.fallbackDns.first()
                val dohUrl = appPrefs.dohUrl.first()
                val safeSearch = appPrefs.safeSearchEnabled.first()
                val youtubeSafe = appPrefs.youtubeRestrictedMode.first()
                val responseType = appPrefs.dnsResponseType.first()

                goTunnelAdapter.configureDns(protocol, primary, fallback, dohUrl)
                goTunnelAdapter.configureSafeSearch(safeSearch, youtubeSafe)
                goTunnelAdapter.setBlockResponseType(responseType)

                // Load firewall rules if enabled
                val firewallEnabled = appPrefs.firewallEnabled.first()
                if (firewallEnabled) {
                    val fwManager = FirewallManager(this@ShizukuProxyService, firewallRuleDao)
                    fwManager.loadRules()
                    firewallManager = fwManager
                    Timber.d("Firewall enabled for Shizuku, rules loaded")
                } else {
                    firewallManager = null
                }

                // Resolve whitelisted apps to UIDs so iptables skips their
                // DNS — Root-mode equivalent of VPN mode's
                // addDisallowedApplication. Without this the whitelist had
                // no effect at all in Shizuku mode (#150).
                whitelistedUids = appPrefs.getWhitelistedAppsSnapshot().mapNotNull { pkg ->
                    try {
                        packageManager.getApplicationInfo(pkg, 0).uid
                    } catch (e: Exception) {
                        Timber.w("Whitelisted app not found, skipping: $pkg")
                        null
                    }
                }.distinct()

                // 3. Retry loop for Standalone mode and IPTables setup
                // Shizuku can take a moment to come up on boot — keep trying
                // within the retry budget.
                //
                // If the enable flow started us *before* showing the Shizuku
                // grant dialog (to dodge Android 12+'s background FGS-start
                // restriction), the dialog may still be on screen — wait for
                // the grant here instead of failing the whole start.
                if (ShizukuManager.isBinderAlive() && !ShizukuManager.hasPermission()) {
                    Timber.d("Waiting up to ${PERMISSION_WAIT_MS}ms for Shizuku permission grant")
                    ShizukuManager.waitForPermissionGrant(PERMISSION_WAIT_MS)
                }

                var proxyStarted = false
                // When the device blocks shell netfilter access on every
                // backend, retrying is pointless — bail out immediately with a
                // clear, actionable error instead of looping 10 times.
                // Post-grant pre-flight probe: now that the Shizuku permission
                // may have just been granted, one cheap command tells us
                // whether netfilter is denied — fail fast instead of burning
                // through the entire retry budget first.
                var iptablesBlocked = ShizukuManager.isBinderAlive() &&
                    ShizukuManager.hasPermission() &&
                    ShizukuManager.probeNetfilterBlocked()
                if (iptablesBlocked) {
                    Timber.e("Post-grant probe: device blocks shell iptables on all backends")
                }
                while (!proxyStarted && !iptablesBlocked && retryManager.shouldRetry()) {
                    // Shizuku must be running and permission granted for
                    // shell-level iptables. Both are re-checked on every
                    // retry (Shizuku can be restarted mid-attempt).
                    if (!ShizukuManager.isBinderAlive()) {
                        Timber.w("Shizuku binder not alive yet")
                    } else if (!ShizukuManager.hasPermission()) {
                        Timber.w("Shizuku permission not granted")
                    } else {
                        // Block (background thread) until the binder is fully
                        // received — the very first call triggers the provider.
                        ShizukuManager.waitForBinder()
                        val engineStarted = goTunnelAdapter.startStandalone(port = 15353)
                        if (engineStarted) {
                            when (ShizukuManager.setupRules(this@ShizukuProxyService, whitelistUids = whitelistedUids)) {
                                ShizukuManager.SetupResult.SUCCESS -> proxyStarted = true
                                ShizukuManager.SetupResult.BLOCKED -> {
                                    // ROM denies shell-level iptables/nft entirely.
                                    iptablesBlocked = true
                                    goTunnelAdapter.stop()
                                }
                                ShizukuManager.SetupResult.FAILED -> {
                                    goTunnelAdapter.stop() // stop engine if iptables fails
                                }
                            }
                        }
                    }

                    if (!proxyStarted && !iptablesBlocked && retryManager.shouldRetry()) {
                         Timber.w("Shizuku establishment failed, retrying... (${retryManager.getRetryCount()}/${retryManager.getMaxRetries()})")
                         retryManager.waitForRetry()
                    }
                }

                if (iptablesBlocked) {
                    Timber.e("Shizuku mode unavailable: device blocks shell iptables on all backends")
                    stopProxy()
                    showIptablesBlockedNotification()
                    _iptablesBlocked.value = true
                    return@launch
                }

                if (!proxyStarted) {
                    Timber.e("Failed to start Shizuku after ${retryManager.getMaxRetries()} attempts")
                    stopProxy()
                    showStartFailedNotification()
                    return@launch
                }

                retryManager.reset()

                // Start the background /proc/net/udp snapshotter. The
                // engine's AppResolver reads from this snapshot instead
                // of doing an inline shell call per DNS query — needed
                // because iptables REDIRECT breaks getConnectionOwnerUid
                // and short-lived DNS sockets close before any on-demand
                // /proc/net read could see them.
                appNameResolver.startSnapshotter(serviceScope)

                startWatchdogJob?.cancel()
                _state.value = VpnState.RUNNING
                ShizukuManager.pendingEnable = false
                if (!preserveUptimeOnRestart || startTimestamp == 0L) {
                    startTimestamp = System.currentTimeMillis()
                }
                preserveUptimeOnRestart = false
                Timber.d("Shizuku mode active — DNS traffic redirected to :15353")

                updateNotification()
                startNotificationUpdates()

                // 4. Start watchdog
                startWatchdog()
            } catch (e: CancellationException) {
                // Service/scope shutting down — let cancellation propagate.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to start Shizuku mode")
                // stopProxy() resets _state to STOPPED (fixes the bug where a
                // failed start left the UI stuck on "Connecting…" forever).
                stopProxy()
                showStartFailedNotification()
            }
        }
    }

    private fun stopProxy(showPausedNotification: Boolean = false) {
        Timber.d("Stopping Shizuku mode")
        _state.value = VpnState.STOPPING
        watchdogJob?.cancel()
        startWatchdogJob?.cancel()
        stopNotificationUpdates()
        appNameResolver.stopSnapshotter()

        // Teardown iptables rules (critical — prevents internet loss)
        ShizukuManager.teardownRules()

        // Stop Go engine
        goTunnelAdapter.stop()

        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        if (showPausedNotification) {
            stopForeground(STOP_FOREGROUND_DETACH)
            showPausedNotification()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun pauseProxy() {
        Timber.d("Pausing Shizuku for 1 hour")

        // Schedule resume after 1 hour
        val resumeWork = OneTimeWorkRequestBuilder<ShizukuProxyResumeWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            ShizukuProxyResumeWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            resumeWork
        )

        // Stop proxy and show paused notification
        stopProxy(showPausedNotification = true)
    }

    /**
     * Restart the Shizuku service to apply new settings/filter changes
     * without requiring the user to manually stop and start.
     */
    private fun restartProxy() {
        if (_state.value == VpnState.RESTARTING) return
        val s = _state.value
        if (s != VpnState.RUNNING && s != VpnState.STARTING) return

        _state.value = VpnState.RESTARTING
        Timber.d("Restarting Shizuku to apply new settings")

        watchdogJob?.cancel()
        stopNotificationUpdates()
        appNameResolver.stopSnapshotter()

        serviceScope.launch(Dispatchers.IO) {
            // Stop Go engine
            goTunnelAdapter.stop()

            // Teardown iptables
            ShizukuManager.teardownRules()

            // Brief delay to let resources clean up
            delay(1000L)

            // Restart
            preserveUptimeOnRestart = true
            _state.value = VpnState.STOPPED
            startProxy()
        }
    }

    /**
     * Hard safety net for the start sequence. If it fails to reach RUNNING
     * (or STOPPED) within the budget — e.g. a shell subsystem wedges in a
     * way the per-command timeouts don't catch — force the state back to
     * STOPPED and surface the start-failed notification. Without this, a
     * failed start could leave the Home screen showing "Connecting…" forever.
     */
    private fun startStartWatchdog(startedFromBoot: Boolean) {
        startWatchdogJob?.cancel()
        val budgetMs = if (startedFromBoot) 10 * 60_000L else 4 * 60_000L
        startWatchdogJob = serviceScope.launch {
            delay(budgetMs)
            if (_state.value == VpnState.STARTING) {
                Timber.e("Start watchdog tripped after ${budgetMs}ms — forcing STOPPED")
                ShizukuManager.teardownRules()
                goTunnelAdapter.stop()
                _state.value = VpnState.STOPPED
                startTimestamp = 0L
                stopForeground(STOP_FOREGROUND_DETACH)
                showStartFailedNotification()
                stopSelf()
            }
        }
    }

    /**
     * Watchdog monitors Go engine health every 10 seconds.
     * If the engine is dead, teardown iptables to prevent internet loss.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && _state.value == VpnState.RUNNING) {
                delay(10_000)
                // TODO: Check Go engine health
                // if (!goEngine.isRunning()) {
                //     Timber.w("Go engine died — tearing down iptables")
                //     ShizukuManager.teardownRules()
                //     isRunning = false
                //     stopSelf()
                //     break
                // }

                // For now, check if iptables rules are still active
                if (!ShizukuManager.isActive()) {
                    Timber.w("iptables rules disappeared — re-applying")
                    ShizukuManager.setupRules(this@ShizukuProxyService, whitelistUids = whitelistedUids)
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.d("ShizukuProxyService onDestroy — teardown iptables")
        _state.value = VpnState.STOPPED
        startTimestamp = 0L
        watchdogJob?.cancel()
        startWatchdogJob?.cancel()
        stopNotificationUpdates()
        if (::appNameResolver.isInitialized) appNameResolver.stopSnapshotter()
        ShizukuManager.teardownRules()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("ShizukuProxyService onTaskRemoved — teardown iptables")
        ShizukuManager.teardownRules()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shizuku Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Shizuku ad blocker is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, ShizukuProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, ShizukuProxyService::class.java).apply {
            action = ACTION_PAUSE_1H
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 2, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val text = if (isRunning) {
            val uptimeStr = formatUptime(System.currentTimeMillis() - startTimestamp)
            getString(R.string.vpn_notification_stats_today, todayBlockedCount, uptimeStr)
        } else {
            getString(R.string.shizuku_proxy_notification_text)
        }

        return builder
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_notification_action_pause), pausePendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_notification_action_stop), stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
    
    private fun showPausedNotification() {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, ShizukuProxyService::class.java).apply {
            action = ACTION_START
        }
        val startPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 3, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.vpn_paused_title))
            .setContentText(getString(R.string.vpn_paused_text))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_stopped_action_enable), startPendingIntent
                ).build()
            )
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun showStartFailedNotification() {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = Intent(this, ShizukuProxyService::class.java).apply {
            action = ACTION_START
        }
        val retryPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 4, retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 4, retryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.shizuku_start_failed_title))
            .setContentText(getString(R.string.shizuku_start_failed_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.shizuku_start_failed_text)))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.vpn_stopped_action_enable), retryPendingIntent
                ).build()
            )
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Shown when the device/ROM refuses shell-level netfilter access on every
     * backend. Unlike the generic start-failed notification, this tells the
     * user the exact cause and the two ways out (root backend / Direct mode).
     */
    private fun showIptablesBlockedNotification() {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(getString(R.string.shizuku_iptables_blocked_title))
            .setContentText(getString(R.string.shizuku_iptables_blocked_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.shizuku_iptables_blocked_text)))
            .setSmallIcon(R.drawable.ic_shield_off)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()

        notificationUpdateJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            while (isActive && isRunning) {
                try {
                    todayBlockedCount = dnsLogDao.getBlockedCountSinceSync(startOfDayMillis())
                    delay(30_000L)
                    if (isRunning && powerManager.isInteractive) {
                        updateNotification()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating notification in ShizukuProxyService")
                    break
                }
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatUptime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }
}

