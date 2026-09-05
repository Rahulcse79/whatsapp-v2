package com.whatsappv2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.AccountDetailRoute
import com.whatsappv2.feature.accounts.AccountEditorRoute
import com.whatsappv2.feature.accounts.AccountsRoute
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
        composable(AppDestination.ACCOUNTS.route) {
            AccountsRoute(
                onAddAccount = { navController.navigate(ACCOUNT_EDITOR_NEW) },
                onOpenAccount = { id -> navController.navigate("$ACCOUNT_DETAIL_BASE/${id.value}") },
            )
        }

        // Adding and editing share one destination: the editor differs only in whether
        // it loads an existing account, and two routes would mean two copies of the form.
        composable(ACCOUNT_EDITOR_NEW) {
            AccountEditorRoute(
                accountId = null,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "$ACCOUNT_EDITOR_BASE/{$ACCOUNT_ID_ARG}",
            arguments = listOf(navArgument(ACCOUNT_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(ACCOUNT_ID_ARG)?.let(::AccountId)
            AccountEditorRoute(
                accountId = id,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        // Registration detail (Task 31): what the account's state actually is, why it is
        // that, and the one action - register now - that can change it from here.
        composable(
            route = "$ACCOUNT_DETAIL_BASE/{$ACCOUNT_ID_ARG}",
            arguments = listOf(navArgument(ACCOUNT_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(ACCOUNT_ID_ARG)?.let(::AccountId)
            if (id == null) {
                navController.popBackStack()
            } else {
                AccountDetailRoute(
                    accountId = id,
                    onEditAccount = { navController.navigate("$ACCOUNT_EDITOR_BASE/${it.value}") },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(AppDestination.SETTINGS.route) { SettingsScreen() }
    }
}

private const val ACCOUNT_DETAIL_BASE = "account-detail"
private const val ACCOUNT_EDITOR_BASE = "account-editor"
private const val ACCOUNT_EDITOR_NEW = "account-editor/new"
private const val ACCOUNT_ID_ARG = "accountId"
