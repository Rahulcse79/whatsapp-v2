package com.whatsappv2.feature.calls

import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.VideoSizes
import com.whatsappv2.domain.model.CallId

/**
 * The phase of a call, as the screen renders it (Task 39).
 *
 * A projection of [CallState], not a copy: the FSM's states carry data the screen does not
 * need — controls, hold party, hangup reason — and the screen needs a single value it can
 * `when` over to choose a label and a set of buttons. Deriving it in one place is what
 * stops two composables disagreeing about what "ringing" looks like.
 */
enum class CallPhase {
    /** INVITE sent, nothing back yet. */
    CALLING,

    /** 180 — the far end is alerting. */
    RINGING,

    /** 183 with SDP: audio is already arriving, so this is not "still ringing". */
    EARLY_MEDIA,

    /** An inbound call the user has not answered. */
    INCOMING,

    /** Media is flowing. */
    CONNECTED,

    /** The local user is holding. */
    ON_HOLD,

    /** The far end is holding us. Shown differently, because the remedy is different. */
    HELD_BY_REMOTE,

    /** Both ends are holding. Resuming locally does not resume the call. */
    HELD_BY_BOTH,

    /** A resume is in flight. */
    RESUMING,

    /** A transfer is in flight. */
    TRANSFERRING,

    /** Over. */
    ENDED,
    ;

    companion object {
        fun of(state: CallState): CallPhase = when (state) {
            is CallState.Idle -> ENDED
            is CallState.Outgoing.Calling -> CALLING
            is CallState.Outgoing.Ringing -> RINGING
            is CallState.Outgoing.EarlyMedia -> EARLY_MEDIA
            is CallState.Incoming -> INCOMING
            is CallState.Connected -> CONNECTED
            is CallState.Held -> when (state.by) {
                HoldParty.LOCAL -> ON_HOLD
                HoldParty.REMOTE -> HELD_BY_REMOTE
                HoldParty.BOTH -> HELD_BY_BOTH
            }
            is CallState.Resuming -> RESUMING
            is CallState.Transferring -> TRANSFERRING
            is CallState.Terminated -> ENDED
        }
    }
}

/**
 * Which actions the call permits **right now**.
 *
 * Task 39's second done-when, as a value: hold is unavailable before `Connected`, and the
 * reason it is unavailable is the state itself rather than a flag somebody remembered to
 * set. Every button on the call screen reads its `enabled` from here, so a button cannot
 * be offered for an action the state machine would reject.
 */
