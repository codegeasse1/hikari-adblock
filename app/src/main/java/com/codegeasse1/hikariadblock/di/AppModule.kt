package com.codegeasse1.hikariadblock.di

import com.codegeasse1.hikariadblock.BuildConfig
import com.codegeasse1.hikariadblock.data.AppDatabase
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.data.entities.ProfileManager
import com.codegeasse1.hikariadblock.data.remote.FilterDownloadManager
import com.codegeasse1.hikariadblock.data.remote.api.CustomFilterApi
import com.codegeasse1.hikariadblock.data.repository.CustomFilterManager
import com.codegeasse1.hikariadblock.data.repository.FilterListRepository
import com.codegeasse1.hikariadblock.ui.dnsprovider.DnsProviderViewModel
import com.codegeasse1.hikariadblock.ui.filter.detail.FilterDetailViewModel
import com.codegeasse1.hikariadblock.ui.filter.FilterSetupViewModel
import com.codegeasse1.hikariadblock.ui.home.HomeViewModel
import com.codegeasse1.hikariadblock.ui.logs.LogViewModel
import com.codegeasse1.hikariadblock.ui.onboarding.OnboardingViewModel
import com.codegeasse1.hikariadblock.ui.profile.ProfileViewModel
import com.codegeasse1.hikariadblock.ui.appearance.AppearanceViewModel
import com.codegeasse1.hikariadblock.ui.settings.SettingsViewModel
import com.codegeasse1.hikariadblock.ui.statistics.StatisticsViewModel
import com.codegeasse1.hikariadblock.ui.whitelist.AppWhitelistViewModel
import com.codegeasse1.hikariadblock.ui.appmanagement.AppManagementViewModel
import com.codegeasse1.hikariadblock.ui.customrules.CustomRulesViewModel
import com.codegeasse1.hikariadblock.ui.domainrules.DomainRulesViewModel
import com.codegeasse1.hikariadblock.ui.firewall.FirewallViewModel
import com.codegeasse1.hikariadblock.ui.splash.SplashViewModel
import com.codegeasse1.hikariadblock.ui.wireguard.WireGuardEditViewModel
import com.codegeasse1.hikariadblock.ui.wireguard.WireGuardImportViewModel
import com.codegeasse1.hikariadblock.ui.httpsfiltering.HttpsFilteringViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import timber.log.Timber

val appModule = module {

    // HTTP Client
    single {
        HttpClient(CIO) {
            engine {
                requestTimeout = 60_000
                endpoint {
                    connectTimeout = 30_000
                }
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Timber.d(message)
                    }
                }
                val logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
                level = logLevel
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
            }
        }
    }

    // DNS Clients (Removed - now handled by Go tunnel)

    // Database
    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().dnsLogDao() }
    single { get<AppDatabase>().filterListDao() }
    single { get<AppDatabase>().whitelistDomainDao() }
    single { get<AppDatabase>().dnsErrorDao() }
    single { get<AppDatabase>().customDnsRuleDao() }
    single { get<AppDatabase>().protectionProfileDao() }
    single { get<AppDatabase>().firewallRuleDao() }

    // Preferences
    single { AppPreferences(androidContext()) }

    // Repository
    single { FilterDownloadManager(androidContext(), get()) }
    single {
        FilterListRepository(
            context = androidContext(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            client = get(),
            downloadManager = get()
        )
    }
    single { CustomFilterApi(get()) }
    single {
        CustomFilterManager(
            context = androidContext(),
            client = get(),
            filterListDao = get(),
            customFilterApi = get()
        )
    }

    // Profile Manager
    single {
        ProfileManager(
            profileDao = get(),
            filterListDao = get(),
            appPrefs = get(),
            filterRepo = get()
        )
    }

    // ViewModels
    viewModel {
        HomeViewModel(
            appPrefs = get(),
            dnsLogDao = get(),
            filterRepo = get(),
            profileDao = get(),
            filterListDao = get()
        )
    }
    viewModel { StatisticsViewModel(dnsLogDao = get()) }
    viewModel {
        LogViewModel(
            dnsLogDao = get(),
            filterListDao = get(),
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            filterListRepository = get(),
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        SettingsViewModel(
            appPrefs = get(),
            filterRepo = get(),
            dnsLogDao = get(),
            whitelistDomainDao = get(),
            filterListDao = get(),
            customDnsRuleDao = get(),
            profileDao = get(),
            profileManager = get(),
            firewallRuleDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        FilterSetupViewModel(
            filterRepo = get(),
            filterListDao = get(),
            customFilterManager = get(),
            application = androidApplication()
        )
    }
    viewModel { (filterId: Long) ->
        FilterDetailViewModel(
            filterId = filterId,
            filterListDao = get(),
            dnsLogDao = get(),
            filterRepo = get(),
            application = androidApplication(),
            customFilterManager = get()
        )
    }
    viewModel {
        AppWhitelistViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        com.codegeasse1.hikariadblock.ui.trustednetworks.TrustedNetworksViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        CustomRulesViewModel(
            customDnsRuleDao = get(),
            filterListRepository = get(),
            application = androidApplication()
        )
    }
    viewModel {
        DnsProviderViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        AppManagementViewModel(
            appPrefs = get(),
            dnsLogDao = get(),
            application = androidApplication(),
        )
    }
    viewModel {
        OnboardingViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        ProfileViewModel(
            profileManager = get(),
            profileDao = get(),
            filterListDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        FirewallViewModel(
            appPrefs = get(),
            firewallRuleDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        AppearanceViewModel(
            appPrefs = get(),
            application = androidApplication()
        )
    }
    viewModel {
        SplashViewModel(
            appPrefs = get(),
        )
    }
    viewModel {
        DomainRulesViewModel(
            whitelistDomainDao = get(),
            customDnsRuleDao = get(),
            application = androidApplication()
        )
    }
    viewModel {
        WireGuardImportViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        WireGuardEditViewModel(
            application = androidApplication()
        )
    }
    viewModel {
        HttpsFilteringViewModel(
            application = androidApplication()
        )
    }
}

