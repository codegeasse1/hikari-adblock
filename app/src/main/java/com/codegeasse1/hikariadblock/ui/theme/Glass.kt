package com.codegeasse1.hikariadblock.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Shared "glass" surface styling: rounded corners with a translucent,
 * elevated, subtly-bordered look — the signature UI of the Hikari apps.
 */
val GlassCorner = RoundedCornerShape(24.dp)
val GlassCornerLarge = RoundedCornerShape(30.dp)
val GlassCornerSmall = RoundedCornerShape(18.dp)
val GlassCornerPill = RoundedCornerShape(50)

@Composable
fun glassCardColors(): CardColors = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
)

@Composable
fun glassBorder(): BorderStroke = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
)

@Composable
fun glassCardElevation(): CardElevation = CardDefaults.cardElevation(
    defaultElevation = 12.dp
)
