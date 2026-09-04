package com.whatsappv2.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.ui.navigation.AppDestination
import com.whatsappv2.ui.navigation.AppNavHost

/**
 * The app shell: a bottom bar over a single navigation host.
 *
 * `Scaffold` consumes the window insets, so nothing here hardcodes a system-bar height
 * — the app draws edge to edge and still keeps content clear of the bars.
 *
 * The back stack survives rotation because `rememberNavController` saves it, and the
 * selected tab is derived from the current destination rather than held in a separate
 * variable. Two sources of truth for "which tab is selected" is how a rotation ends up
 * showing one screen with a different tab highlighted.
 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = {
                            // The label below is always visible, so announcing the icon
                            // as well would read the same thing twice.
                            Icon(destination.icon, contentDescription = null)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Switches top-level tab.
 *
 * `launchSingleTop` and popping to the start destination keep the back stack from
 * growing every time a tab is tapped; `saveState`/`restoreState` mean each tab keeps its
 * own scroll position, which is what people expect from a bottom bar.
 */
private fun NavHostController.navigateToTopLevel(destination: AppDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@ThemePreviews
@Composable
private fun AppRootPreview() = PreviewSurface { AppRoot() }
