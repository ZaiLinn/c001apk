package com.zailinn.c001apk.data

import com.zailinn.c001apk.core.backup.BackupImageQuality
import com.zailinn.c001apk.core.backup.BackupOptions

/** The same opt-in reply rule applies to manual, batch and automatic backups. */
fun AppSettings.backupOptions(images: BackupImageQuality = BackupImageQuality.Original) = BackupOptions(
    images, archiveIncludeReplies, if (archiveIncludeReplies) archiveCommentLimit else 0,
    archiveIncludeReplies && archiveIncludeSubReplies,
)