data class CallControlAvailability(
    val canAnswer: Boolean,
    val canReject: Boolean,
    val canHangUp: Boolean,
    val canMute: Boolean,
    val canChangeRoute: Boolean,
    val canHold: Boolean,
    val canResume: Boolean,
    val canSendDtmf: Boolean,

    /** REFER needs an established dialog, and a transfer already in flight cannot start another. */
    val canTransfer: Boolean,

    /** Adding or dropping video is a re-INVITE, so it needs the same dialog hold does. */
    val canToggleVideo: Boolean,

    /** Only while this call is actually sending video — there is nothing else to switch. */
    val canSwitchCamera: Boolean,

    /** Recording needs media to record. Consent is a separate gate and is asked for later. */
    val canRecord: Boolean,

    /**
     * Whether a second leg can be dialled from here (ADR-009).
     *
     * Without this there was no way to *start* a conference. Merge only appears once two
     * calls exist, and the only route to a second outgoing call was to leave the call
     * screen, reopen the app and find the dialler — which nobody does, so local mixing was
     * reachable in principle and not in practice.
     */
    val canAddCall: Boolean,
) {
    companion object {
        fun of(phase: CallPhase): CallControlAvailability = CallControlAvailability(
            canAnswer = phase == CallPhase.INCOMING,
            canReject = phase == CallPhase.INCOMING,
            // Everything except an inbound call that has not been answered - that one is
            // rejected rather than hung up, and the two send different responses.
            canHangUp = phase != CallPhase.ENDED && phase != CallPhase.INCOMING,
            // Mute needs media, which exists from the moment the call is answered and not
            // before. Muting a ringing call mutes nothing.
            canMute = phase.hasMedia,
            // Routing does not: the platform routes the ringback and any early media from
            // the moment it has the call, and a Speaker press while the far end rings is
            // the most natural time to make one. Measured disabled here on a TC15,
            // 2026-09-10 — the press went nowhere and the call came up on the earpiece.
            canChangeRoute = phase != CallPhase.ENDED,
            // Hold is a re-INVITE on an established dialog. Before Connected there is no
            // dialog to re-INVITE, which is why this is a state question and not a flag.
            canHold = phase == CallPhase.CONNECTED,
            canResume = phase == CallPhase.ON_HOLD || phase == CallPhase.HELD_BY_BOTH,
            // Connected only, and not merely established: RFC 4733 digits ride the RTP
            // stream, and a held call's stream is paused, so a keypad offered there would
            // send tones into a media path that is not running (Task 43).
            canSendDtmf = phase == CallPhase.CONNECTED,
            // Connected only. A REFER sent from a held call is legal SIP and a bad idea:
            // the transferee is handed a call whose audio is paused, and whether it comes
            // back depends on the far end (Task 55).
            canTransfer = phase == CallPhase.CONNECTED,
            // A re-INVITE, so the same rule as hold — and not while one is already in
            // flight, which is what excludes RESUMING and TRANSFERRING (Tasks 53, 54).
            canToggleVideo = phase == CallPhase.CONNECTED,
            // Set by the caller from the call's own video state; the phase alone cannot
            // say whether there is a camera running to switch (Task 53).
            canSwitchCamera = false,
            canRecord = phase.hasMedia,
            // Connected only, for the reason hold and transfer are: placing a second call
            // holds this one, and holding is a re-INVITE that needs an established dialog.
            // A conference member is Connected too, so this stays offered while mixing —
            // which is how a conference grows past two.
            canAddCall = phase == CallPhase.CONNECTED,
        )

        /**
         * The same, refined by what this particular call is doing (Tasks 53, 58).
         *
         * [of] answers from the phase alone, which is all most buttons need. Two of them
         * need more: switching cameras is meaningless unless video is actually running,
         * and offering it on an audio call is a control that cannot work.
         */
        fun of(phase: CallPhase, videoEnabled: Boolean): CallControlAvailability =
            // Not while held: the camera is released on hold (`CameraPolicy`), so there
            // is nothing running to switch.
            of(phase).copy(canSwitchCamera = phase.hasMedia && !phase.isHeld && videoEnabled)
    }
}

/**
 * True while either party is holding: media is paused, and the camera has been released.
 *
 * `CameraPolicy` lets a held call go of the camera whoever holds it, so a screen that kept
 * drawing the self-view through a hold was showing a frozen frame from a camera that was
 * off — and the far end's last frame beside it, with the controls fading out over both.
 * Two still pictures and no buttons reads as a hung call (TC15, 2026-09-22 13:03).
 */
val CallPhase.isHeld: Boolean
    get() = this == CallPhase.ON_HOLD || this == CallPhase.HELD_BY_REMOTE || this == CallPhase.HELD_BY_BOTH

/** True once media has been negotiated, which is what mute and routing act on. */
val CallPhase.hasMedia: Boolean
    get() = this == CallPhase.CONNECTED ||
        this == CallPhase.ON_HOLD ||
        this == CallPhase.HELD_BY_REMOTE ||
        this == CallPhase.HELD_BY_BOTH ||
        this == CallPhase.RESUMING ||
        this == CallPhase.TRANSFERRING

/**
 * One call, ready to render.
 *
 * Presentation-resolved in the ViewModel so a Compose preview can build one without an
 * engine, and so the screen holds no logic worth testing separately from the state that
 * produced it.
 */
