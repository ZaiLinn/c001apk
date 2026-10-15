package com.zailinn.c001apk.ui.screens.main

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zailinn.c001apk.AppContainer
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.model.LoadResult
import com.zailinn.c001apk.core.protocol.TokenUtils
import com.zailinn.c001apk.ui.cards.CardShape
import com.zailinn.c001apk.ui.cards.cardColor
import com.zailinn.c001apk.ui.common.RoundedImage
import com.zailinn.c001apk.ui.common.StatusBox
import com.zailinn.c001apk.ui.common.fromToday
import com.zailinn.c001apk.ui.nav.AppRoute
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Immutable
data class InstalledApp(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val updated: Long,
    val icon: ImageBitmap?,
)

/** Installed user apps and their Coolapk updates. */
class AppListViewModel(private val app: AppContainer, private val pm: PackageManager) : ViewModel() {
    val apps = MutableStateFlow<LoadResult<List<InstalledApp>>>(LoadResult.Loading)
    val updates = MutableStateFlow<LoadResult<List<Datum>>?>(null)
    val refreshing = MutableStateFlow(false)

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        refreshing.value = refresh
        viewModelScope.launch {
            apps.value = try {
                val list = withContext(Dispatchers.IO) { readInstalled() }
                if (list.isEmpty()) LoadResult.Empty else LoadResult.Success(list)
            } catch (_: Exception) {
                LoadResult.Error("无法读取已安装应用")
            }
            refreshing.value = false
        }
    }

    @Suppress("DEPRECATION")
    private fun readInstalled(): List<InstalledApp> =
        pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .mapNotNull { info ->
                val pkg = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull() ?: return@mapNotNull null
                val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
                InstalledApp(
                    pm.getApplicationLabel(info).toString(), info.packageName, pkg.versionName.orEmpty(), code,
                    pkg.lastUpdateTime, runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                )
            }
            .sortedByDescending { it.updated }

    fun checkUpdates() {
        val installed = (apps.value as? LoadResult.Success)?.value ?: return
        updates.value = LoadResult.Loading
        viewModelScope.launch {
            val map = JsonObject(installed.associate { it.packageName to JsonPrimitive("0,${it.versionCode},0") })
            updates.value = app.repository.appsUpdate(TokenUtils.base64NoPadding(map.toString()))
        }
    }
}

@Composable
fun AppListContent() {
    val navigator = LocalNavigator.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm = appViewModel(key = "installed-apps") { AppListViewModel(it, context.packageManager) }
    val state by vm.apps.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val apps = (state as? LoadResult.Success)?.value
    if (apps == null) {
        StatusBox(state, onRetry = { vm.load() })
        return
    }
    PullToRefreshBox(refreshing, onRefresh = { vm.load(refresh = true) }, Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (val u = updates) {
                            null -> "共 ${apps.size} 个应用"
                            LoadResult.Loading -> "正在检查更新…"
                            is LoadResult.Success -> "${u.value.size} 个应用可更新"
                            LoadResult.Empty -> "所有应用均为最新"
                            is LoadResult.Error -> u.message
                        },
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = vm::checkUpdates) { Text("检查更新") }
                }
            }
            (updates as? LoadResult.Success)?.value?.let { list ->
                items(list, key = { "update-${it.string("packageName")}" }) { item ->
                    Surface(
                        color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth(),
                        onClick = { item.string("packageName")?.let { navigator.navigate(AppRoute(it)) } },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundedImage(item.logo, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(item.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${item.text("apkversionname")}(${item.text("apkversioncode")}) · ${item.text("apksize")} · ${fromToday(item.long("lastupdate"))}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
            items(apps, key = { it.packageName }) { app ->
                Surface(
                    color = cardColor(), shape = CardShape, modifier = Modifier.fillMaxWidth(),
                    onClick = { navigator.navigate(AppRoute(app.packageName)) },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        app.icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: RoundedImage(null, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${app.packageName}\n${app.versionName}(${app.versionCode})",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}
