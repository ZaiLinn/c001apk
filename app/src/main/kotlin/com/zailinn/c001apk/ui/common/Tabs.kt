package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tab row without edge padding: tabs share the full width when they fit, and
 * scroll edge to edge when they do not, so no blank strip is left at either side.
 */
@Composable
fun FillingTabRow(
    selectedTabIndex: Int,
    count: Int,
    modifier: Modifier = Modifier,
    divider: @Composable () -> Unit = { HorizontalDivider() },
    tabs: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp,
            minTabWidth = if (count > 0) maxWidth / count else maxWidth,
            divider = divider,
            tabs = tabs,
        )
    }
}