data class CallDisplay(
    val callId: CallId,

    /** The caller's name if they asserted one, else their address. */
    val title: String,

    /** The address, shown under the name. Null when the title is already the address. */
    val subtitle: String?,

    val direction: CallDirection,
    val phase: CallPhase,
    val controls: CallControls,

    /**
     * Seconds since the call connected, or null before it did.
     *
     * A duration rather than a start time, because the screen shows a duration — and
     * computed from the call's connect timestamp on every tick rather than incremented,
     * so a rotation, a process pause, or a dropped tick cannot make it drift (Task 39).
     */
    val durationSeconds: Long?,

    /**
     * The caller's photo from the address book, or null (Task 49).
     *
     * A `content://` reference rather than an image: the screen loads it when it draws,
     * and nothing here makes a copy of somebody's photograph.
     */
    val photoUri: String? = null,

    /** True when the peer offered video, so an inbound call can be answered with it. */
    val videoOffered: Boolean,

    /**
     * True when a video stream is negotiated on this call right now (Tasks 52, 54).
     *
     * Distinct from [videoOffered], which is about the inbound INVITE, and from
     * `controls.isVideoEnabled`, which is whether the user is *sending*. All three differ
     * on a video call whose camera the user has muted, and the screen renders differently
     * for each: a remote picture with no local preview.
     */
    val videoActive: Boolean = false,

    /** True while this call is a conference leg, so the roster is worth showing (Task 60). */
    val isConference: Boolean = false,

    /**
     * True while this device is mixing this call with others (ADR-009).
     *
     * Then [title] names the conference and [subtitle] its members — see
     * [inConferenceIfMixed] — and the screen shows a group where a face would be.
     */
    val isMixed: Boolean = false,
) {
    val availability: CallControlAvailability
        get() = CallControlAvailability.of(phase, controls.isVideoEnabled)

    /** True when there is a remote picture to draw — the far end is sending (Task 52). */
    val showsRemoteVideo: Boolean get() = videoActive && phase.hasMedia

    /** True while the call is held by either side; see [CallPhase.isHeld]. */
    val isHeld: Boolean get() = phase.isHeld

    /**
     * True when the remote picture is actually moving: video negotiated, and nobody holding.
     *
     * What the in-call chrome's auto-hide waits for. Hidden controls trade buttons for a
     * picture worth watching, and a held call has no such picture — only the frame it
     * froze on — so its Resume button stays where the user can see it.
     */
    val videoIsLive: Boolean get() = showsRemoteVideo && !isHeld

    /**
     * True when the local preview should be on screen: **this** device's camera is running.
     *
     * Deliberately independent of [showsRemoteVideo]. It used to require it, and that was
     * wrong in exactly the case that matters most: between joining a conference and the
     * bridge's first composed frame there is no remote picture, so a user whose camera was
     * plainly on — the indicator lit, the shutter open — saw no self-view at all and had
     * every reason to think the camera had failed. The same gap opens on any video call
     * whose far end is slow to send, and on one whose peer has muted their camera.
     *
     * The self-view answers "is my camera working and am I in frame", which is a question
     * about this handset. Nothing the far end does changes the answer — except a hold,
     * which releases the camera on both sides of the question ([CallPhase.isHeld]): a
     * preview drawn then is a frozen frame pretending to be a live one.
     */
    val showsLocalPreview: Boolean get() = controls.isVideoEnabled && phase.hasMedia && !phase.isHeld

    /**
     * True when a video surface should exist at all — either picture is reason enough.
     *
     * The screen draws one video layer holding both surfaces, so this is what gates it.
     * Gating on [showsRemoteVideo] alone meant the local preview could not be shown before
     * the first remote frame, because the composable that owns its surface had not been
     * composed yet.
     */
    val showsAnyVideo: Boolean get() = showsRemoteVideo || showsLocalPreview
}

/** What the call screen is showing. */
sealed interface CallUiState {

    /**
     * The engine has not published this call yet.
     *
     * Real and brief: the screen can be opened from a notification a frame before the
     * flow arrives. Distinct from [Finished] so a call that is starting is not drawn as
     * one that has ended.
     */
    data object Loading : CallUiState

