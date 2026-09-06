package com.whatsappv2.data.sip.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.ConferenceParticipant
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.ParticipantId
import com.whatsappv2.domain.model.SipUri

/**
 * Turns a bridge's roster into the domain's conference model (Task 60, §2.2).
 *
 * Pure, for the same reason as the other two mappers in this package: it is the part that
 * can be tested without a device, so it is the part that holds the decisions.
 *
 * ## Two decisions, and both are about honesty
 *
 * **An unparseable address is not a missing participant.** Somebody the bridge names with
 * an address this app cannot parse is still in the room and still audible. They are kept,
 * with a null [ConferenceParticipant.uri] — the model allows exactly that, and dropping
 * them would show a count lower than the number of voices.
 *
 * **An empty roster is not the same as no roster.** A bridge that publishes nothing and a
 * conference nobody has joined produce the same empty list, and the UI has to say
 * different things about them (§13). [StackConferenceEvent.rosterAvailable] carries the
 * difference and this preserves it rather than inferring it from emptiness.
 */
internal object ConferenceMapper {

    /** [session] with [event]'s roster applied. */
    fun apply(session: ConferenceSession, event: StackConferenceEvent): ConferenceSession =
        if (!event.rosterAvailable) {
            session.withoutRoster()
        } else {
            session.withRoster(event.participants.map(::toDomain))
        }

    private fun toDomain(participant: StackParticipant) = ConferenceParticipant(
        id = ParticipantId(participant.id),
        // Null rather than dropped: see the class note. A voice with an address we cannot
        // render is still a voice in the room.
        uri = participant.uri?.let { SipUri.parse(it).getOrNull() },
        displayName = participant.displayName?.takeIf { it.isNotBlank() },
        isMuted = participant.isMuted,
        isSpeaking = participant.isSpeaking,
        isSelf = participant.isSelf,
        hasVideoStream = participant.hasVideoStream,
        joinedAtEpochMillis = participant.joinedAtEpochMillis,
    )
}
