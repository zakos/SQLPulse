package hu.laurel.sqlpulse.ui.engine

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hu.laurel.sqlpulse.R
import hu.laurel.sqlpulse.data.schema.ObjectKind
import hu.laurel.sqlpulse.data.sql.SqlSessionManager
import hu.laurel.sqlpulse.data.sql.SqlSessionState
import hu.laurel.sqlpulse.data.sql.dialect.DatabaseEngine
import hu.laurel.sqlpulse.data.sql.dialect.EngineFeature
import hu.laurel.sqlpulse.data.sql.dialect.SqlDialects
import hu.laurel.sqlpulse.ui.components.EmptyState
import hu.laurel.sqlpulse.ui.theme.sqlPulseTopBarColors
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What the live session's engine can do, for the screens to read without asking a ViewModel.
 *
 * [engine] is null when no connection is open (or being got back): the screens then show their
 * own "no session" state, so nothing is gated — and MySQL, the only engine with every feature,
 * behaves exactly as before engines existed.
 */
data class EngineFeatures(
    val engine: DatabaseEngine?,
    val features: Set<EngineFeature>,
) {
    fun has(feature: EngineFeature): Boolean = feature in features

    /** Whether the schema browser offers this kind of object for the engine. */
    fun offers(kind: ObjectKind): Boolean = when (kind) {
        ObjectKind.TABLES, ObjectKind.VIEWS -> true
        ObjectKind.ROUTINES -> has(EngineFeature.ROUTINES)
        ObjectKind.TRIGGERS -> has(EngineFeature.TRIGGERS)
        ObjectKind.EVENTS -> has(EngineFeature.EVENTS)
    }

    companion object {
        /** No session, or MySQL: everything is offered. Also what previews and screenshots get. */
        val ALL = EngineFeatures(engine = null, features = EngineFeature.ALL)

        fun of(engine: DatabaseEngine): EngineFeatures =
            EngineFeatures(engine, SqlDialects.forEngine(engine).features)
    }
}

/** Provided once at the top of the navigation graph (SqlPulseApp) from [EngineFeaturesViewModel]. */
val LocalEngineFeatures = staticCompositionLocalOf { EngineFeatures.ALL }

/** Follows the session: the features of the connection that is open, or lost and coming back. */
@HiltViewModel
class EngineFeaturesViewModel @Inject constructor(sessions: SqlSessionManager) : ViewModel() {
    val features: StateFlow<EngineFeatures> = sessions.state
        .map { state ->
            val connection = when (state) {
                is SqlSessionState.Ready -> state.connection
                is SqlSessionState.Lost -> state.connection
                else -> null
            }
            connection?.let { EngineFeatures.of(DatabaseEngine.fromName(it.engine)) } ?: EngineFeatures.ALL
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineFeatures.ALL)
}

/**
 * Shows [content] when the live engine has [feature], and a plain "not available for this engine"
 * page otherwise. Wraps a whole route in the navigation graph, so a screen built for MySQL never
 * starts its ViewModel against an engine it cannot read — the entry points are hidden too, this
 * is the guard for the ways round them (a deep link, a stale back stack).
 */
@Composable
fun EngineGate(feature: EngineFeature, onBack: () -> Unit, content: @Composable () -> Unit) {
    val features = LocalEngineFeatures.current
    val engine = features.engine
    if (features.has(feature) || engine == null) {
        content()
    } else {
        EngineUnavailableContent(engineName = stringResource(engine.labelRes()), onBack = onBack)
    }
}

/** The page [EngineGate] shows; stateless, so a screenshot test can draw it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineUnavailableContent(engineName: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = sqlPulseTopBarColors(),
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.engine_unavailable_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.engine_unavailable_title, engineName),
                body = stringResource(R.string.engine_unavailable_body),
                actionLabel = stringResource(R.string.engine_unavailable_back),
                onAction = onBack,
            )
        }
    }
}

/** The product name, as the editor's engine choice and the "not available" page write it. */
@StringRes
fun DatabaseEngine.labelRes(): Int = when (this) {
    DatabaseEngine.MYSQL -> R.string.engine_mysql
    DatabaseEngine.POSTGRESQL -> R.string.engine_postgresql
    DatabaseEngine.SQLSERVER -> R.string.engine_sqlserver
    DatabaseEngine.SQLITE -> R.string.engine_sqlite
}
