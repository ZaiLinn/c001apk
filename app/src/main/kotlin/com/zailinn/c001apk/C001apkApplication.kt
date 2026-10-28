package com.zailinn.c001apk

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import android.os.Build

class C001apkApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Preview builds are not debuggable; this still lets chrome://inspect see their web pages.
        if (BuildConfig.BUILD_TYPE == "preview") android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(com.zailinn.c001apk.backup.BackupImageFetcher.Factory { container.backups })
            add(OkHttpNetworkFetcherFactory(callFactory = { container.imageHttp }))
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .build()
}
