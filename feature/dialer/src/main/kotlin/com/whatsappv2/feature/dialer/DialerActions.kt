package com.whatsappv2.feature.dialer

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.contacts.SipContact
import com.whatsappv2.domain.model.AccountId

/**
 * What the dialler can do, gathered into one value.
 *
 * Nine callbacks is a long parameter list by any measure, and every one of them is the
 * same kind of thing: something the user did. Grouping them keeps the screen's signature
 * readable and means a new key or shortcut adds a field here rather than another argument
 * to thread through three call sites.
 */
@Stable
data class DialerActions(
    val onInputChanged: (String) -> Unit = {},
    val onDigit: (Char) -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onAccountSelected: (AccountId) -> Unit = {},
    val onRecentSelected: (String) -> Unit = {},
    /** Call this contact on the account the screen is showing (Task 50). */
    val onContactSelected: (SipContact) -> Unit = {},
    val onCall: () -> Unit = {},
    /**
     * Place the call with video (Task 74).
     *
     * Its own callback rather than a boolean on [onCall], because the two are separate
     * buttons and a boolean would mean the screen deciding which one was pressed twice.
     */
    val onVideoCall: () -> Unit = {},
    /** Leave the dialler. It is a screen reached from Calls now, not a tab (Task 70). */
    val onBack: () -> Unit = {},
)
