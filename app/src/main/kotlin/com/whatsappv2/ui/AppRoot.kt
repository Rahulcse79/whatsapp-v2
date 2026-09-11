package com.whatsappv2.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.ui.navigation.AppDestination
import com.whatsappv2.ui.navigation.AppNavHost

/**
 * The app shell: a single navigation host.
 *
 * ## The bottom bar is back, because there is somewhere to go again
 *
 * Tasks 69 and 70 removed one, correctly: Settings and Accounts had moved behind the
 * Calls screen's icon and the Dialer had become a floating button, which left a bar with
 * a single destination — decoration costing a permanent strip of a phone screen.
 *
 * `AppDestination.CHATS` restores the premise. Three places the app can *be* — Chats,
 * Calls, Settings — is what a bar is for, and switching between them with a thumb is the
 * whole reason this app should feel like the messaging apps it sits beside.
 *
 * ## Only the bottom inset is taken here
 *
 * Each destination still owns its own `Scaffold`, so this one deliberately reserves
 * nothing but the bar's height. Consuming the rest would leave every screen inside it
 * padding a second time against bars it had already accounted for, which is exactly why
 * the shell stopped wrapping a `Scaffold` when the bar went away.
 *
 * The bar shows only on the three top-level destinations. A dialler, an account editor or
 * a call detail is somewhere you went *to*, and offering to switch tabs from inside one
 * invites losing what you were doing.
 *
 * The back stack still survives rotation, because `rememberNavController` saves it.
 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    /**
     * Runs a video call once the camera has been asked for (Task 74).
     *
     * Defaulted to a pass-through so a test or a preview can render the whole app without
     * a `PermissionCoordinator`. `MainActivity` supplies the real one, which is also the
     * only place that provides the coordinator it needs.
     */
    videoGate: (proceed: () -> Unit) -> Unit = { it() },
) {
    val entry by navController.currentBackStackEntryAsState()
    val current = AppDestination.fromRoute(entry?.destination?.route)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The shell contributes the bar and nothing else; every screen keeps its own.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (current?.isTopLevel == true) {
                AppBottomBar(current = current, onSelect = { navController.switchTab(it) })
            }
        },
    ) { padding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            videoGate = videoGate,
        )
    }
}

/**
 * The three places the app can be.
 *
 * Labels always shown: an icon-only bar makes people guess, and "Chats" and "Calls" are
 * one word each.
 */
@Composable
private fun AppBottomBar(current: AppDestination, onSelect: (AppDestination) -> Unit) {
    // The screen's own colour, with no tonal lift. Material's default containerColor is a
    // raised surface, which on the dark theme drew the bar as a distinct slab with a black
    // band beneath it — the app looked like two apps stacked. The bar is part of the
    // screen, so it is the same colour as the screen, and the selected pill is what says
    // where you are.
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = AppTheme.spacing.none,
    ) {
        AppDestination.TOP_LEVEL.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
                modifier = Modifier.testTag(tabTag(destination)),
            )
        }
    }
}

/**
 * Switches tabs the way a tabbed app is expected to behave.
 *
 * Popping to the start and saving state means a tab remembers where it was, Back from any
 * tab leaves the app rather than walking a history of tab presses, and pressing the tab
 * you are already on does nothing instead of stacking a second copy of it.
 */
private fun NavHostController.switchTab(destination: AppDestination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Identifies a tab, so a test can press the one it means rather than a position. */
internal fun tabTag(destination: AppDestination) = "tab-${destination.route}"

@ThemePreviews
@Composable
private fun AppRootPreview() = PreviewSurface { AppRoot() }
