package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.clickable
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

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Footer.Idle, Footer.Loading -> LoadingBox()
        Footer.End -> MessageBox("没有更多了")
        is Footer.Error -> MessageBox(footer.message, onClick = onRetry)
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
    header: LazyListScope.() -> Unit = {},
    item: @Composable (index: Int, datum: Datum) -> Unit,
) {
    val state by list.state.collectAsStateWithLifecycle()
    LaunchedEffect(list) { list.start() }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { list.refresh(showIndicator = true) },
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
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) list.loadMore() }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing.dp),
        ) {
            header()
            itemsIndexed(
                state.items,
                key = { index, it -> "${it.entityType}-${it.entityId ?: it.id}-$index" },
                // Lets the list reuse a feed card's layout for the next feed card.
                contentType = { _, it -> "${it.entityType}/${it.entityTemplate}" },
            ) { index, datum ->
                item(index, datum)
            }
            item(key = "footer") { FooterItem(state.footer, list::retry) }
        }
    }
}
