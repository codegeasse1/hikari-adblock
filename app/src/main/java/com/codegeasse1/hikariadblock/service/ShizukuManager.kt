package com.codegeasse1.hikariadblock.service

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import timber.log.Timber

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
 */
object ShizukuManager {

    const val PERMISSION_REQUEST_CODE = 1010

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
     * Request Shizuku permission. [onResult] is invoked (on a binder thread)
     * once with true/false when the user (or the root backend) decides.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                try {
                    Shizuku.removeRequestPermissionResultListener(this)
                } catch (e: Exception) {
                    // ignore — listener cleanup is best-effort
                }
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Timber.e(e, "Shizuku requestPermission failed")
            try {
                Shizuku.removeRequestPermissionResultListener(listener)
            } catch (ignore: Exception) {
                // ignore
            }
            onResult(false)
        }
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

    /**
     * Run a single shell command through Shizuku. Returns true if the
     * process exited 0. Output is drained first so a chatty command can
     * never fill the pipe buffer and deadlock waitFor().
     */
    private fun exec(cmd: String): Boolean {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                Timber.e("Shizuku cmd FAILED (exit=$exit): $cmd\n$output$err")
            }
            exit == 0
        } catch (e: Exception) {
            Timber.e(e, "Shizuku exec failed: $cmd")
            false
        }
    }

    /**
     * Apply the DNS redirect rules via Shizuku. Mirrors
     * [IptablesManager.setupRules] semantics: each command runs
     * individually, IPv6 failures are tolerated, and success requires the
     * rules to be verifiably active.
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

        for (cmd in commands) {
            exec(cmd)
        }

        val verified = isActive()
        Timber.d("Shizuku iptables rules ${if (verified) "verified active" else "NOT active after setup"}")
        return verified
    }

    /** Remove all Hikari AdBlock iptables rules and restore Private DNS. */
    fun teardownRules() {
        for (cmd in IptablesManager.buildTeardownCommands()) {
            exec(cmd)
        }
        Timber.d("Shizuku iptables teardown done, Private DNS restored")
    }

    /** Check if our iptables rules are currently active (via Shizuku). */
    fun isActive(): Boolean {
        return try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", "iptables -t nat -L OUTPUT -n 2>/dev/null | grep ${IptablesManager.CHAIN}"),
                null,
                null
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains(IptablesManager.CHAIN)
        } catch (e: Exception) {
            Timber.e(e, "Shizuku isActive check failed")
            false
        }
    }
}
