package hu.laurel.sqlpulse.data.query

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own file, not the settings one.
 *
 * Drafts are written while the user types and settings are written when a switch is flipped; two
 * very different write rates have no business sharing a file that is rewritten whole on every
 * edit. Keeping them apart also means a corrupt draft file cannot take the preferences with it.
 */
private val Context.draftStore by preferencesDataStore(name = "sqlpulse_query_drafts")

/**
 * Where the editor's unfinished text survives the process being killed.
 *
 * Only the text, the tab's name, its database and which tab was in front — a result set is rows
 * from a server and has no business being written to disk (§9), and re-running the query is both
 * cheaper and more honest than showing yesterday's rows as if they were today's.
 */
@Singleton
class QueryDraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** An unreadable file is an empty one: a draft is a convenience, never a reason to fail to start. */
    suspend fun load(): DraftBook =
        context.draftStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { preferences -> QueryDrafts.decode(preferences[DRAFTS]) }
            .first()

    suspend fun save(book: DraftBook) {
        val encoded = QueryDrafts.encode(book)
        context.draftStore.edit { preferences -> preferences[DRAFTS] = encoded }
    }

    private companion object {
        val DRAFTS = stringPreferencesKey("drafts")
    }
}
