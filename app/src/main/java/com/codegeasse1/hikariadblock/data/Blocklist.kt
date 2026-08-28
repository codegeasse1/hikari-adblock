package com.codegeasse1.hikariadblock.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
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

    fun allDomains(): Set<String> = synchronized(domains) { HashSet(domains) }

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

    suspend fun loadIfNeeded(context: Context) {
        if (isLoaded) return
        load(context)
    }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val files = listFiles(context).sorted()
        if (files.isEmpty()) {
            val text = runCatching { context.assets.open("hosts.txt").bufferedReader().use { it.readText() } }
                .getOrNull()
            if (text == null) return@withContext
            runCatching { fileFor(context, Preferences.DEFAULT_UPDATE_URL).writeText(text) }
            applyLoaded(parse(text))
        } else {
            val merged = HashSet<String>()
            for (f in files) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                merged.addAll(parse(text))
            }
            applyLoaded(merged)
        }
    }

    fun fileFor(context: Context, url: String): File =
        File(context.filesDir, "list_${url.hashCode().toUInt().toString(16)}.txt")

    private fun listFiles(context: Context): List<File> =
        runCatching {
            context.filesDir.listFiles { _, name -> name.startsWith("list_") }?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    private fun applyLoaded(loaded: Set<String>) {
        synchronized(domains) {
            domains.clear()
            domains.addAll(loaded)
            _sizeFlow.value = domains.size
        }
        isLoaded = true
    }

    suspend fun refreshFromUrls(context: Context, urls: List<String>, onStatus: (String) -> Unit = {}): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val active = HashSet<String>()
                val merged = HashSet<String>()
                var fetched = 0
                for (i in urls.indices) {
                    val url = urls[i].trim()
                    if (url.isEmpty()) continue
                    onStatus("Downloading list ${i + 1}/${urls.size}…")
                    val text = try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 60000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "Hikari-AdBlock/1.0")
                        if (conn.responseCode !in 200..299) {
                            null
                        } else {
                            conn.inputStream.bufferedReader().use { it.readText() }
                        }
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    val parsed = parse(text)
                    if (parsed.size < 100) continue
                    fetched++
                    active.add(url)
                    merged.addAll(parsed)
                    runCatching { fileFor(context, url).writeText(text) }
                }
                if (fetched == 0) {
                    return@withContext Result.failure(Exception("Could not download any filter list"))
                }
                if (merged.size < 1000) {
                    return@withContext Result.failure(Exception("Blocklist too small (${merged.size} entries)"))
                }
                val activeNames = active.map { fileFor(context, it).name }.toSet()
                for (f in listFiles(context)) {
                    if (f.name !in activeNames) {
                        runCatching { f.delete() }
                    }
                }
                onStatus("Applying ${merged.size} entries…")
                applyLoaded(merged)
                Result.success(merged.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun parse(text: String): Set<String> {
        val out = HashSet<String>()
        val ws = Regex("\\s+")
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val parts = ws.split(l)
            var candidate: String? = null
            if (parts.size >= 2) {
                val first = parts[0]
                if (first == "0.0.0.0" || first == "127.0.0.1" || first == "::" || first == "::1") {
                    candidate = parts[1]
                }
            }
            if (candidate == null) candidate = parts[0]
            val d = candidate.lowercase().trimEnd('.')
            if (d.isNotEmpty() && domainRegex.matches(d)) out.add(d)
        }
        return out
    }
}
