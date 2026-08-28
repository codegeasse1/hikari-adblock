package com.codegeasse1.hikariadblock.utils

import android.content.Context
import timber.log.Timber

object CrashReportingManager {

    fun toggleSentry(context: Context, isEnabled: Boolean) {
        Timber.i("Crash reporting is disabled in Hikari AdBlock (Sentry is not bundled). Requested state: $isEnabled")
    }
}
