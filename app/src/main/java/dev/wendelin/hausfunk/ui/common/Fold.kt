package dev.wendelin.hausfunk.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Where a horizontal fold is while the phone stands half open (Galaxy Flip "Flex mode"), as the
 * distance from the window's top; null when the phone is flat, closed or has no fold.
 */
@Composable
fun rememberHalfOpenFoldTop(): Dp? {
    val activity = LocalActivity.current ?: return null
    val density = LocalDensity.current
    val top by produceState<Int?>(null, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL
            }
            value = fold?.bounds?.top
        }
    }
    return top?.let { with(density) { it.toDp() } }
}
