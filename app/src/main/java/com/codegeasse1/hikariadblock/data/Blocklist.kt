package com.codegeasse1.hikariadblock.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object Blocklist {

    private val domains = HashSet<String>()
    private val whitelist = HashSet<String>()
    private val customBlocked = HashSet<String>()

    @Volatile
    var isLoaded = false
        private set

    private val _sizeFlow = MutableStateFlow(0)
    val sizeFlow: StateFlow<Int> = _sizeFlow.asStateFlow()

    private val domainRegex = Regex("^[a-z0-9]([a-z0-9_-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9_-]*[a-z0-9])?)+$")

    fun applyWhitelist(set: Set<String>) {
        val cleaned = set.map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.toHashSet()
        synchronized(domains) { whitelist.clear(); whitelist.addAll(cleaned) }
    }

    fun applyCustomBlocked(set: Set<String>) {
        val cleaned = set.map { it.trim().lowercase().trimEnd('.') }.filter { it.isNotEmpty() }.toHashSet()
        synchronized(domains) { customBlocked.clear(); customBlocked.addAll(cleaned) }
    }

    fun isAllowed(host: String): Boolean {
        var d = host.trim().lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        synchronized(domains) {
            while (d.isNotEmpty()) {
                if (whitelist.contains(d)) return true
                val idx = d.indexOf('.')
                if (idx < 0) break
                d = d.substring(idx + 1)
            }
        }
        return false
    }

    fun isBlocked(host: String): Boolean {
        var d = host.trim().lowercase().trimEnd('.')
        if (d.isEmpty() || !isLoaded) return false
        synchronized(domains) {
            while (d.isNotEmpty()) {
                if (whitelist.contains(d)) return false
                if (customBlocked.contains(d) || domains.contains(d)) return true
                val idx = d.indexOf('.')
                if (idx < 0) break
                d = d.substring(idx + 1)
            }
        }
        return false
    }

    fun loadIfNeeded(context: Context) {
        if (isLoaded) return
        load(context)
    }

    fun load(context: Context) {
        val file = File(context.filesDir, "hosts.txt")
        val input: InputStream? = if (file.exists() && file.length() > 0) file.inputStream() else context.assets.open("hosts.txt")
        if (input == null) return
        val loaded = HashSet<String>()
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val parts = l.split(Regex("\\s+"))
                val domain = parts.lastOrNull() ?: continue
                val d = domain.lowercase().trimEnd('.')
                if (d.isEmpty() || !domainRegex.matches(d)) continue
                loaded.add(d)
            }
        }
        synchronized(domains) {
            domains.clear()
            domains.addAll(loaded)
            _sizeFlow.value = domains.size
        }
        isLoaded = true
    }

    suspend fun updateFromUrl(context: Context, url: String, onStatus: (String) -> Unit = {}): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                onStatus("Downloading…")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Hikari-AdBlock/1.0")
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(text)
                if (parsed.size < 1000) {
                    return@withContext Result.failure(Exception("Blocklist too small (${parsed.size} entries)"))
                }
                onStatus("Applying ${parsed.size} entries…")
                synchronized(domains) {
                    domains.clear()
                    domains.addAll(parsed)
                    _sizeFlow.value = domains.size
                }
                runCatching { File(context.filesDir, "hosts.txt").writeText(text) }
                isLoaded = true
                Result.success(parsed.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun parse(text: String): List<String> {
        val out = HashSet<String>()
        val lineParts = Regex("\\s+")
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val m = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::)\\s+([^\\s#]+)").find(l)
            val domain = if (m != null) m.groupValues[1] else lineParts.split(l).firstOrNull() ?: continue
            val d = domain.lowercase().trimEnd('.')
            if (d.isNotEmpty() && domainRegex.matches(d)) out.add(d)
        }
        return out.toList()
    }
}
