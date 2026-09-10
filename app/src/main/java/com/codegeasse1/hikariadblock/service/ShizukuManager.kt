package com.codegeasse1.hikariadblock.service

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the DNS-redirect iptables rules through Shizuku (shell/ADB identity)
 * instead of root. Used by Shizuku Proxy Mode — the same rules as
 * [IptablesManager] (built by its shared command builders) but executed via
 * Shizuku's `newProcess`, which grants shell-UID privileges without root.
 *
 * No VpnService is involved, so the system never shows the VPN status-bar
 * icon. Requirements:
 * - The Shizuku (or Sui) app must be installed and running.
 * - This app's Shizuku permission must be granted (auto with root backend,
 *   or a one-time grant dialog / Shizuku-app grant with the ADB backend).
 *
 * Caveat: shell can run iptables on stock Android (same as `adb shell
 * iptables`), but a handful of OEMs restrict it — setupRules() then reports
 * failure cleanly and the service shows a start-failed notification.
 *
 * Robustness notes (added after user reports of "mode won't enable" and
 * "stuck on Connecting"):
 * - Every Shizuku command runs under a hard timeout. `iptables` can block
 *   indefinitely on the xtables lock, and an unbounded wait used to leave
 *   the proxy permanently in the STARTING state.
 * - Permission is confirmed by polling [checkSelfPermission] in addition to
 *   the result callback, because some Shizuku forks (notably Shevery) do
 *   not reliably deliver OnRequestPermissionResultListener.
 */
object ShizukuManager {

    const val PERMISSION_REQUEST_CODE = 1010

    /** Per-command hard timeout. iptables/settings commands are quick; a
     *  shell that never returns must not be able to hang the caller. */
    private const val CMD_TIMEOUT_MS = 8_000L

    /** Overall wall-clock budget for applying the full rule set. */
    private const val SETUP_TIMEOUT_MS = 45_000L

    /** How often the permission polling fallback re-checks the grant. */
    private const val PERMISSION_POLL_INTERVAL_MS = 500L

    /** How long to wait for the user/backend to grant permission before
     *  giving up. The Shizuku-app dialog can sit on screen for a while,
     *  and the user may switch apps to grant. */
    const val PERMISSION_TIMEOUT_MS = 60_000L

