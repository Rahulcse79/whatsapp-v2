package com.whatsappv2.feature.history

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallLogFilter

/**
 * What the history screen can do, gathered into one value.
 *
 * Eight callbacks, all the same kind of thing: something the user did. Grouping them keeps
 * the screen's signature readable and means the next control adds a field here rather than
 * another argument threaded through the rows. `:feature:dialer` and `:feature:calls` group
 * their own for the same reason.
 */
@Stable
data class HistoryActions(
    val onFilterChanged: (CallLogFilter) -> Unit = {},
    val onEntryOpened: (CallLogEntry) -> Unit = {},
    val onDetailDismissed: () -> Unit = {},
    val onDelete: (CallLogEntry) -> Unit = {},
    val onClearAllRequested: () -> Unit = {},
    val onClearAllDismissed: () -> Unit = {},
    val onClearAllConfirmed: () -> Unit = {},
    /** Redial. The entry rather than the id, because the dialler needs the address. */
    val onCallBack: (CallLogEntry) -> Unit = {},
)
