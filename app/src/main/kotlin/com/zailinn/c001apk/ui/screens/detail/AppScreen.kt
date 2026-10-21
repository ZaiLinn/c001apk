package com.zailinn.c001apk.ui.screens.detail

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.ui.LocalSession
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.fromToday
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.nav.SearchRoute
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.compose.ComposeTarget
import com.zailinn.c001apk.ui.screens.compose.ComposerSheet
import com.zailinn.c001apk.ui.screens.list.DataListContent
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import com.zailinn.c001apk.ui.screens.list.TabbedPages
import com.zailinn.c001apk.ui.theme.TextSizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AppDetailViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    val info = MutableStateFlow<LoadResult<Datum>>(LoadResult.Loading)

    init {
        load()
    }

    fun load() {
        info.value = LoadResult.Loading
        viewModelScope.launch { info.value = app.repository.appInfo(id) }
    }

    /** Resolves the download redirect, copies it and hands it to the system downloader. */
    fun download(context: Context, onFallback: (String) -> Unit) {
        val data = (info.value as? LoadResult.Success)?.value ?: return
        viewModelScope.launch {
            try {
                val url = app.repository.appDownloadUrl(data.string("packageName").orEmpty(), data.id.orEmpty(), data.text("apkversioncode"))
                if (url == null) return@launch app.toast("无法获取下载链接，请稍后重试")
                val name = "${data.title}-${data.text("apkversionname")}-${data.text("apkversioncode")}.apk"
                val enqueued = runCatching {
                    val request = DownloadManager.Request(url.toUri())
                        .setTitle(name)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    context.getSystemService(DownloadManager::class.java).enqueue(request)
                }.isSuccess
                if (enqueued) app.toast("已开始下载 $name") else onFallback(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast(ApiFailure.from(e).message)
            }
        }
    }
}

@Composable
fun AppScreen(id: String) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val context = LocalContext.current
    val vm = appViewModel(key = "app-$id") { AppDetailViewModel(it, id) }
    val state by vm.info.collectAsStateWithLifecycle()
    var composing by remember { mutableStateOf(false) }
    val data = (state as? LoadResult.Success)?.value
    val commentsOpen = data?.int("comment_status") == 1
    Scaffold(
        topBar = {
            SimpleTopBar(data?.title ?: id) {
                if (data != null && commentsOpen) {
                    IconButton(onClick = {
                        navigator.navigate(SearchRoute(title = data.title, pageType = "apk", pageParam = data.id))
                    }) { Icon(Icons.Outlined.Search, "搜索") }
                }
            }
        },
        floatingActionButton = {
            if (data != null && session.isLogin && commentsOpen) {
                FloatingActionButton(onClick = { composing = true }) { Icon(Icons.Filled.Add, "评论") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (data == null) {
                StatusBox(state, vm::load)
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundedImage(data.logo, 64.dp, 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(data.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text("版本: ${data.text("apkversionname")}(${data.text("apkversioncode")})", fontSize = TextSizes.Meta)
                    Text(
                        "大小: ${data.text("apksize")}  更新时间: ${data.long("lastupdate")?.let(::fromToday) ?: "未知"}",
                        fontSize = TextSizes.Meta, color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (data.entityType == "apk") {
                    FilledTonalButton(onClick = { vm.download(context) { navigator.launchBrowser(it) } }) { Text("下载") }
                }
            }
            if (!commentsOpen) {
                Text(data.string("commentStatusText") ?: "评论区已关闭", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            } else {
                val base = "/page?url=/feed/apkCommentList?id=${data.id}"
                TabbedPages(listOf("最近回复", "最新发布", "热度排序")) { index ->
                    val url = base + listOf("", "&sort=dateline_desc", "&sort=popular")[index]
                    DataListContent(url, data.title.orEmpty(), key = "app-$id-$index")
                }
            }
        }
    }
    if (composing && data != null) {
        ComposerSheet(
            target = ComposeTarget.NewFeed("apk", (1_000_000_000L + (data.id?.toLongOrNull() ?: 4599L)).toString(), data.title),
            onDismiss = { composing = false },
        )
    }
}
