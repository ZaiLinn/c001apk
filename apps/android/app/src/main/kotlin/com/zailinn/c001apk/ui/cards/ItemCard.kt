package com.zailinn.c001apk.ui.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zailinn.c001apk.core.model.Datum

/** Picks the card for an entity, mirroring the Flutter itemCard switch. */
@Composable
fun ItemCard(data: Datum, actions: ItemActions = ItemActions(), onSurface: Boolean = false) {
    when (data.entityType) {
        "card" -> when (data.entityTemplate) {
            "imageCarouselCard_1" -> CarouselCard(data.entities)
            "iconLinkGridCard" -> IconLinkGridCard(data.entities)
            "iconMiniScrollCard" -> IconMiniScrollCard(data.entities)
            "iconMiniGridCard" -> IconMiniGridCard(data)
            "imageSquareScrollCard" -> ImageSquareScrollCard(data.entities)
            "titleCard" -> TitleCard(data.title.orEmpty(), data.url.orEmpty())
            "iconScrollCard" -> IconScrollCard(data)
            "imageTextScrollCard" -> ImageTextScrollCard(data)
            "noMoreDataCard" -> TextCard(data.title.orEmpty(), end = true)
            "messageCard" -> TextCard(data.description.orEmpty())
            "refreshCard" -> TextCard(data.title.orEmpty())
            else -> Unsupported()
        }
        "feed" -> FeedCard(data, actions)
        "feed_reply" -> if (data.has("likeUserInfo")) LikeCard(data) else ReplyCard(data, actions)
        "apk", "product", "user", "topic", "contacts", "recentHistory", "productBrand" -> EntityCard(data, onSurface)
        "notification" -> NotificationCard(data, actions)
        "message" -> MessageCard(data, actions)
        "collection" -> CollectionCard(data)
        else -> when (data.entityTemplate) {
            "noMoreDataCard" -> TextCard(data.title.orEmpty(), end = true)
            else -> Unsupported()
        }
    }
}

@Composable
private fun Unsupported() {
    ListItem(headlineContent = { Text("暂不支持此内容") }, modifier = Modifier.fillMaxWidth().padding(0.dp))
}
