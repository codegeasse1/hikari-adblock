package com.codegeasse1.hikariadblock.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hikari_adblock_prefs")

object Preferences {

    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    val autostart = booleanPreferencesKey("autostart")
    val theme = stringPreferencesKey("theme")
    val updateUrl = stringPreferencesKey("update_url")
    val autoUpdateHours = intPreferencesKey("auto_update_hours")
    val lastUpdateMillis = longPreferencesKey("last_update_millis")
    val whitelist = stringSetPreferencesKey("whitelist")
    val customBlocked = stringSetPreferencesKey("custom_blocked")
    val totalBlocked = longPreferencesKey("total_blocked")
    val totalQueries = longPreferencesKey("total_queries")

    fun themeFlow(context: Context): Flow<String> = context.dataStore.data.map { it[theme] ?: "system" }

    fun autostartFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { it[autostart] ?: false }

    fun autoUpdateHoursFlow(context: Context): Flow<Int> = context.dataStore.data.map { it[autoUpdateHours] ?: 0 }

    fun lastUpdateMillisFlow(context: Context): Flow<Long> = context.dataStore.data.map { it[lastUpdateMillis] ?: 0L }

    fun updateUrlFlow(context: Context): Flow<String> = context.dataStore.data.map { it[updateUrl] ?: DEFAULT_UPDATE_URL }

    fun whitelistFlow(context: Context): Flow<Set<String>> = context.dataStore.data.map { it[whitelist] ?: emptySet() }

    fun customBlockedFlow(context: Context): Flow<Set<String>> = context.dataStore.data.map { it[customBlocked] ?: emptySet() }

    fun totalsFlow(context: Context): Flow<Pair<Long, Long>> = context.dataStore.data.map {
        (it[totalQueries] ?: 0L) to (it[totalBlocked] ?: 0L)
    }

    suspend fun setAutostart(context: Context, value: Boolean) = context.dataStore.edit { it[autostart] = value }

    suspend fun setTheme(context: Context, value: String) = context.dataStore.edit { it[theme] = value }

    suspend fun setAutoUpdateHours(context: Context, value: Int) = context.dataStore.edit { it[autoUpdateHours] = value }

    suspend fun setUpdateUrl(context: Context, value: String) = context.dataStore.edit { it[updateUrl] = value }

    suspend fun setLastUpdate(context: Context, millis: Long) = context.dataStore.edit { it[lastUpdateMillis] = millis }

    suspend fun setWhitelist(context: Context, set: Set<String>) = context.dataStore.edit { it[whitelist] = set }

    suspend fun setCustomBlocked(context: Context, set: Set<String>) = context.dataStore.edit { it[customBlocked] = set }

    suspend fun addTotals(context: Context, queries: Long, blocked: Long) = context.dataStore.edit { p ->
        p[totalQueries] = (p[totalQueries] ?: 0L) + queries
        p[totalBlocked] = (p[totalBlocked] ?: 0L) + blocked
    }

    suspend fun isAutostartEnabled(context: Context): Boolean = context.dataStore.data.first()[autostart] ?: false

    suspend fun whitelistOnce(context: Context): Set<String> = context.dataStore.data.first()[whitelist] ?: emptySet()

    suspend fun customBlockedOnce(context: Context): Set<String> = context.dataStore.data.first()[customBlocked] ?: emptySet()

    suspend fun autoUpdateHoursOnce(context: Context): Int = context.dataStore.data.first()[autoUpdateHours] ?: 0

    suspend fun lastUpdateMillisOnce(context: Context): Long = context.dataStore.data.first()[lastUpdateMillis] ?: 0L
}
