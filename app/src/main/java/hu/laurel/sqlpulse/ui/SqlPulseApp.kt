package hu.laurel.sqlpulse.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreen
import hu.laurel.sqlpulse.ui.connections.ConnectionListScreen
import hu.laurel.sqlpulse.ui.keys.KeyStoreScreen
import hu.laurel.sqlpulse.ui.map.SchemaMapScreen
import hu.laurel.sqlpulse.ui.pulse.PulseScreen
import hu.laurel.sqlpulse.ui.query.QueryEditorScreen
import hu.laurel.sqlpulse.ui.settings.SettingsScreen
import hu.laurel.sqlpulse.ui.schema.SchemaBrowserScreen
import hu.laurel.sqlpulse.ui.server.ServerScreen
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
    const val TABLE = "table/{database}/{table}"

    fun editor(connectionId: Long) = "editor/$connectionId"

    fun table(database: String, table: String) =
        "table/${Uri.encode(database)}/${Uri.encode(table)}"
}

@Composable
fun SqlPulseApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CONNECTIONS) {
        composable(Routes.CONNECTIONS) {
            ConnectionListScreen(
                onCreate = { navController.navigate(Routes.editor(0)) },
                onEdit = { id -> navController.navigate(Routes.editor(id)) },
                onOpenKeyStore = { navController.navigate(Routes.KEYS) },
                onOpenSchema = { navController.navigate(Routes.SCHEMA) },
                onOpenQuery = { navController.navigate(Routes.QUERY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
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
            ServerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PULSE) {
            PulseScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MAP) {
            SchemaMapScreen(
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
            )
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
