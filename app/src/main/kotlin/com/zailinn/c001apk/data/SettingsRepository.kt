package com.zailinn.c001apk.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zailinn.c001apk.core.protocol.Constants
import com.zailinn.c001apk.core.protocol.ProtocolParams
import com.zailinn.c001apk.core.semantic.SemanticAnchors
import com.zailinn.c001apk.core.update.UpdatePreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val dynamicColor: Boolean = true,
    val staticColor: Int = 0,
    /** 0 follow system, 1 light, 2 dark. */
    val themeMode: Int = 0,
    val fontScale: Float = 1f,
    /** Added to every text style weight: 0 standard, 1 medium (+100), 2 bold (+200). */
    val fontWeightLevel: Int = 0,
    /** Visible home tabs in order, as [com.zailinn.c001apk.ui.screens.main.HomeTab] names; empty means the default set. */
    val homeTabs: List<String> = emptyList(),
    val followType: Int = 0,
    val openInBrowser: Boolean = false,
    val recordHistory: Boolean = true,
    val showEmoji: Boolean = true,
    /** Refresh the home list when coming back to it from another page or tab. */
    val refreshOnReturn: Boolean = false,
    val secretFollow: Boolean = false,
    /** Fold feeds and replies that match the sensitive-topic word list. */
    val sensitiveFold: Boolean = false,
    /** Remove matching feeds and replies entirely instead of folding them. */
    val sensitiveHide: Boolean = false,
    /** Also fold by meaning with the downloaded model. */
    val semanticFold: Boolean = false,
    val semanticThreshold: Float = SemanticAnchors.DEFAULT_THRESHOLD,
    val installTime: String = "",
    val protocol: ProtocolParams = ProtocolParams(),
)

/** Either switch turns sensitive-topic matching on; hiding wins over folding. */
val AppSettings.sensitiveFilterOn: Boolean get() = sensitiveFold || sensitiveHide

object SettingKeys {
    val dynamicColor = booleanPreferencesKey("useMaterial")
    val staticColor = intPreferencesKey("staticColor")
    val themeMode = intPreferencesKey("selectedTheme")
    val fontScale = floatPreferencesKey("fontScale")
    val fontWeightLevel = intPreferencesKey("fontWeightLevel")
    val homeTabs = stringPreferencesKey("homeTabs")
    val followType = intPreferencesKey("followType")
    val openInBrowser = booleanPreferencesKey("openInBrowser")
    val recordHistory = booleanPreferencesKey("recordHistory")
    val showEmoji = booleanPreferencesKey("showEmoji")
    val refreshOnReturn = booleanPreferencesKey("refreshOnReturn")
    val secretFollow = booleanPreferencesKey("secretFollowEnabled")
    val sensitiveFold = booleanPreferencesKey("sensitiveFold")
    val sensitiveHide = booleanPreferencesKey("sensitiveHide")
    val semanticFold = booleanPreferencesKey("semanticFold")
    val semanticThreshold = floatPreferencesKey("semanticThreshold")
    val installTime = stringPreferencesKey("installTime")

    val versionName = stringPreferencesKey("versionName")
    val versionCode = stringPreferencesKey("versionCode")
    val apiVersion = stringPreferencesKey("apiVersion")
    val manufacturer = stringPreferencesKey("manufacturer")
    val brand = stringPreferencesKey("brand")
    val model = stringPreferencesKey("model")
    val buildNumber = stringPreferencesKey("buildNumber")
    val sdkInt = stringPreferencesKey("sdkInt")
    val androidVersion = stringPreferencesKey("androidVersion")
    val userAgent = stringPreferencesKey("userAgent")
    val xAppDevice = stringPreferencesKey("xAppDevice")
    val szlmId = stringPreferencesKey("szlmId")
}

class SettingsRepository(private val store: LocalStore) : UpdatePreferenceStore {
    val settings: StateFlow<AppSettings> = store.state(::read)
    val current: AppSettings get() = settings.value

    /** First launch creates a synthetic device; later launches keep it stable. */
    suspend fun initialize() {
        if (store.prefs.value[SettingKeys.installTime].isNullOrEmpty()) {
            store.edit { it[SettingKeys.installTime] = System.currentTimeMillis().toString() }
            saveProtocol(ProtocolParams.generate())
        }
    }

