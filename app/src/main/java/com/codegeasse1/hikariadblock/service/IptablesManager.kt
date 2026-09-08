package com.codegeasse1.hikariadblock.service

import android.content.Context
import com.topjohnwu.superuser.Shell
import timber.log.Timber

/**
 * Manages iptables rules for Root/Proxy mode.
 * Redirects all outbound DNS traffic (port 53 UDP/TCP) to the local
 * Go engine at 127.0.0.1:15353, excluding this app's own UID to prevent
 * infinite redirect loops.
 *
 * Uses a custom chain (BLOCKADS_DNS) for clean setup/teardown.
 *
 * Architecture:
 * - nat table:    REDIRECT port 53 → 15353 (DNS interception)
 * - filter table: DROP port 853 (block DoT to force plain DNS)
 * - settings:     Disable Android Private DNS (forces port 53 fallback)
 */
object IptablesManager {

    const val CHAIN = "BLOCKADS_DNS"
    private const val CHAIN_FILTER = "BLOCKADS_DOT"
    private const val LOCAL_DNS_PORT = 15353

    /**
     * Ensure the cached libsu main shell actually has root.
     *
     * libsu creates the main shell once and caches it for the process
     * lifetime. If the first shell was created while the su daemon wasn't
     * ready yet (typical right after boot), a non-root `sh` gets cached and
     * every subsequent command silently runs without root — retries can
     * never succeed. Closing the poisoned shell forces libsu to build a
     * fresh one that attempts `su` again.
     */
    fun ensureRootShell(): Boolean {
        val cached = Shell.getCachedShell()
        if (cached != null && cached.isRoot) return true

        if (cached != null) {
            try {
                cached.close()
            } catch (e: Exception) {
                Timber.w(e, "Failed to close non-root shell")
            }
        }

        val fresh = Shell.getShell()
        Timber.d("Recreated libsu main shell, isRoot=${fresh.isRoot}")
        return fresh.isRoot
    }

    /**
     * Actively request root access. This will trigger the Magisk/KernelSU
     * permission prompt if it hasn't been granted yet.
     */
    fun isRootAvailable(): Boolean {
        // Explicitly trigger 'su' so Magisk/KernelSU shows the permission prompt
        val result = Shell.cmd("su -c id").exec()
        return result.isSuccess && result.out.any { it.contains("uid=0") }
    }

    /**
     * Build the IPv4 setup commands (shared by root mode and Shizuku mode):
     * disable Android Private DNS, then the nat REDIRECT chain and the
     * filter DoT-block chain. See [setupRules] for semantics.
     *
     * @param context App context (used to get UID)
     * @param blockDoT If true, blocks DoT (port 853) to force DNS fallback to port 53
     * @param whitelistUids UIDs of whitelisted apps whose DNS must bypass the redirect
     */
    fun buildSetupCommandsIpv4(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): List<String> {
        val uid = context.applicationInfo.uid
        val commands = mutableListOf<String>()

        // Step 0: Disable Android Private DNS so system uses port 53.
        // This is CRITICAL — without this, Android 9+ uses DoT (853)
        // and our port 53 redirect never sees traffic.
        commands += "settings put global private_dns_mode off"

        // Step 1: nat table — REDIRECT port 53 → local engine
        commands += "iptables -t nat -N $CHAIN 2>/dev/null || true"
        // Skip our own app's traffic (prevents infinite loop)
        commands += "iptables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN"
        // Skip whitelisted apps — their DNS goes straight upstream
        for (wUid in whitelistUids) {
            commands += "iptables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN"
        }
        // Redirect UDP DNS → local engine
        commands += "iptables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT"
        // Redirect TCP DNS → local engine
        commands += "iptables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT"
        // Hook into OUTPUT chain
        commands += "iptables -t nat -A OUTPUT -j $CHAIN"

        if (blockDoT) {
            // filter table — DROP port 853 (DoT)
            commands += "iptables -t filter -N $CHAIN_FILTER 2>/dev/null || true"
            commands += "iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN"
            for (wUid in whitelistUids) {
                commands += "iptables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN"
            }
            commands += "iptables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT"
            commands += "iptables -t filter -A OUTPUT -j $CHAIN_FILTER"
        }
        return commands
    }

    /**
     * Build the IPv6 setup commands (shared by root mode and Shizuku mode).
     * Attempted independently — many Android kernels lack ip6tables nat.
     */
    fun buildSetupCommandsIpv6(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): List<String> {
        val uid = context.applicationInfo.uid
        return buildList {
            add("ip6tables -t nat -N $CHAIN 2>/dev/null || true")
            // Skip our own app's traffic (prevents infinite loop)
            add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $uid -j RETURN")
            for (wUid in whitelistUids) {
                add("ip6tables -t nat -A $CHAIN -m owner --uid-owner $wUid -j RETURN")
            }
            add("ip6tables -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports $LOCAL_DNS_PORT")
            add("ip6tables -t nat -A OUTPUT -j $CHAIN")
            if (blockDoT) {
                add("ip6tables -t filter -N $CHAIN_FILTER 2>/dev/null || true")
                add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $uid -j RETURN")
                for (wUid in whitelistUids) {
                    add("ip6tables -t filter -A $CHAIN_FILTER -m owner --uid-owner $wUid -j RETURN")
                }
                add("ip6tables -t filter -A $CHAIN_FILTER -p tcp --dport 853 -j REJECT")
                add("ip6tables -t filter -A OUTPUT -j $CHAIN_FILTER")
            }
        }
    }

