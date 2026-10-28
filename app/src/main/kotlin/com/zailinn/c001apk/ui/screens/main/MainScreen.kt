package com.zailinn.c001apk.ui.screens.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.ui.common.HidingBar
import com.zailinn.c001apk.ui.common.LocalScrollBars
import com.zailinn.c001apk.ui.common.ScrollBarsState
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.LocalAppSettings

private data class Destination(val label: String, val icon: ImageVector, val selected: ImageVector)

private val destinations = listOf(
    Destination("主页", Icons.Outlined.Home, Icons.Filled.Home),
    Destination("关注", Icons.Outlined.Group, Icons.Filled.Group),
    Destination("我的", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

/** Home, follow feed and profile with a bottom bar, or a rail on wide windows. */
@Composable
fun MainScreen(generation: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Taps on the already selected tab, counted per tab so one tab's taps never reach another.
    var homeReselect by rememberSaveable { mutableIntStateOf(0) }
    var followReselect by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    val refreshOnReturn = LocalAppSettings.current.refreshOnReturn
    val hideBars = LocalAppSettings.current.hideBarsOnScroll
    // Counts returns to 主页 from another tab or page; HomeScreen refreshes once per new count.
    var homeReturns by rememberSaveable { mutableIntStateOf(0) }
    var shownBefore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // A page popped back to this one: the destination is composed again with its saved state.
        if (shownBefore && refreshOnReturn && tab == 0) homeReturns++
        shownBefore = true
    }
    val goHome = {
        if (refreshOnReturn) homeReturns++
        tab = 0
    }
    // Back from 关注 or 我的 returns to 主页 first.
    BackHandler(tab != 0) { goHome() }
    val select = { index: Int ->
        when {
            index == tab -> when (tab) {
                0 -> homeReselect++
                1 -> followReselect++
            }
            index == 0 -> goHome()
            else -> tab = index
        }
    }
    val bars = remember(hideBars) { ScrollBarsState(hideBars) }
    // Another tab starts with its bars shown.
    LaunchedEffect(tab) { bars.show() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!wide) {
                    // The gesture area stays reserved; only the bar itself slides away.
                    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()) {
                        HidingBar(bars, fromTop = false) {
                            // Expressive short bar: compact height, labels always visible.
                            ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, windowInsets = WindowInsets(0)) {
                                destinations.forEachIndexed { index, item ->
                                    ShortNavigationBarItem(
                                        selected = tab == index,
                                        onClick = hapticClick { select(index) },
                                        icon = { Icon(if (tab == index) item.selected else item.icon, contentDescription = null) },
                                        label = { Text(item.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        destinations.forEachIndexed { index, item ->
                            NavigationRailItem(
                                selected = tab == index,
                                onClick = hapticClick { select(index) },
                                icon = { Icon(if (tab == index) item.selected else item.icon, item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize().padding(padding)) {
                    CompositionLocalProvider(LocalScrollBars provides bars) {
                        holder.SaveableStateProvider("$tab-$generation") {
                            when (tab) {
                                0 -> HomeScreen(generation, homeReselect, homeReturns)
                                1 -> FollowScreen(generation, followReselect)
                                else -> MineScreen(generation)
                            }
                        }
                    }
                }
            }
        }
    }
}
