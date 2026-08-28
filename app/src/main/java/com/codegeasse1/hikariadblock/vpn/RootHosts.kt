package com.codegeasse1.hikariadblock.vpn

import android.content.Context
import com.codegeasse1.hikariadblock.data.Blocklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object RootHosts {

    private const val MARKER = "# HIKARI_ADBLOCK"
    private const val BACKUP = "/data/local/tmp/hosts.hikari.bak"

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun isRootAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, TimeUnit.SECONDS)
        out == "0"
    }.getOrDefault(false)

    fun isActive(): Boolean {
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "grep -q '$MARKER' /etc/hosts && echo yes").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            out.contains("yes")
        }.getOrDefault(false)
        _active.value = ok
        return ok
    }

    private fun runSu(command: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        p.exitValue() == 0
    }.getOrDefault(false)

    suspend fun apply(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext Result.failure(Exception("Root access not available"))
        }
        val domains = Blocklist.allDomains()
        if (domains.isEmpty()) {
            return@withContext Result.failure(Exception("Blocklist is empty"))
        }
        val sb = StringBuilder()
        sb.append(MARKER).append('\n')
        for (d in domains) sb.append("0.0.0.0 ").append(d).append('\n')
        val f = File(context.cacheDir, "hosts_hikari")
        f.writeText(sb.toString())
        val ok = runSu("cp /etc/hosts $BACKUP 2>/dev/null; cp '${f.absolutePath}' /etc/hosts && chmod 644 /etc/hosts")
        if (!ok) {
            return@withContext Result.failure(Exception("Failed to write /etc/hosts (is it writable?)"))
        }
        _active.value = true
        Result.success(domains.size)
    }

    suspend fun restore(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val ok = runSu("if [ -f $BACKUP ]; then cp $BACKUP /etc/hosts && chmod 644 /etc/hosts && rm -f $BACKUP; else sed -i '/$MARKER/d' /etc/hosts; fi")
        if (!ok) {
            return@withContext Result.failure(Exception("Failed to restore /etc/hosts"))
        }
        _active.value = false
        Result.success(Unit)
    }
}
