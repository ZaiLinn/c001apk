package com.zailinn.c001apk.ui.screens.search

import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.ui.cards.CardShape
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.cards.cardColor
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The first products and topics for a keyword, shown above the feeds on 综合. */
class SearchOverviewViewModel(private val app: AppContainer, private val keyword: String) : ViewModel() {
    val products = MutableStateFlow<List<Datum>>(emptyList())
    val topics = MutableStateFlow<List<Datum>>(emptyList())

    init { load() }

    fun load() {
        viewModelScope.launch {
            val product = async { first("product") }
            val topic = async { first("feedTopic") }
            products.value = product.await()
            topics.value = topic.await()
        }
    }

    private suspend fun first(type: String): List<Datum> {
        val result = app.repository.search(type, "all", "default", keyword, 0, null, null, Page())
        return (result as? LoadResult.Success)?.value.orEmpty().filter { it.entityType == "product" || it.entityType == "topic" }.take(SECTION_SIZE)
    }

    companion object {
        const val SECTION_SIZE = 5
    }
}

/** The feed sorts shown as chips on 综合, as in the official app. */
internal val overviewSorts = listOf(
    "综合" to ("default" to 0), "实时" to ("dateline" to 0), "热度" to ("hot" to 0),
    "评论" to ("reply" to 0), "精确" to ("" to 1),
)

/**
 * 综合: 数码 and 话题 sections with their first results and a link to the full
 * tab, then the feeds with sort chips, all in one scrolling list.
 */
@Composable
internal fun SearchOverview(
    overview: SearchOverviewViewModel,
    feeds: SearchViewModel,
    onMore: (type: String) -> Unit,
) {
    val products by overview.products.collectAsStateWithLifecycle()
    val topics by overview.topics.collectAsStateWithLifecycle()
    val sort by feeds.sort.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // 数码 and 话题 usually arrive after the 动态 row is on screen, often while the feeds still load.
    // The list keeps that row in place and would push them above the screen, so until the reader
    // drags the list it goes back to the top when they change. Both are saved, so coming back
    // from a result keeps the place.
    var dragged by rememberSaveable { mutableStateOf(false) }
    var shownSections by rememberSaveable { mutableStateOf("0/0") }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true }
    }
    val sections = "${products.size}/${topics.size}"
    LaunchedEffect(sections) {
        if (sections == shownSections) return@LaunchedEffect
        shownSections = sections
        if (!dragged) listState.scrollToItem(0)
    }
    PagedListView(
        feeds.list,
        listState = listState,
        onRefresh = overview::load,
        header = {
            if (products.isNotEmpty()) item(key = "products") { Section("数码", products, "更多相关数码") { onMore("product") } }
            if (topics.isNotEmpty()) item(key = "topics") { Section("话题", topics, "更多相关话题") { onMore("feedTopic") } }
            item(key = "feed-sorts") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("动态", Modifier.padding(start = 4.dp, end = 12.dp), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        overviewSorts.forEach { option ->
                            FilterChip(
                                selected = sort.second == option.second,
                                onClick = hapticClick { feeds.setSort(option) },
                                label = { Text(option.first) },
                            )
                        }
                    }
                }
            }
        },
    ) { _, item -> ItemCard(item, feeds.actions) }
}

@Composable
private fun Section(title: String, items: List<Datum>, more: String, onMore: () -> Unit) {
    val navigator = LocalNavigator.current
    Surface(color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Text(title, Modifier.padding(horizontal = 14.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { navigator.openLink(item.url.orEmpty(), item.title) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundedImage(item.logo, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stats(item), fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                    }
                    val score = item.string("star_average_score")?.takeIf { item.entityType == "product" && (it.toDoubleOrNull() ?: 0.0) > 0 }
                    if (score != null) {
                        Text(score, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            TextButton(onClick = hapticClick(onMore), Modifier.padding(horizontal = 4.dp)) { Text(more) }
        }
    }
}

private fun stats(item: Datum): String = listOfNotNull(
    item.string("hot_num_txt")?.let { "${it}热度" },
    (item.string("feed_comment_num_txt") ?: item.string("commentnum_txt"))?.let { "${it}讨论" },
).joinToString("  ")
