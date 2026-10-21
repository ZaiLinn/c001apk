package com.zailinn.c001apk.ui.screens.compose

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zailinn.c001apk.core.model.Datum
import com.zailinn.c001apk.core.net.ApiFailure
import com.zailinn.c001apk.core.upload.UploadImage
import com.zailinn.c001apk.ui.common.emojiBitmap
import com.zailinn.c001apk.ui.common.EmojiPanel
import com.zailinn.c001apk.ui.common.readUploadImage
import com.zailinn.c001apk.ui.screens.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** What the composer publishes. */
sealed interface ComposeTarget {
    /** A new feed, optionally inside a topic, product or app. */
    data class NewFeed(val targetType: String? = null, val targetId: String? = null, val title: String? = null) : ComposeTarget

    /** A reply to a feed (`type = feed`) or to another reply (`type = reply`). */
    data class Reply(val id: String, val type: String, val username: String?) : ComposeTarget
}

/**
 * Text, emoticons and up to nine images. Images are uploaded to OSS first, then
 * the post or reply is sent; the legacy image captcha is answered inline.
 */
@Composable
fun ComposerSheet(
    target: ComposeTarget = ComposeTarget.NewFeed(),
    onDismiss: () -> Unit,
    onReplied: (Datum) -> Unit = {},
) {
    val app = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = (target as? ComposeTarget.NewFeed)?.takeIf { it.targetType == "tag" }?.title?.let { "#$it# " }.orEmpty()
    // Saved, so a rotation or a trip to the photo picker keeps what was written.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var checked by rememberSaveable { mutableStateOf(false) }
    var emoji by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var captcha by remember { mutableStateOf<ByteArray?>(null) }
    val images = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<Uri>() }
    var discarding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // A swipe, a tap outside or back asks first when there is something to lose,
    // and waits while a post is being sent: leaving would cancel it halfway.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        when {
            value != SheetValue.Hidden || leaving -> true
            busy != null -> { app.toast("正在发布，请稍候"); false }
            text.text != initial || images.isNotEmpty() -> { discarding = true; false }
            else -> true
        }
    })
    val focus = remember { FocusRequester() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        images.addAll(uris.filter { it !in images }.take(9 - images.size))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun insert(value: String) {
        val start = text.selection.min
        val end = text.selection.max
        val next = text.text.replaceRange(start, end, value)
        text = TextFieldValue(next, TextRange(start + value.length))
    }

    lateinit var publish: () -> Unit
    publish = {
        if (busy == null) scope.launch {
            try {
                var pic = ""
                if (images.isNotEmpty()) {
                    busy = "准备上传图片"
                    val files = withContext(Dispatchers.IO) { images.mapIndexed { i, uri -> readUploadImage(context, uri, i) } }
                    pic = app.uploader.upload(files) { index, total -> busy = "正在上传图片 ${index + 1}/$total" }
                }
                busy = "正在发布"
                val result = when (target) {
                    is ComposeTarget.NewFeed -> app.repository.createFeed(buildList {
                        add("type" to if (target.targetType == "apk") "comment" else "feed")
                        target.targetType?.let { add("targetType" to it) }
                        target.targetId?.let { add("targetId" to it) }
                        add("message" to text.text)
                        add("status" to if (checked) "-1" else "1")
                        add("pic" to pic)
                    })
                    is ComposeTarget.Reply -> app.repository.reply(
                        target.id, target.type,
                        listOf("message" to text.text, "replyAndForward" to if (checked) "1" else "0", "pic" to pic),
                    )
                }
                when {
                    result.needsImageCaptcha -> {
                        result.message?.let(app::toast)
                        captcha = app.repository.captchaImage()
                    }
                    result.message != null -> app.toast(result.message!!)
                    result.data != null -> {
                        if (target is ComposeTarget.NewFeed) app.toast("发布成功")
                        (result.data as? JsonObject)?.let { onReplied(Datum(it)) }
                        onDismiss()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toast(ApiFailure.from(e).message)
            } finally {
                busy = null
            }
        }
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text("放弃编辑？") },
            text = { Text("已输入的内容不会保存。") },
            confirmButton = {
                TextButton(onClick = {
                    discarding = false
                    leaving = true
                    scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text("放弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discarding = false }) { Text("继续编辑") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
                placeholder = {
                    Text(
                        when (target) {
                            is ComposeTarget.Reply -> "回复: ${target.username.orEmpty()}"
                            is ComposeTarget.NewFeed -> "发布" + (target.title?.let { "于: $it" } ?: "动态")
                        },
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    itemsIndexed(images) { index, uri ->
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(uri, null, Modifier.size(72.dp), contentScale = ContentScale.Crop)
                            IconButton(onClick = { images.removeAt(index) }, Modifier.align(Alignment.TopEnd).size(24.dp)) {
                                Icon(Icons.Filled.Close, "移除", tint = Color.White)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { emoji = !emoji }) {
                    Icon(if (emoji) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions, "表情")
                }
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = images.size < 9) { Icon(Icons.Outlined.Image, "图片") }
                Checkbox(checked, { checked = it })
                Text(if (target is ComposeTarget.NewFeed) "仅自己可见" else "回复并转发")
                Spacer(Modifier.weight(1f))
                if (busy != null) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text(busy!!, Modifier.padding(horizontal = 8.dp))
                } else {
                    Button(onClick = publish, enabled = text.text.isNotBlank() || images.isNotEmpty()) { Text("发布") }
                }
            }
            if (emoji) EmojiPanel(::insert)
            Spacer(Modifier.height(8.dp))
        }
    }

    captcha?.let { bytes ->
        var code by remember(bytes) { mutableStateOf("") }
        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { captcha = null },
            title = { Text("验证码") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    bitmap?.let { Image(it, "验证码", Modifier.weight(1f).height(56.dp)) }
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        code, { code = it.filter(Char::isLetterOrDigit).take(4) },
                        Modifier.weight(1f), label = { Text("验证码") }, singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    captcha = null
                    scope.launch {
                        try {
                            val result = app.repository.requestValidate(code)
                            when {
                                result.dataText == "验证通过" -> publish()
                                result.message != null -> {
                                    app.toast(result.message!!)
                                    if (result.message == "请输入正确的图形验证码") captcha = app.repository.captchaImage()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            app.toast(ApiFailure.from(e).message)
                        }
                    }
                }) { Text("验证并继续") }
            },
            dismissButton = { TextButton(onClick = { captcha = null }) { Text("取消") } },
        )
    }
}
