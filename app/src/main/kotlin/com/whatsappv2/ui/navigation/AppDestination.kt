package com.whatsappv2.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's top-level destinations.
 *
 * An enum rather than loose route strings: the nav graph and everything that navigates to
 * it read from one list, so a destination cannot be registered under a route nothing can
 * reach, and a typo cannot become a screen that silently never opens.
 *
 * ## They stopped being tabs, two of them are tabs again, and Settings is not
 *
 * Tasks 69 and 70 removed a bottom bar carrying Dialer, Calls, Accounts and Settings.
 * Dropping the Dialer and Settings tabs left one item, and — correctly — "a one-item
 * bottom bar is chrome that navigates nowhere", so the bar went with them.
 *
 * [CHATS] restores the premise rather than overturning the reasoning. A chat module is
 * coming from another team, and it is a destination of the same weight as Calls: not a
 * page reached from somewhere, a place the app can *be*. With two such places a bar
 * earns its strip of screen again, which is exactly the test Task 70 applied and failed.
 *
 * [SETTINGS] came back into the bar with Chats and has left it again. Settings is not
 * somewhere the app *is*; it is somewhere you go to change how the app behaves and then
 * leave, and the messaging apps this one sits beside all put it behind a gear at the top
 * right of the first tab. That is where it lives now — a gear on Chats — and the bar
 * carries the two places you actually switch between. Two destinations still earn a
 * bar; if Chats ever leaves, Task 70's reasoning applies again and the bar goes with it.
 *
 * [isTopLevel] marks the two. The rest stay where they are: the dialler is still a
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
    RECORDINGS("recordings", "Recordings", Icons.Filled.Mic),
    ;

    /** True for the two destinations the bottom bar switches between, in bar order. */
    val isTopLevel: Boolean get() = this in TOP_LEVEL

    companion object {
        /**
         * Where the app opens.
         *
         * Chats — the first item in the bar, which is where a bar's first item should
         * land you.
         *
         * It was the dialler, then Calls, on the reasoning that the screen worth landing
         * on is the one answering "what happened while I was away". That argument was
         * about a phone. This is a messaging app that also calls, its bar opens on Chats,
         * and a bar whose first tab is not the one the app starts on makes the user's
         * first action every launch a correction. The call log is one tap away and keeps
         * its badge for anything missed.
         *
         * Chats is a placeholder until that module lands, and starting on it is still
         * right: it is the shell's front door, and moving the front door once the
         * furniture arrives is the change nobody wants to make twice.
         */
        val START: AppDestination = CHATS

        /**
         * The bar, in order, and [START] is its first item. Settings is deliberately
         * absent — see the class comment.
         */
        val TOP_LEVEL: List<AppDestination> = listOf(CHATS, HISTORY)

        fun fromRoute(route: String?): AppDestination? = entries.firstOrNull { it.route == route }
    }
}
