package com.whatsappv2.domain.engine

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri

/** Who started the call. Needed by the call log and by the incoming-call UI. */
enum class CallDirection {
    INCOMING,
    OUTGOING,
}

/**
 * An immutable view of one call at a moment in time.
 *
 * A snapshot rather than a live object: it is emitted through a [kotlinx.coroutines.flow.StateFlow]
 * and may be held by a ViewModel across a configuration change, so it must not be a
 * handle onto engine-owned mutable state.
 *
 * Timestamps are epoch milliseconds rather than a date-time type. `:domain` is a pure
 * Kotlin module with no clock of its own, and the values come from the engine; keeping
 * them primitive avoids pulling a date-time dependency into the layer that most needs
 * to stay dependency-free.
 */
data class CallSnapshot(
    val callId: CallId,

    /** Which configured account this call belongs to. */
    val accountId: AccountId,

    /** The far end. Redacted in [toString] because it is a phone number (§7). */
    val remote: SipUri,

    /** Display name from the `From`/`To` header, when the peer sent one. */
    val remoteDisplayName: String?,

    val direction: CallDirection,

    /** Phase plus controls — see [com.whatsappv2.domain.call.CallStateMachine]. */
    val state: CallState,

    /** Media currently negotiated. Changes on a re-INVITE that adds or drops video. */
    val media: MediaProfile,

    /** When the call was created locally. */
    val startedAtEpochMillis: Long,

    /** When media started flowing, or `null` if it never did. */
    val connectedAtEpochMillis: Long?,

    /** True while this call is part of a conference (§2.2). */
    val isConference: Boolean = false,
) {
    /** True when the call has been answered and media has flowed at least once. */
    val wasAnswered: Boolean get() = connectedAtEpochMillis != null

    /**
     * Talk time in milliseconds at [nowEpochMillis], or `null` before the call
     * connected. The caller supplies the clock so this stays a pure function.
     */
    fun durationMillis(nowEpochMillis: Long): Long? =
        connectedAtEpochMillis?.let { (nowEpochMillis - it).coerceAtLeast(0) }

    /** Redacted: the remote URI is a phone number or extension (§7, DoD 12). */
    override fun toString(): String =
        "CallSnapshot(id=$callId, account=$accountId, remote=$remote, " +
            "direction=$direction, state=$state, conference=$isConference)"
}
