package com.zailinn.c001apk.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.AppDropdownMenu
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.common.hapticClick
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val search = { keyword: String ->
        val value = keyword.trim()
        if (value.isNotEmpty()) {
            scope.launch { app.content.addSearch(value) }
            // The results open without the keyboard; it would otherwise stay up over them.
            focusManager.clearFocus()
            keyboard?.hide()
            navigator.navigate(SearchResultRoute(value, title, pageType, pageParam))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    SearchField(
                        text, { text = it }, { search(text) },
                        if (title != null) "在${scopeLabel(pageType)}「$title」中搜索" else "搜索",
                        Modifier.focusRequester(focus),
                    )
                },
                actions = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Clear, "清除") }
                    IconButton(onClick = { search(text) }) { Icon(Icons.Outlined.Search, "搜索") }
                },
            )
        },
    ) { padding ->
        // Scrolls: up to fifty keywords do not fit a small screen.
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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

/** Tabs of a global search; 综合 ("all") is built here from the others. */
private val contentTypes = listOf("综合" to "all", "数码" to "product", "动态" to "feed", "话题" to "feedTopic", "用户" to "user", "应用" to "apk", "游戏" to "game")
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
    val app = appContainer()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The box stays editable: a new keyword searches again on this page.
    var text by rememberSaveable { mutableStateOf(keyword) }
    var active by rememberSaveable { mutableStateOf(keyword) }
    val search = {
        val value = text.trim()
        if (value.isNotEmpty()) {
            scope.launch { app.content.addSearch(value) }
            active = value
            text = value
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val feedVm = appViewModel(key = "search-feed-$active-$pageType-$pageParam") { SearchViewModel(it, "feed", active, pageType, pageParam) }
    // 综合 keeps its own feed list, so its sort chips and the 动态 tab do not move each other.
    val overviewFeeds = if (types.size > 1) appViewModel(key = "search-overview-feed-$active") { SearchViewModel(it, "feed", active, null, null) } else null
    val pager = rememberPagerState { types.size }
    // The menus filter what is shown: 综合's feeds there, the 动态 tab elsewhere.
    val menuVm = if (overviewFeeds != null && pager.currentPage == 0) overviewFeeds else feedVm
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    SearchField(
                        text, { text = it }, search, "搜索",
                        prefix = title?.let { "${scopeLabel(pageType)}「$it」 " },
                    )
                },
                actions = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Outlined.Clear, "清除") }
                    val feedsShown = types.size == 1 || types[pager.currentPage].second in setOf("all", "feed")
                    if (feedsShown) {
                        IconButton(onClick = hapticClick { menu = "type" }) { Icon(Icons.Outlined.FilterList, "内容类型") }
                        IconButton(onClick = hapticClick { menu = "sort" }) { Icon(Icons.AutoMirrored.Outlined.Sort, "排序方式") }
                    }
                    AppDropdownMenu(menu == "type", { menu = null }) {
                        feedTypes.forEach { (label, value) ->
                            DropdownMenuItem({ Text(label) }, hapticClick { menu = null; menuVm.setFeedType(value) })
                        }
                    }
                    AppDropdownMenu(menu == "sort", { menu = null }) {
                        sortTypes.forEach { item ->
                            DropdownMenuItem({ Text(item.first) }, { menu = null; menuVm.setSort(item) })
                        }
                    }
                },
            )
        },
    ) { padding ->
        // A new keyword starts every list at its top instead of where the last search was scrolled to.
        key(active) { Column(Modifier.padding(padding).fillMaxSize()) {
            if (overviewFeeds == null) {
                PagedListView(feedVm.list) { _, item -> ItemCard(item, feedVm.actions) }
            } else {
                TabbedPages(types.map { it.first }, pager = pager) { index ->
                    when (val type = types[index].second) {
                        "all" -> {
                            val overview = appViewModel(key = "search-overview-$active") { SearchOverviewViewModel(it, active) }
                            SearchOverview(overview, overviewFeeds) { more ->
                                scope.launch { pager.animateScrollToPage(types.indexOfFirst { it.second == more }) }
                            }
                        }
                        "feed" -> PagedListView(feedVm.list) { _, item -> ItemCard(item, feedVm.actions) }
                        else -> {
                            val vm = appViewModel(key = "search-$type-$active") { SearchViewModel(it, type, active, pageType, pageParam) }
                            PagedListView(vm.list) { _, item -> ItemCard(item, vm.actions) }
                        }
                    }
                }
            }
        } }
    }
}

/** The search box of the search page and the result page: 16sp like the home search pill. */
@Composable
private fun SearchField(
    text: String,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    TextField(
        text, onChange,
        modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        prefix = prefix?.let { { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
