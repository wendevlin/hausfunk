package dev.wendelin.hausfunk.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import dev.wendelin.hausfunk.R

/**
 * The Hausfunk mark. Brand colours only work on a dark background, so on a light one the mark
 * is drawn in a single colour (onSurface) instead.
 */
@Composable
fun LogoMark(modifier: Modifier = Modifier) {
    val painter = painterResource(R.drawable.ic_logo_mark)
    val colors = MaterialTheme.colorScheme
    if (colors.surface.luminance() < 0.5f) {
        Image(painter, contentDescription = null, modifier = modifier)
    } else {
        Icon(painter, contentDescription = null, tint = colors.onSurface, modifier = modifier)
    }
}
