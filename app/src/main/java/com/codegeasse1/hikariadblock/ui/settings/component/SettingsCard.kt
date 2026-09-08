package com.codegeasse1.hikariadblock.ui.settings.component

import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codegeasse1.hikariadblock.ui.theme.GlassCorner
import com.codegeasse1.hikariadblock.ui.theme.glassBorder
import com.codegeasse1.hikariadblock.ui.theme.glassCardColors
import com.codegeasse1.hikariadblock.ui.theme.glassCardElevation

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            colors = glassCardColors(),
            shape = GlassCorner,
            border = glassBorder(),
            elevation = glassCardElevation(),
            modifier = modifier
        ) {
            content()
        }
    } else {
        Card(
            colors = glassCardColors(),
            shape = GlassCorner,
            border = glassBorder(),
            elevation = glassCardElevation(),
            modifier = modifier
        ) {
            content()
        }
    }
}