    /**
     * A call is on screen.
     *
     * Everything beyond [call] arrived with Tasks 52-60 and is defaulted, so the shape a
     * preview or a test builds for an ordinary audio call is unchanged: an audio call has
     * no other calls, no escalation pending, no transfer in flight and no roster.
     */
    data class Active(
        val call: CallDisplay,

        /**
         * Every other call this app is holding (Task 56).
         *
         * Held calls, almost always exactly one. Present so the screen can show who is
         * waiting and offer a swap, which is the only way "exactly one active" is visible
         * to the person it protects.
         */
        val otherCalls: List<CallDisplay> = emptyList(),

        /** An escalation the far end asked for, still unanswered (Task 54). */
        val pendingVideoRequest: PendingVideoRequest? = null,

        /** A second call arriving right now, with its three answers (Task 56). */
        val secondCall: SecondCallPrompt? = null,

        val transfer: TransferUiState = TransferUiState.Idle,

        val recording: RecordingUiState = RecordingUiState(),

        /** The roster, when this call is a conference leg (Task 60). */
        val conference: ConferenceUiState? = null,

        /**
         * True when this device could mix the calls it is holding into one (ADR-009).
         *
         * A property of the calls rather than a button's enabled flag: merging needs two
         * or more calls that are *established*, because a ringing one has no audio to
         * contribute. Deriving it here means the control cannot offer a merge the engine
         * would then refuse.
         */
        val canMerge: Boolean = false,

        /** How many calls this device is mixing right now; 0 when it is not (ADR-009). */
        val mixedCallCount: Int = 0,

        /**
         * The shapes of the two pictures on screen, or [VideoSizes.UNKNOWN].
         *
         * Here rather than on [CallDisplay] because it is not a fact about the call: it
         * is a fact about the decoder and the camera, it changes when nothing about the
         * call has, and it arrives from a different port. [CallDisplay] is built from a
         * `CallSnapshot`, and putting a renderer's measurement in it would mean inventing
         * a field on the engine's snapshot that the engine does not know.
         *
         * The screen needs it because PJSIP's renderer stretches a frame to whatever
         * bounds it is handed — see `VideoSurfaceController.videoSizes`. Unknown until
         * the first frame decodes, which is the ordinary state for the first moments of
         * every video call.
         */
        val videoSizes: VideoSizes = VideoSizes.UNKNOWN,

        /**
         * Actions asked of the engine that it has not answered yet (Task 76).
         *
         * The screen shows these as busy and refuses a second press. It deliberately does
         * **not** show the action's result early: a mute that the engine later refuses
         * would leave the button reading "Muted" over a live microphone, which is the same
         * class of lie as a call drawn as held whose re-INVITE the far end rejected.
         */
        val pendingActions: Set<CallAction> = emptySet(),
    ) : CallUiState {

        /**
         * True when something on this screen is waiting on the user.
         *
         * Used to keep the in-call controls on screen during a video call, where they
         * otherwise get out of the way of the picture. A prompt nobody can see is worse
         * than no prompt at all, so a ringing second call, an escalation the far end is
         * waiting on, or a transfer in flight all pin the controls open.
         */
        val needsAttention: Boolean
            get() = secondCall != null ||
                pendingVideoRequest != null ||
                transfer !is TransferUiState.Idle ||
                recording.askingConsent
    }

    /**
     * The call is over and the screen should close.
     *
     * Not an error state: this is the normal end of every call, and the screen's whole
     * lifetime is the call's. [reason] is the one thing worth reading on the way out —
     * why a call that never connected did not, in the words `HangupReason.userMessage`
     * gives it — and null for an ordinary hang-up, when the screen simply closes. A call
     * to a number that did not exist used to close the screen and say nothing at all
     * (TC15, 2026-09-22, `404` → the dialler, no message).
     */
    data class Finished(val reason: String? = null) : CallUiState
}

/** A one-shot thing the screen must react to, as opposed to state it renders. */
sealed interface CallEvent {

    /** An action the engine refused, with the reason worth showing. */
    data class ActionFailed(val action: CallAction, val detail: String) : CallEvent
}

/** The actions the screen offers, named so a failure can say which one failed. */
enum class CallAction {
    ANSWER,
    REJECT,
    HANG_UP,
    MUTE,
    SPEAKER,
    HOLD,
    DTMF,
    VIDEO,
    SWITCH_CAMERA,
    TRANSFER,
    SWAP,
    RECORD,
    MERGE,

