package com.whatsappv2.feature.accounts

/**
 * What to tell the user after a save, and how hard to insist (Task 73).
 *
 * A value rather than a plain string because "saved" and "saved but you are now
 * unreachable" are not the same news. [isWarning] is what keeps the second one on screen
 * until it is dismissed; §5.1 calls a silent partial re-registration a bug, and a message
 * that disappears after two seconds is most of the way to silent.
 */
data class AccountSavedMessage(val text: String, val isWarning: Boolean)
