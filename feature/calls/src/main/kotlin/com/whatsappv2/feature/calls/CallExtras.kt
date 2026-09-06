package com.whatsappv2.feature.calls

import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.model.CallId

/**
 * An escalation the far end has asked for, awaiting an answer (Task 54, §5.2).
 *
 * Present in the state rather than delivered as an event, and that is the requirement: a
 * prompt shown by a one-shot event disappears on rotation and leaves the peer's re-INVITE
 * unanswered until it times out. As state, the question survives whatever the screen does
 * until somebody answers it.
 */
data class PendingVideoRequest(
    val callId: CallId,

    /** Who asked, already resolved to a name where the address book knew one. */
    val from: String,
)

/** Where a transfer has got to, for the screen to say (Task 55, DoD 10). */
sealed interface TransferUiState {

    /** No transfer is happening, and none has failed recently enough to still show. */
    data object Idle : TransferUiState

    /** The user is choosing a destination. */
    data class Choosing(val input: String = "") : TransferUiState

    /**
     * A REFER is in flight.
     *
     * [detail] is the sipfrag progress when the server sent any — "ringing" rather than a
     * spinner that could mean anything. Null until something is known, which is honest:
     * between the 202 and the first NOTIFY nobody knows more than "it was accepted".
     */
    data class InProgress(val target: String, val detail: String? = null) : TransferUiState

    /**
     * The transfer failed and the call came back (§5.2).
     *
     * Shown rather than logged, and with the reason: the caller is still on the line, so
     * "that extension is busy" is something the user can act on in the next few seconds.
     */
    data class Failed(val target: String, val reason: String) : TransferUiState

    /**
     * A consultation call is up and the user is deciding (Task 57).
     *
     * Holds both legs, because completing the transfer needs both and cancelling needs to
     * know which one to hang up and which to bring back.
     */
    data class Consulting(val callId: CallId, val consultationCallId: CallId, val target: String) :
        TransferUiState
}

/**
 * A second call arriving while one is in progress (Task 56, §5.2).
 *
 * The three answers are offered together because they are genuinely different outcomes
 * for the first caller — held, hung up, or untouched — and a UI that offers only
 * accept/reject silently picks one of them.
 */
data class SecondCallPrompt(
    val callId: CallId,

    /** Who is calling, resolved to a name where one was known. */
    val from: String,

    /** Who they would be interrupting, so the choice is between two named people. */
    val currentCallWith: String,
)

/** One row of a conference roster (Task 60). */
data class ConferenceParticipantRow(
    val id: String,

    /** Their name, or their address, or "Unknown" — in that order of preference. */
    val label: String,

    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val isSelf: Boolean,
)

/**
 * The conference this call is part of, as the screen renders it (Task 60, §2.2).
 *
 * ## An absent roster is a thing to say, not a thing to hide
 *
 * [rosterAvailable] false means the bridge publishes no participant list at all. Rendering
 * that as an empty list would tell the user they are alone in a room they can hear other
 * people in, and inventing entries to fill it would be worse — §13 forbids exactly that,
 * and Task 60's third done-when asks for the screen to say so instead.
 */
data class ConferenceUiState(
    val participants: List<ConferenceParticipantRow>,
    val rosterAvailable: Boolean,
) {
    /** How many people are in the conference, or null when the bridge does not say. */
    val count: Int? get() = participants.size.takeIf { rosterAvailable }
}

/** Builds the screen's conference model from the engine's (Task 60). */
internal fun ConferenceSession.toUiState(unknownLabel: String): ConferenceUiState = ConferenceUiState(
    participants = participants.map { participant ->
        ConferenceParticipantRow(
            id = participant.id.value,
            // Name, then address, then a placeholder: a bridge may know somebody is there
            // without knowing anything about them, and an empty row is still a person.
            label = participant.displayName?.takeIf { it.isNotBlank() }
                ?: participant.uri?.render()
                ?: unknownLabel,
            isMuted = participant.isMuted,
            isSpeaking = participant.isSpeaking,
            isSelf = participant.isSelf,
        )
    },
    rosterAvailable = rosterAvailable,
)

/** Whether this call is being recorded, and therefore whether the indicator is on (Task 58). */
data class RecordingUiState(
    val isRecording: Boolean = false,

    /**
     * True while the consent dialog is up.
     *
     * Recording cannot start without passing through this: §2.6 forbids a silent recorder,
     * and the dialog is where the user is told what is and is not captured.
     */
    val askingConsent: Boolean = false,
)
