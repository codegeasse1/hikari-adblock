package com.codegeasse1.hikariadblock.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** Result of asking Android to open an APK for installation. */
enum class InstallOutcome { LAUNCHED, NEEDS_PERMISSION, FAILED }

/**
 * Downloads a release APK to the app cache and hands it to the system package
 * installer via a [FileProvider] URI.
 *
 * On Android 8+ a user must first grant "install unknown apps" for this app;
 * [install] detects that and routes the user to the relevant settings screen,
 * returning [InstallOutcome.NEEDS_PERMISSION] so the UI can offer a retry.
 */
class ApkDownloadInstaller(
    private val context: Context,
    private val client: HttpClient,
) {

    /** True if we already have permission to launch the package installer. */
    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Stream the APK at [url] into the cache, reporting progress via
     * [onProgress] (total is -1 when the server does not send a length).
     * Returns the downloaded file, or null on failure.
     */
    suspend fun downloadApk(
        url: String,
        version: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.isFile) it.delete() }
            val outFile = File(dir, "hikari-$version.apk")

            val response = client.get(url) {
                timeout {
                    requestTimeoutMillis = 10 * 60_000
                    connectTimeoutMillis = 30_000
                    socketTimeoutMillis = 60_000
                }
            }
            if (response.status.value !in 200..299) {
                Timber.w("APK download returned HTTP ${response.status.value}")
                return@withContext null
            }

            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var downloaded = 0L
            outFile.outputStream().use { output ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
                output.flush()
            }

            if (outFile.length() > 0L) outFile else null
        } catch (e: Exception) {
            Timber.w(e, "APK download failed")
            null
        }
    }

    /** Open the downloaded [apk] with the system package installer. */
    fun install(apk: File): InstallOutcome {
        return try {
            if (!canInstallPackages()) {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settings)
                return InstallOutcome.NEEDS_PERMISSION
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallOutcome.LAUNCHED
        } catch (e: Exception) {
            Timber.w(e, "Could not launch APK installer")
            InstallOutcome.FAILED
        }
    }
}
