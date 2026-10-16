package com.zailinn.c001apk.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Runs [action] each time [count] grows past the last value handled here. The
 * handled value is saved, so coming back to a screen (a popped page, another
 * tab) does not replay an old tap as a new one.
 */
@Composable
fun OnCountIncrease(count: Int, action: suspend () -> Unit) {
    var handled by rememberSaveable { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > handled) {
            handled = count
            action()
        }
    }
}
