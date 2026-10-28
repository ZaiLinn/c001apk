package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlin.math.max

private val Space = 5.dp
private val Corner = RoundedCornerShape(12.dp)

/**
 * Feed images: one image keeps its proportions (bounded at 22:9), several are
 * laid out three per row. Lists always load thumbnails; tapping opens originals.
 * Sizes come from weights and aspect ratios, so no subcomposition is needed.
 */
@Composable
fun ImageGrid(pics: List<String>, modifier: Modifier = Modifier, article: Boolean = false, gallery: List<String> = pics, media: List<com.zailinn.c001apk.core.model.GalleryMedia> = emptyList()) {
    if (pics.isEmpty()) return
    val navigator = LocalNavigator.current
    val open = { url: String, index: Int -> navigator.openImages(gallery, gallery.indexOf(url).takeIf { it >= 0 } ?: index, media) }
    if (article || pics.size == 1) {
        val url = pics.first()
        val ratio = imageAspectRatio(url) ?: 1f
        // Width as a share of the row: full, two cells or one cell.
        val share = when {
            article || ratio > 1.5f -> 1f
            ratio >= 1f || 1f / ratio < 1.5f -> 2f / 3f
            else -> 1f / 3f
        }
        Box(modifier.fillMaxWidth()) {
            ImageCell(url, Modifier.fillMaxWidth(share).aspectRatio(max(ratio, 9f / 22f)), long = 1f / ratio > 22f / 9f, media = media.firstOrNull { it.matches(url) }) { open(url, 0) }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space)) {
            pics.chunked(3).forEachIndexed { row, items ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space)) {
                    items.forEachIndexed { column, url ->
                        ImageCell(url, Modifier.weight(1f).aspectRatio(1f), media = media.firstOrNull { it.matches(url) }) { open(url, row * 3 + column) }
                    }
                    repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ImageCell(url: String, modifier: Modifier, long: Boolean = false, media: com.zailinn.c001apk.core.model.GalleryMedia? = null, onClick: () -> Unit) {
    Box(modifier.clip(Corner).clickable(onClick = onClick).semantics { contentDescription = "查看图片" }) {
        NetworkImage(thumbnail(url), Modifier.fillMaxSize())
        val item = media ?: com.zailinn.c001apk.core.model.GalleryMedia.fromUrl(url)
        val header = rememberJpegFlags(url)
        if (item.isHdr || header.hdr) CellLabel("HDR", Modifier.align(Alignment.TopStart))
        if (item.isLive || header.live) CellLabel("Live", Modifier.align(Alignment.TopEnd))
        val label = when {
            url.endsWith(".gif") -> "GIF"
            long -> "长图"
            else -> null
        }
        if (label != null) CellLabel(label, Modifier.align(Alignment.BottomEnd))
    }
}

/** Small white label on a dark chip over an image corner. */
@Composable
private fun CellLabel(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        color = Color.White,
        fontSize = TextSizes.Tag,
    )
}
