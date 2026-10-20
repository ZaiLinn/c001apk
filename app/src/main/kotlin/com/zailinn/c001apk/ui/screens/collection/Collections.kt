package com.zailinn.c001apk.ui.screens.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.ui.screens.appContainer
import com.zailinn.c001apk.ui.screens.runAction
import kotlinx.coroutines.launch

/** Title, description and visibility of a new or edited collection. */
@Composable
fun CollectionEditDialog(
    heading: String,
    initial: Datum? = null,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, open: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var open by remember { mutableStateOf(initial?.int("is_open") == 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(description, { description = it }, Modifier.padding(top = 8.dp), label = { Text("简介（可选）") }, maxLines = 3)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                    Text("公开收藏夹", Modifier.weight(1f))
                    Switch(open, { open = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onSave(title.trim(), description.trim(), open) }, enabled = title.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** List rows on the sheet's own background. */
@Composable
private fun sheetItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Creates a collection; returns its id, or null after reporting the failure. */
suspend fun AppContainer.createCollection(title: String, description: String, open: Boolean): String? {
    val result = runAction { repository.createCollection(title, description, open) } ?: return null
    val id = result.dataObject?.let { Datum(it).id }
    toast("已新建收藏夹「$title」")
    return id
}

/**
 * The account's collections with the ones holding [feedId] ticked; ticking adds
 * the feed and unticking takes it out, one request per change.
 */
@Composable
fun CollectionPickerSheet(feedId: String, onDismiss: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadResult<List<Datum>>>(LoadResult.Loading) }
    var busy by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    suspend fun load() {
        state = app.repository.collections(feedId)
    }
    LaunchedEffect(feedId) { load() }

    fun toggle(item: Datum) {
        val id = item.id ?: return
        if (busy != null) return
        val collected = item.int("isBeCollected") == 1
        busy = id
        scope.launch {
            try {
                val result = app.runAction { app.repository.setCollected(id, feedId, !collected) } ?: return@launch
                val now = result.dataObject?.let { Datum(it).int("collect") } ?: if (collected) 0 else 1
                state = (state as? LoadResult.Success)?.let { current ->
                    LoadResult.Success(current.value.map { if (it.id == id) it.with("isBeCollected", now) else it })
                } ?: state
                app.toast(if (now == 1) "已收藏到「${item.title.orEmpty()}」" else "已从「${item.title.orEmpty()}」移除")
            } finally {
                busy = null
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text("收藏到", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
            when (val current = state) {
                is LoadResult.Success -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(current.value, key = { it.id.orEmpty() }) { item ->
                        ListItem(
                            headlineContent = { Text(item.title.orEmpty()) },
                            supportingContent = {
                                Text(listOf(item.string("is_open_title").orEmpty(), "${item.int("item_num") ?: 0}个内容").filter { it.isNotEmpty() }.joinToString("  "))
                            },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            trailingContent = {
                                if (busy == item.id) {
                                    CircularProgressIndicator(Modifier.padding(12.dp).heightIn(max = 24.dp), strokeWidth = 2.dp)
                                } else {
                                    Checkbox(item.int("isBeCollected") == 1, { toggle(item) })
                                }
                            },
                            modifier = Modifier.clickable { toggle(item) },
                            colors = sheetItemColors(),
                        )
                    }
                }
                LoadResult.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> ListItem(
                    headlineContent = { Text((current as? LoadResult.Error)?.message ?: "暂无收藏夹") },
                    supportingContent = { Text("点按重试") },
                    modifier = Modifier.clickable { state = LoadResult.Loading; scope.launch { load() } },
                    colors = sheetItemColors(),
                )
            }
            ListItem(
                headlineContent = { Text("新建收藏夹") },
                leadingContent = { Icon(Icons.Outlined.Add, null) },
                modifier = Modifier.clickable { creating = true },
                colors = sheetItemColors(),
            )
        }
    }
    if (creating) {
        CollectionEditDialog("新建收藏夹", onDismiss = { creating = false }) { title, description, open ->
            scope.launch {
                val id = app.createCollection(title, description, open) ?: return@launch
                // The new collection takes the feed right away, as in the official app.
                app.runAction { app.repository.setCollected(id, feedId, true) }
                load()
            }
        }
    }
}
