package dev.coinutxo.wallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument

const val SCAN_RESULT = "scan_result"

@Composable
fun AppNav(nav: NavHostController) {
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        navigation(startDestination = "create/backup", route = "create") {
            composable("create/backup") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("create") }
                CreateBackupScreen(nav, viewModel(owner))
            }
            composable("create/confirm") { entry ->
                val owner = remember(entry) { nav.getBackStackEntry("create") }
                CreateConfirmScreen(nav, viewModel(owner))
            }
        }
        composable("import") { ImportScreen(nav) }
        composable("watch") { WatchScreen(nav) }
        composable(
            route = "wallet/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            WalletScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable(
            route = "send/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            SendScreen(nav, entry.arguments?.getString("id").orEmpty())
        }
        composable(
            route = "scan?secure={secure}",
            arguments = listOf(navArgument("secure") { type = NavType.StringType; defaultValue = "0" }),
        ) { entry ->
            ScanScreen(nav, secure = entry.arguments?.getString("secure") == "1")
        }
    }
}