    /**
     * Build the teardown commands (shared by root mode and Shizuku mode):
     * remove all Hikari AdBlock iptables chains and restore Private DNS.
     */
    fun buildTeardownCommands(): List<String> = listOf(
        // IPv4 nat chain
        "iptables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
        "iptables -t nat -F $CHAIN 2>/dev/null",
        "iptables -t nat -X $CHAIN 2>/dev/null",
        // IPv4 filter chain (DoT blocking)
        "iptables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
        "iptables -t filter -F $CHAIN_FILTER 2>/dev/null",
        "iptables -t filter -X $CHAIN_FILTER 2>/dev/null",
        // IPv6 nat chain
        "ip6tables -t nat -D OUTPUT -j $CHAIN 2>/dev/null",
        "ip6tables -t nat -F $CHAIN 2>/dev/null",
        "ip6tables -t nat -X $CHAIN 2>/dev/null",
        // IPv6 filter chain (DoT blocking)
        "ip6tables -t filter -D OUTPUT -j $CHAIN_FILTER 2>/dev/null",
        "ip6tables -t filter -F $CHAIN_FILTER 2>/dev/null",
        "ip6tables -t filter -X $CHAIN_FILTER 2>/dev/null",
        // Restore Android Private DNS to automatic mode
        "settings put global private_dns_mode opportunistic",
    )

    /**
     * Apply iptables rules to redirect DNS traffic.
     *
     * @param context App context (used to get UID)
     * @param blockDoT If true, blocks DoT (port 853) to force DNS fallback to port 53
     * @param whitelistUids UIDs of whitelisted apps whose DNS must bypass the
     *        redirect entirely — the Root-mode equivalent of VPN mode's
     *        addDisallowedApplication (#150)
     * @return true if at least IPv4 rules succeeded
     */
    fun setupRules(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): Boolean {
        val uid = context.applicationInfo.uid
        Timber.d("Setting up iptables rules for UID=$uid, blockDoT=$blockDoT, whitelistUids=$whitelistUids")

        // Always teardown first (idempotent)
        teardownRules()

        // ══════════════════════════════════════════════════════════════
        // Step 0: Disable Android Private DNS + IPv4 nat/filter rules.
        // Each command runs individually so that a single failure
        // (e.g. chain already exists) doesn't abort the entire setup.
        // ══════════════════════════════════════════════════════════════
        val ipv4Commands = buildSetupCommandsIpv4(context, blockDoT, whitelistUids)
        var ipv4Success = true
        for (cmd in ipv4Commands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.e("IPv4 iptables cmd FAILED: [$cmd] err=${result.err} out=${result.out}")
                ipv4Success = false
                // Don't break — continue applying remaining rules so partial state is maximised
            }
        }

        if (ipv4Success) {
            Timber.d("IPv4 iptables setup SUCCESS")
        } else {
            Timber.e("IPv4 iptables setup had failures (see individual logs above)")
        }

        // ══════════════════════════════════════════════════════════════
        // IPv6 — try independently, many Android kernels lack ip6tables nat
        // ══════════════════════════════════════════════════════════════
        val ipv6Commands = buildSetupCommandsIpv6(context, blockDoT, whitelistUids)
        for (cmd in ipv6Commands) {
            val result = Shell.cmd(cmd).exec()
            if (!result.isSuccess) {
                Timber.w("IPv6 ip6tables cmd FAILED (ignoring): [$cmd] err=${result.err}")
            }
        }

        // Verify rules are actually in place
        val verified = isActive()
        if (verified) {
            Timber.d("iptables rules verified active")
        } else {
            Timber.e("iptables rules NOT active after setup — root may have been denied")
        }

        return ipv4Success && verified
    }

    /**
     * Remove all Hikari AdBlock iptables rules and restore Private DNS.
     * Safe to call multiple times. Uses 2>/dev/null to suppress errors.
     */
    fun teardownRules(): Boolean {
        val commands = buildTeardownCommands()
        Shell.cmd(*commands.toTypedArray()).exec()
        Timber.d("iptables teardown done, Private DNS restored")
        return true
    }

    /**
     * Check if our iptables rules are currently active.
     */
    fun isActive(): Boolean {
        val result = Shell.cmd(
            "iptables -t nat -L OUTPUT -n 2>/dev/null | grep $CHAIN"
        ).exec()
        return result.out.any { it.contains(CHAIN) }
    }
}
