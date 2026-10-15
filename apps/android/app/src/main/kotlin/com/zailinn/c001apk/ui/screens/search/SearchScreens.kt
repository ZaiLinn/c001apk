package com.zailinn.c001apk.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchResultRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.list.BackButton
import com.zailinn.c001apk.ui.screens.list.TabbedPages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private fun scopeLabel(pageType: String?) = when (pageType) {
    "user" -> "用户"
    "apk" -> "应用"
    "tag" -> "话题"
    "product_phone" -> "数码"
    else -> "范围"
}

@Composable
fun SearchScreen(title: String?, pageType: String?, pageParam: String?) {
    val navigator = LocalNavigator.current
    val app = appContainer()
    val history by app.content.searchHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val search = { keyword: String ->
        val value = keyword.trim()
        if (value.isNotEmpty()) {
            scope.launch { app.content.addSearch(value) }
            navigator.navigate(SearchResultRoute(value, title, pageType, pageParam))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    TextField(
                        text, { text = it },
                        Modifier.fillMaxWidth().focusRequester(focus),
                        placeholder = { Text(if (title != null) "在${scopeLabel(pageType)}「$title」中搜索" else "搜索") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search(text) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Clear, "清除") }
                    IconButton(onClick = { search(text) }) { Icon(Icons.Outlined.Search, "搜索") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            if (history.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { scope.launch { app.content.clearSearch() } }) { Text("清空") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { keyword ->
                        InputChip(
                            selected = false,
                            onClick = { text = keyword; search(keyword) },
                            label = { Text(keyword, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Clear, "删除",
                                    Modifier.clickable { scope.launch { app.content.removeSearch(keyword) } },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private val contentTypes = listOf("动态" to "feed", "应用" to "apk", "游戏" to "game", "话题" to "feedTopic", "数码" to "product", "用户" to "user")
private val feedTypes = listOf("全部" to "all", "动态" to "feed", "文章" to "feedArticle", "酷图" to "picture", "评论" to "comment",
    "评分" to "rating", "回答" to "answer", "问答" to "question", "投票" to "vote")
private val sortTypes = listOf("最新发布" to ("dateline" to 0), "默认" to ("none" to 0), "热门" to ("hot" to 0),
    "最新回复" to ("reply" to 0), "精确匹配" to ("" to 1))

class SearchViewModel(
    app: AppContainer,
    private val type: String,
    private val keyword: String,
    private val pageType: String?,
    private val pageParam: String?,
) : ContentViewModel(app) {
    val feedType = MutableStateFlow("all")
    val sort = MutableStateFlow("default" to ("default" to 0))

    override val list = PagedList(viewModelScope) { page: Page ->
        val (sortValue, strict) = sort.value.second
        app.repository.search(type, feedType.value, sortValue, keyword, strict, pageType, pageParam, page)
    }

    fun setFeedType(value: String) { feedType.value = value; list.refresh() }
    fun setSort(value: Pair<String, Pair<String, Int>>) { sort.value = value; list.refresh() }
}

@Composable
fun SearchResultScreen(keyword: String, title: String?, pageType: String?, pageParam: String?) {
    val navigator = LocalNavigator.current
    val types = if (title == null) contentTypes else contentTypes.take(1)
    var menu by remember { mutableStateOf<String?>(null) }
    val feedVm = appViewModel(key = "search-feed-$keyword-$pageType-$pageParam") { SearchViewModel(it, "feed", keyword, pageType, pageParam) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    Column(Modifier.padding(end = 8.dp)) {
                        Text(keyword, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = androidx.compose.ui.Modifier)
                        if (title != null) Text("${scopeLabel(pageType)}：$title", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = "type" }) { Icon(Icons.Outlined.FilterList, "内容类型") }
                    IconButton(onClick = { menu = "sort" }) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序方式") }
                    DropdownMenu(menu == "type", { menu = null }) {
                        feedTypes.forEach { (label, value) ->
                            DropdownMenuItem({ Text(label) }, { menu = null; feedVm.setFeedType(value) })
                        }
                    }
                    DropdownMenu(menu == "sort", { menu = null }) {
                        sortTypes.forEach { item ->
                            DropdownMenuItem({ Text(item.first) }, { menu = null; feedVm.setSort(item) })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (types.size == 1) {
                PagedListView(feedVm.list) { _, item -> ItemCard(item, feedVm.actions) }
            } else {
                TabbedPages(types.map { it.first }, scrollable = true) { index ->
                    val vm = if (index == 0) feedVm else appViewModel(key = "search-${types[index].second}-$keyword") {
                        SearchViewModel(it, types[index].second, keyword, pageType, pageParam)
                    }
                    PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
                }
            }
        }
    }
}
