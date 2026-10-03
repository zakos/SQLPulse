package hu.laurel.sqlpulse.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import hu.laurel.sqlpulse.data.shortcuts.ShortcutRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreen
import hu.laurel.sqlpulse.ui.connections.ConnectionListScreen
import hu.laurel.sqlpulse.ui.backup.BackupScreen
import hu.laurel.sqlpulse.ui.keys.KeyStoreScreen
import hu.laurel.sqlpulse.ui.map.SchemaMapScreen
import hu.laurel.sqlpulse.ui.pulse.PulseScreen
import hu.laurel.sqlpulse.ui.query.QueryEditorScreen
import hu.laurel.sqlpulse.ui.search.DatabaseSearchScreen
import hu.laurel.sqlpulse.ui.settings.SettingsScreen
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserScreen
import hu.laurel.sqlpulse.ui.schemadiff.SchemaDiffScreen
import hu.laurel.sqlpulse.ui.server.ServerScreen
import hu.laurel.sqlpulse.ui.settings.KeyBarSettingsScreen
import hu.laurel.sqlpulse.ui.storage.StorageScreen
import hu.laurel.sqlpulse.ui.writelog.WriteLogScreen
import hu.laurel.sqlpulse.ui.schema.TableDetailScreen

object Routes {
    const val CONNECTIONS = "connections"
    const val KEYS = "keys"
    const val EDITOR = "editor/{connectionId}"
    const val SCHEMA = "schema"
    const val QUERY = "query"
    const val SETTINGS = "settings"
    const val SERVER = "server"
    const val PULSE = "pulse"
    const val MAP = "map"
    const val BACKUP = "backup"
    const val SEARCH = "search"
    const val SCHEMA_DIFF = "schema-diff"
    const val STORAGE = "storage"
    const val WRITE_LOG = "write_log"
    const val KEY_BAR = "key_bar"
    const val TABLE = "table/{database}/{table}"

    fun editor(connectionId: Long) = "editor/$connectionId"

    fun table(database: String, table: String) =
        "table/${Uri.encode(database)}/${Uri.encode(table)}"
}

@Composable
fun SqlPulseApp(connectRequests: Flow<ShortcutRequest?> = emptyFlow()) {
    val navController = rememberNavController()

    // A launcher shortcut lands on the list, wherever the user was; the list then does the connecting.
    LaunchedEffect(connectRequests) {
        connectRequests.filterNotNull().collect { navController.popBackStack(Routes.CONNECTIONS, false) }
    }

    NavHost(navController = navController, startDestination = Routes.CONNECTIONS) {
        composable(Routes.CONNECTIONS) {
            ConnectionListScreen(
                onCreate = { navController.navigate(Routes.editor(0)) },
                onEdit = { id -> navController.navigate(Routes.editor(id)) },
                onOpenKeyStore = { navController.navigate(Routes.KEYS) },
                onOpenSchema = { navController.navigate(Routes.SCHEMA) },
                onOpenQuery = { navController.navigate(Routes.QUERY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSchemaDiff = { navController.navigate(Routes.SCHEMA_DIFF) },
            )
        }

        composable(Routes.SCHEMA_DIFF) {
            SchemaDiffScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument("connectionId") { type = NavType.LongType }),
        ) {
            ConnectionEditorScreen(
                onBack = { navController.popBackStack() },
                onOpenKeyStore = { navController.navigate(Routes.KEYS) },
            )
        }

        composable(Routes.KEYS) {
            KeyStoreScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SERVER) {
            ServerScreen(
                onBack = { navController.popBackStack() },
                onOpenQuery = { navController.navigate(Routes.QUERY) },
            )
        }

        composable(Routes.PULSE) {
            PulseScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BACKUP) {
            BackupScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MAP) {
            SchemaMapScreen(
                onBack = { navController.popBackStack() },
                onOpenTable = { database, table ->
                    navController.navigate(Routes.table(database, table))
                },
            )
        }

        composable(Routes.SEARCH) {
            DatabaseSearchScreen(
                onBack = { navController.popBackStack() },
                onOpenTable = { database, table ->
                    navController.navigate(Routes.table(database, table))
                },
            )
        }

        composable(Routes.STORAGE) {
            StorageScreen(
                onBack = { navController.popBackStack() },
                onOpenTable = { database, table ->
                    navController.navigate(Routes.table(database, table))
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenKeyStore = { navController.navigate(Routes.KEYS) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenWriteLog = { navController.navigate(Routes.WRITE_LOG) },
                onOpenKeyBar = { navController.navigate(Routes.KEY_BAR) },
            )
        }

        composable(Routes.KEY_BAR) {
            KeyBarSettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.WRITE_LOG) {
            WriteLogScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.QUERY) {
            QueryEditorScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SCHEMA) {
            SchemaBrowserScreen(
                onBack = { navController.popBackStack() },
                onOpenQuery = { navController.navigate(Routes.QUERY) },
                onOpenServer = { navController.navigate(Routes.SERVER) },
                onOpenPulse = { navController.navigate(Routes.PULSE) },
                onOpenMap = { navController.navigate(Routes.MAP) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenStorage = { navController.navigate(Routes.STORAGE) },
                onOpenTable = { database, table ->
                    navController.navigate(Routes.table(database, table))
                },
            )
        }

        composable(
            route = Routes.TABLE,
            arguments = listOf(
                navArgument("database") { type = NavType.StringType },
                navArgument("table") { type = NavType.StringType },
            ),
        ) {
            TableDetailScreen(
                onBack = { navController.popBackStack() },
                onOpenTable = { database, table ->
                    navController.navigate(Routes.table(database, table))
                },
            )
        }
    }
}
