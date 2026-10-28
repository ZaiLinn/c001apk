package com.zailinn.c001apk.ui.screens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.net.Page
import com.zailinn.c001apk.ui.common.AppAlertDialog
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.delay

/**
 * Picks a topic for a new feed: topics followed on this device first, then the
 * server's topic search once typing pauses, or the typed name as it is.
 */
@Composable
fun TopicPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = appContainer()
    val local by app.content.localFollowTopics.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<String>()) }
    val name = query.trim().trim('#').trim()
    LaunchedEffect(name) {
        found = emptyList()
        if (name.isEmpty()) return@LaunchedEffect
        // One request after typing stops, not one per character.
        delay(SEARCH_DELAY)
        val result = app.repository.search("feedTopic", "all", "default", name, 0, null, null, Page())
        found = (result as? LoadResult.Success)?.value.orEmpty()
            .filter { it.entityType == "topic" }.mapNotNull { it.title?.takeIf(String::isNotBlank) }
    }
    val suggestions = (local.map { it.title }.filter { name.isEmpty() || name in it } + found).distinct()
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加话题") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索或输入话题") })
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    if (name.isNotEmpty() && name !in suggestions) {
                        item { Suggestion("使用 #$name#") { onPick(name) } }
                    }
                    items(suggestions) { title -> Suggestion("#$title#") { onPick(title) } }
                    if (suggestions.isEmpty() && name.isEmpty()) {
                        item {
                            Text(
                                "输入名称搜索话题；本地关注的话题会显示在这里。",
                                Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(text) }, modifier = Modifier.clickable(onClick = onClick))
}

private const val SEARCH_DELAY = 600L
