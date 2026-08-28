package com.codegeasse1.hikariadblock.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    const val REPO_URL = "https://github.com/codegeasse1/hikari-adblock"
    private const val RELEASES_API = "https://api.github.com/repos/codegeasse1/hikari-adblock/releases/latest"

    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Hikari-AdBlock/1.0")
            if (conn.responseCode != 200) {
                null
            } else {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .getString("tag_name")
                    .removePrefix("v")
            }
        }.getOrNull()
    }

    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }
}
