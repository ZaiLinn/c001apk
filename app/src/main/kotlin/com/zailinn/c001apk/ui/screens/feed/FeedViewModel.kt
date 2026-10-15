package com.zailinn.c001apk.ui.screens.feed

import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.screens.ContentViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class ReplySort(val label: String, val listType: String, val fromAuthor: Int) {
    Default("默认", "lastupdate_desc", 0),
    Latest("最新", "dateline_desc", 0),
    Hot("热门", "popular", 0),
    Author("楼主", "", 1),
}

class FeedViewModel(app: AppContainer, val id: String) : ContentViewModel(app) {
    val feed = MutableStateFlow<LoadResult<Datum>>(LoadResult.Loading)
    val articles = MutableStateFlow<List<FeedArticle>>(emptyList())
    /** Set when only the public representation could be read. */
    val notice = MutableStateFlow("")
    val loadingDetail = MutableStateFlow(false)
    val sort = MutableStateFlow(ReplySort.Default)
    val favorite = MutableStateFlow(app.content.isFavorite(id))
    var topReply: Datum? = null
        private set
    private var replyMe: Datum? = null
    private var repliesStarted = false

    override val list = PagedList(
        viewModelScope,
        transform = { page, items ->
            val head = if (page == 1 && sort.value == ReplySort.Default) listOfNotNull(topReply, replyMe) else emptyList()
            val rules = app.content.filter()
            (head + items).map(rules::filterReplyRows)
        },
    ) { page -> app.repository.feedReplies(id, sort.value.listType, page, sort.value.fromAuthor) }

    init {
        loadDetail()
    }

    fun loadDetail(verify: Boolean = false) {
        if (loadingDetail.value) return
        val previous = feed.value
        loadingDetail.value = true
        if (!verify && previous !is LoadResult.Success) feed.value = LoadResult.Loading
        viewModelScope.launch {
            try {
                val result = app.repository.feedDetail(id, verify)
                if (result is LoadResult.Success) {
                    val data = result.value
                    val parsed = try {
                        FeedArticle.parse(data)
                    } catch (e: Exception) {
                        val error = LoadResult.Error(ApiFailure.from(e).message)
                        if (verify && previous is LoadResult.Success) notice.value = error.message else feed.value = error
                        return@launch
                    }
                    articles.value = parsed
                    topReply = data.list("topReplyRows").firstOrNull()
                    replyMe = data.list("replyMeRows").firstOrNull()
                    feed.value = result
                    notice.value = if (result.isPublic) "正文可正常阅读，当前账号的点赞状态暂不可用。" else ""
                    if (!repliesStarted) {
                        repliesStarted = true
                        list.start()
                    } else {
                        list.refresh()
                    }
                    if (app.settings.current.recordHistory) app.content.addHistory(data)
                } else if (verify) {
                    notice.value = (result as? LoadResult.Error)?.message ?: "账号详情暂不可用，请稍后重试"
                    if (previous is LoadResult.Success) feed.value = previous
                } else {
                    feed.value = result
                }
            } finally {
                loadingDetail.value = false
            }
        }
    }

    fun setSort(value: ReplySort) {
        if (sort.value == value) return
        sort.value = value
        list.refresh()
    }

    override fun onLiked(item: Datum) {
        val current = feed.value
        if (item.id == id && item.entityType == "feed" && current is LoadResult.Success) {
            feed.value = current.copy(value = item)
        } else {
            super.onLiked(item)
        }
    }

    fun toggleFavorite() {
        val data = (feed.value as? LoadResult.Success)?.value ?: return
        viewModelScope.launch {
            val added = app.content.toggleFavorite(data)
            favorite.value = added
            app.toast(if (added) "已收藏" else "已取消收藏")
        }
    }

    fun blockAuthor() {
        val uid = (feed.value as? LoadResult.Success)?.value?.uid ?: return
        viewModelScope.launch { if (app.content.block(uid)) app.toast("已屏蔽") else app.toast("已存在") }
    }

    /** Shows a newly posted reply without reloading the list. */
    fun addReply(reply: Datum, target: Datum?, parent: Datum?) {
        if (target == null) {
            list.update { items ->
                items.toMutableList().apply { add(if (topReply == null) 0 else minOf(1, size), reply) }
            }
        } else {
            val holder = parent?.id ?: target.id
            list.replace(holder) { it.withList("replyRows", it.replyRows + reply) }
        }
    }
}

/** Replies of one reply, with the original reply first. */
class ReplyThreadViewModel(app: AppContainer, private val origin: Datum) : ContentViewModel(app) {
    override val list = PagedList(
        viewModelScope,
        transform = { page, items -> if (page == 1) listOf(origin) + items else items },
        fallback = { listOf(origin) },
    ) { page -> app.repository.replyToReply(origin.id.orEmpty(), page) }

    fun addReply(reply: Datum, after: Datum?) {
        list.update { items ->
            val index = items.indexOfFirst { it.id == after?.id }
            items.toMutableList().apply { add(if (index >= 0) index + 1 else size, reply) }
        }
    }
}
