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
        // "验证后查看全部": one prompting attempt, on the first page only. It is
        // one-shot — once verified, the passed session is kept, so later pages and
        // sort changes reach the full list through the non-prompting path below
        // without popping the dialog again, and fall back silently if the server
        // starts gating once more.
        if (verifyReplies && page.page == 1) {
            verifyReplies = false
            val result = app.repository.feedReplies(id, listType, page, fromAuthor)
            if (result !is LoadResult.Error) {
                repliesSource.value = ReplySource.Api
                repliesNotice.value = ""
                return result
            }
            repliesNotice.value = result.message
            // fall through to the non-prompting fallback
        }
        // Keep paging the fallback the first page came from, without re-hitting the reply API.
        if (page.page > 1) {
            when (repliesSource.value) {
                ReplySource.Hot -> return app.repository.hotReplies(id, page)
                ReplySource.Web -> return LoadResult.Empty // the web page has no further pages
                ReplySource.Api -> Unit
            }
        }
        val (result, source) = app.repository.feedRepliesWithoutPrompt(id, listType, page, fromAuthor)
        // Reflect the source on every page: if the server starts gating mid-scroll,
        // the notice and the disabled sort control flip over too.
        repliesSource.value = source
        if (page.page == 1 && source == ReplySource.Web && result is LoadResult.Success &&
            result.value.isEmpty() && repliesNotice.value.isEmpty()
        ) {
            repliesNotice.value = "评论接口需要验证，网页版没有可显示的热门评论"
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
    /** Api while the full sub-reply list shows; a fallback source once the server gates it. */
    val repliesSource = MutableStateFlow(ReplySource.Api)
    /** Why the full sub-reply list could not be loaded after the user asked for it. */
    val repliesNotice = MutableStateFlow("")
    /** The user asked for the full list: request the sub-reply API with its captcha prompt. */
    private var verifyReplies = false

    override val list = PagedList(
        viewModelScope,
        transform = { page, items -> if (page == 1) listOf(origin) + items else items },
        // The sub-reply list is gated by a captcha: without a verification show the
        // embedded preview, filtered by the same blacklist the normal list goes through.
        fallback = { preview() },
    ) { page -> loadReplies(page) }

    private fun preview(): List<Datum> {
        val rules = app.content.filter()
        return listOf(origin) + rules.markAll(origin.replyRows.filter { rules.accepts(it) })
    }

    /**
     * Without a prompt the sub-reply API as before; when it gates, the embedded
     * preview (through [PagedList]'s fallback) and a notice offering verification.
     * Only [loadAllReplies] goes through the prompt, once, on the first page.
     */
    private suspend fun loadReplies(page: Page): LoadResult<List<Datum>> {
        if (verifyReplies && page.page == 1) {
            verifyReplies = false
            val result = app.repository.subRepliesVerified(origin.id.orEmpty(), page)
            if (result !is LoadResult.Error) {
                repliesSource.value = ReplySource.Api
                repliesNotice.value = ""
                return result
            }
            repliesNotice.value = result.message
            // fall through to the non-prompting path, which shows the preview
        }
        // The preview has no further pages; stay on it once the list is gated.
        if (page.page > 1 && repliesSource.value != ReplySource.Api) return LoadResult.Empty
        val (result, source) = app.repository.subReplies(origin.id.orEmpty(), page)
        repliesSource.value = source
        return result
    }

    /** Verifies once and loads the full, pageable sub-reply list. */
    fun loadAllReplies() {
        verifyReplies = true
        repliesNotice.value = ""
        list.refresh()
    }

    fun addReply(reply: Datum, after: Datum?) {
        list.update { items ->
            val index = items.indexOfFirst { it.id == after?.id }
            items.toMutableList().apply { add(if (index >= 0) index + 1 else size, reply) }
        }
    }
}