    suspend fun saveProtocol(params: ProtocolParams) = store.edit {
        it[SettingKeys.versionName] = params.versionName
        it[SettingKeys.versionCode] = params.versionCode
        it[SettingKeys.apiVersion] = params.apiVersion
        it[SettingKeys.manufacturer] = params.manufacturer
        it[SettingKeys.brand] = params.brand
        it[SettingKeys.model] = params.model
        it[SettingKeys.buildNumber] = params.buildNumber
        it[SettingKeys.sdkInt] = params.sdkInt
        it[SettingKeys.androidVersion] = params.androidVersion
        it[SettingKeys.userAgent] = params.userAgent
        it[SettingKeys.xAppDevice] = params.xAppDevice
        it[SettingKeys.szlmId] = params.szlmId
    }

    suspend fun regenerateProtocol() = saveProtocol(ProtocolParams.generate(current.protocol.szlmId))

    suspend fun <T> set(key: Preferences.Key<T>, value: T) = store.edit { it[key] = value }

    private fun read(p: Preferences) = AppSettings(
        dynamicColor = p[SettingKeys.dynamicColor] ?: true,
        staticColor = p[SettingKeys.staticColor] ?: 0,
        themeMode = p[SettingKeys.themeMode] ?: 0,
        fontScale = p[SettingKeys.fontScale] ?: 1f,
        fontWeightLevel = p[SettingKeys.fontWeightLevel] ?: 0,
        homeTabs = p[SettingKeys.homeTabs]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
        followType = p[SettingKeys.followType] ?: 0,
        openInBrowser = p[SettingKeys.openInBrowser] ?: false,
        recordHistory = p[SettingKeys.recordHistory] ?: true,
        showEmoji = p[SettingKeys.showEmoji] ?: true,
        refreshOnReturn = p[SettingKeys.refreshOnReturn] ?: false,
        secretFollow = p[SettingKeys.secretFollow] ?: false,
        sensitiveFold = p[SettingKeys.sensitiveFold] ?: false,
        sensitiveHide = p[SettingKeys.sensitiveHide] ?: false,
        semanticFold = p[SettingKeys.semanticFold] ?: false,
        semanticThreshold = p[SettingKeys.semanticThreshold] ?: SemanticAnchors.DEFAULT_THRESHOLD,
        installTime = p[SettingKeys.installTime].orEmpty(),
        protocol = ProtocolParams(
            versionName = p[SettingKeys.versionName] ?: Constants.VERSION_NAME,
            versionCode = p[SettingKeys.versionCode] ?: Constants.VERSION_CODE,
            apiVersion = p[SettingKeys.apiVersion] ?: Constants.API_VERSION,
            manufacturer = p[SettingKeys.manufacturer].orEmpty(),
            brand = p[SettingKeys.brand].orEmpty(),
            model = p[SettingKeys.model].orEmpty(),
            buildNumber = p[SettingKeys.buildNumber].orEmpty(),
            sdkInt = p[SettingKeys.sdkInt].orEmpty(),
            androidVersion = p[SettingKeys.androidVersion].orEmpty(),
            userAgent = p[SettingKeys.userAgent].orEmpty(),
            xAppDevice = p[SettingKeys.xAppDevice].orEmpty(),
            szlmId = p[SettingKeys.szlmId].orEmpty(),
        ),
    )

    // UpdatePreferenceStore: reminder state for c001apk's own update checks.
    override fun getLong(key: String) = store.prefs.value[longPreferencesKey(key)]
    override fun getString(key: String) = store.prefs.value[stringPreferencesKey(key)]
    override fun getBoolean(key: String) = store.prefs.value[booleanPreferencesKey(key)]
    override suspend fun put(key: String, value: Any) = store.edit {
        when (value) {
            is Long -> it[longPreferencesKey(key)] = value
            is String -> it[stringPreferencesKey(key)] = value
            is Boolean -> it[booleanPreferencesKey(key)] = value
            else -> error("Unsupported preference type")
        }
    }
}
