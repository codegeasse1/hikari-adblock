package com.codegeasse1.hikariadblock.ui.settings.component

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.codegeasse1.hikariadblock.R

@Composable
fun CommunitySection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier) {
        SectionHeader(
            title = stringResource(R.string.settings_community),
            icon = Icons.AutoMirrored.Filled.Chat,
            description = stringResource(R.string.settings_category_info_desc)
        )
        SettingsCard {
            SettingItem(
                icon = Icons.Default.Code,
                iconTint = Color(0xFF24292F),
                title = stringResource(R.string.settings_github),
                desc = stringResource(R.string.settings_github_desc),
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://github.com/codegeasse1/hikari-adblock".toUri())
                    )
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
            )
        }
    }
}
