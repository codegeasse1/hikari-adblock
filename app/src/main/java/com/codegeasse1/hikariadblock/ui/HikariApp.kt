package com.codegeasse1.hikariadblock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun HikariApp(onToggle: (Boolean) -> Unit, onCheckUpdate: () -> Unit = {}) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        TabSpec("Home", Icons.Filled.Shield),
        TabSpec("Logs", Icons.Filled.ReceiptLong),
        TabSpec("Filters", Icons.Filled.FilterList),
        TabSpec("Settings", Icons.Filled.Settings)
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, spec ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(spec.icon, contentDescription = spec.label) },
                        label = { Text(spec.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selected) {
                0 -> HomeScreen(onToggle)
                1 -> LogsScreen()
                2 -> FiltersScreen()
                else -> SettingsScreen(onCheckUpdate)
            }
        }
    }
}

private data class TabSpec(val label: String, val icon: ImageVector)
