package com.whatsappv2.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.whatsappv2.call.CallActivity
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.feature.accounts.AccountDetailRoute
import com.whatsappv2.feature.accounts.AccountEditorRoute
import com.whatsappv2.feature.accounts.AccountSavedMessage
import com.whatsappv2.feature.accounts.AccountsRoute
import com.whatsappv2.feature.accounts.status.RegistrationIndicatorRoute
import com.whatsappv2.feature.dialer.DialerScreen
import com.whatsappv2.feature.history.HistoryRoute
import com.whatsappv2.feature.recordings.RecordingsRoute
import com.whatsappv2.feature.settings.SettingsScreen
import com.whatsappv2.ui.chats.ChatsPlaceholderScreen

/**
 * The navigation graph.
 *
 * Lives in `:app` because this is the only module that may know about every feature.
 * A feature that navigated to another feature directly would couple them and break the
 * layering rule that keeps them independently testable — which is why every screen below
 * takes callbacks rather than a `NavController`.
 *
 * ## The shape changed with Tasks 69 and 70, and again when Chats arrived
 *
 * Four of these used to be tabs. They are all still routes: the dialler behind a floating
 * button on Calls, settings behind the gear on Chats, and the account list from inside
 * settings. Nothing was removed from the graph — a tab going away is not a destination
 * going away, and `account-detail/{accountId}` still resolves exactly as it did.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    /**
     * Runs a video call once the camera has been asked for (Task 74).
     *
     * Passed in rather than built here. `rememberCameraGate` reads
     * `LocalPermissionCoordinator`, which only `MainActivity` provides — building it in
     * this graph would make every test and preview that renders `AppRoot` need permission
     * machinery to draw a screen that has nothing to do with permissions.
     */
    videoGate: (proceed: () -> Unit) -> Unit = { it() },
) {
    val context = LocalContext.current

    // The call screen is an activity, not a destination in this graph: it is also what a
    // full-screen intent opens on a locked device, and one call screen reached two ways
    // would be two screens (Task 39). Every route that places a call uses this one lambda.
    val openCall: (CallId) -> Unit = { callId ->
        context.startActivity(CallActivity.intentFor(context, callId))
    }

    // Two kinds of move, two kinds of motion (item 5.6). Switching tabs is a change of
    // place, so the screens cross-fade where they stand; going *into* a screen — the
    // dialler, settings, an account — is a push, so it slides in from the end and slides
    // back out on Back. The default was a scale-and-fade that read as a jump.
    NavHost(
        navController = navController,
        startDestination = AppDestination.START.route,
        modifier = modifier,
        enterTransition = {
            if (switchingTabs()) {
                fadeIn(tween(TAB_FADE_MILLIS))
            } else {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(PUSH_MILLIS)) +
                    fadeIn(tween(PUSH_MILLIS))
            }
        },
        exitTransition = {
            if (switchingTabs()) {
                fadeOut(tween(TAB_FADE_MILLIS))
            } else {
                // The screen underneath moves a quarter of the way, the way a stack does.
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Start,
                    tween(PUSH_MILLIS),
                    targetOffset = { it / PARALLAX_DIVISOR },
                ) + fadeOut(tween(PUSH_MILLIS))
            }
        },
        popEnterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(PUSH_MILLIS),
                initialOffset = { it / PARALLAX_DIVISOR },
            ) + fadeIn(tween(PUSH_MILLIS))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PUSH_MILLIS)) +
                fadeOut(tween(PUSH_MILLIS))
        },
    ) {
        callRoutes(navController, openCall, videoGate)
        accountRoutes(navController)
    }
}

