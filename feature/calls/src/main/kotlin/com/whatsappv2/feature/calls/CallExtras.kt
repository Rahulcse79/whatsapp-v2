package com.whatsappv2.feature.calls

import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri

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

    /**
     * True when the call in progress is a live conference this device mixes, so the
     * caller can be brought into it instead of interrupting it (ADR-009).
     *
     * This is how somebody who missed the conference gets in: they call the host. Not
     * offered over a held conference — the host stepped out of the room, and answering
     * into it would put them back in without asking — and not over a dial-in bridge,
     * which this app cannot add a second leg to.
     */
    val canAddToConference: Boolean = false,
)

/** One row of a conference roster (Task 60). */
data class ConferenceParticipantRow(
    val id: String,

    /** Their name, or their address, or "Unknown" — in that order of preference. */
    val label: String,

    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val isSelf: Boolean,

    /**
     * The member's address — in practice their extension — when [label] is a name.
     *
     * Null when the two would say the same thing, which is the common case on a server
     * that asserts no display name: a row reading "1003" over "1003" is noise. Kept
     * separate from [label] rather than folded into one string so the screen can give the
     * name and the number different weight, and so a test can assert the number is there
     * at all — "which of these six people am I about to remove" is an extension question.
     */
    val detail: String? = null,

    /**
     * What this member is doing, when it is anything other than simply being in the
     * conference: held, or still connecting. Null when there is nothing to say.
     *
     * Worth a mark of its own because a member who is not carrying audio is invisible
     * otherwise — they are in the list, the conference looks whole, and they can neither
     * hear nor be heard. That is exactly the failure this screen exists to make visible.
     * A kind rather than a string, so the screen can give each its own colour as well as
     * its own words.
     */
    val status: ParticipantStatus? = null,

    /** The member's address-book photo, when the address book has one. */
    val photoUri: String? = null,
)

/** A member's state when it is not simply "in the conference". */
enum class ParticipantStatus(val label: String) {
    /** Held by this device: hears nothing and is heard by nobody until resumed. */
    ON_HOLD("On hold"),

    /** Our resume is out and the far end has not answered it yet. */
    REJOINING("Rejoining"),

    /** Ringing, or otherwise not yet carrying audio. */
    CONNECTING("Connecting"),
}

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

/**
 * This device's own row in a conference it is hosting.
 *
 * Its own type rather than two loose strings so the caller cannot swap them, and nullable
 * at the call site because a conference is still worth listing before the account behind
 * it has been read back.
 */
data class LocalParticipant(
    /** The name this account presents, or its extension when it presents none. */
    val label: String,

    /** This device's extension, when it is not already the label. */
    val extension: String?,
)

/**
 * The conference this device is mixing, as a participant list (ADR-009).
 *
 * ## Why a locally-mixed conference needs one at all
 *
 * A dial-in conference has a bridge that may publish a roster; a locally-mixed one has no
 * bridge and needs none, because this device *is* the mixer and therefore already holds
 * the complete membership — every member is one of its own call legs. So where the bridge
 * case has to admit it may not know who is here, this case always knows exactly, and
 * [ConferenceUiState.rosterAvailable] is true by construction rather than by hope.
 *
 * Until now that knowledge went no further than a line of names under the title, which on
 * a conference of six read `1005 · 1001 · 1002 · 1003 · 1005 · 1004` and truncated. The
 * list is where it belongs: one row per member, the name and the extension both, and the
 * state of anyone who is not actually carrying audio.
 *
 * ## The local user is a member, and is listed first
 *
 * A conference of six that lists five people is a conference the user has to count
 * themselves into. They are listed first because the list is read from the top and "am I
 * muted" is the question most often asked of it — which is why the microphone indicator on
 * that row is this device's own mute rather than anything a server said.
 *
 * ## Names come from the address book, because the wire does not carry them
 *
 * A leg this device dialled has no display name from the far end: the 200 OK's To header
 * is whatever this device put in the INVITE, and FreeSWITCH asserts its directory name
 * only on calls it places *to* the phone. So for the conference the host built — every
 * conference — the name is the address book's or nothing, exactly as the watched call's
 * title already is. [contacts] is that lookup, done once per membership rather than once
 * per tick, by the caller.
 *
 * @param calls every call on this device.
 * @param mixed the membership the engine is mixing.
 * @param self this device's own identity, when it is known.
 * @param contacts the address book's answer for each member's address, where it had one.
 * @return null when this device is not mixing a conference, so the screen shows nothing.
 */
internal fun localMixRoster(
    calls: List<CallSnapshot>,
    mixed: Set<CallId>,
    self: LocalParticipant?,
    contacts: Map<SipUri, Contact> = emptyMap(),
): ConferenceUiState? {
    if (mixed.size < SipConferenceController.MINIMUM_MIXED) return null

    val members = calls.filter { it.callId in mixed }
    if (members.isEmpty()) return null

    return ConferenceUiState(
        participants = buildList {
            self?.let {
                add(
                    ConferenceParticipantRow(
                        id = SELF_ROW_ID,
                        label = it.label,
                        // One microphone, one answer. Since the mute fan-out the members
                        // cannot disagree, and `any` rather than `all` is the safe reading
                        // of a moment when they briefly do: reporting a live microphone
                        // that is not is the error that matters.
                        isMuted = members.any { member -> member.state.controlsOrNull?.isMuted == true },
                        isSpeaking = false,
                        isSelf = true,
                        detail = it.extension?.takeIf { extension -> extension != it.label },
                    ),
                )
            }
            members.forEach { add(it.participantRow(contacts[it.remote])) }
        },
        rosterAvailable = true,
    )
}

/**
 * One member of the local mix, as a row.
 *
 * The address book's name first, then the name the far end asserted, then the extension —
 * the same order [toDisplay] uses for the watched call's title, so a person is called the
 * same thing in the list as over it.
 */
private fun CallSnapshot.participantRow(contact: Contact?): ConferenceParticipantRow {
    val address = remote.user ?: remote.host.rendered
    val name = contact?.displayName?.takeIf { it.isNotBlank() } ?: label()
    return ConferenceParticipantRow(
        id = callId.value,
        label = name,
        isMuted = false,
        isSpeaking = false,
        isSelf = false,
        // The address only when the label is something else — a name.
        detail = address.takeIf { it != name },
        status = memberStatus(),
        photoUri = contact?.photoUri,
    )
}

/**
 * What to say about a member that is not simply present, or null when it is.
 *
 * Only states that mean "this member is not carrying audio right now", because that is the
 * only thing the list can usefully add: a member who is held hears nothing and is heard by
 * nobody, and a conference that looks whole while one member is deaf is the failure this
 * list exists to surface. `Connected` says nothing, because being in the conference is
 * what every row already means.
 */
private fun CallSnapshot.memberStatus(): ParticipantStatus? = when (state) {
    is CallState.Held -> ParticipantStatus.ON_HOLD
    is CallState.Resuming -> ParticipantStatus.REJOINING
    is CallState.Connected -> null
    else -> ParticipantStatus.CONNECTING
}

/** The local user's row has no call of its own, so it carries an id nothing else can collide with. */
private const val SELF_ROW_ID = "self"
