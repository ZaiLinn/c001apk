package com.zailinn.c001apk.ui.screens.other

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Feed
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zailinn.c001apk.ui.common.EmojiPanel
import com.zailinn.c001apk.ui.common.readUploadImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.ActionSheet
import com.zailinn.c001apk.ui.common.Avatar
import com.zailinn.c001apk.ui.common.FooterItem
import com.zailinn.c001apk.ui.common.ContentPress
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.LocalContentPress
import com.zailinn.c001apk.ui.common.NetworkImage
import com.zailinn.c001apk.ui.common.PagedList
import com.zailinn.c001apk.ui.common.SheetAction
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.formatTime
import com.zailinn.c001apk.ui.common.htmlToCopy
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.UserRoute
import com.zailinn.c001apk.ui.screens.ContentViewModel
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.screens.runAction
import com.zailinn.c001apk.core.net.ActionResult
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.net.ApiFailureKind
import com.zailinn.c001apk.core.upload.UploadImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class ChatViewModel(app: AppContainer, private val ukey: String, private val uid: String) : ContentViewModel(app) {
    // Pages arrive oldest first; reversed so the newest message sits at the bottom of the reversed list.
    override val list = PagedList(viewModelScope, transform = { _, items -> items.reversed() }) { page ->
        app.repository.messageList("/v6/message/chat", ukey = ukey, page = page)
    }

    /** Image messages first carry an id; the real address comes from a redirect. */
    fun resolveImage(item: Datum) {
        val id = item.id ?: return
        viewModelScope.launch {
            runCatching { app.repository.messageImageUrl(id) }.getOrNull()?.let { url ->
                list.replace(id) { it.with("message_pic", url) }
            }
        }
    }

    fun deleteMessage(item: Datum) {
        viewModelScope.launch {
            val result = app.runAction { app.repository.getAction("/v6/message/delete", listOf("ukey" to ukey, "id" to item.id.orEmpty())) }
                ?: return@launch
            list.update { items -> items.filter { it.id != item.id } }
            result.dataText?.let(app::toast)
        }
    }

    fun send(text: String, onSent: () -> Unit = {}) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val result = app.runAction { app.repository.sendMessage(uid, listOf("message" to text)) } ?: return@launch
            showSent(result)
            onSent()
        }
    }

    private fun showSent(result: ActionResult) {
        val sent = (result.data as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::Datum) }.orEmpty()
        list.update { sent + it }
    }

    /** An image being sent; kept after a failure so it can be retried without uploading again. */
    data class PendingImage(val image: UploadImage, val uploaded: String? = null, val error: String? = null)

    val pendingImage = MutableStateFlow<PendingImage?>(null)

    fun sendImage(image: UploadImage) {
        if (pendingImage.value?.error == null && pendingImage.value != null) return
        pendingImage.value = PendingImage(image)
        deliverImage()
    }

    fun retryImage() {
        val current = pendingImage.value ?: return
        pendingImage.value = current.copy(error = null)
        deliverImage()
    }

    fun discardImage() {
        pendingImage.value = null
    }

    private fun deliverImage() {
        val generation = app.session.generation
        viewModelScope.launch {
            var current = pendingImage.value ?: return@launch
            try {
                val pic = current.uploaded ?: app.uploader.uploadMessageImage(current.image, uid)
                current = current.copy(uploaded = pic)
                val result = app.repository.sendMessage(uid, listOf("message" to "", "message_pic" to pic))
                result.message?.let { throw ApiFailure(ApiFailureKind.Business, it) }
                if (generation != app.session.generation) return@launch
                showSent(result)
                pendingImage.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == app.session.generation) pendingImage.value = current.copy(error = ApiFailure.from(e).message)
            }
        }
    }
}

