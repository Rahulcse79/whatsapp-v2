package com.whatsappv2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.whatsappv2.feature.accounts.AccountsScreen
import com.whatsappv2.feature.dialer.DialerScreen
import com.whatsappv2.feature.history.HistoryScreen
import com.whatsappv2.feature.settings.SettingsScreen

/**
 * The navigation graph.
 *
 * Lives in `:app` because this is the only module that may know about every feature.
 * A feature that navigated to another feature directly would couple them and break the
 * layering rule that keeps them independently testable.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.START.route,
        modifier = modifier,
    ) {
        composable(AppDestination.DIALER.route) { DialerScreen() }
        composable(AppDestination.HISTORY.route) { HistoryScreen() }
        composable(AppDestination.ACCOUNTS.route) { AccountsScreen() }
        composable(AppDestination.SETTINGS.route) { SettingsScreen() }
    }
}
