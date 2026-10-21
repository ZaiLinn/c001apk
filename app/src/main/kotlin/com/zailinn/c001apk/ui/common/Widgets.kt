package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.ui.theme.TextSizes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Relative time: 刚刚, N秒/分钟/小时/天前 within 30 days, then a date. */
fun fromToday(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return ""
    val now = System.currentTimeMillis() / 1000
    val ago = now - seconds
    val day = 86400L
    return when {
        ago <= 0L -> "刚刚"
        ago < 60 -> "${ago}秒前"
        ago <= 3600 -> "${ago / 60}分钟前"
        ago <= day -> "${ago / 3600}小时前"
        ago <= day * 2 -> "1天前"
        ago <= day * 3 -> "2天前"
        ago <= 2592000 -> "${ago / day}天前"
        else -> {
            val date = Date(seconds * 1000)
            val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
            val pattern = if (year == Calendar.getInstance().get(Calendar.YEAR)) "MM月dd日" else "yyyy年MM月dd日"
            SimpleDateFormat(pattern, Locale.CHINA).format(date)
        }
    }
}

fun formatTime(millis: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
    SimpleDateFormat(pattern, Locale.CHINA).format(Date(millis))

/** Numbers above ten thousand shown as 万, like the server's own text fields. */
fun formatCount(value: String?): String {
    val number = value?.toLongOrNull() ?: return value ?: "0"
    return if (number >= 10000) String.format(Locale.CHINA, "%.1f万", number / 10000.0) else number.toString()
}

@Composable
fun IconText(icon: ImageVector, text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outline) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(2.dp))
        Text(text, color = color, fontSize = TextSizes.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class SheetAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** Bottom sheet with a list of actions for "more" menus, under an optional [header] line such as a link address. */
@Composable
fun ActionSheet(actions: List<SheetAction>, onDismiss: () -> Unit, header: String? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            header?.let {
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions.forEach { action ->
                ListItem(
                    headlineContent = {
                        Text(action.label, color = if (action.danger) MaterialTheme.colorScheme.error else Color.Unspecified)
                    },
                    leadingContent = { Icon(action.icon, null) },
                    modifier = Modifier.clickable {
                        onDismiss()
                        action.onClick()
                    },
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

val CardSpacing = Arrangement.spacedBy(10.dp)
