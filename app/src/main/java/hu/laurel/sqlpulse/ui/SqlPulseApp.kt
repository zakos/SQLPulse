package hu.laurel.sqlpulse.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hu.laurel.sqlpulse.ui.connections.ConnectionEditorScreen
import hu.laurel.sqlpulse.ui.connections.ConnectionListScreen
import hu.laurel.sqlpulse.ui.keys.KeyStoreScreen

object Routes {
    const val CONNECTIONS = "connections"
    const val KEYS = "keys"
    const val EDITOR = "editor/{connectionId}"

    fun editor(connectionId: Long) = "editor/$connectionId"
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
    }
}
