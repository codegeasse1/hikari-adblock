package com.codegeasse1.hikariadblock.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.codegeasse1.hikariadblock.ui.data.HomeAppKey
import com.codegeasse1.hikariadblock.ui.data.OnboardingKey
import com.codegeasse1.hikariadblock.ui.data.SplashKey
import com.codegeasse1.hikariadblock.ui.dialog.VPNConflictDialog
import com.codegeasse1.hikariadblock.ui.onboarding.OnboardingScreen
import com.codegeasse1.hikariadblock.ui.splash.SplashScreen


@Composable
fun HikariAdBlockApp(
    modifier: Modifier = Modifier,
    onRequestVpnPermission: () -> Unit,
    showVpnConflictDialog: Boolean = false,
    onDismissVpnConflictDialog: () -> Unit = {},
    onShowVpnConflictDialog: () -> Unit = {},
) {

    if (showVpnConflictDialog) {
        VPNConflictDialog(
            onDismissVpnConflictDialog = onDismissVpnConflictDialog,
        )
    }

    val backStack = rememberNavBackStack(SplashKey)
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        modifier = modifier,
        entryProvider = entryProvider {
            entry<SplashKey> {
                SplashScreen(
                    onNavigateToHome = {
                        backStack.removeLastOrNull()
                        backStack.add(HomeAppKey)
                    },
                    onNavigateToOnboarding = {
                        backStack.removeLastOrNull()
                        backStack.add(OnboardingKey)
                    }
                )
            }
            entry<OnboardingKey> {
                OnboardingScreen(
                    onNavigateToHome = {
                        backStack.removeLastOrNull()
                        backStack.add(HomeAppKey)
                    }
                )
            }
            entry<HomeAppKey> {
                HomeApp(
                    onRequestVpnPermission = onRequestVpnPermission,
                    onShowVpnConflictDialog = onShowVpnConflictDialog
                )
            }

        }
    )
}
