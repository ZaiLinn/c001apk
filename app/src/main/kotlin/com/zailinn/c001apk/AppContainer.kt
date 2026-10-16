package com.zailinn.c001apk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.zailinn.c001apk.core.net.CaptchaChallenge
import com.zailinn.c001apk.core.net.CoolapkClient
import com.zailinn.c001apk.core.net.CoolapkHeaders
import com.zailinn.c001apk.core.net.CoolapkRepository
import com.zailinn.c001apk.core.net.SessionState
import com.zailinn.c001apk.core.update.UpdatePreferences
import com.zailinn.c001apk.core.update.UpdateService
import com.zailinn.c001apk.data.AccountManager
import com.zailinn.c001apk.data.LocalContent
import com.zailinn.c001apk.data.LocalStore
import com.zailinn.c001apk.data.AppSettings
import com.zailinn.c001apk.data.SettingsRepository
import com.zailinn.c001apk.data.sensitiveFilterOn
import com.zailinn.c001apk.semantic.SemanticConfig
import com.zailinn.c001apk.semantic.SemanticModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Process-wide dependencies, created once in [C001apkApplication]. */
class AppContainer(
    private val context: Context,
    storeName: String = "c001apk",
    /** Test seam: replaces the network for Coolapk requests after signing. */
    transport: okhttp3.Interceptor? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = LocalStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            context.preferencesDataStoreFile(storeName)
        },
        scope,
    )
    val settings = SettingsRepository(store)
    val content = LocalContent(store)
    val session = SessionState()
    val accounts = AccountManager(store, session)

    private val baseHttp = OkHttpClient()

    /** Images use the client user agent, but never API credentials. */
    val imageHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor { chain ->
            val ua = settings.current.protocol.userAgent
            chain.proceed(if (ua.isEmpty()) chain.request() else chain.request().newBuilder().header("User-Agent", ua).build())
        }
        .build()

    val client = CoolapkClient(session, CoolapkHeaders(session, { settings.current.protocol }), baseHttp, transport)
    val semantic = SemanticModel(context, baseHttp, scope)
    val repository = CoolapkRepository(client) {
        val current = settings.current
        content.filter(current).copy(semantic = semantic.matcher(current.sensitiveFilterOn && current.semanticFold, semanticConfig()))
    }

    fun semanticConfig(current: AppSettings = settings.current) =
        SemanticConfig(content.filterCategories.value.filter { it.enabled }, current.semanticThreshold)
    val uploader = com.zailinn.c001apk.core.upload.OssUploader(repository, baseHttp)
    val captcha = CaptchaCoordinator()
    val updates = UpdateService(UpdatePreferences(settings), { BuildConfig.VERSION_NAME }, UpdateService.defaultFetcher(baseHttp))

    private val main = Handler(Looper.getMainLooper())

    /** Short message from any thread. */
    fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    init {
        runBlocking {
            settings.initialize()
            content.migrateCategories()
        }
        client.captchaVerifier = captcha::verify
        // Start loading the model early so the first pages can already use it.
        settings.current.let { semantic.matcher(it.sensitiveFilterOn && it.semanticFold, semanticConfig(it)) }
    }
}

/** Bridges a request's captcha challenge to the verification screen. One at a time. */
class CaptchaCoordinator {
    class Pending(val challenge: CaptchaChallenge, internal val result: CompletableDeferred<String?>) {
        fun complete(proof: String?) {
            result.complete(proof)
        }
    }

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    suspend fun verify(challenge: CaptchaChallenge): String? = mutex.withLock {
        val request = Pending(challenge, CompletableDeferred())
        _pending.value = request
        try {
            request.result.await()
        } finally {
            _pending.value = null
        }
    }
}
