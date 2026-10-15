package com.zailinn.c001apk.ui.screens.other

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zailinn.c001apk.ui.nav.LocalNavigator
import com.zailinn.c001apk.ui.screens.list.SimpleTopBar

/** Free text selection for a feed or reply. */
@Composable
fun CopyScreen(text: String) {
    val navigator = LocalNavigator.current
    Scaffold(
        topBar = { SimpleTopBar("自由复制") { IconButton(onClick = { navigator.copy(text) }) { Icon(Icons.Outlined.ContentCopy, "复制全部") } } },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(text, fontSize = 17.sp, lineHeight = 26.sp)
        }
    }
}
