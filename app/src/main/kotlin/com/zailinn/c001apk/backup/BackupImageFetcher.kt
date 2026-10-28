package com.zailinn.c001apk.backup

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.zailinn.c001apk.core.backup.BACKUP_MEDIA_SCHEME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source

/** Local backup URLs have no network fallback, including when their files are missing. */
internal class BackupImageFetcher(private val value: String, private val store: () -> FeedBackupStore, private val options: Options) : Fetcher {
    override suspend fun fetch(): SourceFetchResult = withContext(Dispatchers.IO) {
        val file = store().localMedia(value) ?: error("备份图片缺失或损坏")
        SourceFetchResult(ImageSource(file.source().buffer(), options.fileSystem), null, DataSource.DISK)
    }
    class Factory(private val store: () -> FeedBackupStore) : Fetcher.Factory<Uri> {
        constructor(store: FeedBackupStore) : this({ store })
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.toString().startsWith(BACKUP_MEDIA_SCHEME)) BackupImageFetcher(data.toString(), store, options) else null
    }
}
