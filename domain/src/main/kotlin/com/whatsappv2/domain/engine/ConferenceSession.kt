package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri

/** Identifies one participant within a conference. Opaque; do not parse it. */
@JvmInline
value class ParticipantId(val value: String) {
    init {
        require(value.isNotBlank()) { "ParticipantId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * One person in a conference.
 *
 * Modelled per participant even though the dial-in MCU (ADR-003) delivers a single
 * mixed stream. That is the point: §2.2 requires the domain to be shaped so moving to
 * an SFU is an implementation swap in `:data:sip`, not a rewrite. Under an MCU,
 * [hasVideoStream] is simply false for everyone and the roster comes from the server.
 */
data class ConferenceParticipant(
    val id: ParticipantId,

    /** The participant's address. Null when the bridge publishes no roster detail. */
    val uri: SipUri?,

    val displayName: String?,

    /** Muted at the bridge, as reported by it — not our local mute. */
    val isMuted: Boolean = false,

    /** True while the bridge reports this participant as the active speaker. */
    val isSpeaking: Boolean = false,

    /** True when this participant is `me`. */
    val isSelf: Boolean = false,

    /**
     * True when a **separate** video stream exists for this participant. Always false
     * under a mixing MCU, where one composed stream carries everyone.
     */
    val hasVideoStream: Boolean = false,

    val joinedAtEpochMillis: Long? = null,
)

/**
 * A conference the local user is joined to (§2.2).
 *
 * Under the dial-in MCU this is one ordinary call to a conference URI, which is why it
 * carries a [callId]: hanging up the conference is hanging up that call.
 */
data class ConferenceSession(
    /** The underlying call leg to the bridge. */
    val callId: CallId,

    val accountId: AccountId,

    /** The conference address that was dialled. */
    val conferenceUri: SipUri,

    /**
     * Everyone currently in the conference, including [ConferenceParticipant.isSelf].
     *
     * Empty when the bridge publishes no roster. The UI must say so rather than render
     * a fabricated list (§13, Task 60) — an invented participant list is worse than
     * none, because it looks authoritative.
     */
    val participants: List<ConferenceParticipant> = emptyList(),

    /**
     * False when the bridge does not publish a roster at all, so an empty
     * [participants] can be told apart from "nobody has joined yet".
     */
    val rosterAvailable: Boolean = false,
) {
    /** Participants other than the local user. */
    val others: List<ConferenceParticipant> get() = participants.filterNot { it.isSelf }

    /** The active speaker, when the bridge reports one. */
    val activeSpeaker: ConferenceParticipant? get() = participants.firstOrNull { it.isSpeaking }

    /** True when any participant has a separate video stream — i.e. an SFU, not an MCU. */
    val hasPerParticipantVideo: Boolean get() = participants.any { it.hasVideoStream }

    /**
     * How many people are in the conference, as the bridge reports it.
     *
     * `null` when there is no roster: a count of zero would be a claim, and the honest
     * answer to "how many" from a bridge that publishes nothing is "not known" (§13).
     */
    val participantCount: Int? get() = participants.size.takeIf { rosterAvailable }

    /**
     * The same conference with [participant] present (Task 59).
     *
     * Replaces any entry with the same [ParticipantId] rather than appending: a bridge
     * that re-announces someone on a full-state NOTIFY would otherwise show them twice,
     * and a duplicated participant is a wrong count on a screen whose whole job is to say
     * who is here.
     *
     * A join makes the roster available, whatever it was before. Hearing about anybody at
     * all is proof the bridge publishes one — which is the only thing
     * [rosterAvailable] claims.
     */
    fun withParticipantJoined(participant: ConferenceParticipant): ConferenceSession = copy(
        participants = participants.filterNot { it.id == participant.id } + participant,
        rosterAvailable = true,
    )

    /**
     * The same conference without [id].
     *
     * [rosterAvailable] deliberately stays true when the last participant leaves. The two
     * facts are different — "the bridge tells us who is here" and "nobody is here" — and
     * collapsing them is what makes an empty conference indistinguishable from a silent
     * one (Task 60's third done-when).
     */
    fun withParticipantLeft(id: ParticipantId): ConferenceSession =
        copy(participants = participants.filterNot { it.id == id })

    /** The same conference with [id] muted or unmuted **at the bridge**, not locally. */
    fun withParticipantMuted(id: ParticipantId, muted: Boolean): ConferenceSession =
        mapParticipant(id) { it.copy(isMuted = muted) }

    /**
     * The same conference with [id] as the only speaker, or nobody speaking when null.
     *
     * Exclusive by construction: the bridge reports one active speaker, and a model that
     * let two be true at once would let a UI highlight two tiles for a state the server
     * never described.
     */
    fun withActiveSpeaker(id: ParticipantId?): ConferenceSession =
        copy(participants = participants.map { it.copy(isSpeaking = it.id == id) })

    /** The same conference with [id]'s separate video stream present or gone (an SFU). */
    fun withParticipantVideo(id: ParticipantId, hasVideo: Boolean): ConferenceSession =
        mapParticipant(id) { it.copy(hasVideoStream = hasVideo) }

    /**
     * The whole roster, replaced (a full-state NOTIFY).
     *
     * Separate from the incremental transitions because it means something different: the
     * bridge is stating the complete list, so anyone missing from it has left, even if no
     * departure was ever announced. Applying it as a series of joins would keep ghosts.
     */
    fun withRoster(roster: List<ConferenceParticipant>): ConferenceSession =
        copy(participants = roster, rosterAvailable = true)

    /** Records that this bridge publishes no roster at all, which the UI must say (Task 60). */
    fun withoutRoster(): ConferenceSession = copy(participants = emptyList(), rosterAvailable = false)

    private fun mapParticipant(
        id: ParticipantId,
        transform: (ConferenceParticipant) -> ConferenceParticipant,
    ): ConferenceSession =
        copy(participants = participants.map { if (it.id == id) transform(it) else it })
}
