package com.whatsappv2.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The top-level destinations, in bottom-bar order.
 *
 * An enum rather than loose route strings: the bar, the nav graph and the selected-state
 * logic all read from one list, so a destination cannot exist in the graph but be
 * unreachable from the bar — or appear in the bar and navigate nowhere.
 *
 * Dialer is first because placing a call is the app's primary job.
 */
enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    DIALER("dialer", "Dialer", Icons.Filled.Dialpad),
    HISTORY("history", "Calls", Icons.Filled.History),
    ACCOUNTS("accounts", "Accounts", Icons.Filled.AccountCircle),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    ;

    companion object {
        /** Where the app opens. */
        val START: AppDestination = DIALER

        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}
