package hu.laurel.sqlpulse.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.sql.AffectedRowLimit
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
    /**
     * Refuse UPDATE and DELETE without a WHERE clause. On by default: on a phone the statement is
     * typed with a thumb, and the whole-table version of it looks almost identical.
     */
    val blockWritesWithoutWhere: Boolean = true,
    /**
     * The most rows a hand-typed INSERT, UPDATE, DELETE or REPLACE may be estimated to change
     * before the confirmation refuses it. 0 turns the ceiling off.
     *
     * A thousand is high enough that ordinary work never meets it and low enough to stop the
     * WHERE that matched the whole table.
     */
    val maxAffectedRows: Int = AffectedRowLimit.DEFAULT_MAX_AFFECTED_ROWS,
    /**
     * Keep screenshots and the recents-list preview blank (§6).
     *
     * On by default, because a result grid on screen is customer data. It can be turned off, since
     * a screenshot is the quickest way to report what an app is doing wrong.
     */
    val blockScreenshots: Boolean = true,
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
            blockWritesWithoutWhere = preferences[BLOCK_UNGUARDED_WRITES] ?: true,
            maxAffectedRows = preferences[MAX_AFFECTED_ROWS] ?: AffectedRowLimit.DEFAULT_MAX_AFFECTED_ROWS,
            blockScreenshots = preferences[BLOCK_SCREENSHOTS] ?: true,
        )
    }

    suspend fun setDefaultRowLimit(limit: Int) = put(ROW_LIMIT, limit.coerceIn(10, 10_000))

    /** 0 means no ceiling; the upper bound only keeps the slider's numbers sane. */
    suspend fun setMaxAffectedRows(rows: Int) = put(MAX_AFFECTED_ROWS, rows.coerceIn(0, 1_000_000))

    suspend fun setAutoLockMinutes(minutes: Int) = put(AUTO_LOCK, minutes.coerceIn(1, 60))

    suspend fun setGridFontScale(scale: Int) = put(GRID_FONT, scale.coerceIn(80, 150))

    suspend fun setBlockWritesWithoutWhere(block: Boolean) {
        context.dataStore.edit { it[BLOCK_UNGUARDED_WRITES] = block }
    }

    suspend fun setBlockScreenshots(block: Boolean) {
        context.dataStore.edit { it[BLOCK_SCREENSHOTS] = block }
    }

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
        val BLOCK_UNGUARDED_WRITES = booleanPreferencesKey("block_writes_without_where")
        val MAX_AFFECTED_ROWS = intPreferencesKey("max_affected_rows")
        val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
    }
}
