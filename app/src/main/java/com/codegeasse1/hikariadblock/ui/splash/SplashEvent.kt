package com.codegeasse1.hikariadblock.ui.splash

sealed interface SplashEvent {
    data object Home : SplashEvent
    data object Onboarding : SplashEvent
}