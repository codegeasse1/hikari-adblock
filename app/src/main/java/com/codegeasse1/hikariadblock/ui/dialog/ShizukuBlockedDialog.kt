package com.codegeasse1.hikariadblock.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.codegeasse1.hikariadblock.R

/**
 * Shown when the device/ROM refuses shell-level netfilter access on every
 * backend, so Shizuku mode can never apply its DNS-redirect rules. It replaces
 * the previous endless "Connecting…" with an immediate, clear explanation and
 * points the user at the two working alternatives (root backend / Direct mode).
 */
@Composable
fun ShizukuBlockedDialog(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onSwitchToDirect: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.shizuku_iptables_blocked_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.shizuku_iptables_blocked_dialog_desc))
        },
        confirmButton = {
            TextButton(onClick = onSwitchToDirect) {
                Text(stringResource(R.string.shizuku_blocked_switch_direct))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}