    private val drainExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "shizuku-drain").apply { isDaemon = true }
    }

    private val initialized = AtomicBoolean(false)

    /**
     * True while the user has asked to enable Shizuku mode but we have not yet
     * managed to start [ShizukuProxyService] (e.g. the foreground-service start
     * was blocked because the grant dialog backgrounded us). The UI checks this
     * on resume so the start can be retried from a foreground context.
     */
    @Volatile
    var pendingEnable: Boolean = false

    /**
     * Register a permanent permission-result listener. Call once from
     * [com.codegeasse1.hikariadblock.HikariApp.onCreate].
     *
     * Some Shizuku forks deliver the grant out-of-band or with an
     * unexpected request code, so this listener only logs; the enable flow
     * independently polls [checkSelfPermission] and never depends on the
     * callback firing.
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Timber.d("Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
            }
        } catch (e: Exception) {
            Timber.w(e, "Shizuku addRequestPermissionResultListener failed")
        }
    }

    /** True when the Shizuku server binder is alive (Shizuku is running). */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        Timber.w(e, "Shizuku pingBinder failed")
        false
    }

    /** True when the installed Shizuku server is too old (pre-v11). */
    fun isPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (e: Exception) {
        true
    }

    /** True when this app has been granted Shizuku permission. */
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        Timber.w(e, "Shizuku checkSelfPermission failed")
        false
    }

    /**
     * Request Shizuku permission and block the calling (background) thread
     * until the grant is observed, the user denies, or [timeoutMs] elapses.
     *
     * Unlike a callback-only implementation this ALSO polls
     * [checkSelfPermission] — which re-queries the server whenever the
     * cached value is false — so it still succeeds on Shizuku forks that
     * fail to deliver the result callback (e.g. Shevery).
     *
     * MUST be called from a background thread.
     */
    fun requestPermissionAndWait(timeoutMs: Long = PERMISSION_TIMEOUT_MS): Boolean {
        if (hasPermission()) return true

        // 0 = pending, 1 = granted, -1 = denied
        val result = AtomicInteger(0)
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    result.compareAndSet(0, 1)
                } else {
                    result.compareAndSet(0, -1)
                }
            }
        }

        try {
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                // Some forks throw here yet still show the dialog — keep polling.
                Timber.e(e, "Shizuku requestPermission failed — falling back to polling")
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (hasPermission()) {
                    Timber.d("Shizuku permission granted (observed)")
                    return true
                }
                if (result.get() == -1) {
                    Timber.w("Shizuku permission denied by user")
                    return false
                }
                try {
                    Thread.sleep(PERMISSION_POLL_INTERVAL_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return hasPermission()
                }
            }
            Timber.w("Shizuku permission not granted within ${timeoutMs}ms")
            return hasPermission()
        } finally {
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (ignore: Exception) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Async wrapper around [requestPermissionAndWait]. [onResult] is invoked
     * on a background thread once the outcome is known. Kept for callers
     * that cannot block.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        Thread { onResult(requestPermissionAndWait()) }.apply {
            name = "shizuku-permission"
            isDaemon = true
        }.start()
    }

    /**
     * Block (background thread) until the permission is observed as granted,
     * the binder dies, or [timeoutMs] elapses.
     *
     * Used by [ShizukuProxyService] as a safety net: if it is (re)started
     * while no grant is visible yet — e.g. a just-granted permission that has
     * not propagated, or a boot/widget start — it waits here instead of burning
     * its short retry budget and giving up. MUST be called from a background
     * thread.
     */
    fun waitForPermissionGrant(timeoutMs: Long = PERMISSION_TIMEOUT_MS): Boolean {
        if (hasPermission()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasPermission()) return true
            if (!isBinderAlive()) return false
            try {
                Thread.sleep(PERMISSION_POLL_INTERVAL_MS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return hasPermission()
            }
        }
        return hasPermission()
    }

    /**
     * Block (background thread) until the Shizuku binder is available or the
     * timeout elapses. Polls [isBinderAlive] — mirrors the classic
     * `Shizuku.waitForBinder()` helper (which newer API versions no longer
     * expose). MUST be called from a background thread (never the main
     * thread). Returns true once the binder is alive.
     */
    fun waitForBinder(timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isBinderAlive()) return true
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return isBinderAlive()
            }
        }
        return isBinderAlive()
    }

    /** Outcome of a Shizuku iptables setup attempt. */
    enum class SetupResult {
        /** Rules were applied and verified — the proxy can run. */
        SUCCESS,

        /**
         * Every available firewall backend was refused by the device/ROM
         * (shell netfilter access denied). Retrying cannot help, so the caller
         * should surface an actionable error instead of looping.
         */
        BLOCKED,

        /** A (likely transient) failure — worth retrying. */
        FAILED,
    }

    /**
     * A set of commands that can actually manipulate the kernel's netfilter
     * tables. Tried in order:
     *  - `iptables`/`ip6tables` — the legacy path; works for root and on most
     *    stock/AOSP ROMs.
     *  - `iptables-nft`/`ip6tables-nft` — the nftables-backed variant, which
     *    works on kernels that dropped legacy netfilter support. This is the
     *    second option that rescues many devices where the legacy binary
     *    reports "Permission denied (you must be root)".
     */
    private data class FirewallBackend(val bin: String, val bin6: String, val label: String)

    private val FIREWALL_BACKENDS = listOf(
        FirewallBackend(IptablesManager.BIN_IPV4, IptablesManager.BIN_IPV6, "iptables-legacy"),
        FirewallBackend(IptablesManager.BIN_NFT_IPV4, IptablesManager.BIN_NFT_IPV6, "iptables-nft"),
    )

    @Volatile
    private var activeBackend: FirewallBackend? = null

    /** Human-readable label of the backend that last applied the rules. */
    val lastUsedBackend: String? get() = activeBackend?.label

    /**
     * stderr fingerprints that mean "this identity may not touch netfilter" —
     * as opposed to a rule syntax problem, which retrying could fix.
     */
    private val BLOCKED_PATTERNS = listOf(
        "Permission denied",
        "you must be root",
        "can't initialize iptables table",
        "can't initialize ip6tables table",
        "Operation not permitted",
        "not permitted",
    )

    private fun looksBlocked(output: String): Boolean =
        BLOCKED_PATTERNS.any { output.contains(it, ignoreCase = true) }

    private enum class BackendOutcome { SUCCESS, BLOCKED, FAILED, UNAVAILABLE }

    private data class ExecResult(
        val ok: Boolean,
        val timedOut: Boolean,
        val output: String,
    )

    /**
     * Run a single shell command through Shizuku with a hard timeout.
     *
     * Both output pipes are drained on separate threads (a command that
     * fills the stderr pipe buffer while we read stdout would otherwise
     * deadlock), and the process is killed if it exceeds [CMD_TIMEOUT_MS]
     * (e.g. `iptables` waiting on the xtables lock).
     */
    private fun exec(cmd: String, logErrors: Boolean = true): ExecResult {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val outFuture = drain(process.inputStream)
            val errFuture = drain(process.errorStream)

            try {
                process.waitForTimeout(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Timber.w(e, "waitForTimeout failed for: $cmd")
            }

            val alive = try {
                process.alive()
            } catch (e: Exception) {
                false
            }
            if (alive) {
                Timber.w("Shizuku cmd timed out after ${CMD_TIMEOUT_MS}ms — killing: $cmd")
                try {
                    process.destroy()
                } catch (e: Exception) {
                    // ignore — best-effort kill
                }
                return ExecResult(false, true, "")
            }

            val exit = try {
                process.exitValue()
            } catch (e: Exception) {
                -1
            }
            val output = awaitOutput(outFuture)
            val err = awaitOutput(errFuture)
            if (exit != 0 && logErrors) {
                Timber.e("Shizuku cmd FAILED (exit=$exit): $cmd\n$output$err")
            }
            ExecResult(exit == 0, false, output + err)
        } catch (e: Exception) {
            Timber.e(e, "Shizuku exec failed: $cmd")
            ExecResult(false, false, "")
        }
    }

    private fun drain(stream: InputStream): Future<String> =
        drainExecutor.submit(Callable {
            try {
                stream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
        })

    private fun awaitOutput(future: Future<String>): String = try {
        future.get(1, TimeUnit.SECONDS)
    } catch (e: Exception) {
        ""
    }

    /** True if `bin` exists on the device (checked through Shizuku's shell). */
    private fun binaryExists(bin: String): Boolean =
        exec("command -v $bin >/dev/null 2>&1 || $bin --version >/dev/null 2>&1").ok

    /** Check if our rules are active using a specific backend. */
    private fun isActive(backend: FirewallBackend): Boolean {
        val result = exec(
            "${backend.bin} -t nat -L OUTPUT -n 2>/dev/null | grep ${IptablesManager.CHAIN}",
            logErrors = false
        )
        return result.output.contains(IptablesManager.CHAIN)
    }

    /**
     * Try to apply and verify the full rule set with one backend.
     * The overall budget is per-backend so a wedged shell aborts cleanly and
     * the next backend still gets a chance.
     */
    private fun tryBackend(
        backend: FirewallBackend,
        context: Context,
        blockDoT: Boolean,
        whitelistUids: Collection<Int>
    ): BackendOutcome {
        if (!binaryExists(backend.bin)) {
            Timber.d("Shizuku firewall backend ${backend.label} not present on device")
            return BackendOutcome.UNAVAILABLE
        }

        val commands = IptablesManager.buildSetupCommandsIpv4(
            context, blockDoT, whitelistUids, bin = backend.bin
        ) + IptablesManager.buildSetupCommandsIpv6(
            context, blockDoT, whitelistUids, bin6 = backend.bin6
        )

        var blocked = false
        val deadline = System.currentTimeMillis() + SETUP_TIMEOUT_MS
        for (cmd in commands) {
            if (System.currentTimeMillis() > deadline) {
                Timber.e("Shizuku ${backend.label} setup exceeded ${SETUP_TIMEOUT_MS}ms — aborting backend")
                return BackendOutcome.FAILED
            }
            val result = exec(cmd)
            if (result.timedOut) {
                // A hung command means the shell/subsystem is wedged — don't
                // queue more commands behind it.
                return BackendOutcome.FAILED
            }
            if (!result.ok && looksBlocked(result.output)) blocked = true
        }

        return when {
            isActive(backend) -> {
                Timber.d("Shizuku iptables rules verified active via ${backend.label}")
                BackendOutcome.SUCCESS
            }
            blocked -> {
                Timber.w("Shizuku backend ${backend.label} refused by device (shell netfilter access denied)")
                BackendOutcome.BLOCKED
            }
            else -> {
                Timber.w("Shizuku iptables rules NOT active after ${backend.label} setup")
                BackendOutcome.FAILED
            }
        }
    }

    /**
     * Apply the DNS redirect rules via Shizuku, trying each available firewall
     * backend until one verifiably works (legacy `iptables`, then `iptables-nft`).
     *
     * Mirrors [IptablesManager.setupRules] semantics: each command runs
     * individually, IPv6 failures are tolerated, and success requires the
     * rules to be verifiably active.
     *
     * Returns [SetupResult.BLOCKED] when every backend was refused by the
     * ROM (e.g. "Permission denied (you must be root)") — retrying cannot
     * help there, so the caller can show an immediate, actionable error
     * instead of staying on "Connecting…".
     */
    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): SetupResult {
        Timber.d("Setting up Shizuku iptables rules (blockDoT=$blockDoT, whitelistUids=$whitelistUids)")

        // Always teardown first (idempotent, and clears rules left by either backend)
        teardownRules()

        var sawBlocked = false
        for (backend in FIREWALL_BACKENDS) {
            when (tryBackend(backend, context, blockDoT, whitelistUids)) {
                BackendOutcome.SUCCESS -> {
                    activeBackend = backend
                    Timber.d("Shizuku rules active via ${backend.label}")
                    return SetupResult.SUCCESS
                }
                BackendOutcome.BLOCKED -> sawBlocked = true
                BackendOutcome.UNAVAILABLE -> Unit // try the next backend
                BackendOutcome.FAILED -> Unit // try the next backend
            }
            // Clean up any partial state before trying the next backend
            teardownBackend(backend)
        }

        activeBackend = null
        return if (sawBlocked) {
            Timber.e("All Shizuku firewall backends refused by device — shell iptables is blocked here")
            SetupResult.BLOCKED
        } else {
            SetupResult.FAILED
        }
    }

    /**
     * Remove all Hikari AdBlock rules and restore Private DNS. Tears down on
     * every backend, so it also cleans rules left by a previous process run
     * that used a different backend. Safe/idempotent.
     */
    fun teardownRules() {
        for (backend in FIREWALL_BACKENDS) teardownBackend(backend)
        Timber.d("Shizuku iptables teardown done, Private DNS restored")
    }

    private fun teardownBackend(backend: FirewallBackend) {
        for (cmd in IptablesManager.buildTeardownCommands(backend.bin, backend.bin6)) {
            if (exec(cmd, logErrors = false).timedOut) break
        }
    }

    /** Check if our iptables rules are currently active (via Shizuku). */
    fun isActive(): Boolean {
        val backend = activeBackend
        if (backend != null) return isActive(backend)
        return FIREWALL_BACKENDS.any { isActive(it) }
    }
}
