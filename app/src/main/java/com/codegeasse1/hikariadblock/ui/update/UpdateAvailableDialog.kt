package com.codegeasse1.hikariadblock.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codegeasse1.hikariadblock.R
import com.codegeasse1.hikariadblock.utils.InstallState

/**
 * Shown when [com.codegeasse1.hikariadblock.utils.AppUpdateManager] detects a
 * newer GitHub release. Offers a one-tap in-app download/install plus a link
 * to the release page for manual download.
 */
@Composable
fun UpdateAvailableDialog(
    version: String,
    changelog: String,
    installState: InstallState,
    onUpdate: () -> Unit,
    onRetryInstall: () -> Unit,
    onViewRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloading = installState is InstallState.Downloading

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_available_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.update_available_message, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (changelog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.update_changelog_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = changelog.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                when (installState) {
                    is InstallState.Downloading -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        val fraction = if (installState.total > 0L) {
                            (installState.downloaded.toFloat() / installState.total.toFloat())
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (installState.total > 0L) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.update_downloading_progress,
                                    (fraction * 100).toInt()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.update_downloading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is InstallState.NeedsPermission -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_allow_installs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    is InstallState.Failed -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.update_failed, installState.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (installState) {
                is InstallState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_downloading))
                }

                is InstallState.Installing -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_installing))
                }

                is InstallState.NeedsPermission -> TextButton(onClick = onRetryInstall) {
                    Text(stringResource(R.string.update_install))
                }

                else -> TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.update_now))
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onViewRelease) {
                    Text(stringResource(R.string.update_view_release))
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !downloading
                ) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    )
}
