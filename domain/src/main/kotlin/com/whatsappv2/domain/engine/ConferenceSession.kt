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
}
