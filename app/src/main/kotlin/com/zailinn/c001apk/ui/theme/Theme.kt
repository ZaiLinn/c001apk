package com.zailinn.c001apk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.materialkolor.dynamicColorScheme
import com.zailinn.c001apk.data.AppSettings

val seedColors = listOf(
    0xFF6650A4, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
    0xFF009688, 0xFF4FAF50, 0xFF8BC3A4, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
    0xFF795548, 0xFF607D8F, 0xFFFF9CA8,
).map { Color(it) }

val seedColorNames = listOf(
    "默认", "红色", "粉色", "紫色", "靛蓝", "蓝色", "浅蓝", "青色", "蓝绿", "绿色", "浅绿", "青柠",
    "黄色", "琥珀", "橙色", "深橙", "棕色", "蓝灰", "樱粉",
)

@Composable
fun isAppInDarkTheme(settings: AppSettings): Boolean = when (settings.themeMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

/** Material 3 Expressive theme with dynamic or seed colors and the app font scale. */
@Composable
fun C001apkTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(settings)
    val context = LocalContext.current
    val useDynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val seed = seedColors.getOrElse(settings.staticColor) { seedColors.first() }
    val colorScheme = remember(useDynamic, dark, seed) {
        when {
            useDynamic && dark -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            else -> dynamicColorScheme(seedColor = seed, isDark = dark)
        }
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive()) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, density.fontScale * settings.fontScale),
            content = content,
        )
    }
}
