package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Sheets, dialogs and menus open in a window of their own, whose root sets
// LocalDensity again from the system. The app's font scale lives in
// LocalDensity, so these wrappers carry the caller's density into the window.
// Carry haptics too, so the app's feedback switch also applies in these windows.

/** [content] wrapped to run with [density] once it is composed inside another window. */
private fun withAppLocals(density: Density, haptics: HapticFeedback, content: @Composable () -> Unit): @Composable () -> Unit =
    { CompositionLocalProvider(LocalDensity provides density, LocalHapticFeedback provides haptics, content = content) }

/** [ModalBottomSheet] at the app's font scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest, modifier, sheetState = sheetState) {
        CompositionLocalProvider(LocalDensity provides density, LocalHapticFeedback provides haptics) { content() }
    }
}

/** [AlertDialog] at the app's font scale. */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = withAppLocals(density, haptics, confirmButton),
        modifier = modifier,
        dismissButton = dismissButton?.let { withAppLocals(density, haptics, it) },
        title = title?.let { withAppLocals(density, haptics, it) },
        text = text?.let { withAppLocals(density, haptics, it) },
    )
}

/** [DropdownMenu] at the app's font scale. */
@Composable
fun AppDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    DropdownMenu(expanded, onDismissRequest, modifier) {
        CompositionLocalProvider(LocalDensity provides density, LocalHapticFeedback provides haptics) { content() }
    }
}

/** [Dialog] at the app's font scale. */
@Composable
fun AppDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties, withAppLocals(LocalDensity.current, LocalHapticFeedback.current, content))
}
