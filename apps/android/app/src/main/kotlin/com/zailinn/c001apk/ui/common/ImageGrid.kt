package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import com.zailinn.c001apk.ui.nav.LocalNavigator
import kotlin.math.min

/**
 * Feed images: one image keeps its proportions (bounded at 22:9), several are
 * laid out three per row with thumbnails. Tapping opens the full viewer.
 */
@Composable
fun ImageGrid(pics: List<String>, modifier: Modifier = Modifier, article: Boolean = false, gallery: List<String> = pics) {
    if (pics.isEmpty()) return
    val navigator = LocalNavigator.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val space = 5.dp
        val cell = (maxWidth - space * 2) / 3
        if (article || pics.size == 1) {
            val url = pics.first()
            val ratio = imageAspectRatio(url) ?: 1f
            val width: Dp = when {
                article -> maxWidth
                ratio > 1.5f -> maxWidth
                ratio >= 1f || (ratio < 1f && 1f / ratio < 1.5f) -> cell * 2 + space
                else -> cell
            }
            val height = width * min(1f / ratio, 22f / 9f)
            ImageCell(url, width, height, long = 1f / ratio > 22f / 9f) { navigator.openImages(gallery, gallery.indexOf(url).coerceAtLeast(0)) }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                pics.chunked(3).forEachIndexed { row, items ->
                    Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                        items.forEachIndexed { column, url ->
                            val index = row * 3 + column
                            ImageCell(thumbnail(url), cell, cell, gif = url.endsWith(".gif")) { navigator.openImages(gallery, gallery.indexOf(url).takeIf { it >= 0 } ?: index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageCell(url: String, width: Dp, height: Dp, long: Boolean = false, gif: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        NetworkImage(url, Modifier.size(width, height))
        val label = when {
            gif -> "动图"
            long -> "长图"
            else -> null
        }
        if (label != null) {
            Text(
                label,
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
