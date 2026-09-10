package com.codegeasse1.hikariadblock.service

import android.content.Context

/**
 * Builds native nftables rules for the DNS redirect.
 *
 * This is the third (and last) Shizuku firewall backend. Where [IptablesManager]
 * emits iptables syntax for the `iptables` / `iptables-nft` binaries, these are
 * real `nft` commands that talk to the kernel directly over a netlink socket
 * (`NETLINK_NETFILTER`). The two paths use different kernel entry points and
 * therefore different SELinux permission checks, so on some ROMs the netlink
 * path is permitted to the `shell` identity even when the iptables ioctl path
 * is denied ("Permission denied (you must be root)").
 *
 * The rules mirror the iptables ones:
 *  - nat:   redirect outbound DNS (udp/tcp 53) to the local engine at
 *          127.0.0.1:15353, skipping this app's UID and whitelisted app UIDs.
 *  - filter: drop DoT (tcp 853) so DNS falls back to plain port 53.
 *
 * Everything lives in a single table (`ip hikari` + `ip6 hikari`), so teardown
 * is a clean table delete and there is no risk of leaking state.
 */
object NftablesManager {

    const val BIN = "nft"
    const val FAMILY_IP = "ip"
    const val FAMILY_IP6 = "ip6"
    const val TABLE = "hikari"
    const val CHAIN_NAT = "dns_nat"
    const val CHAIN_DOT = "dot_filter"

    private const val LOCAL_DNS_PORT = 15353

    /** Marker present in `nft list table ip hikari` output once the redirect rule is installed. */
    const val ACTIVE_MARKER = "15353"

    fun buildSetupCommands(
        context: Context,
        blockDoT: Boolean = true,
        whitelistUids: Collection<Int> = emptyList()
    ): List<String> {
        val uid = context.applicationInfo.uid
        return buildList {
            addAll(buildFamilyCommands(FAMILY_IP, uid, blockDoT, whitelistUids))
            addAll(buildFamilyCommands(FAMILY_IP6, uid, blockDoT, whitelistUids))
        }
    }

    private fun buildFamilyCommands(
        family: String,
        uid: Int,
        blockDoT: Boolean,
        whitelistUids: Collection<Int>
    ): List<String> = buildList {
        // nat table — OUTPUT hook, highest priority so the redirect happens
        // before routing. Rules are evaluated in insertion order, so the
        // skuid bypass rules must be added before the redirect rules.
        add("$BIN add table $family $TABLE")
        add("$BIN add chain $family $TABLE $CHAIN_NAT '{ type nat hook output priority -100 ; policy accept ; }'")
        add("$BIN add rule $family $TABLE $CHAIN_NAT meta skuid $uid return")
        for (wUid in whitelistUids) {
            add("$BIN add rule $family $TABLE $CHAIN_NAT meta skuid $wUid return")
        }
        add("$BIN add rule $family $TABLE $CHAIN_NAT udp dport 53 redirect to :$LOCAL_DNS_PORT")
        add("$BIN add rule $family $TABLE $CHAIN_NAT tcp dport 53 redirect to :$LOCAL_DNS_PORT")

        if (blockDoT) {
            add("$BIN add chain $family $TABLE $CHAIN_DOT '{ type filter hook output priority 0 ; policy accept ; }'")
            add("$BIN add rule $family $TABLE $CHAIN_DOT meta skuid $uid return")
            for (wUid in whitelistUids) {
                add("$BIN add rule $family $TABLE $CHAIN_DOT meta skuid $wUid return")
            }
            add("$BIN add rule $family $TABLE $CHAIN_DOT tcp dport 853 reject")
        }
    }

    /** Delete both tables (idempotent — errors suppressed since absence is fine). */
    fun buildTeardownCommands(): List<String> = listOf(
        "$BIN delete table $FAMILY_IP $TABLE 2>/dev/null",
        "$BIN delete table $FAMILY_IP6 $TABLE 2>/dev/null",
    )

    fun activeCheckCommand(): String = "$BIN list table $FAMILY_IP $TABLE 2>/dev/null"
}
