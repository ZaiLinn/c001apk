package com.zailinn.c001apk.ui.theme

import androidx.compose.ui.unit.sp

/**
 * Font sizes by role, so the same kind of text matches across cards and
 * screens. They are sp, so the app font scale applies to them as usual.
 */
object TextSizes {
    /** Feed, reply, forwarded and article body text. */
    val Body = 16.sp

    /** User names above a feed or reply. */
    val Name = 15.sp

    /** Content nested inside a card: sub-replies, hot replies, notices, bios. */
    val Nested = 14.sp

    /** Time, source, device, counts, stats and other secondary lines. */
    val Meta = 13.sp

    /** Small labels: badges, chips, captions under grid icons. */
    val Tag = 12.sp
}
