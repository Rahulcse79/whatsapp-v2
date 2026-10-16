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
 *
 * [accountDomain] is what the account's domain was when the call happened. [remote] alone
 * cannot say whether its host is the far end's own address or just the server's — see
 * [redialTarget], which is the reader that needs to know.
 */
data class CallLogEntry(
    /** Stable identity for this row. The engine's [CallId] is not reused across restarts. */
    val id: CallLogId,

    /** The account the call was placed on or arrived at. */
    val accountId: AccountId,

    /** The far end. Redacted in [toString] because it is a phone number (§7). */
    val remote: SipUri,

    /**
     * The account's domain at the time, or null for a row written before it was recorded
     * (call log schema 1).
     */
    val accountDomain: String?,

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

    /**
     * True when this call was part of a conference — either kind (ADR-003, ADR-009).
     *
     * ## One row per leg, marked, rather than one row per conference
     *
     * A dial-in conference is a single call to the bridge, so it is already one row and
     * this only labels it. A conference **this device** mixed is N calls, each dialled at
     * a different moment and each ending at its own, and collapsing them into one row
     * would have to invent a start, an end and a duration that no leg actually had. So
     * every leg keeps its own row and its own truth, and this says they belonged
     * together — which is the thing the history screen could not show at all before: a
     * merged call read as two unrelated calls to two people.
     *
     * Never cleared once set. A leg that left the conference before the others still
     * *was* in one, and a log is a record of what happened rather than of what is still
     * true.
     */
    val isConference: Boolean = false,

    /**
     * Which conference the leg belonged to, so the legs of one can be shown as one.
     *
     * The rows stay per leg — see [isConference] for why — but a history that lists a
     * six-way call as six rows to six people asks the reader to reassemble the meeting
     * from the timestamps. The key is what lets the screen do that instead: every leg
     * this device mixed into the same conference carries the same value, and legs with
     * different values were different conferences, however close in time. Null on rows
     * written before the column existed and on dial-in rooms, which are one row already.
     */
    val conferenceKey: String? = null,
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

    /**
     * What to dial to reach the far end again, as [DialledTarget] input.
     *
     * ## The extension, not the address, when the far end is on the account's own server
     *
     * [remote]'s host is whatever the account's domain was when the call happened: for an
     * outgoing call because [DialledTarget] completed the extension against it, for an
     * incoming one because the server put its own address in `From`. That host belongs to
     * the server, not to the person. When the server moves — a laptop-hosted PBX that
     * followed its owner to another Wi-Fi network, 2026-09-14 — every row still reads
     * "1003", and calling any of them back sends the INVITE to the previous network's
     * address, where nothing answers and Timer B ends the call 32 s later. The extension
     * still reaches them; the address does not. So the user part alone is returned, for
     * [DialledTarget] to complete against the domain the account has *now*.
     *
     * A far end on some other domain keeps its full address: it was dialled as one on
     * purpose, and rewriting it is the silent rewrite [DialledTarget] refuses.
     *
     * A null [accountDomain] is a row from before the domain was recorded. Those are
     * treated as on the account's own server, because every such row on every handset was
     * — and treating them as foreign would leave exactly the rows this exists for dead.
     */
    fun redialTarget(): String {
        val extension = remote.user ?: return remote.render()
        val recorded = accountDomain ?: return extension
        return if (remote.host.rendered.equals(recorded.trim(), ignoreCase = true)) {
            extension
        } else {
            remote.render()
        }
    }

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
