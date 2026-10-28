package com.zailinn.c001apk.ui.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.ui.common.NetworkImage
import com.zailinn.c001apk.ui.nav.AppNavigator
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.theme.TextSizes

/** The product's versions (`configRows` of its detail) for the 参数 tab's version card. */
val LocalProductConfigs = staticCompositionLocalOf { emptyList<Datum>() }

/** An event topic's 活动详情 section: a heading and plain text with line breaks. */
@Composable
fun SectionTextCard(data: Datum) {
    Surface(color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.title?.takeIf { it.isNotEmpty() }?.let { Text(it, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            data.description?.takeIf { it.isNotEmpty() }?.let { SelectionContainer { Text(it, fontSize = TextSizes.Body) } }
        }
    }
}

/** Full-width banner images, sized by the `@WxH` in their address. */
/**
 * Banners lead somewhere; one this app cannot open (a live room, the official composer at
 * "/feed/writer") is only a dead picture, so it is left out.
 */
fun Datum.openableImages(): List<Datum> =
    entities.filter { val url = it.url.orEmpty(); !url.startsWith("/feed/writer") && AppNavigator.canOpen(url) }

@Composable
fun ImageScaleCard(data: Datum) {
    val navigator = LocalNavigator.current
    val shown = data.openableImages()
    if (shown.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { image ->
            NetworkImage(
                image.pic,
                Modifier.fillMaxWidth().aspectRatio(aspect(image.pic)).clip(CardShape)
                    .clickable { navigator.openLink(image.url.orEmpty(), image.title) },
            )
        }
    }
}

/** 版本配置: each version with its chip, memory and price; 配置对比 opens the official comparison page. */
@Composable
fun ProductConfigCard(data: Datum) {
    val navigator = LocalNavigator.current
    val configs = LocalProductConfigs.current
    if (configs.isEmpty()) return
    Surface(color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(data.title?.ifEmpty { null } ?: "版本配置", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                data.url?.takeIf { it.isNotEmpty() }?.let { url ->
                    TextButton(onClick = { navigator.openLink(url, "配置对比") }) { Text(data.string("subTitle") ?: "配置对比") }
                }
            }
            // Some versions come without a chip; when every other version names the same one, it is theirs too.
            val sharedCpu = configs.mapNotNull { it.string("cpu")?.takeIf(String::isNotBlank) }.distinct().singleOrNull()
            configs.forEachIndexed { index, config ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                val url = config.url.orEmpty()
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = url.isNotEmpty()) { navigator.openLink(url, config.title) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(config.title.orEmpty(), fontSize = TextSizes.Body)
                        val cpu = config.string("cpu")?.takeIf(String::isNotBlank) ?: sharedCpu
                        // The memory is usually the start of the title (12GB+256GB); only a different one is shown.
                        val ram = config.string("ram")?.takeIf { it.isNotBlank() && !config.title.orEmpty().startsWith(it) }
                        listOfNotNull(cpu, ram).joinToString(" · ").takeIf { it.isNotEmpty() }
                            ?.let { Text(it, fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline) }
                    }
                    config.string("price")?.takeIf { it.isNotEmpty() && it != "0" }?.let {
                        Text("¥$it", color = MaterialTheme.colorScheme.primary, fontSize = TextSizes.Body)
                    }
                }
            }
        }
    }
}

private val SIZE = Regex("@(\\d+)x(\\d+)")

private fun aspect(url: String?): Float {
    val (w, h) = url?.let { SIZE.find(it) }?.destructured ?: return 4.5f
    return (w.toFloat() / h.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 4.5f
}
