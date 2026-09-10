package com.codegeasse1.hikariadblock.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Application-lifetime coroutine scope.
 *
 * Used for work that must survive an Activity/ViewModel being recreated.
 * This matters for the Shizuku permission flow: when the Shizuku (or a
 * Shizuku fork such as Shevery) grant dialog is shown, the user often
 * switches to that app, which backgrounds and can destroy our Activity.
 * Continuations launched on `viewModelScope` / `lifecycleScope` are
 * cancelled at that point, which is exactly why enabling Shizuku mode
 * sometimes appeared to do nothing even after the permission was allowed.
 *
 * A [CoroutineExceptionHandler] is installed so that a failure here (e.g. a
 * foreground-service start being refused because the app is in the
 * background) is logged instead of crashing the app.
 *
 * Never cancel this scope.
 */
object AppScope {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in AppScope")
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
}
