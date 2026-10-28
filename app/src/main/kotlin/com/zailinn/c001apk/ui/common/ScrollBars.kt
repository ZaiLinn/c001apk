package com.zailinn.c001apk.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far the main screen's top and bottom bars are hidden, from 0 (shown) to
 * 1 (hidden). Scrolling a list down hides them, scrolling up brings them back;
 * a fling that ends halfway settles to whichever end is closer. A slow drag stays
 * where it is let go, so nothing keeps moving under the finger's last position.
 */
@Stable
class ScrollBarsState(val enabled: Boolean = true) {
    var hidden by mutableFloatStateOf(0f)
        private set

    /** The top bar's height in pixels. */
    var range = 1f

    private val settle = Animatable(0f)

    fun show() { hidden = 0f }

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Only real list movement counts, so pulling at the top to refresh shows nothing new.
            // The bars hide over a few bar heights of scrolling, not one, so they ease away instead of snapping off.
            if (enabled && consumed.y != 0f) hidden = (hidden - consumed.y / (range.coerceAtLeast(1f) * HIDE_DISTANCE)).coerceIn(0f, 1f)
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val flung = abs(consumed.y + available.y) >= SETTLE_VELOCITY
            if (flung && hidden > 0f && hidden < 1f) {
                settle.snapTo(hidden)
                settle.animateTo(if (hidden > .5f) 1f else 0f, tween(SETTLE_MILLIS, easing = FastOutSlowInEasing)) { hidden = value }
            }
            return Velocity.Zero
        }
    }
}

/** Scroll distance that hides the bars fully, in top bar heights. */
private const val HIDE_DISTANCE = 3f
private const val SETTLE_MILLIS = 220
/** Release speed, in pixels per second, from which a scroll counts as a fling. */
private const val SETTLE_VELOCITY = 1500f

/** No hiding outside the main screen. */
val LocalScrollBars = staticCompositionLocalOf { ScrollBarsState(enabled = false) }

/**
 * Slides [content] out over its top ([fromTop]) or bottom edge as the bars hide,
 * giving its space back to the page below.
 */
@Composable
fun HidingBar(state: ScrollBarsState, fromTop: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clipToBounds().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            if (fromTop && placeable.height > 0) state.range = placeable.height.toFloat()
            val gone = (placeable.height * state.hidden).roundToInt()
            layout(placeable.width, placeable.height - gone) { placeable.place(0, if (fromTop) -gone else 0) }
        },
    ) { content() }
}
