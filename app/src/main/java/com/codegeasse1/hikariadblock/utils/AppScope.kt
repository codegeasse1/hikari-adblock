package com.codegeasse1.hikariadblock.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
 * Never cancel this scope.
 */
object AppScope {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
