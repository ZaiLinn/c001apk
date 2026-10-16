package com.zailinn.c001apk.ui.cards

import com.zailinn.c001apk.core.model.Datum

/** Callbacks a list screen offers to its cards. Absent callbacks hide the action. */
data class ItemActions(
    val onLike: ((Datum) -> Unit)? = null,
    /** Delete a feed, reply or notification; parent is the reply holding a nested reply. */
    val onDelete: ((item: Datum, parent: Datum?) -> Unit)? = null,
    val onBlock: ((uid: String, parent: Datum?) -> Unit)? = null,
    /** Reply to [target]; parent is the top-level reply for nested ones. */
    val onReply: ((target: Datum, parent: Datum?) -> Unit)? = null,
    val onShowReplies: ((Datum) -> Unit)? = null,
    val onOpenMessage: ((Datum) -> Unit)? = null,
    /** Long press on a conversation: pin, unpin or delete it. */
    val onMessageMenu: ((Datum) -> Unit)? = null,
    /** Shown on history and favorites to remove local records. */
    val removable: Boolean = false,
)
