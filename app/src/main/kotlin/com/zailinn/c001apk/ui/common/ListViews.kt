package com.zailinn.c001apk.ui.common

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.zailinn.c001apk.core.model.FoldReason
import com.zailinn.c001apk.core.model.foldReason
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

/** A centered message; with [onClick] it says so under the text. */
@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Text("点击重试", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/** First-page state (loading, empty, error) shown in place of a list. */
@Composable
fun StatusBox(status: LoadResult<*>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        when (status) {
            LoadResult.Loading -> LoadingBox()
            LoadResult.Empty -> MessageBox("暂无内容", onClick = onRetry)
            is LoadResult.Error -> MessageBox(status.message, onClick = onRetry)
            is LoadResult.Success -> Unit
        }
    }
}

@Composable
fun FooterItem(footer: Footer, onRetry: () -> Unit) {
    when (footer) {
        // Idle: more pages exist but none is loading; the next one starts near the end.
        Footer.Idle -> Spacer(Modifier.fillMaxWidth())
        Footer.Loading -> LoadingBox()
        Footer.End -> MessageBox("没有更多了")
        is Footer.Error -> MessageBox(footer.message, onClick = onRetry)
    }
}

/** A sensitive-topic item shown as one line until it is tapped open. */
@Composable
fun FoldedItem(datum: Datum, reason: FoldReason, inset: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(datum.entityType, datum.id) { mutableStateOf(false) }
    if (open) return content()
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = 12.dp, vertical = 6.dp) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.VisibilityOff, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append("已折叠 · ").append(reason.title)
                    if (reason.hit.isNotEmpty()) append(" · ").append(reason.hit)
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("展开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Pull-to-refresh list with automatic paging. [header] items stay above the
 * entries; [item] renders one entry.
 */
@Composable
fun PagedListView(
    list: PagedList,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(10.dp),
    spacing: Int = 10,
    /** Also run on pull-to-refresh, for what the screen shows above the list. */
    onRefresh: () -> Unit = {},
    header: LazyListScope.() -> Unit = {},
    item: @Composable (index: Int, datum: Datum) -> Unit,
) {
    val state by list.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(list) { list.start() }
    LaunchedEffect(list) { list.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = {
            list.refresh(showIndicator = true)
            onRefresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        if (state.status !is LoadResult.Success) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
                header()
                item { StatusBox(state.status, list::retry, Modifier.fillParentMaxHeight(0.6f)) }
            }
            return@PullToRefreshBox
        }
        val nearEnd by remember(listState) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                // Ask for the next page well before the end, so it usually arrives before it is needed.
                last >= listState.layoutInfo.totalItemsCount - PREFETCH_DISTANCE
            }
        }
        LaunchedEffect(nearEnd, state.items.size, state.footer) { if (nearEnd) list.loadMore() }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            header()
            itemsIndexed(
                state.items,
                // The id alone where the list is de-duplicated: an entry keeps its key, and so its
                // state and the scroll anchor, when entries are added or removed above it.
                key = { index, it ->
                    val id = it.entityId ?: it.id
                    if (list.unique && id != null) "${it.entityType}-$id" else "${it.entityType}-$id-$index"
                },
                // Lets the list reuse a feed card's layout for the next feed card.
                contentType = { _, it -> "${it.entityType}/${it.entityTemplate}" },
            ) { index, datum ->
                val reason = datum.foldReason
                if (reason == null) {
                    item(index, datum)
                } else {
                    FoldedItem(datum, reason, inset = spacing == 0) { item(index, datum) }
                }
            }
            item(key = "footer") { FooterItem(state.footer, list::retry) }
        }
    }
}

private const val PREFETCH_DISTANCE = 8
