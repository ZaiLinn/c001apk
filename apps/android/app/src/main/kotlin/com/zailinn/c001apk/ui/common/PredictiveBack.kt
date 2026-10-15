package com.zailinn.c001apk.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Back handling that follows the predictive back gesture instead of jumping on
 * release. Returns the gesture progress for [backPreview].
 */
@Composable
fun predictiveBack(enabled: Boolean, onBack: () -> Unit): Float {
    var progress by remember { mutableFloatStateOf(0f) }
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress = it.progress }
            currentOnBack()
        } finally {
            // Also runs when the gesture is cancelled; the preview springs back.
            progress = 0f
        }
    }
    val shown by animateFloatAsState(progress, spring(stiffness = Spring.StiffnessMediumLow), label = "back")
    return shown
}

/** Shrinks content with the gesture, matching the page transition used by the navigation host. */
fun Modifier.backPreview(progress: Float): Modifier = if (progress == 0f) this else graphicsLayer {
    val scale = 1f - 0.1f * progress
    scaleX = scale
    scaleY = scale
    translationX = 24.dp.toPx() * progress
    shape = RoundedCornerShape(28.dp * progress)
    clip = true
}
