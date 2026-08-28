package com.codegeasse1.hikariadblock.util

import android.content.Context
import android.net.ConnectivityManager

object DnsServers {

    fun get(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            for (network in cm.allNetworks) {
                val props = cm.getLinkProperties(network) ?: continue
                for (dns in props.dnsServers) {
                    val host = dns.hostAddress ?: continue
                    if (host.contains(":")) {
                        val lower = host.lowercase()
                        if (lower.startsWith("fe80") || lower.startsWith("ff")) continue
                    }
                    out.add(host)
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4"))
        }
        return out.toList()
    }
}
