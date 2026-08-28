package com.codegeasse1.hikariadblock.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codegeasse1.hikariadblock.R
import com.codegeasse1.hikariadblock.data.dao.CustomDnsRuleDao
import com.codegeasse1.hikariadblock.data.dao.DnsLogDao
import com.codegeasse1.hikariadblock.data.dao.FilterListDao
import com.codegeasse1.hikariadblock.data.dao.FirewallRuleDao
import com.codegeasse1.hikariadblock.data.dao.ProtectionProfileDao
import com.codegeasse1.hikariadblock.data.dao.WhitelistDomainDao
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.data.entities.FilterList
import com.codegeasse1.hikariadblock.data.entities.FilterListBackup
import com.codegeasse1.hikariadblock.data.entities.FirewallRule
import com.codegeasse1.hikariadblock.data.entities.FirewallRuleBackup
import com.codegeasse1.hikariadblock.data.entities.ProfileManager
import com.codegeasse1.hikariadblock.data.entities.SettingsBackup
import com.codegeasse1.hikariadblock.data.entities.WhitelistDomain
import com.codegeasse1.hikariadblock.data.repository.FilterListRepository
import com.codegeasse1.hikariadblock.service.AdBlockVpnService
import com.codegeasse1.hikariadblock.service.ServiceController
import com.codegeasse1.hikariadblock.ui.event.UiEvent
import com.codegeasse1.hikariadblock.ui.event.toast
import com.codegeasse1.hikariadblock.utils.CustomRuleParser
import com.codegeasse1.hikariadblock.worker.DailySummaryScheduler
import com.codegeasse1.hikariadblock.worker.FilterUpdateScheduler
import com.codegeasse1.hikariadblock.service.IptablesManager
import com.codegeasse1.hikariadblock.service.RootProxyService
import com.codegeasse1.hikariadblock.utils.CrashReportingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appPrefs: AppPreferences,
    private val filterRepo: FilterListRepository,
    private val dnsLogDao: DnsLogDao,
    private val whitelistDomainDao: WhitelistDomainDao,
    private val filterListDao: FilterListDao,
    private val customDnsRuleDao: CustomDnsRuleDao,
    private val profileDao: ProtectionProfileDao,
    private val profileManager: ProfileManager,
    private val firewallRuleDao: FirewallRuleDao,
    application: Application,
) : AndroidViewModel(application) {

    val autoReconnect: StateFlow<Boolean> = appPrefs.autoReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val filterLists: StateFlow<List<FilterList>> = filterListDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val whitelistDomains: StateFlow<List<WhitelistDomain>> = whitelistDomainDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val crashReportingEnabled: StateFlow<Boolean> = appPrefs.crashReportingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val hideFromRecents: StateFlow<Boolean> = appPrefs.hideFromRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoUpdateEnabled: StateFlow<Boolean> = appPrefs.autoUpdateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateFrequency: StateFlow<String> = appPrefs.autoUpdateFrequency
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.UPDATE_FREQUENCY_24H
        )

    val autoUpdateWifiOnly: StateFlow<Boolean> = appPrefs.autoUpdateWifiOnly
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val autoUpdateNotification: StateFlow<String> = appPrefs.autoUpdateNotification
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.NOTIFICATION_NORMAL
        )

    val dnsResponseType: StateFlow<String> = appPrefs.dnsResponseType
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DNS_RESPONSE_CUSTOM_IP
        )

    val safeSearchEnabled: StateFlow<Boolean> = appPrefs.safeSearchEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    val youtubeRestrictedMode: StateFlow<Boolean> = appPrefs.youtubeRestrictedMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dailySummaryEnabled: StateFlow<Boolean> = appPrefs.dailySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val milestoneNotificationsEnabled: StateFlow<Boolean> = appPrefs.milestoneNotificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val upstreamDns: StateFlow<String> = appPrefs.upstreamDns
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_UPSTREAM_DNS
        )

    val networkSwitchDelayEnabled: StateFlow<Boolean> = appPrefs.networkSwitchDelayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val networkSwitchDelaySec: StateFlow<Int> = appPrefs.networkSwitchDelaySec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val routingMode: StateFlow<String> = appPrefs.routingMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences.ROUTING_MODE_DIRECT)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            filterRepo.seedDefaultsIfNeeded()
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setAutoReconnect(enabled) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setCrashReportingEnabled(enabled)
            CrashReportingManager.toggleSentry(getApplication(), enabled)
        }
    }

    fun setHideFromRecents(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setHideFromRecents(enabled) }
    }

    fun setNetworkSwitchDelayEnabled(enabled: Boolean) {
        viewModelScope.launch { appPrefs.setNetworkSwitchDelayEnabled(enabled) }
    }

    fun setNetworkSwitchDelaySec(seconds: Int) {
        viewModelScope.launch { appPrefs.setNetworkSwitchDelaySec(seconds) }
    }

    fun setRoutingModeEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enabled) {
                if (IptablesManager.isRootAvailable()) {
                    applyRoutingMode(AppPreferences.ROUTING_MODE_ROOT)
                } else {
                    _events.toast(R.string.root_not_available)
                }
            } else {
                applyRoutingMode(AppPreferences.ROUTING_MODE_DIRECT)
            }
        }
    }

    private suspend fun applyRoutingMode(mode: String) {
        val oldMode = appPrefs.routingMode.first()
        if (oldMode == mode) return

        appPrefs.setRoutingMode(mode)
        val context = getApplication<Application>().applicationContext

        val isRoot = mode == AppPreferences.ROUTING_MODE_ROOT

        if (AdBlockVpnService.isRunning || RootProxyService.isRunning) {
            if (isRoot) {
                val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_STOP
                }
                context.startService(stopIntent)
                delay(800)
                RootProxyService.start(context)
            } else {
                RootProxyService.stop(context)
                delay(800)
                val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }
        }
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateEnabled(enabled)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
        }
    }

    fun setAutoUpdateFrequency(frequency: String) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateFrequency(frequency)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
        }
    }

    fun setAutoUpdateWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateWifiOnly(wifiOnly)
            FilterUpdateScheduler.scheduleFilterUpdate(
                getApplication<Application>().applicationContext,
                appPrefs
            )
        }
    }

    fun setAutoUpdateNotification(notificationType: String) {
        viewModelScope.launch {
            appPrefs.setAutoUpdateNotification(notificationType)
        }
    }

    fun setDnsResponseType(responseType: String) {
        viewModelScope.launch {
            appPrefs.setDnsResponseType(responseType)
            requestVpnRestart()
        }
    }

    fun setSafeSearchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setSafeSearchEnabled(enabled)
            requestVpnRestart()
        }
    }


    fun setYoutubeRestrictedMode(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setYoutubeRestrictedMode(enabled)
            requestVpnRestart()
        }
    }

    fun setDailySummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setDailySummaryEnabled(enabled)
            if (enabled) {
                DailySummaryScheduler.scheduleDailySummary(
                    getApplication<Application>().applicationContext
                )
            } else {
                DailySummaryScheduler.cancelDailySummary(
                    getApplication<Application>().applicationContext
                )
            }
        }
    }

    fun setMilestoneNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setMilestoneNotificationsEnabled(enabled)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            dnsLogDao.clearAll()
            _events.toast(R.string.filter_log_cleared)
        }
    }

    // ── Export Settings ──────────────────────────────────────────────
    fun exportSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val activeProfile = profileDao.getActive()
                val backup = SettingsBackup(
                    upstreamDns = appPrefs.upstreamDns.first(),
                    fallbackDns = appPrefs.fallbackDns.first(),
                    autoReconnect = appPrefs.autoReconnect.first(),
                    themeMode = appPrefs.themeMode.first(),
                    appLanguage = appPrefs.appLanguage.first(),
                    safeSearchEnabled = appPrefs.safeSearchEnabled.first(),
                    youtubeRestrictedMode = appPrefs.youtubeRestrictedMode.first(),
                    dailySummaryEnabled = appPrefs.dailySummaryEnabled.first(),
                    milestoneNotificationsEnabled = appPrefs.milestoneNotificationsEnabled.first(),
                    activeProfileType = activeProfile?.profileType ?: "",
                    firewallEnabled = appPrefs.firewallEnabled.first(),
                    filterLists = filterLists.value.map { f ->
                        FilterListBackup(name = f.name, url = f.url, isEnabled = f.isEnabled)
                    },
                    whitelistDomains = whitelistDomains.value.map { it.domain },
                    whitelistedApps = appPrefs.getWhitelistedAppsSnapshot().toList(),
                    customRules = customDnsRuleDao.getAll().map { it.rule },
                    firewallRules = firewallRuleDao.getEnabledRules().map { r ->
                        FirewallRuleBackup(
                            packageName = r.packageName,
                            blockWifi = r.blockWifi,
                            blockMobileData = r.blockMobileData,
                            scheduleEnabled = r.scheduleEnabled,
                            scheduleStartHour = r.scheduleStartHour,
                            scheduleStartMinute = r.scheduleStartMinute,
                            scheduleEndHour = r.scheduleEndHour,
                            scheduleEndMinute = r.scheduleEndMinute,
                            isEnabled = r.isEnabled
                        )
                    }
                )

                val jsonFormat = kotlinx.serialization.json.Json { prettyPrint = true }
                getApplication<Application>().applicationContext.contentResolver.openOutputStream(
                    uri
                )?.use { out ->
                    out.write(
                        jsonFormat.encodeToString(SettingsBackup.serializer(), backup).toByteArray()
                    )
                }
                _events.toast(R.string.filter_settings_export)
            } catch (e: Exception) {
                _events.toast(R.string.filter_export_failed, listOf("${e.message}"))
            }
        }
    }

    // ── Import Settings ──────────────────────────────────────────────
    fun importSettings(uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonStr =
                    getApplication<Application>().applicationContext.contentResolver.openInputStream(
                        uri
                    )?.use { input ->
                        input.bufferedReader().readText()
                    } ?: throw Exception("Cannot read file")

                val jsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val backup = jsonFormat.decodeFromString(SettingsBackup.serializer(), jsonStr)

                // Preferences
                appPrefs.setUpstreamDns(backup.upstreamDns)
                appPrefs.setFallbackDns(backup.fallbackDns)
                appPrefs.setAutoReconnect(backup.autoReconnect)
                appPrefs.setThemeMode(backup.themeMode)
                appPrefs.setAppLanguage(backup.appLanguage)
                appPrefs.setSafeSearchEnabled(backup.safeSearchEnabled)
                appPrefs.setYoutubeRestrictedMode(backup.youtubeRestrictedMode)
                appPrefs.setDailySummaryEnabled(backup.dailySummaryEnabled)
                if (backup.dailySummaryEnabled) {
                    DailySummaryScheduler.scheduleDailySummary(getApplication())
                } else {
                    DailySummaryScheduler.cancelDailySummary(getApplication())
                }
                appPrefs.setMilestoneNotificationsEnabled(backup.milestoneNotificationsEnabled)
                appPrefs.setFirewallEnabled(backup.firewallEnabled)

                // Filter lists — add new AND update isEnabled for existing
                backup.filterLists.forEach { f ->
                    val existing = filterLists.value.firstOrNull { it.url == f.url }
                    if (existing != null) {
                        // Update isEnabled state if it differs
                        if (existing.isEnabled != f.isEnabled) {
                            filterListDao.setEnabled(existing.id, f.isEnabled)
                        }
                    } else {
                        filterListDao.insert(
                            FilterList(
                                name = f.name,
                                url = f.url,
                                isEnabled = f.isEnabled
                            )
                        )
                    }
                }

                // Whitelist domains — only add new
                backup.whitelistDomains.forEach { domain ->
                    if (whitelistDomainDao.exists(domain) == 0) {
                        whitelistDomainDao.insert(WhitelistDomain(domain = domain))
                    }
                }

                // Whitelisted apps — merge
                val current = appPrefs.getWhitelistedAppsSnapshot()
                appPrefs.setWhitelistedApps(current + backup.whitelistedApps.toSet())

                // Custom rules — parse and add (avoid duplicates)
                val existingRules = customDnsRuleDao.getAll().map { it.rule }.toSet()
                backup.customRules.forEach { ruleText ->
                    if (ruleText !in existingRules) {
                        val rule = CustomRuleParser.parseRule(ruleText)
                        if (rule != null) {
                            customDnsRuleDao.insert(rule)
                        }
                    }
                }

                // Firewall rules — only add new
                backup.firewallRules.forEach { r ->
                    if (firewallRuleDao.getByPackageName(r.packageName) == null) {
                        firewallRuleDao.insert(
                            FirewallRule(
                                packageName = r.packageName,
                                blockWifi = r.blockWifi,
                                blockMobileData = r.blockMobileData,
                                scheduleEnabled = r.scheduleEnabled,
                                scheduleStartHour = r.scheduleStartHour,
                                scheduleStartMinute = r.scheduleStartMinute,
                                scheduleEndHour = r.scheduleEndHour,
                                scheduleEndMinute = r.scheduleEndMinute,
                                isEnabled = r.isEnabled
                            )
                        )
                    }
                }

                // Restore active profile LAST — after all filter/rule data is in place.
                // switchToProfile() overwrites filter isEnabled states based on profile
                // template, so it must run after the filter list import above.
                if (backup.activeProfileType.isNotBlank()) {
                    val profile = profileDao.getByType(backup.activeProfileType)
                    if (profile != null) {
                        profileManager.switchToProfile(profile.id)
                    }
                }

                _events.toast(R.string.filter_settings_imported)
                requestVpnRestart()
            } catch (e: Exception) {
                _events.toast(R.string.filter_import_failed, listOf("${e.message}"))
            }
        }
    }

    private fun requestVpnRestart() {
        ServiceController.requestRestart(getApplication<Application>().applicationContext)
    }
}
