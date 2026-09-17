package hu.laurel.sqlpulse.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.sql.SqlGuards
import hu.laurel.sqlpulse.ui.theme.ThemePreference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sqlpulse_settings")

/** The preferences of §7.7. Nothing secret lives here, so plain DataStore is enough. */
data class Settings(
    val defaultRowLimit: Int = SqlGuards.DEFAULT_ROW_LIMIT,
    /** §6: minutes of inactivity before the app locks and the tunnel drops. */
    val autoLockMinutes: Int = 5,
    val theme: ThemePreference = ThemePreference.System,
    val gridFontScale: Int = 100,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val settings: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            defaultRowLimit = preferences[ROW_LIMIT] ?: SqlGuards.DEFAULT_ROW_LIMIT,
            autoLockMinutes = preferences[AUTO_LOCK] ?: 5,
            theme = preferences[THEME]?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
                ?: ThemePreference.System,
            gridFontScale = preferences[GRID_FONT] ?: 100,
        )
    }

    suspend fun setDefaultRowLimit(limit: Int) = put(ROW_LIMIT, limit.coerceIn(10, 10_000))

    suspend fun setAutoLockMinutes(minutes: Int) = put(AUTO_LOCK, minutes.coerceIn(1, 60))

    suspend fun setGridFontScale(scale: Int) = put(GRID_FONT, scale.coerceIn(80, 150))

    suspend fun setTheme(theme: ThemePreference) {
        context.dataStore.edit { it[THEME] = theme.name }
    }

    private suspend fun put(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val ROW_LIMIT = intPreferencesKey("default_row_limit")
        val AUTO_LOCK = intPreferencesKey("auto_lock_minutes")
        val THEME = stringPreferencesKey("theme")
        val GRID_FONT = intPreferencesKey("grid_font_scale")
    }
}