/** Private conversation, newest message at the bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(ukey: String, uid: String, username: String) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val key = ukey.ifEmpty {
        val a = uid.toLongOrNull() ?: 0
        val b = session.uid.toLongOrNull() ?: 0
        "${minOf(a, b)}_${maxOf(a, b)}"
    }
    val vm = appViewModel(key = "chat-$key") { ChatViewModel(it, key, uid) }
    val state by vm.list.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Datum?>(null) }
    var emoji by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val pending by vm.pendingImage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { readUploadImage(context, uri) } }
                .onSuccess(vm::sendImage)
                .onFailure { navigator.toast(it.message ?: "无法读取图片") }
        }
    }
    LaunchedEffect(Unit) { vm.list.start() }
    val nearTop by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(nearTop, state.items.size) { if (nearTop) vm.list.loadMore() }
    Scaffold(topBar = { SimpleTopBar(username) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (state.status !is LoadResult.Success) {
                StatusBox(state.status, vm.list::retry, Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f), state = listState, reverseLayout = true,
                    contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(state.items, key = { index, it -> "${it.id}-$index" }) { _, item ->
                        if (item.entityTemplate == "time" || item.entityType == "time") {
                            Text(
                                item.dateline?.let { formatTime(it * 1000) } ?: item.title.orEmpty(),
                                Modifier.fillMaxWidth().padding(4.dp),
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        } else {
                            val mine = item.string("fromuid") == session.uid || (item.string("fromuid") == null && item.uid == session.uid)
                            Bubble(item, mine, onAvatar = { item.string("fromuid")?.let { navigator.navigate(UserRoute(it)) } },
                                onLongClick = { menuFor = item }, onImage = { vm.resolveImage(item) })
                        }
                    }
                    item { FooterItem(state.footer, vm.list::retry) }
                }
            }
            pending?.let { image ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        image.error?.let { "图片发送失败：$it" } ?: "正在发送图片…",
                        Modifier.weight(1f),
                        color = if (image.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (image.error != null) {
                        TextButton(onClick = vm::retryImage) { Text("重试") }
                        TextButton(onClick = vm::discardImage) { Text("放弃") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { emoji = !emoji }) {
                        Icon(if (emoji) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions, "表情")
                    }
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("发送消息") }, maxLines = 4)
                    if (text.isBlank()) {
                        IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = pending == null) {
                            Icon(Icons.Outlined.Image, "发送图片")
                        }
                        IconButton(onClick = { sharing = true }) { Icon(Icons.Outlined.Feed, "发送动态") }
                    } else {
                        IconButton(onClick = { vm.send(text) { text = "" } }) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
                    }
                }
                if (emoji) EmojiPanel(onPick = { text += it })
            }
        }
    }
    if (sharing) {
        ChatFeedPicker(onDismiss = { sharing = false }) { link ->
            sharing = false
            vm.send(link)
        }
    }
    menuFor?.let { item ->
        ActionSheet(
            listOf(
                SheetAction(Icons.Outlined.ContentCopy, "复制") { navigator.copy(htmlToCopy(item.message.orEmpty())) },
                SheetAction(Icons.Outlined.Delete, "删除", danger = true) { vm.deleteMessage(item) },
            ),
            onDismiss = { menuFor = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(item: Datum, mine: Boolean, onAvatar: () -> Unit, onLongClick: () -> Unit, onImage: () -> Unit) {
    val navigator = LocalNavigator.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (!mine) {
            Avatar(item.string("fromUserAvatar") ?: item.userAvatar, 36.dp, onAvatar)
            Spacer(Modifier.size(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 280.dp)) {
            if (!item.message.isNullOrEmpty()) {
                val press = remember { ContentPress() }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(onClick = {}, onLongClick = { if (!press.active) onLongClick() })
                        .padding(10.dp),
                ) { CompositionLocalProvider(LocalContentPress provides press) { HtmlText(item.message.orEmpty()) } }
            }
            item.string("message_pic")?.takeIf { it.isNotEmpty() }?.let { pic ->
                if (pic.startsWith("http")) {
                    NetworkImage(
                        pic, Modifier.padding(top = 4.dp).size(160.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { navigator.openImages(listOf(pic.substringBefore('?'))) },
                    )
                } else {
                    LaunchedEffect(item.id) { onImage() }
                    Box(Modifier.padding(top = 4.dp).size(160.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            }
        }
        if (mine) {
            Spacer(Modifier.size(8.dp))
            Avatar(item.string("fromUserAvatar") ?: item.userAvatar, 36.dp, onAvatar)
        }
    }
}
