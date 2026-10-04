package hu.laurel.sqlpulse.data.alerts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A store of its own: alert rules are one small document per connection, not queryable data. */
private val Context.alertStore by preferencesDataStore(name = "sqlpulse_alerts")

/**
 * The alert rules, keyed by connection id.
 *
 * DataStore rather than Room because this round adds no migration; a connection that is deleted
 * leaves its (thresholds-only, secret-free) entry behind, which is harmless.
 */
@Singleton
class AlertRulesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The engine's rules with the saved ones on top; every rule is off until the user turns it on. */
    fun rules(connectionId: Long, engine: DatabaseEngine): Flow<List<AlertRule>> =
        context.alertStore.data
            .map { AlertCatalog.merge(engine, AlertRuleCodec.decode(it[key(connectionId)])) }
            .distinctUntilChanged()

    suspend fun save(connectionId: Long, engine: DatabaseEngine, rule: AlertRule) {
        context.alertStore.edit { prefs ->
            val current = AlertCatalog.merge(engine, AlertRuleCodec.decode(prefs[key(connectionId)]))
            val clean = AlertCatalog.sanitize(rule)
            prefs[key(connectionId)] = AlertRuleCodec.encode(current.map { if (it.metric == clean.metric) clean else it })
        }
    }

    private fun key(connectionId: Long) = stringPreferencesKey("rules_$connectionId")
}
