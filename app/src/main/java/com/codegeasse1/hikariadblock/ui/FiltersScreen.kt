package com.codegeasse1.hikariadblock.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codegeasse1.hikariadblock.data.Blocklist
import com.codegeasse1.hikariadblock.data.Preferences
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FiltersScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val listSize by Blocklist.sizeFlow.collectAsStateWithLifecycle(initialValue = 0)
    val lastUpdate by Preferences.lastUpdateMillisFlow(context).collectAsStateWithLifecycle(initialValue = 0L)
    val autoUpdateHours by Preferences.autoUpdateHoursFlow(context).collectAsStateWithLifecycle(initialValue = 0)
    val whitelist by Preferences.whitelistFlow(context).collectAsStateWithLifecycle(initialValue = emptySet())
    val customBlocked by Preferences.customBlockedFlow(context).collectAsStateWithLifecycle(initialValue = emptySet())

    var updating by remember { mutableStateOf(false) }
    var newWhitelist by remember { mutableStateOf("") }
    var newCustom by remember { mutableStateOf("") }

    val onUpdate: () -> Unit = {
        if (!updating) {
            updating = true
            scope.launch {
                val result = Blocklist.updateFromUrl(context, Preferences.DEFAULT_UPDATE_URL)
                updating = false
                result.onSuccess { n ->
                    Preferences.setLastUpdate(context, System.currentTimeMillis())
                    Blocklist.applyWhitelist(whitelist)
                    Blocklist.applyCustomBlocked(customBlocked)
                    Toast.makeText(context, "Blocklist updated: $n domains", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Filter lists", style = MaterialTheme.typography.titleLarge)
        Text(
            "Domains in these lists are blocked from resolving",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("StevenBlack hosts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$listSize domains · last updated ${formatDate(lastUpdate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (updating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onUpdate) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Update now")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Auto-update", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 6 to "6h", 12 to "12h", 24 to "24h", 48 to "48h").forEach { (hours, label) ->
                        FilterChip(
                            selected = autoUpdateHours == hours,
                            onClick = {
                                scope.launch { Preferences.setAutoUpdateHours(context, hours) }
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Whitelist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Allowed domains are never blocked, including subdomains",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newWhitelist,
                        onValueChange = { newWhitelist = it },
                        label = { Text("example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        val d = normalize(newWhitelist)
                        if (d != null) {
                            val next = whitelist + d
                            scope.launch {
                                Preferences.setWhitelist(context, next)
                                Blocklist.applyWhitelist(next)
                            }
                            newWhitelist = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                }
                if (whitelist.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    whitelist.sorted().forEach { domain ->
                        DomainRow(domain, onDelete = {
                            val next = whitelist - domain
                            scope.launch {
                                Preferences.setWhitelist(context, next)
                                Blocklist.applyWhitelist(next)
                            }
                        })
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Custom blocked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Extra domains to block, on top of the filter list",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCustom,
                        onValueChange = { newCustom = it },
                        label = { Text("ads.example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        val d = normalize(newCustom)
                        if (d != null) {
                            val next = customBlocked + d
                            scope.launch {
                                Preferences.setCustomBlocked(context, next)
                                Blocklist.applyCustomBlocked(next)
                            }
                            newCustom = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                }
                if (customBlocked.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    customBlocked.sorted().forEach { domain ->
                        DomainRow(domain, onDelete = {
                            val next = customBlocked - domain
                            scope.launch {
                                Preferences.setCustomBlocked(context, next)
                                Blocklist.applyCustomBlocked(next)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainRow(domain: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun normalize(input: String): String? {
    val d = input.trim().lowercase().trimEnd('.').removePrefix("*.")
    return d.takeIf { it.isNotEmpty() && it.contains('.') && !it.contains(' ') }
}

private fun formatDate(millis: Long): String =
    if (millis <= 0) "never"
    else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
