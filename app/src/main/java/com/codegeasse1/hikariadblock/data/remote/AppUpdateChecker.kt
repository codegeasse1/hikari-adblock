package com.codegeasse1.hikariadblock.data.remote

import android.os.Build
import com.codegeasse1.hikariadblock.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/** GitHub repository that publishes Hikari AdBlock releases. */
const val GITHUB_REPO = "codegeasse1/hikari-adblock"

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

/** Fallback link if a release has no explicit html_url. */
const val RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO/releases/latest"

/** A single downloadable file attached to a GitHub release. */
data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/**
 * Information about a newer release that is available on GitHub.
 *
 * @param version   normalized version, e.g. "1.2.1" (tag "v1.2.1")
 * @param releaseUrl the GitHub release page (opened by the "release" button)
 * @param apk       APK asset matching this device's ABI, or null if none
 */
data class AppUpdateInfo(
    val version: String,
    val releaseUrl: String,
    val releaseName: String,
    val changelog: String,
    val apk: AppReleaseAsset?,
)

/**
 * Checks GitHub for a newer Hikari AdBlock release.
 *
 * The app is distributed as signed APKs attached to GitHub releases, so
 * "is there an update?" is answered by comparing our own
 * [BuildConfig.VERSION_NAME] with the latest release tag, and the download
 * URL is the release asset matching the device's CPU ABI (falling back to
 * the universal APK).
 */
class AppUpdateChecker(
    private val client: HttpClient,
) {

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Fetch the newest published release, or null on any error/offline. */
    suspend fun fetchLatestRelease(): AppUpdateInfo? {
        return try {
            val response = client.get(LATEST_RELEASE_URL) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "HikariAdBlock/${BuildConfig.VERSION_NAME}")
                timeout {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 10_000
                }
            }
            if (response.status.value !in 200..299) {
                Timber.w("Update check returned HTTP ${response.status.value}")
                return null
            }
            parseRelease(response.bodyAsText())
        } catch (e: Exception) {
            Timber.w(e, "Update check failed")
            null
        }
    }

    /** Parse the GitHub "latest release" JSON. Exposed for unit testing. */
    fun parseRelease(body: String): AppUpdateInfo? {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = normalizeVersion(tag)
            if (version.isBlank()) return null

            val releaseUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/$GITHUB_REPO/releases"
            val releaseName = root["name"]?.jsonPrimitive?.contentOrNull ?: "v$version"
            val changelog = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                AppReleaseAsset(name = name, downloadUrl = url, sizeBytes = size)
            }

            AppUpdateInfo(
                version = version,
                releaseUrl = releaseUrl,
                releaseName = releaseName,
                changelog = changelog,
                apk = pickApkForDevice(assets),
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse release JSON")
            null
        }
    }

    /** True when [remote] is a strictly newer version than [current]. */
    fun isNewerVersion(remote: String, current: String = currentVersion): Boolean {
        val r = parseVersionNumbers(remote)
        val c = parseVersionNumbers(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /**
     * Prefer the release asset built for this device's primary ABI, then the
     * universal APK, then any APK. The release publishes split APKs named
     * `app-<abi>-release.apk` plus `app-universal-release.apk`.
     */
    private fun pickApkForDevice(assets: List<AppReleaseAsset>): AppReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: apks.first()
    }

    private fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").substringBefore('-').trim()

    private fun parseVersionNumbers(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}