/** True when both ends of the move are tabs — a change of place rather than a push. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.switchingTabs(): Boolean =
    AppDestination.fromRoute(initialState.destination.route)?.isTopLevel == true &&
        AppDestination.fromRoute(targetState.destination.route)?.isTopLevel == true

private const val TAB_FADE_MILLIS = 180
private const val PUSH_MILLIS = 260
private const val PARALLAX_DIVISOR = 4

/** The two tabs, and the two screens reached from them (Tasks 69, 70). */
private fun NavGraphBuilder.callRoutes(
    navController: NavHostController,
    openCall: (CallId) -> Unit,
    videoGate: (proceed: () -> Unit) -> Unit,
) {
    // All three routes that can start a video call share one gate. One launcher is
    // enough: only one destination is on screen to press it.
    composable(AppDestination.CHATS.route) {
        // A real destination behind a placeholder, so the module another team is building
        // replaces a composable rather than negotiating an app shell (Task: item 8). The
        // gear is the one way into settings; it travels with the route, not the placeholder.
        ChatsPlaceholderScreen(
            onOpenSettings = { navController.navigate(AppDestination.SETTINGS.route) },
            registrationIndicator = {
                // Straight to the account list, not to Settings and then the list: the
                // person pressing this has a registration to fix or an account to add.
                RegistrationIndicatorRoute(
                    onManageAccounts = { navController.navigate(AppDestination.ACCOUNTS.route) },
                )
            },
        )
    }

    composable(AppDestination.HISTORY.route) {
        HistoryRoute(
            onCallPlaced = openCall,
            onOpenDialer = { navController.navigate(AppDestination.DIALER.route) },
            videoGate = videoGate,
        )
    }

    composable(AppDestination.DIALER.route) {
        DialerScreen(
            onCallPlaced = openCall,
            onBack = { navController.popBackStack() },
            videoGate = videoGate,
        )
    }

    composable(AppDestination.SETTINGS.route) {
        SettingsScreen(
            onOpenAccounts = { navController.navigate(AppDestination.ACCOUNTS.route) },
            onOpenRecordings = { navController.navigate(AppDestination.RECORDINGS.route) },
            onBack = { navController.popBackStack() },
        )
    }

    // Behind Settings, like the accounts: the recordings are a thing the app holds, and
    // the screen that lists them owns their playback for as long as it is open.
    composable(AppDestination.RECORDINGS.route) {
        RecordingsRoute(onBack = { navController.popBackStack() })
    }
}

/** The account list, its editor and its registration detail — all behind settings now. */
private fun NavGraphBuilder.accountRoutes(navController: NavHostController) {
    composable(AppDestination.ACCOUNTS.route) { entry ->
        // Task 73: the editor pops on a successful save, so the confirmation is
        // delivered here, to the screen that outlives it. Cleared once shown, so a
        // rotation does not announce the same save twice.
        val text by entry.savedStateHandle
            .getStateFlow<String?>(SAVED_TEXT, null)
            .collectAsState()
        val isWarning by entry.savedStateHandle
            .getStateFlow(SAVED_IS_WARNING, false)
            .collectAsState()

        AccountsRoute(
            onAddAccount = { navController.navigate(ACCOUNT_EDITOR_NEW) },
            onOpenAccount = { id -> navController.navigate("$ACCOUNT_DETAIL_BASE/${id.value}") },
            onBack = { navController.popBackStack() },
            savedMessage = text?.let { AccountSavedMessage(it, isWarning) },
            onSavedMessageShown = { entry.clearSavedMessage() },
        )
    }

    // Adding and editing share one destination: the editor differs only in whether
    // it loads an existing account, and two routes would mean two copies of the form.
    composable(ACCOUNT_EDITOR_NEW) {
        AccountEditorRoute(
            accountId = null,
            onSaved = { navController.reportSaved(it) },
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
            onSaved = { navController.reportSaved(it) },
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
}

/**
 * Hands the save confirmation back to whatever opened the editor, then leaves (Task 73).
 *
 * `previousBackStackEntry`, so the message lands on the screen the pop returns to. Written
 * as two primitives rather than the value itself because saved state has to survive process
 * death, and a `Bundle` can carry a String and a Boolean without either being made
 * `Parcelable` for the sake of one snackbar.
 */
private fun NavHostController.reportSaved(message: AccountSavedMessage) {
    previousBackStackEntry?.savedStateHandle?.let { handle ->
        handle[SAVED_TEXT] = message.text
        handle[SAVED_IS_WARNING] = message.isWarning
    }
    popBackStack()
}

/** Clears it, so returning to this screen a second time does not repeat the news. */
private fun NavBackStackEntry.clearSavedMessage() {
    savedStateHandle[SAVED_TEXT] = null
    savedStateHandle[SAVED_IS_WARNING] = false
}

private const val ACCOUNT_DETAIL_BASE = "account-detail"
private const val ACCOUNT_EDITOR_BASE = "account-editor"
private const val ACCOUNT_EDITOR_NEW = "account-editor/new"
private const val ACCOUNT_ID_ARG = "accountId"
private const val SAVED_TEXT = "account-saved-text"
private const val SAVED_IS_WARNING = "account-saved-is-warning"
