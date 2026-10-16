package com.zailinn.c001apk.ui.screens.other

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.htmlToPlain
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.launch

/** Feed id from a Coolapk feed link or a bare number. */
internal fun feedIdFrom(text: String): String? {
    val value = text.trim()
    if (Regex("^[0-9]{1,15}$").matches(value)) return value
    return Regex("""^(?:https?://)?(?:www\.|m\.)?coolapk\.com/feed/([0-9]{1,15})(?:[/?#].*)?$""").find(value)?.groupValues?.get(1)
}

fun feedShareLink(id: String) = "https://www.coolapk.com/feed/$id"

/** Picks one of the user's feeds, a local favorite or a pasted link; [onSend] receives the feed link. */
@Composable
fun ChatFeedPicker(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)) {
            Column {
                Text("发送动态", Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("我的动态", "本地收藏", "链接").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                when (tab) {
                    0 -> MyFeeds { confirm = it }
                    1 -> Favorites { confirm = it }
                    else -> LinkInput { confirm = it }
                }
            }
        }
    }
    confirm?.let { id ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("发送动态？") },
            text = { Text(feedShareLink(id)) },
            confirmButton = { Button(onClick = { confirm = null; onSend(feedShareLink(id)) }) { Text("发送") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PickableFeed(id: String, text: String, onPick: (String) -> Unit) {
    TextButton(onClick = { onPick(id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(htmlToPlain(text).ifBlank { "（无文字）" }, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text("动态 $id", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun MyFeeds(onPick: (String) -> Unit) {
    val app = appContainer()
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val feeds = remember { mutableStateListOf<Datum>() }
    var page by remember { mutableIntStateOf(1) }
    var status by remember { mutableStateOf<String?>("加载中…") }
    var more by remember { mutableStateOf(false) }
    fun load() {
        status = "加载中…"
        scope.launch {
            when (val result = app.repository.userFeed(session.uid, Page(page), includeTop = false)) {
                is LoadResult.Success -> {
                    val items = result.value.filter { it.entityType == "feed" && it.id != null }
                    feeds += items.filter { item -> feeds.none { it.id == item.id } }
                    more = items.isNotEmpty()
                    page++
                    status = if (feeds.isEmpty()) "暂无动态，可在“链接”中粘贴动态链接" else null
                }
                is LoadResult.Error -> status = result.message
                else -> {
                    more = false
                    status = if (feeds.isEmpty()) "暂无动态，可在“链接”中粘贴动态链接" else null
                }
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn {
        items(feeds, key = { it.id!! }) { feed -> PickableFeed(feed.id!!, feed.message.orEmpty(), onPick) }
        item {
            when {
                status != null -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status!!, color = MaterialTheme.colorScheme.outline)
                    if (status != "加载中…" && feeds.isEmpty()) TextButton(onClick = ::load) { Text("重试") }
                }
                more -> TextButton(onClick = ::load, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun Favorites(onPick: (String) -> Unit) {
    val app = appContainer()
    val favorites by app.content.favorites.collectAsStateWithLifecycle()
    if (favorites.isEmpty()) {
        Text("暂无本地收藏，可在“链接”中粘贴动态链接", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.outline)
    } else {
        LazyColumn { items(favorites, key = { it.id }) { PickableFeed(it.id, it.message, onPick) } }
    }
}

@Composable
private fun LinkInput(onPick: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(
            text, { text = it; error = null }, Modifier.fillMaxWidth(),
            label = { Text("动态链接或编号") }, placeholder = { Text("https://www.coolapk.com/feed/…") },
            isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = true,
        )
        Button(
            onClick = { feedIdFrom(text)?.let(onPick) ?: run { error = "请输入有效的酷安动态链接或编号" } },
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        ) { Text("下一步") }
    }
}
