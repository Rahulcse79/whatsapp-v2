package com.whatsappv2.domain.model

import com.whatsappv2.domain.engine.CallDirection

/**
 * One finished call, as the history screen and the redial button need it (Task 47, §5.2).
 *
 * ## Written once, from the call's own ending
 *
 * Every terminal transition produces exactly one of these, including the calls that never
 * connected: a missed call and a call that failed to a 503 are both things the user needs
 * to see, and a log that recorded only answered calls would be a log of the calls least
 * worth looking up.
 *
 * ## Why the fields are what they are
 *
 * [remoteDisplayName] is what the peer sent in its `From`/`To` header and [contactName] is
 * what the address book said, kept apart because they disagree: the header is whatever the
 * far end chose to call itself, while the contact name is the user's own word for that
 * person and is the one to show. Task 49 fills the second in; until then it is null and
 * the screen falls back through display name to the raw address.
 *
 * [answeredAtEpochMillis] is null for a call that never connected, which is what makes
 * "missed" a fact rather than a guess: [durationSeconds] is then zero and [wasAnswered] is
 * false, and no reader has to decide what a zero-length answered call would mean.
 */
data class CallLogEntry(
    /** Stable identity for this row. The engine's [CallId] is not reused across restarts. */
    val id: CallLogId,

    /** The account the call was placed on or arrived at. */
    val accountId: AccountId,

    /** The far end. Redacted in [toString] because it is a phone number (§7). */
    val remote: SipUri,

    /** Display name from the peer's own header, when it sent one. */
    val remoteDisplayName: String?,

    /** The name the address book gave this address, when it knew it (Task 49). */
    val contactName: String?,

    val direction: CallDirection,

    /** When the call was created locally, answered or not. */
    val startedAtEpochMillis: Long,

    /** When media started flowing, or null for a call that never connected. */
    val answeredAtEpochMillis: Long?,

    /** When the call reached a terminal state. */
    val endedAtEpochMillis: Long,

    /** Why it ended. Task 44 turns this into the sentence the screen shows. */
    val reason: HangupReason,

    /** What was negotiated. Audio for a call that never connected. */
    val media: MediaProfile,
) {

    /** True when media flowed at all — the distinction "missed" is drawn from. */
    val wasAnswered: Boolean get() = answeredAtEpochMillis != null

    /**
     * How long the two ends were connected, in whole seconds.
     *
     * Measured from the answer rather than from the dial: a call that rang for thirty
     * seconds and lasted five did not last thirty-five, and a list that said so would
     * make every missed call look like a conversation. Zero for a call never answered.
     */
    val durationSeconds: Long
        get() = answeredAtEpochMillis
            ?.let { (endedAtEpochMillis - it).coerceAtLeast(0L) / MILLIS_PER_SECOND }
            ?: 0L

    /**
     * True for an inbound call the user never took.
     *
     * Outbound calls are never missed — nobody misses their own call — so direction is
     * part of the question, not just the absence of an answer.
     */
    val wasMissed: Boolean
        get() = direction == CallDirection.INCOMING && !wasAnswered

    override fun toString(): String =
        "CallLogEntry(id=$id, direction=$direction, reason=$reason, answered=$wasAnswered)"

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * A call log row's identity.
 *
 * Its own type rather than a bare `Long`, so a row id and a [CallId] cannot be passed to
 * each other's functions — they name different things and only one of them survives a
 * restart.
 */
@JvmInline
value class CallLogId(val value: Long) {
    companion object {
        /** What an entry carries before the store has assigned it a row. */
        val UNSAVED = CallLogId(0L)
    }
}
