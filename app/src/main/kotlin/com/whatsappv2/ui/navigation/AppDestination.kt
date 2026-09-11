package com.whatsappv2.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's top-level destinations.
 *
 * An enum rather than loose route strings: the nav graph and everything that navigates to
 * it read from one list, so a destination cannot be registered under a route nothing can
 * reach, and a typo cannot become a screen that silently never opens.
 *
 * ## They stopped being tabs, and three of them are tabs again
 *
 * Tasks 69 and 70 removed a bottom bar carrying Dialer, Calls, Accounts and Settings.
 * Dropping the Dialer and Settings tabs left one item, and — correctly — "a one-item
 * bottom bar is chrome that navigates nowhere", so the bar went with them.
 *
 * [CHATS] restores the premise rather than overturning the reasoning. A chat module is
 * coming from another team, and it is a destination of the same weight as Calls: not a
 * page reached from somewhere, a place the app can *be*. With three such places a bar
 * earns its strip of screen again, which is exactly the test Task 70 applied and failed.
 *
 * [isTopLevel] marks the three. The rest stay where they are: the dialler is still a
 * floating button on Calls, and accounts still live inside Settings — putting either in
 * the bar would be the four-tab arrangement that was removed for good reason.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    /**
     * Chat, which another team is building.
     *
     * A real destination with a placeholder behind it, on purpose: the tab, the route and
     * the back-stack behaviour are settled now, so the module that arrives has a slot to
     * drop into rather than a navigation redesign to negotiate.
     */
    CHATS("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    HISTORY("history", "Calls", Icons.Filled.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    DIALER("dialer", "Dialer", Icons.Filled.Dialpad),
    ACCOUNTS("accounts", "Accounts", Icons.Filled.AccountCircle),
    ;

    /** True for the destinations the bottom bar switches between, in bar order. */
    val isTopLevel: Boolean get() = this in TOP_LEVEL

    companion object {
        /**
         * Where the app opens.
         *
         * Calls, since Task 70. It was the dialler, on the reasoning that placing a call is
         * the app's primary job — which is still true, and is why the dialler is one tap
         * away behind a floating button. But the screen worth *landing* on is the one that
         * answers "what happened while I was away", and the log is that screen.
         */
        val START: AppDestination = HISTORY

        /**
         * The bar, in order. Chats sits first because that is where a messaging app opens
         * once it has messages; the app still *starts* on Calls until the module lands,
         * which is what [START] says and what makes this list a layout rather than a
         * promise.
         */
        val TOP_LEVEL: List<AppDestination> = listOf(CHATS, HISTORY, SETTINGS)

        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}
