package com.whatsappv2.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Groups
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
 * ## These stopped being tabs (Tasks 69, 70)
 *
 * There was a bottom bar with Dialer, Calls, Accounts and Settings on it. Removing the
 * Dialer and Settings tabs left one item, and a one-item bottom bar is chrome that
 * navigates nowhere — so the bar went too. Calls is now the app's home, and the other four
 * are reached from it: the dialler and the group page from floating buttons, settings from
 * the top-right icon, and the account list from inside settings.
 *
 * Each still keeps a [label] and an [icon], which are now the words and glyph used by
 * whatever opens it rather than by a tab.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HISTORY("history", "Calls", Icons.Filled.History),
    DIALER("dialer", "Dialer", Icons.Filled.Dialpad),
    GROUP("group", "Group call", Icons.Filled.Groups),
    ACCOUNTS("accounts", "Accounts", Icons.Filled.AccountCircle),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    ;

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

        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}
