package com.zailinn.c001apk.ui.common

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.core.upload.UploadImage

/** Emoticon groups of the panel: everyday ones, the 牛牛 set and the 酷币 coins. */
internal fun emojiGroups(): List<Pair<String, List<String>>> {
    val markers = emojiMap.filterValues { it.endsWith(".png") }.keys
    val niuniu = markers.filter { it.startsWith("[牛牛") }
    val coins = markers.filter { it.contains("酷币") || it == "[新币1分]" }
    return listOf("常用" to markers.filter { it !in niuniu && it !in coins }, "牛牛" to niuniu, "酷币" to coins)
}

/** Emoticon picker in three tabs; [onPick] receives the marker, e.g. "[牛牛点赞]". */
@Composable
fun EmojiPanel(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val groups = remember { emojiGroups() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            groups.forEachIndexed { index, (title, _) ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.height(240.dp)) {
            items(groups[tab].second, key = { it }) { key ->
                val bitmap = emojiBitmap(emojiMap.getValue(key))
                Box(Modifier.size(44.dp).clickable { onPick(key) }, contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, key, Modifier.size(30.dp)) }
                }
            }
        }
    }
}

/** Reads a picked image with its size and type for upload. */
fun readUploadImage(context: android.content.Context, uri: Uri, index: Int = 0): UploadImage {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("请选择有效的图片文件")
    val mime = resolver.getType(uri) ?: bounds.outMimeType ?: "image/jpeg"
    val ext = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    return UploadImage("${System.currentTimeMillis()}_$index.$ext", bounds.outWidth, bounds.outHeight, bytes, mime)
}
