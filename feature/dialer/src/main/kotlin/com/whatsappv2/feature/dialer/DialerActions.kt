package com.whatsappv2.feature.dialer

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.model.AccountId

/**
 * What the dialler can do, gathered into one value.
 *
 * Seven callbacks is a long parameter list by any measure, and every one of them is the
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
    val onCall: () -> Unit = {},
)
