package com.zailinn.c001apk.ui.common

import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.model.cursorFirstId
import com.zailinn.c001apk.core.model.cursorLastId
import com.zailinn.c001apk.core.net.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Footer {
    data object Idle : Footer
    data object Loading : Footer
    data object End : Footer
    data class Error(val message: String) : Footer
}

data class ListState(
    val items: List<Datum> = emptyList(),
    /** Loading, Success(Unit), Empty or Error for the first page. */
    val status: LoadResult<Unit> = LoadResult.Loading,
    val footer: Footer = Footer.Idle,
    val refreshing: Boolean = false,
)

/**
 * Cursor pagination shared by every list screen: first/last item ids, page
 * counter, de-duplication by entity id and separate first-page/footer states.
 */
class PagedList(
    private val scope: CoroutineScope,
    /** Entries are de-duplicated by id, so the id alone tells them apart. */
    val unique: Boolean = true,
    /** Adjust a loaded page before it is shown; receives the 1-based page number. */
    private val transform: (page: Int, items: List<Datum>) -> List<Datum> = { _, items -> items },
    /** Replacement for a failed first page, e.g. the parent reply without children. */
    private val fallback: ((LoadResult<List<Datum>>) -> List<Datum>?)? = null,
    private val load: suspend (Page) -> LoadResult<List<Datum>>,
) {
    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state.asStateFlow()
    private var cursor = Page()
    private var end = false
    private var job: Job? = null
    private var started = false
    private data class PagingPosition(val cursor: Page, val end: Boolean, val footer: Footer)
    /** Paging position before a pull-to-refresh, put back if the refresh fails. */
    private var beforeRefresh: PagingPosition? = null
    /** Posted while the first page is loading, keyed by row identity and insertion anchor. */
    private var firstPageInsertions: MutableMap<String, String?>? = null

    /** One-off messages for the screen, like a failed pull-to-refresh that kept the list. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val items: List<Datum> get() = _state.value.items

    fun start() {
        if (started) return
        started = true
        refresh()
    }

    /**
     * Reloads from the first page. With [showIndicator] (a pull or a tap by the
     * user) a failure keeps the shown items instead of replacing them with the error.
     */
    fun refresh(showIndicator: Boolean = false) {
        job?.cancel()
        beforeRefresh = if (showIndicator && _state.value.items.isNotEmpty()) {
            beforeRefresh ?: PagingPosition(cursor, end, _state.value.footer.takeUnless { it == Footer.Loading } ?: Footer.Idle)
        } else null
        cursor = Page()
        end = false
        if (firstPageInsertions == null) firstPageInsertions = linkedMapOf()
        _state.update {
            it.copy(
                refreshing = showIndicator && it.status is LoadResult.Success,
                status = if (it.status is LoadResult.Success) it.status else LoadResult.Loading,
                footer = Footer.Idle,
            )
        }
        job = scope.launch { fetch(isRefresh = true) }
    }

    /** Starts over with an empty list, for a new query whose shown items no longer apply. */
    fun reset() {
        job?.cancel()
        beforeRefresh = null
        cursor = Page()
        end = false
        firstPageInsertions = linkedMapOf()
        _state.value = ListState()
        job = scope.launch { fetch(isRefresh = true) }
    }

    fun loadMore() {
        if (end || job?.isActive == true || _state.value.status !is LoadResult.Success) return
        _state.update { it.copy(footer = Footer.Loading) }
        job = scope.launch { fetch(isRefresh = false) }
    }

    fun retry() {
        if (_state.value.status is LoadResult.Success) {
            end = false
            loadMore()
        } else {
            _state.update { it.copy(status = LoadResult.Loading) }
            refresh()
        }
    }

    fun update(transform: (List<Datum>) -> List<Datum>) = _state.update { it.copy(items = transform(it.items)) }

    fun replace(id: String?, transform: (Datum) -> Datum) = update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun insertLocal(item: Datum, afterId: String? = null) {
        val key = localKey(item)
        firstPageInsertions?.set(key, afterId)
        _state.update { state ->
            state.copy(
                items = insertAfter(state.items.filterNot { localKey(it) == key }, item, afterId),
                status = LoadResult.Success(Unit),
                footer = if (state.status == LoadResult.Empty) Footer.End else state.footer,
            )
        }
    }

    private fun localKey(item: Datum) = item.id ?: item.entityId ?: item.json.toString()

    private fun insertAfter(items: List<Datum>, item: Datum, afterId: String?): List<Datum> {
        val index = if (afterId == null) 0 else items.indexOfFirst { it.id == afterId } + 1
        return items.toMutableList().apply { add(index, item) }
    }

    private suspend fun fetch(isRefresh: Boolean, skipped: Int = 0) {
        val requested = cursor
        val result = load(requested)
        currentCoroutineContext().ensureActive()
        val insertions = if (isRefresh) firstPageInsertions.orEmpty().also { firstPageInsertions = null } else emptyMap()
        val postedRows = if (insertions.isEmpty()) emptyMap() else items.associateBy(::localKey)
        fun withPostedRows(loaded: List<Datum>): List<Datum> =
            insertions.entries.fold(loaded) { rows, (key, anchor) ->
                val posted = postedRows[key]
                if (posted == null || rows.any { localKey(it) == key }) rows else insertAfter(rows, posted, anchor)
            }
        when (result) {
            is LoadResult.Success -> {
                if (isRefresh) beforeRefresh = null
                var items = result.value
                cursor = Page(
                    page = requested.page + 1,
                    firstItem = requested.firstItem ?: items.cursorFirstId,
                    lastItem = items.cursorLastId ?: requested.lastItem,
                )
                if (items.lastOrNull()?.entityTemplate == "noMoreDataCard") end = true
                items = transform(requested.page, items)
                val before = if (isRefresh) 0 else _state.value.items.size
                val merged = if (isRefresh) withPostedRows(items) else _state.value.items + items
                val shown = if (unique) merged.distinctBy { it.entityId ?: it.id ?: it.hashCode().toString() } else merged
                // A page whose items were all blocked or duplicates adds nothing for the
                // scroll trigger to react to, so fetch the next one directly.
                val more = !end && shown.size == before && skipped < MAX_EMPTY_PAGES
                _state.update {
                    it.copy(
                        items = shown,
                        status = LoadResult.Success(Unit),
                        refreshing = false,
                        footer = when {
                            more -> Footer.Loading
                            end -> Footer.End
                            else -> Footer.Idle
                        },
                    )
                }
                if (more) fetch(isRefresh = false, skipped = skipped + 1)
            }
            else -> {
                end = true
                val replacement = if (isRefresh) fallback?.invoke(result) else null
                val kept = if (isRefresh && replacement == null) beforeRefresh else null
                if (isRefresh) beforeRefresh = null
                if (kept != null) {
                    cursor = kept.cursor
                    end = kept.end
                    _state.update { it.copy(refreshing = false, footer = kept.footer) }
                    messages.tryEmit("刷新失败：" + ((result as? LoadResult.Error)?.message ?: "暂无新内容"))
                    return
                }
                val available = withPostedRows(replacement.orEmpty())
                _state.update {
                    when {
                        replacement != null || available.isNotEmpty() -> it.copy(
                            items = available,
                            status = LoadResult.Success(Unit),
                            footer = if (replacement == null && result is LoadResult.Error) Footer.Error(result.message) else Footer.End,
                            refreshing = false,
                        )
                        isRefresh -> it.copy(
                            items = emptyList(),
                            status = if (result is LoadResult.Error) result else LoadResult.Empty,
                            refreshing = false,
                        )
                        else -> it.copy(footer = if (result is LoadResult.Error) Footer.Error(result.message) else Footer.End)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_EMPTY_PAGES = 3
    }
}
