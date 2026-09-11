package com.whatsappv2.feature.history

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogFilter

/**
 * What the history screen can do, gathered into one value.
 *
 * Fourteen callbacks, all the same kind of thing: something the user did. Grouping them
 * keeps the screen's signature readable and means the next control adds a field here rather
 * than another argument threaded through the rows. `:feature:dialer` and `:feature:calls`
 * group their own for the same reason.
 *
 * The last one leaves this module entirely (Task 70). Calls is the app's home screen, so
 * the dialler is reached from it — and `:feature:history` may not navigate to another
 * feature, so it says *that the user asked* and `:app` decides what that opens. Settings
 * used to be reached from here too; it is behind the gear on Chats now, and the callback
 * went with it rather than staying as a parameter nothing reads.
 */
@Stable
data class HistoryActions(
    val onFilterChanged: (CallLogFilter) -> Unit = {},

    /** Opens or closes the search field. Closing clears what was typed. */
    val onSearchToggled: (Boolean) -> Unit = {},

    val onSearchTextChanged: (String) -> Unit = {},

    /** Narrows by direction, with missed as its own case (CallDirectionFilter). */
    val onDirectionChanged: (CallDirectionFilter) -> Unit = {},

    /** Back to everything, without closing a search field still being typed in. */
    val onFiltersCleared: () -> Unit = {},
    /** The whole row, so the detail sheet is headed with the name the list showed. */
    val onEntryOpened: (HistoryRow.Call) -> Unit = {},
    val onDetailDismissed: () -> Unit = {},
    val onDelete: (CallLogEntry) -> Unit = {},
    val onClearAllRequested: () -> Unit = {},
    val onClearAllDismissed: () -> Unit = {},
    val onClearAllConfirmed: () -> Unit = {},
    /** Redial. The entry rather than the id, because the dialler needs the address. */
    val onCallBack: (CallLogEntry) -> Unit = {},
    /** Redial with video (Task 75). Downgrades to audio when the camera cannot be used. */
    val onVideoCallBack: (CallLogEntry) -> Unit = {},
    /** Open the dialler, which is a floating button on this screen rather than a tab (Task 70). */
    val onOpenDialer: () -> Unit = {},
)
