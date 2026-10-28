package com.zailinn.c001apk.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.common.HtmlText
import com.zailinn.c001apk.ui.common.NetworkImage
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.formatTime
import com.zailinn.c001apk.ui.common.hapticClick
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appViewModel
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class EventViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    val event = MutableStateFlow<LoadResult<Datum>>(LoadResult.Loading)

    init {
        load()
    }

    fun load() {
        event.value = LoadResult.Loading
        viewModelScope.launch {
            event.value = when (val result = app.repository.eventDetail(id)) {
                is LoadResult.Success -> if (result.value.string("title").isNullOrBlank()) LoadResult.Error("活动数据格式异常，请稍后重试") else result
                LoadResult.Empty -> LoadResult.Error("未找到活动，可能已下架")
                else -> result
            }
        }
    }
}

/** An event (/event/<id>): picture, title, sign-up deadline, rules and the sign-up page. */
@Composable
fun EventScreen(id: String) {
    val navigator = LocalNavigator.current
    val vm = appViewModel(key = "event-$id") { EventViewModel(it, id) }
    val state by vm.event.collectAsStateWithLifecycle()
    Scaffold(topBar = { SimpleTopBar("活动详情") }) { padding ->
        val data = (state as? LoadResult.Success)?.value
        if (data == null) {
            StatusBox(state, vm::load, Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            data.string("pic")?.takeIf { it.isNotBlank() }?.let { pic ->
                NetworkImage(
                    pic, Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Text(data.string("title").orEmpty().trim(), style = MaterialTheme.typography.headlineSmall)
            data.string("time_reg_end")?.toLongOrNull()?.takeIf { it > 0 }?.let { end ->
                Text("报名截止：${formatTime(end * 1000)}", color = MaterialTheme.colorScheme.outline)
            }
            listOf("description", "notice_rule").mapNotNull { data.string(it)?.trim()?.takeIf(String::isNotEmpty) }.forEach {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            data.string("content")?.takeIf { it.isNotBlank() }?.let { HtmlText(it, fontSize = MaterialTheme.typography.bodyLarge.fontSize) }
            data.string("action_url")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                Button(onClick = hapticClick { navigator.openLink(url, data.string("title")) }, Modifier.fillMaxWidth()) { Text("查看报名页面") }
            }
        }
    }
}
