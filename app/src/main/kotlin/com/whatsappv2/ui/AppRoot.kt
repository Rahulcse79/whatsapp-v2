package com.whatsappv2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
 * `AppDestination.CHATS` restores the premise. Two places the app can *be* — Chats and
 * Calls — is what a bar is for, and switching between them with a thumb is the whole
 * reason this app should feel like the messaging apps it sits beside. Settings sat in the
 * bar briefly and left again: it is behind a gear on Chats now, because it is somewhere
 * you go and come back from, not somewhere the app is.
 *
 * ## Only the bottom inset is taken here — and it is *consumed*, not just padded
 *
 * Each destination still owns its own `Scaffold`, so this one deliberately reserves
 * nothing but the bar's height. Consuming the rest would leave every screen inside it
 * padding a second time against bars it had already accounted for, which is exactly why
 * the shell stopped wrapping a `Scaffold` when the bar went away.
 *
 * The bar itself sits on the system navigation inset and draws behind it, which is right.
 * What was wrong was the hand-off: `Modifier.padding` tells the screens how far to move
 * up but says nothing about insets, so every inner `Scaffold` still saw the system bar
 * inset underneath the padding and added it again — a second strip the height of the
 * three-button bar between each screen's content and this bar, with the floating button
 * floating 48 dp too high. `consumeWindowInsets` on the same padding is the one line that
 * tells the screens the bar has already dealt with it. Nothing about the bar's height is
 * hardcoded anywhere: the shell measures the bar and the screens are told the result.
 *
 * The bar shows only on the two top-level destinations. A dialler, settings, an account
 * editor or a call detail is somewhere you went *to*, and offering to switch tabs from
 * inside one invites losing what you were doing.
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
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                // The bar's height includes the system inset it sat on; consuming the
                // same padding is what stops every screen re-adding that inset.
                .consumeWindowInsets(padding),
            videoGate = videoGate,
        )
    }
}

/**
 * The two places the app can be.
 *
 * Labels always shown: an icon-only bar makes people guess, and "Chats" and "Calls" are
 * one word each.
 *
 * ## Why this is a `Row` of `NavigationBarItem`s and not a `NavigationBar`
 *
 * Material's `NavigationBar` is 80 dp tall and centres a 32 dp indicator and a label
 * inside it, which leaves a quarter of the bar as empty space above the icons — the
 * "extra space above the icons" that was reported. Material's own answer is the 64 dp
 * short bar, but in this Compose release that API is still sealed behind an internal
 * opt-in, so the bar is assembled here from the same items on a row of the height the
 * design system says (`AppTheme.sizing.bottomBar`). The items, their pill, their ripple
 * and their colours are Material's; only the container height is ours.
 *
 * The screen's own colour, with no tonal lift. Material's default containerColor is a
 * raised surface, which on the dark theme drew the bar as a distinct slab with a black
 * band beneath it — the app looked like two apps stacked. The bar is part of the
 * screen, so it is the same colour as the screen, and the selected pill is what says
 * where you are. The system navigation inset is padded *inside* the surface, so the
 * colour runs under the gesture bar rather than stopping above it.
 */
@Composable
private fun AppBottomBar(current: AppDestination, onSelect: (AppDestination) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .height(AppTheme.sizing.bottomBar)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
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
