package com.zailinn.c001apk.ui.screens.main

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.ui.common.backPreview
import com.zailinn.c001apk.ui.common.predictiveBack

private data class Destination(val label: String, val icon: ImageVector, val selected: ImageVector)

private val destinations = listOf(
    Destination("主页", Icons.Outlined.Home, Icons.Filled.Home),
    Destination("关注", Icons.Outlined.DynamicFeed, Icons.Filled.DynamicFeed),
    Destination("我的", Icons.Outlined.Person, Icons.Filled.Person),
)

/** Home, follow feed and profile with a bottom bar, or a rail on wide windows. */
@Composable
fun MainScreen(generation: Int) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reselect by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    // Back from 关注 or 我的 returns to 主页 first, previewed with the gesture.
    val backProgress = predictiveBack(tab != 0) { tab = 0 }
    val select = { index: Int ->
        if (index == tab) reselect++ else tab = index
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        destinations.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { select(index) },
                                icon = { Icon(if (tab == index) item.selected else item.icon, item.label) },
                                label = { Text(item.label) },
                                alwaysShowLabel = false,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    NavigationRail {
                        destinations.forEachIndexed { index, item ->
                            NavigationRailItem(
                                selected = tab == index,
                                onClick = { select(index) },
                                icon = { Icon(if (tab == index) item.selected else item.icon, item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize().padding(padding).backPreview(backProgress)) {
                    holder.SaveableStateProvider("$tab-$generation") {
                        when (tab) {
                            0 -> HomeScreen(generation, reselect)
                            1 -> FollowScreen(generation, reselect)
                            else -> MineScreen(generation)
                        }
                    }
                }
            }
        }
    }
}
