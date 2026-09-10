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

    /**
     * Apply the DNS redirect rules via Shizuku. Mirrors
     * [IptablesManager.setupRules] semantics: each command runs
     * individually, IPv6 failures are tolerated, and success requires the
     * rules to be verifiably active.
     *
     * Aborts early if a command times out (the shell is wedged) or the
     * overall budget is exceeded, so the caller can never hang here.
     */
    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): Boolean {
        Timber.d("Setting up Shizuku iptables rules (blockDoT=$blockDoT, whitelistUids=$whitelistUids)")

        // Always teardown first (idempotent)
        teardownRules()

        val commands = IptablesManager.buildSetupCommandsIpv4(context, blockDoT, whitelistUids) +
            IptablesManager.buildSetupCommandsIpv6(context, blockDoT, whitelistUids)

        val deadline = System.currentTimeMillis() + SETUP_TIMEOUT_MS
        for (cmd in commands) {
            if (System.currentTimeMillis() > deadline) {
                Timber.e("Shizuku rule setup exceeded ${SETUP_TIMEOUT_MS}ms — aborting")
                return false
            }
            if (exec(cmd).timedOut) {
                // A hung command means the shell/subsystem is wedged — don't
                // queue more commands behind it.
                return false
            }
        }

        val verified = isActive()
        Timber.d("Shizuku iptables rules ${if (verified) "verified active" else "NOT active after setup"}")
        return verified
    }

    /** Remove all Hikari AdBlock iptables rules and restore Private DNS. */
    fun teardownRules() {
        for (cmd in IptablesManager.buildTeardownCommands()) {
            if (exec(cmd, logErrors = false).timedOut) break
        }
        Timber.d("Shizuku iptables teardown done, Private DNS restored")
    }

    /** Check if our iptables rules are currently active (via Shizuku). */
    fun isActive(): Boolean {
        val result = exec(
            "iptables -t nat -L OUTPUT -n 2>/dev/null | grep ${IptablesManager.CHAIN}",
            logErrors = false
        )
        return result.output.contains(IptablesManager.CHAIN)
    }
}
