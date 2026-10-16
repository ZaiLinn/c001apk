package com.zailinn.c001apk.ui.screens.feed

import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.FeedArticle
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.core.net.ReplySource
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.screens.ContentViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** Hot or web replies are shown because the reply API asked for a captcha. */
    val repliesSource = MutableStateFlow(ReplySource.Api)
    /** Why the full reply list could not be loaded after the user asked for it. */
    val repliesNotice = MutableStateFlow("")
    /** The user asked for the full list: request the reply API with its captcha prompt. */
    private var verifyReplies = false
    /** The first reply page also needs the detail's pinned rows, so it waits for the detail. */
    private val detailReady = CompletableDeferred<Unit>()

    override val list = PagedList(
        viewModelScope,
        transform = { page, items ->
            val head = if (page == 1 && sort.value == ReplySort.Default) listOfNotNull(topReply, replyMe) else emptyList()
            val rules = app.content.filter()
            val headIds = head.mapNotNull { it.id }.toSet()
            (head + items.filter { it.id == null || it.id !in headIds }).map(rules::filterReplyRows)
        },
    ) { page ->
        // Replies are requested together with the detail instead of after it: one round trip, not two.
        coroutineScope {
            val replies = async { loadReplies(page) }
            detailReady.await()
            replies.await()
        }
    }

    /**
     * Without a captcha the reply API as before; when it asks for one, the hot
     * replies (or the web page's) without a prompt. Only [loadAllReplies] goes
     * through the prompt.
     */
    private suspend fun loadReplies(page: Page): LoadResult<List<Datum>> {
        val (listType, fromAuthor) = sort.value.let { it.listType to it.fromAuthor }
        if (page.page > 1 && !verifyReplies) {
            when (repliesSource.value) {
                // Keep paging the list the first page came from, without asking the reply API again.
                ReplySource.Hot -> return app.repository.hotReplies(id, page)
                // The web page has no further pages.
                ReplySource.Web -> return LoadResult.Empty
                ReplySource.Api -> Unit
            }
        }
        if (verifyReplies) {
            val result = app.repository.feedReplies(id, listType, page, fromAuthor)
            if (page.page > 1) return result
            if (result !is LoadResult.Error) {
                repliesSource.value = ReplySource.Api
                repliesNotice.value = ""
                return result
            }
            verifyReplies = false
            repliesNotice.value = result.message
        }
        val (result, source) = app.repository.feedRepliesWithoutPrompt(id, listType, page, fromAuthor)
        if (page.page == 1) {
            repliesSource.value = source
            if (source == ReplySource.Web && result is LoadResult.Success && result.value.isEmpty() && repliesNotice.value.isEmpty()) {
                repliesNotice.value = "评论接口需要验证，网页版没有可显示的热门评论"
            }
        }
        return result
    }

    /** Verifies once and loads the full, pageable reply list. */
    fun loadAllReplies() {
        verifyReplies = true
        repliesNotice.value = ""
        list.refresh()
    }

    init {
        loadDetail()
        list.start()
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
                    // A later reload (verify) refreshes replies; the first one is already loading in parallel.
                    if (detailReady.isCompleted) list.refresh()
                    if (app.settings.current.recordHistory) app.content.addHistory(data)
                } else if (verify) {
                    notice.value = (result as? LoadResult.Error)?.message ?: "账号详情暂不可用，请稍后重试"
                    if (previous is LoadResult.Success) feed.value = previous
                } else {
                    feed.value = result
                }
            } finally {
                loadingDetail.value = false
                detailReady.complete(Unit)
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
        // The sub-reply list asks for a captcha the server does not accept: show the embedded preview.
        fallback = { listOf(origin) + origin.replyRows },
    ) { page -> app.repository.replyToReply(origin.id.orEmpty(), page) }

    fun addReply(reply: Datum, after: Datum?) {
        list.update { items ->
            val index = items.indexOfFirst { it.id == after?.id }
            items.toMutableList().apply { add(if (index >= 0) index + 1 else size, reply) }
        }
    }
}
