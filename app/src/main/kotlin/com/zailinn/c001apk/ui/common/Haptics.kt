package com.zailinn.c001apk.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Gates both app actions and Compose's built-in long-press feedback. */
@Composable
fun rememberAppHapticFeedback(enabled: Boolean): HapticFeedback {
    val platform = LocalHapticFeedback.current
    val currentEnabled = rememberUpdatedState(enabled)
    return remember(platform) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (currentEnabled.value) platform.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
}

/** Feedback follows an accepted action, so canceled touches and scrolling stay silent. */
@Composable
fun hapticClick(onClick: () -> Unit): () -> Unit {
    val feedback = LocalHapticFeedback.current
    return {
        feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
        onClick()
    }
}

@Composable
fun <T> hapticChange(onChange: (T) -> Unit): (T) -> Unit {
    val feedback = LocalHapticFeedback.current
    return {
        feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
        onChange(it)
    }
}
