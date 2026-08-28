package com.codegeasse1.hikariadblock.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.ui.about.AboutScreen
import com.codegeasse1.hikariadblock.ui.appearance.AppearanceScreen
import com.codegeasse1.hikariadblock.ui.appmanagement.AppManagementScreen
import com.codegeasse1.hikariadblock.ui.customrules.CustomRulesScreen
import com.codegeasse1.hikariadblock.ui.data.AboutKey
import com.codegeasse1.hikariadblock.ui.data.AppManagementKey
import com.codegeasse1.hikariadblock.ui.data.AppearanceKey
import com.codegeasse1.hikariadblock.ui.data.BottomBarScreen
import com.codegeasse1.hikariadblock.ui.data.CustomRuleKey
import com.codegeasse1.hikariadblock.ui.data.DnsProviderKey
import com.codegeasse1.hikariadblock.ui.data.DomainRulesKey
import com.codegeasse1.hikariadblock.ui.data.FilterDetailKey
import com.codegeasse1.hikariadblock.ui.data.FilterKey
import com.codegeasse1.hikariadblock.ui.data.FireWallKey
import com.codegeasse1.hikariadblock.ui.data.HomeKey
import com.codegeasse1.hikariadblock.ui.data.HttpsFilteringKey
import com.codegeasse1.hikariadblock.ui.data.LogsKey
import com.codegeasse1.hikariadblock.ui.data.ProfileKey
import com.codegeasse1.hikariadblock.ui.data.SettingsKey
import com.codegeasse1.hikariadblock.ui.data.StatisticsKey
import com.codegeasse1.hikariadblock.ui.data.WhiteListAppKey
import com.codegeasse1.hikariadblock.ui.data.TrustedNetworksKey
import com.codegeasse1.hikariadblock.ui.data.WireGuardEditKey
import com.codegeasse1.hikariadblock.ui.data.WireGuardImportKey
import com.codegeasse1.hikariadblock.ui.dnsprovider.DnsProviderScreen
import com.codegeasse1.hikariadblock.ui.domainrules.DomainRulesScreen
import com.codegeasse1.hikariadblock.ui.filter.FilterSetupScreen
import com.codegeasse1.hikariadblock.ui.filter.detail.FilterDetailScreen
import com.codegeasse1.hikariadblock.ui.firewall.FirewallScreen
import com.codegeasse1.hikariadblock.ui.home.HomeScreen
import com.codegeasse1.hikariadblock.ui.httpsfiltering.HttpsFilteringScreen
import com.codegeasse1.hikariadblock.ui.logs.LogsScreen
import com.codegeasse1.hikariadblock.ui.profile.ProfileScreen
import com.codegeasse1.hikariadblock.ui.settings.SettingsScreen
import com.codegeasse1.hikariadblock.ui.statistics.StatisticsScreen
import com.codegeasse1.hikariadblock.ui.whitelist.AppWhitelistScreen
import com.codegeasse1.hikariadblock.ui.wireguard.WireGuardEditScreen
import com.codegeasse1.hikariadblock.ui.wireguard.WireGuardImportScreen
import org.koin.compose.koinInject

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeApp(
    onRequestVpnPermission: () -> Unit = {},
    onShowVpnConflictDialog: () -> Unit = {}
) {
    val appPrefs: AppPreferences = koinInject()
    val showBottomNavLabels by appPrefs.showBottomNavLabels.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val homeStack = rememberNavBackStack(HomeKey)
    val filterStack = rememberNavBackStack(FilterKey)
    val firewallStack = rememberNavBackStack(FireWallKey)
    val domainRuleStack = rememberNavBackStack(DomainRulesKey)
    val settingsStack = rememberNavBackStack(SettingsKey)
    var currentTab by rememberSaveable { mutableStateOf(BottomBarScreen.Home) }

    val currentBackStack = when (currentTab) {
        BottomBarScreen.Home -> homeStack
        BottomBarScreen.FilterSetup -> filterStack
        BottomBarScreen.Firewall -> firewallStack
        BottomBarScreen.DomainRule -> domainRuleStack
        BottomBarScreen.Settings -> settingsStack
    }

    val bottomBarScreens = listOf(
        BottomBarScreen.Home,
        BottomBarScreen.FilterSetup,
        BottomBarScreen.Firewall,
        BottomBarScreen.DomainRule,
        BottomBarScreen.Settings
    )
    var showBottomBar by rememberSaveable { mutableStateOf(true) }
    Scaffold(
        bottomBar = {
            if (!showBottomBar) return@Scaffold
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                bottomBarScreens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentBackStack == when (screen) {
                            BottomBarScreen.Home -> homeStack
                            BottomBarScreen.FilterSetup -> filterStack
                            BottomBarScreen.Firewall -> firewallStack
                            BottomBarScreen.DomainRule -> domainRuleStack
                            BottomBarScreen.Settings -> settingsStack
                        },
                        onClick = {
                            currentTab = screen
                        },
                        icon = {
                            Icon(
                                painter = painterResource(screen.icon),
                                contentDescription = stringResource(screen.labelRes),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = if (showBottomNavLabels) {
                            {
                                Text(
                                    text = stringResource(screen.labelRes),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 12.sp
                                    )
                                )
                            }
                        } else null,
                        alwaysShowLabel = showBottomNavLabels,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) {
        // When on a non-Home tab root, back should switch to Home tab instead of exiting
        BackHandler(enabled = currentTab != BottomBarScreen.Home && currentBackStack.size <= 1) {
            currentTab = BottomBarScreen.Home
            showBottomBar = true
        }

        NavDisplay(
            backStack = currentBackStack,
            onBack = {
                if (currentBackStack.size > 1) currentBackStack.removeLastOrNull()
                showBottomBar = currentBackStack.size <= 1
            },
            entryProvider = entryProvider {
                entry<HomeKey> {
                    HomeScreen(
                        onShowVpnConflictDialog = onShowVpnConflictDialog,
                        onRequestVpnPermission = onRequestVpnPermission,
                        onNavigateToLogScreen = {
                            showBottomBar = false
                            homeStack.add(LogsKey)
                        },
                        onNavigateToStatisticsScreen = {
                            showBottomBar = false
                            homeStack.add(StatisticsKey)
                        },
                        onNavigateToProfileScreen = {
                            showBottomBar = false
                            homeStack.add(ProfileKey)
                        }
                    )
                }
                entry<FilterKey> {
                    FilterSetupScreen(
                        onNavigateToFilterDetail = { filterId ->
                            showBottomBar = false
                            filterStack.add(FilterDetailKey(filterId))
                        },
                        onNavigateToCustomRules = {
                            showBottomBar = false
                            filterStack.add(CustomRuleKey)
                        }
                    )
                }
                entry<FireWallKey> {
                    FirewallScreen()
                }
                entry<DomainRulesKey> {
                    DomainRulesScreen()
                }
                entry<SettingsKey> {
                    SettingsScreen(
                        onNavigateToAbout = {
                            showBottomBar = false
                            settingsStack.add(AboutKey)
                        },
                        onNavigateToAppearance = {
                            showBottomBar = false
                            settingsStack.add(AppearanceKey)
                        },
                        onNavigateToAppManagement = {
                            showBottomBar = false
                            settingsStack.add(AppManagementKey)
                        },
                        onNavigateToFilterSetup = {
                            currentTab = BottomBarScreen.FilterSetup
                        },
                        onNavigateToWhitelistApps = {
                            showBottomBar = false
                            settingsStack.add(WhiteListAppKey)
                        },
                        onNavigateToTrustedNetworks = {
                            showBottomBar = false
                            settingsStack.add(TrustedNetworksKey)
                        },
                        onNavigateToWireGuardImport = {
                            showBottomBar = false
                            settingsStack.add(WireGuardImportKey)
                        },
                        onNavigateToHttpsFiltering = {
                            showBottomBar = false
                            settingsStack.add(HttpsFilteringKey)
                        },
                        onNavigateToDNSProvider = {
                            showBottomBar = false
                            settingsStack.add(DnsProviderKey)
                        }
                    )
                }
                entry<StatisticsKey> {
                    StatisticsScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<LogsKey> {
                    LogsScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<ProfileKey> {
                    ProfileScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            homeStack.removeLastOrNull()
                        }
                    )
                }
                entry<FilterDetailKey> {
                    FilterDetailScreen(
                        filterId = it.filterId,
                        onNavigateBack = {
                            showBottomBar = true
                            filterStack.removeLastOrNull()
                        }
                    )
                }
                entry<CustomRuleKey> {
                    CustomRulesScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            filterStack.removeLastOrNull()
                        }
                    )
                }
                entry<AboutKey> {
                    AboutScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<AppearanceKey> {
                    AppearanceScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<AppManagementKey> {
                    AppManagementScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<DnsProviderKey> {
                    DnsProviderScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<WhiteListAppKey> {
                    AppWhitelistScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<TrustedNetworksKey> {
                    com.codegeasse1.hikariadblock.ui.trustednetworks.TrustedNetworksScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
                entry<WireGuardImportKey> {
                    WireGuardImportScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        },
                        onEditProfile = { profileId ->
                            settingsStack.add(WireGuardEditKey(profileId))
                        },
                    )
                }
                entry<WireGuardEditKey> { key ->
                    WireGuardEditScreen(
                        profileId = key.profileId,
                        onNavigateBack = {
                            settingsStack.removeLastOrNull()
                        },
                    )
                }
                entry<HttpsFilteringKey> {
                    HttpsFilteringScreen(
                        onNavigateBack = {
                            showBottomBar = true
                            settingsStack.removeLastOrNull()
                        }
                    )
                }
            }
        )
    }
}