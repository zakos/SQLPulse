package hu.laurel.sqlpulse.data.query

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A store of its own, so the editor's preferences never contend with the main settings file. */
private val Context.editorStore by preferencesDataStore(name = "sqlpulse_editor")

/**
 * The user's snippets and key bar layout.
 *
 * DataStore rather than Room: both are one small document that is always read and written whole,
 * and the Room schema is versioned for data that is queried. Nothing secret is kept here — but a
 * snippet is SQL the user typed, so it is not part of any export either.
 */
@Singleton
class EditorPrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val snippets: Flow<List<Snippet>> =
        context.editorStore.data.map { UserSnippets.decode(it[SNIPPETS]) }

    val keyBar: Flow<KeyBarConfig> =
        context.editorStore.data.map { KeyBar.decode(it[KEY_BAR]) }

    /**
     * Saves a snippet; [id] null creates one. Returns false when the name or text was blank or the
     * list is full, in which case nothing changed.
     */
    suspend fun saveSnippet(id: String?, name: String, body: String): Boolean {
        var saved = false
        context.editorStore.edit { prefs ->
            val current = UserSnippets.decode(prefs[SNIPPETS])
            val updated = UserSnippets.upsert(current, id ?: UUID.randomUUID().toString(), name, body)
            if (updated != null) {
                prefs[SNIPPETS] = UserSnippets.encode(updated)
                saved = true
            }
        }
        return saved
    }

    suspend fun deleteSnippet(id: String) {
        context.editorStore.edit { prefs ->
            prefs[SNIPPETS] = UserSnippets.encode(UserSnippets.delete(UserSnippets.decode(prefs[SNIPPETS]), id))
        }
    }

    suspend fun setKeyBar(config: KeyBarConfig) {
        context.editorStore.edit { it[KEY_BAR] = KeyBar.encode(config) }
    }

    /** Back to the shipped bar: forgetting the setting is what makes later additions show up. */
    suspend fun resetKeyBar() {
        context.editorStore.edit { it.remove(KEY_BAR) }
    }

    private companion object {
        val SNIPPETS = stringPreferencesKey("user_snippets")
        val KEY_BAR = stringPreferencesKey("key_bar")
    }
}
