package dev.coinutxo.wallet.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

const val SCAN_RESULT = "scan_result"

@Composable
fun AppNav(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("home") { HomeScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("create/backup") { CreateBackupScreen(nav) }
        composable("create/confirm") { CreateConfirmScreen(nav) }
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
