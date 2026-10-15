package com.zailinn.c001apk.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.C001apkApplication
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.ActionResult
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.ui.cards.ItemActions
import com.zailinn.c001apk.ui.common.PagedList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The app's dependencies; tests provide their own container. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer?> { null }

@Composable
fun appContainer(): AppContainer =
    LocalAppContainer.current ?: (LocalContext.current.applicationContext as C001apkApplication).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val app = appContainer()
    return viewModel(key = key) { create(app) }
}

/** Runs a server action and reports its message or failure as a toast. */
suspend fun AppContainer.runAction(block: suspend () -> ActionResult): ActionResult? = try {
    val result = block()
    result.message?.let { toast(it); null } ?: result
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    toast(ApiFailure.from(e).message)
    null
}

/** Base for list screens: paging plus the like, delete and block actions of the Flutter CommonController. */
abstract class ContentViewModel(val app: AppContainer) : ViewModel() {
    abstract val list: PagedList

    open val actions: ItemActions
        get() = ItemActions(onLike = ::like, onDelete = ::delete, onBlock = ::block)

    open fun onLiked(item: Datum) = list.replace(item.id) { item }

    fun like(item: Datum) {
        if (!app.session.isLogin) return app.toast("登录后才能点赞")
        viewModelScope.launch {
            val updated = toggleLike(item) ?: return@launch
            onLiked(updated)
        }
    }

    protected suspend fun toggleLike(item: Datum): Datum? {
        val isFeed = item.entityType == "feed"
        val liked = item.isLiked
        val url = when {
            isFeed && liked -> "/v6/feed/unlike"
            isFeed -> "/v6/feed/like"
            liked -> "/v6/feed/unLikeReply"
            else -> "/v6/feed/likeReply"
        }
        val result = app.runAction { app.repository.postAction(url, id = item.id) } ?: return null
        val count = if (isFeed) (result.dataObject?.get("count") as? JsonPrimitive)?.content else result.dataText
        return item.with("likenum", count ?: item.string("likenum"))
            .with("userAction", JsonObject(mapOf("like" to JsonPrimitive(if (liked) 0 else 1))))
    }

    open fun delete(item: Datum, parent: Datum?) {
        val url = when (item.entityType) {
            "feed" -> "/v6/feed/deleteFeed"
            "feed_reply" -> "/v6/feed/deleteReply"
            "notification" -> "/v6/notification/delete"
            else -> return
        }
        viewModelScope.launch {
            val result = app.runAction { app.repository.postAction(url, id = item.id) } ?: return@launch
            removeItem(item, parent)
            result.dataText?.let(app::toast)
        }
    }

    protected fun removeItem(item: Datum, parent: Datum?) {
        if (parent != null) {
            list.replace(parent.id) { it.withList("replyRows", it.replyRows.filter { r -> r.id != item.id }) }
        } else {
            list.update { items -> items.filter { it.id != item.id } }
        }
    }

    open fun block(uid: String, parent: Datum?) {
        viewModelScope.launch {
            if (!app.content.block(uid)) app.toast("已存在")
            if (parent != null) {
                list.replace(parent.id) { it.withList("replyRows", it.replyRows.filter { r -> r.uid != uid }) }
            } else {
                list.update { items -> items.filter { it.uid != uid } }
            }
        }
    }
}

/** A plain list driven by a single loader. */
class SimpleListViewModel(app: AppContainer, unique: Boolean = true, loader: suspend AppContainer.(com.zailinn.c001apk.core.net.Page) -> com.zailinn.c001apk.core.model.LoadResult<List<Datum>>) :
    ContentViewModel(app) {
    override val list = PagedList(viewModelScope, unique = unique) { page -> app.loader(page) }
}
