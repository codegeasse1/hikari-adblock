package com.codegeasse1.hikariadblock.ui.settings.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codegeasse1.hikariadblock.R
import com.codegeasse1.hikariadblock.data.datastore.AppPreferences
import com.codegeasse1.hikariadblock.data.entities.FilterList
import com.codegeasse1.hikariadblock.ui.theme.TextSecondary

@Composable
fun FilterSetupSection(
    modifier: Modifier = Modifier,
    filterLists: List<FilterList>,
    autoUpdateNotification: String,
    autoUpdateFrequency: String,
    autoUpdateWifiOnly: Boolean,
    autoUpdateEnabled: Boolean,
    onNavigateToFilterSetup: () -> Unit = {},
    onSetAutoUpdateWifiOnly: (Boolean) -> Unit = {},
    onSetAutoUpdateFrequency: (String) -> Unit = {},
    onSetAutoUpdateNotification: (String) -> Unit = {},
    onSetAutoUpdateEnable: (Boolean) -> Unit = {},
) {
    var showFrequencyDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }

    SettingsCard(modifier = modifier) {
        Column {
            // Filter list navigation
            SettingItem(
                icon = Icons.Default.FilterList,
                title = stringResource(R.string.filter_setup_title),
                desc = stringResource(R.string.settings_filter_lists, filterLists.count { it.isEnabled }),
                onClick = onNavigateToFilterSetup
            )

            // Auto-update section
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsToggleItem(
                        icon = Icons.Default.Download,
                        title = stringResource(R.string.settings_auto_update_enabled),
                        subtitle = stringResource(R.string.settings_auto_update_enabled_desc),
                        isChecked = autoUpdateEnabled,
                        onCheckedChange = { onSetAutoUpdateEnable(it) }
                    )

                    if (autoUpdateEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )

                        // Update frequency
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFrequencyDialog = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_auto_update_frequency),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    when (autoUpdateFrequency) {
                                        AppPreferences.UPDATE_FREQUENCY_6H -> stringResource(R.string.settings_auto_update_frequency_6h)
                                        AppPreferences.UPDATE_FREQUENCY_12H -> stringResource(R.string.settings_auto_update_frequency_12h)
                                        AppPreferences.UPDATE_FREQUENCY_24H -> stringResource(R.string.settings_auto_update_frequency_24h)
                                        AppPreferences.UPDATE_FREQUENCY_48H -> stringResource(R.string.settings_auto_update_frequency_48h)
                                        AppPreferences.UPDATE_FREQUENCY_MANUAL -> stringResource(R.string.settings_auto_update_frequency_manual)
                                        else -> stringResource(R.string.settings_auto_update_frequency_24h)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )

                        // Wi-Fi only
                        SettingsToggleItem(
                            icon = Icons.Default.Wifi,
                            title = stringResource(R.string.settings_auto_update_wifi_only),
                            subtitle = stringResource(R.string.settings_auto_update_wifi_only_desc),
                            isChecked = autoUpdateWifiOnly,
                            onCheckedChange = { onSetAutoUpdateWifiOnly(it) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )

                        // Notification preference
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showNotificationDialog = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.settings_auto_update_notification),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    when (autoUpdateNotification) {
                                        AppPreferences.NOTIFICATION_NORMAL -> stringResource(R.string.settings_auto_update_notification_normal)
                                        AppPreferences.NOTIFICATION_SILENT -> stringResource(R.string.settings_auto_update_notification_silent)
                                        AppPreferences.NOTIFICATION_NONE -> stringResource(R.string.settings_auto_update_notification_none)
                                        else -> stringResource(R.string.settings_auto_update_notification_normal)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showFrequencyDialog) {
        FrequencyDialog(
            autoUpdateFrequency = autoUpdateFrequency,
            onUpdateFrequencyChange = { freq ->
                onSetAutoUpdateFrequency(freq)
                showFrequencyDialog = false
            },
            onDismiss = { showFrequencyDialog = false }
        )
    }

    if (showNotificationDialog) {
        NotificationDialog(
            autoUpdateNotification = autoUpdateNotification,
            onUpdateNotification = { type ->
                onSetAutoUpdateNotification(type)
                showNotificationDialog = false
            },
            onDismiss = { showNotificationDialog = false }
        )
    }
}