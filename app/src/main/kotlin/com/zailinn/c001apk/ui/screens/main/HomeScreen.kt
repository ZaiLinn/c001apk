package com.zailinn.c001apk.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.zailinn.c001apk.data.SettingKeys
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.settings.HomeTabsDialog
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalAppSettings
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.cards.ItemCard
import com.zailinn.c001apk.ui.common.FillingTabRow
import com.zailinn.c001apk.ui.common.OnCountIncrease
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.SearchPill
import com.zailinn.c001apk.ui.common.PagedListView
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import com.zailinn.c001apk.ui.screens.list.DataListContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    Apps("应用"), Feed("动态"), Hot("热门"), Topic("话题"), Product("数码"), Coolpic("酷图");

    companion object {
        /** Saved tab names in order; unknown names are dropped, and nothing left means every tab. */
        fun visible(names: List<String>): List<HomeTab> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct().ifEmpty { entries }
    }
}

class HomeFeedViewModel(app: AppContainer, tab: HomeTab, url: String?, title: String?) : ContentViewModel(app) {
    override val list = PagedList(viewModelScope) { page ->
        if (tab == HomeTab.Feed) {
            app.repository.homeFeed(page, firstLaunch = 1, installTime = app.settings.current.installTime)
        } else {
            app.repository.dataList(url!!, title!!, page)
        }
    }
}

@Composable
fun HomeScreen(generation: Int, reselect: Int, homeReturns: Int = 0) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val tabs = HomeTab.visible(LocalAppSettings.current.homeTabs)
    val pager = rememberPagerState(initialPage = tabs.indexOf(HomeTab.Feed).coerceAtLeast(0)) { tabs.size }
    val scope = rememberCoroutineScope()
    var composing by remember { mutableStateOf(false) }
    var editingTabs by remember { mutableStateOf(false) }
    // Scroll-to-top-and-refresh requests for the visible list; saved so a rebuilt screen does not repeat one.
    val reselectTab = rememberSaveable { mutableIntStateOf(0) }
    OnCountIncrease(reselect) { reselectTab.intValue++ }
    // "返回首页时自动刷新": each new return scrolls the current list to the top and refreshes it once.
    OnCountIncrease(homeReturns) { reselectTab.intValue++ }
    // The search row slides away while reading and returns on the first scroll up; tabs stay pinned.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { SearchPill("搜索动态、应用、话题和用户", Modifier.padding(end = 16.dp)) { navigator.navigate(SearchRoute()) } },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                    windowInsets = TopAppBarDefaults.windowInsets,
                )
                FillingTabRow(pager.currentPage.coerceAtMost(tabs.lastIndex), tabs.size, divider = {}) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = pager.currentPage == index
                        Tab(
                            selected = selected,
                            modifier = Modifier.onLongPress { editingTabs = true },
                            onClick = {
                                if (selected) reselectTab.intValue++
                                else scope.launch { pager.animateScrollToPage(index) }
                            },
                            text = {
                                Text(tab.label, style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
                            },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (session.isLogin && tabs.getOrNull(pager.currentPage) == HomeTab.Feed) {
                FloatingActionButton(onClick = { composing = true }) { Icon(Icons.Filled.Add, "发布动态") }
            }
        },
    ) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(), key = { tabs[it].name }) { page ->
            val active = pager.currentPage == page
            when (val tab = tabs[page]) {
                HomeTab.Apps -> AppListContent()
                HomeTab.Topic, HomeTab.Product -> HomeTopicContent(tab, generation)
                else -> {
                    val (url, title) = when (tab) {
                        HomeTab.Hot -> "/page?url=V9_HOME_TAB_RANKING" to "热榜"
                        HomeTab.Coolpic -> "/page?url=V11_FIND_COOLPIC" to "酷图"
                        else -> null to null
                    }
                    val vm = appViewModel(key = "home-${tab.name}-$generation") { HomeFeedViewModel(it, tab, url, title) }
                    val listState = rememberLazyListState()
                    OnCountIncrease(reselectTab.intValue) {
                        if (active) {
                            listState.animateScrollToItem(0)
                            vm.list.refresh(showIndicator = true)
                        }
                    }
                    PagedListView(vm.list, listState = listState) { _, item -> ItemCard(item, vm.actions) }
                }
            }
        }
    }
    if (composing) ComposerSheet(onDismiss = { composing = false })
    // Long-pressing any tab opens the same editor as 设置 → 关注与内容 → 首页标签.
    if (editingTabs) {
        val app = appContainer()
        HomeTabsDialog(tabs, onDismiss = { editingTabs = false }) { edited ->
            scope.launch { app.settings.set(SettingKeys.homeTabs, edited.joinToString(",") { it.name }) }
        }
    }
}

/**
 * Long press seen before the tab's own click handling; the release after it is
 * consumed so the tab does not also switch or refresh.
 */
private fun Modifier.onLongPress(action: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(PointerEventPass.Initial)
        }
        if (released != null) return@awaitEachGesture
        action()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

class HomeTopicViewModel(private val app: AppContainer, private val tab: HomeTab) : ViewModel() {
    val categories = MutableStateFlow<LoadResult<List<Datum>>>(LoadResult.Loading)

    init {
        load()
    }

    fun load() {
        categories.value = LoadResult.Loading
        viewModelScope.launch {
            categories.value = when (val result = if (tab == HomeTab.Topic) {
                app.repository.dataListFromUrl("/v6/page/dataList", listOf("url" to "V11_VERTICAL_TOPIC", "title" to "话题", "page" to "1"))
            } else {
                app.repository.dataListFromUrl("/v6/product/categoryList")
            }) {
                is LoadResult.Success -> {
                    val items = if (tab == HomeTab.Topic) result.value.firstOrNull()?.entities.orEmpty() else result.value
                    if (items.isEmpty()) LoadResult.Empty else LoadResult.Success(items)
                }
                else -> result
            }
        }
    }
}

/** Category list on the left, the selected category's content on the right. */
@Composable
private fun HomeTopicContent(tab: HomeTab, generation: Int) {
    val vm = appViewModel(key = "home-topic-${tab.name}-$generation") { HomeTopicViewModel(it, tab) }
    val state by vm.categories.collectAsStateWithLifecycle()
    val items = (state as? LoadResult.Success)?.value
    if (items == null) {
        StatusBox(state, onRetry = vm::load)
        return
    }
    var selected by rememberSaveable { mutableStateOf(if (tab == HomeTab.Topic) 1.coerceAtMost(items.lastIndex) else 0) }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(0.22f).fillMaxHeight()) {
            itemsIndexed(items) { index, item ->
                val active = index == selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable { selected = index },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(40.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
                    Text(
                        item.title.orEmpty(),
                        Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp),
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        Column(Modifier.weight(0.78f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            val item = items[selected.coerceIn(0, items.lastIndex)]
            DataListContent(item.url.orEmpty(), item.title.orEmpty(), key = "home-topic-${item.url}-$generation", onSurface = true)
        }
    }
}
