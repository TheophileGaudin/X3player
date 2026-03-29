package com.x3player.glasses.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "x3player_settings")

class SettingsRepository(
    private val context: Context,
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                lastSort = preferences[LAST_SORT].parseEnum(AppSettings().lastSort),
                lastFilter = preferences[LAST_FILTER].parseEnum(AppSettings().lastFilter),
                autoResume = preferences[AUTO_RESUME] ?: AppSettings().autoResume,
                reopenLastVideoOnLaunch = preferences[REOPEN_LAST_VIDEO] ?: AppSettings().reopenLastVideoOnLaunch,
                lastVideoUri = preferences[LAST_VIDEO_URI],
            )
        }
        .distinctUntilChanged()

    suspend fun setLastSort(sort: LibrarySort) {
        context.settingsDataStore.edit { it[LAST_SORT] = sort.name }
    }

    suspend fun setLastFilter(filter: LibraryFilter) {
        context.settingsDataStore.edit { it[LAST_FILTER] = filter.name }
    }

    suspend fun setAutoResume(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTO_RESUME] = enabled }
    }

    suspend fun setReopenLastVideoOnLaunch(enabled: Boolean) {
        context.settingsDataStore.edit { it[REOPEN_LAST_VIDEO] = enabled }
    }

    suspend fun setLastVideoUri(uri: String?) {
        context.settingsDataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(LAST_VIDEO_URI)
            } else {
                preferences[LAST_VIDEO_URI] = uri
            }
        }
    }

    companion object {
        private val LAST_SORT = stringPreferencesKey("last_sort")
        private val LAST_FILTER = stringPreferencesKey("last_filter")
        private val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        private val REOPEN_LAST_VIDEO = booleanPreferencesKey("reopen_last_video_on_launch")
        private val LAST_VIDEO_URI = stringPreferencesKey("last_video_uri")
    }
}

private inline fun <reified T : Enum<T>> String?.parseEnum(defaultValue: T): T {
    if (this.isNullOrBlank()) return defaultValue
    return runCatching { enumValueOf<T>(this) }.getOrDefault(defaultValue)
}
