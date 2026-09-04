package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri

/**
 * An inbound INVITE that has not yet been answered or rejected.
 *
 * Delivered on a separate [kotlinx.coroutines.flow.Flow] rather than only appearing in
 * `activeCalls`, because ringing is an **event** that must be acted on exactly once —
 * it starts a foreground service and posts a full-screen notification. Deriving that
 * from a state diff would fire again on every re-emission and re-ring a call the user
 * already answered.
 */
data class IncomingCall(
    val callId: CallId,

    /** The account the call arrived on; a multi-account client must show which. */
    val accountId: AccountId,

    /** The caller. Redacted in [toString] (§7, DoD 12). */
    val from: SipUri,

    /** Display name the caller asserted. Untrusted: callers set their own. */
    val fromDisplayName: String?,

    /** What the caller offered. Video here means the UI must offer a video answer. */
    val offeredMedia: MediaProfile,

    val receivedAtEpochMillis: Long,

    /**
     * True when this INVITE arrived after a push wake-up rather than on a socket that
     * was already registered (§2.5). Useful for diagnosing the push path in the field.
     */
    val viaPush: Boolean = false,
) {
    override fun toString(): String =
        "IncomingCall(id=$callId, account=$accountId, from=$from, media=$offeredMedia, viaPush=$viaPush)"
}