    /** Dropping one member, as opposed to [HANG_UP], which ends the whole conference. */
    REMOVE_PARTICIPANT,
    ;

    /**
     * True when pressing again while the last press is still in flight is a **new**
     * instruction rather than a duplicate (Task 76).
     *
     * Only [DTMF], and the exception is the whole point. Every other action here is a
     * toggle or a one-shot, where a second press mid-flight queues the opposite request
     * and ends with the screen and the call disagreeing. A digit is neither: `1234` is
     * four tones, they are typed faster than a round trip, and dropping the ones that
     * arrive while the first is in flight sends an IVR a number nobody dialled.
     */
    val isRepeatable: Boolean get() = this == DTMF
}

/**
 * Builds the display model for [snapshot] at [nowEpochMillis].
 *
 * [contact] is what the address book said, when it knew and when it was allowed to be
 * asked. It wins over the name the peer asserted: the user's own word for a person is
 * more trustworthy than whatever the far end chose to call itself, and it is the name
 * they will recognise. Null for a stranger, and for a device where READ_CONTACTS was
 * declined — both of which leave the screen exactly as it was before Task 49.
 */
internal fun CallSnapshot.toDisplay(nowEpochMillis: Long, contact: Contact? = null): CallDisplay {
    val name = contact?.displayName?.takeIf { it.isNotBlank() }
        ?: remoteDisplayName?.takeIf { it.isNotBlank() }
    val address = remote.render()

    return CallDisplay(
        callId = callId,
        title = contact?.displayName?.takeIf { it.isNotBlank() } ?: label(),
        // Only when it adds something: repeating the address under itself is noise.
        subtitle = address.takeIf { name != null },
        photoUri = contact?.photoUri,
        direction = direction,
        phase = CallPhase.of(state),
        // Before media the controls are defaults — except the route, which the user may
        // already have chosen and which the platform is already honouring.
        controls = state.controlsOrNull
            ?: CallControls.DEFAULT.copy(audioRoute = requestedAudioRoute ?: CallControls.DEFAULT.audioRoute),
        durationSeconds = durationMillis(nowEpochMillis)?.let { it / MILLIS_PER_SECOND },
        videoOffered = media.hasVideo,
        videoActive = media.hasVideo,
        isConference = isConference,
    )
}

/**
 * The watched call as the conference this device is mixing it into — or itself (ADR-009).
 *
 * ## The screen shows the conference, not the leg
 *
 * A merged call kept the first leg's name as its title: "9196" over a button reading
 * "2 calls merged" (TC15, 2026-09-14). The display is built from the watched call, and the
 * watched call is still one leg. But once this device is mixing it, what the user is in
 * is a conference — so that is the title, and the photo goes with the name, because a
 * conference has no face.
 *
 * The members are not the line under it. They were, once — every label joined with a
 * dot — and on a conference of seven that line read `1005 · 1001 · 1002 · 1003 · 1005 ·
 * 1004 · 9198` and truncated (TC15, 2026-09-19). The roster is where they belong: one row
 * each, name and extension both, and the state of anyone not carrying audio — see
 * `localMixRoster`. Saying it twice, once legibly and once not, is worse than once.
 *
 * Fewer than [SipConferenceController.MINIMUM_MIXED] mixed calls is a call, not a conference, and a
 * watched call that is not among the mixed ones — a third call on hold beside a merged
 * pair — stays itself. Both leave the display exactly as [toDisplay] made it.
 */
internal fun CallDisplay.inConferenceIfMixed(mixed: Set<CallId>): CallDisplay {
    if (mixed.size < SipConferenceController.MINIMUM_MIXED || callId !in mixed) return this

    return copy(
        title = CONFERENCE_TITLE,
        subtitle = null,
        photoUri = null,
        isMixed = true,
    )
}

/** The peer's asserted name, else their extension, else their host — [toDisplay]'s title without an address book. */
internal fun CallSnapshot.label(): String =
    remoteDisplayName?.takeIf { it.isNotBlank() } ?: remote.user ?: remote.host.rendered

internal const val CONFERENCE_TITLE = "Conference call"
private const val MILLIS_PER_SECOND = 1_000L
