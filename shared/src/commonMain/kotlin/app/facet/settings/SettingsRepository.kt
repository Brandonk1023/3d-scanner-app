package app.facet.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.facet.export.ExportFormat
import app.facet.mesh.Smoothing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okio.IOException
import okio.Path.Companion.toPath

/** App settings, observed by every screen and the theme. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** File name for [createSettingsDataStore]; DataStore requires the `.preferences_pb` suffix. */
const val SETTINGS_FILE_NAME = "settings.preferences_pb"

/** One instance per process. [path] is an absolute path ending in [SETTINGS_FILE_NAME]. */
fun createSettingsDataStore(path: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { path.toPath() })

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.data
        // An unreadable file falls back to defaults instead of crashing the app.
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> prefs.write(transform(prefs.toAppSettings())) }
    }
}

/** For tests and previews. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) = state.update(transform)
}

// Keys are part of the on-disk format: never rename them.
private object Keys {
    val theme = stringPreferencesKey("theme_mode")
    val units = stringPreferencesKey("units")
    val defaultResult = stringPreferencesKey("default_result")
    val watertight = booleanPreferencesKey("watertight")
    val smoothing = stringPreferencesKey("smoothing")
    val haptics = booleanPreferencesKey("haptic_guidance")
    val onboardingDone = booleanPreferencesKey("onboarding_done")
    val lastExportFormat = stringPreferencesKey("last_export_format")
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

internal fun Preferences.toAppSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        themeMode = this[Keys.theme].toEnum(d.themeMode),
        units = this[Keys.units].toEnum(d.units),
        defaultResult = this[Keys.defaultResult].toEnum(d.defaultResult),
        watertight = this[Keys.watertight] ?: d.watertight,
        smoothing = this[Keys.smoothing].toEnum<Smoothing>(d.smoothing),
        hapticGuidance = this[Keys.haptics] ?: d.hapticGuidance,
        onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
        lastExportFormat = this[Keys.lastExportFormat].toEnum<ExportFormat>(d.lastExportFormat),
    )
}

internal fun MutablePreferences.write(s: AppSettings) {
    this[Keys.theme] = s.themeMode.name
    this[Keys.units] = s.units.name
    this[Keys.defaultResult] = s.defaultResult.name
    this[Keys.watertight] = s.watertight
    this[Keys.smoothing] = s.smoothing.name
    this[Keys.haptics] = s.hapticGuidance
    this[Keys.onboardingDone] = s.onboardingDone
    this[Keys.lastExportFormat] = s.lastExportFormat.name
}
