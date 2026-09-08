package com.whatsappv2.data.sip.registration.stack

import android.content.Context
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.data.sip.call.SipCallGateway
import com.whatsappv2.data.sip.call.SipRecordingGateway
import com.whatsappv2.data.sip.call.SipVideoGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackConferenceEvent
import com.whatsappv2.data.sip.call.StackParticipant
import com.whatsappv2.data.sip.call.StackTransferEvent
import com.whatsappv2.data.sip.registration.SipCoreGateway
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackMediaEncryption
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.CodecPriorityPolicy
import org.linphone.core.Conference
import org.linphone.core.ConferenceListenerStub
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.Participant
import org.linphone.core.ParticipantDevice
import org.linphone.core.PayloadType
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real SIP stack, behind the gateway seam.
 *
 * **This is the only class in the project that touches liblinphone's registration API.**
 *
 * It sits in its own package for the same reason `AndroidKeystoreSecretKeyProvider` does:
 * it cannot run on the JVM, so keeping it beside the testable mapper would drag that
 * package's coverage gate down until the gate measured nothing.
 * Everything above it works in this module's own types, which is what keeps DoD 3
 * ("no SDK import outside `:data:sip`") true and what lets the engine be tested on the
 * JVM at all.
 *
 * ## One core, one account per identity
 *
 * A single [Core] holds every transport, and each configured account maps to exactly one
 * liblinphone [Account]. Re-registering an existing identity replaces its params rather
 * than adding a second account — two bindings for one identity fight over the same
 * registrar record, and the loser's calls go to a device that is no longer listening.
 *
 * ## Threading
 *
 * liblinphone's callbacks arrive on the thread that iterates the core. Events are
 * published to a buffered [MutableSharedFlow] rather than handled inline, so nothing this
 * module does can block that iteration — a blocked core stops processing SIP entirely.
 *
 * **The maps below are touched from both sides, so they are all concurrent.** The core's
 * thread writes them from `onCallStateChanged` and `onAccountRegistrationStateChanged`;
 * every gateway method — `placeCall`, `terminateCall`, `setVideoEnabled`, `addAccount` —
 * reads and writes them from whichever `Dispatchers.IO` thread the engine's scope handed
 * out, and `Dispatchers.IO` is a pool rather than a single thread. They used to be plain
 * `mutableMapOf`, which is a `LinkedHashMap`: unsynchronised concurrent mutation loses
 * entries, throws `ConcurrentModificationException` from the iteration in
 * `onCallStateChanged`, and in the worst case spins forever inside a resize. A lost entry
 * here is a call that cannot be hung up.
 *
 * `ConcurrentHashMap` makes each operation atomic, which is all this needs — no method
 * reads one map and writes another as a unit.
 */
@Singleton
internal class RealLinphoneCoreGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: Logger,
) : SipCoreGateway, SipCallGateway, SipVideoGateway, SipRecordingGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        // Dropping the oldest is the right failure mode: a registration state that has
        // been superseded is not worth blocking the SIP core to deliver.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    private val callEventFlow = MutableSharedFlow<StackCallEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val callEvents: Flow<StackCallEvent> = callEventFlow.asSharedFlow()

    private val transferEventFlow = MutableSharedFlow<StackTransferEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val transferEvents: Flow<StackTransferEvent> = transferEventFlow.asSharedFlow()

    private val conferenceEventFlow = MutableSharedFlow<StackConferenceEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val conferenceEvents: Flow<StackConferenceEvent> = conferenceEventFlow.asSharedFlow()

    /**
     * Conferences already being watched, by the call key of the leg that joined them.
     *
     * Tracked so a listener is attached exactly once. liblinphone reports the same
     * conference on every subsequent call state change, and attaching again would publish
     * the roster once per listener per change.
     */
    private val watchedConferences = ConcurrentHashMap<String, Conference>()

    /** Our call key to the stack's call, so terminate can find the right one. */
    private val callsByKey = ConcurrentHashMap<String, Call>()

    private var core: Core? = null

    /** Our account key to the stack's account, so a re-register can replace in place. */
    private val accountsByKey = ConcurrentHashMap<String, Account>()

    /**
     * Our account key to the credential the core is holding for it.
     *
     * Tracked purely so it can be taken back out. The core's auth store has no notion of
     * our account keys, and `removeAccount` alone leaves the password sitting in it for
     * the life of the process - which is exactly what Task 29 forbids after a logout.
     */
    private val authInfoByKey = ConcurrentHashMap<String, AuthInfo>()

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String,
        ) {
            val key = accountsByKey.entries.firstOrNull { it.value == account }?.key ?: return
            events.tryEmit(
                StackRegistrationEvent(
                    accountKey = key,
                    state = state.toStackState(),
                    // The SIP response code, when the failure came from a server. Null
                    // means the request never got an answer, which the mapper treats as a
                    // transport problem rather than a rejection.
                    statusCode = account.errorInfo.protocolCode.takeIf { it > 0 },
                    message = message,
                ),
            )
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String,
        ) {
            // Released first, and before the mapping: it is the stack's signal that it
            // will not touch this call again, and it maps to no app state — so a version
            // that checked it after the mapping's early return never ran, and leaked one
            // entry per call for the life of the process.
            if (state == Call.State.Released) {
                callsByKey.entries.firstOrNull { it.value == call }?.let { callsByKey -= it.key }
                return
            }

            val mapped = state.toStackCallState() ?: return
            // An inbound INVITE is the one call this gateway has never seen before, so it
            // is the one place a key is minted rather than looked up. Everything above
            // this line addresses calls by the app's id and never by the stack's object.
            val key = callsByKey.entries.firstOrNull { it.value == call }?.key
                ?: if (mapped == StackCallState.INCOMING_RECEIVED) {
                    UUID.randomUUID().toString().also { callsByKey[it] = call }
                } else {
                    return
                }

            callEventFlow.tryEmit(
                StackCallEvent(
                    callKey = key,
                    // Which of our accounts placed it. Empty when the core did not
                    // attribute the call to one, which the engine treats as unknown.
                    // The account hangs off the call's params, not off the call: the
                    // stack resolves it there for an inbound INVITE just as `placeCall`
                    // sets it there for an outbound one.
                    accountKey = accountsByKey.entries
                        .firstOrNull { it.value == call.params.account }
                        ?.key
                        .orEmpty(),
                    remoteUri = call.remoteAddress.asStringUriOnly(),
                    remoteDisplayName = call.remoteAddress.displayName,
                    state = mapped,
                    statusCode = call.errorInfo.protocolCode.takeIf { it > 0 },
                    message = message,
                    // What the peer offered, read from the remote parameters rather than
                    // from ours: ours say what we would accept, not what was asked for.
                    // Also read on UpdatedByRemote, which is the other moment an offer
                    // exists and nothing has answered it (Task 54).
                    videoOffered = (
                        mapped == StackCallState.INCOMING_RECEIVED ||
                            mapped == StackCallState.UPDATED_BY_REMOTE
                        ) && call.remoteParams?.isVideoEnabled == true,
                    // What is negotiated and running now, from our own params: after a
                    // re-INVITE this is the only place the change is visible (Task 54).
                    videoActive = call.currentParams.isVideoEnabled,
                    // After negotiation, not before: a peer that answers an SRTP offer
                    // with cleartext has encrypted nothing (Task 62, DoD 13).
                    mediaEncrypted = call.currentParams.mediaEncryption != MediaEncryption.None,
                ),
            )

            watchConferenceOf(key, call)
        }

        /**
         * REFER progress (Task 55).
         *
         * A callback of its own in the SDK, and rightly: `call` here is the call being
         * transferred, while `state` describes the *transfer*, not the call. A version
         * that read this as a call state would report the transferor's leg as ringing.
         */
        override fun onTransferStateChanged(core: Core, call: Call, state: Call.State) {
            val key = callsByKey.entries.firstOrNull { it.value == call }?.key ?: return
            val mapped = state.toStackCallState() ?: return

            transferEventFlow.tryEmit(
                StackTransferEvent(
                    callKey = key,
                    state = mapped,
                    // The code from the NOTIFY sipfrag. `errorInfo` is where liblinphone
                    // puts it, which is why a busy transferee can be named as busy rather
                    // than as a generic failure.
                    statusCode = call.errorInfo.protocolCode.takeIf { it > 0 },
                ),
            )
        }
    }

    /**
     * The roster listener, shared by every conference this app joins (Task 60).
     *
     * One stub rather than one per conference: every callback publishes the same thing —
     * the full roster, restated — so there is nothing per-conference to close over. The
     * conference is a parameter of each callback, which is how the right call key is
     * found.
     */
    private val conferenceListener = object : ConferenceListenerStub() {
        override fun onParticipantAdded(conference: Conference, participant: Participant) =
            publishRoster(conference)

        override fun onParticipantRemoved(conference: Conference, participant: Participant) =
            publishRoster(conference)

        override fun onParticipantDeviceAdded(conference: Conference, device: ParticipantDevice) =
            publishRoster(conference)

        override fun onParticipantDeviceRemoved(conference: Conference, device: ParticipantDevice) =
            publishRoster(conference)

        override fun onParticipantDeviceIsMuted(
            conference: Conference,
            device: ParticipantDevice,
            isMuted: Boolean,
        ) = publishRoster(conference)

        override fun onParticipantDeviceIsSpeakingChanged(
            conference: Conference,
            device: ParticipantDevice,
            isSpeaking: Boolean,
        ) = publishRoster(conference)

        // Nullable, unlike the other device callbacks: the bridge reports "nobody is
        // speaking" by naming no device at all.
        override fun onActiveSpeakerParticipantDevice(
            conference: Conference,
            device: ParticipantDevice?,
        ) = publishRoster(conference)

        /** The bridge stated the complete list. The one callback that proves a roster exists. */
        override fun onFullStateReceived(conference: Conference) = publishRoster(conference)
    }

    /**
     * Starts watching [call]'s conference, if it has one and is not already watched.
     *
     * Called from the call listener because that is when a conference becomes reachable:
     * `call.conference` is null until the bridge has answered, so there is nothing to
     * attach to at the moment [placeCall] returns.
     */
    private fun watchConferenceOf(callKey: String, call: Call) {
        val conference = call.conference ?: return
        if (watchedConferences[callKey] === conference) return

        watchedConferences[callKey] = conference
        conference.addListener(conferenceListener)
        // Published immediately: the bridge may have sent its full state before this
        // listener existed, and a roster nobody asked for again would never arrive.
        publishRoster(conference)
    }

    /**
     * Publishes [conference]'s participants as they stand.
     *
     * Full state every time — see [StackConferenceEvent]. Reconciling deltas against a
     * roster that may have been missed is how a list ends up showing somebody who left.
     *
     * `rosterAvailable` is **false when the bridge names nobody at all**, including us.
     * Every bridge that publishes a roster lists the local participant, so an entirely
     * empty list means there is no roster rather than an empty room — which the UI has to
     * say out loud rather than render as nobody being there (§13, Task 60).
     */
    private fun publishRoster(conference: Conference) {
        val callKey = watchedConferences.entries.firstOrNull { it.value === conference }?.key ?: return
        val devices = conference.participantDeviceList.orEmpty()

        conferenceEventFlow.tryEmit(
            StackConferenceEvent(
                callKey = callKey,
                participants = devices.map { it.toStackParticipant(conference) },
                rosterAvailable = devices.isNotEmpty(),
            ),
        )
    }

    /**
     * One participant device, flattened.
     *
     * A *device* rather than a participant: one person may join from a phone and a
     * desktop, and it is the device that is muted, speaking, and sending video. A roster
     * keyed on participants would show one entry for two streams and no way to say which
     * of them is talking.
     */
    private fun ParticipantDevice.toStackParticipant(conference: Conference) = StackParticipant(
        // The address is the id: the bridge echoes it back on every later notification, and
        // it is the only handle stable across them. A device with a blank one falls back to
        // its name, which is worse but is still something to key on.
        id = address.asStringUriOnly().ifBlank { name.orEmpty() },
        uri = address.asStringUriOnly(),
        displayName = name ?: address.displayName,
        isMuted = isMuted,
        isSpeaking = isSpeaking,
        // Identity by address, which is what the bridge echoes back to us. `Conference.me`
        // is the local participant, and its devices are the ones that are ours.
        isSelf = conference.me.devices.any { it.address.weakEqual(address) },
        // Under a mixing MCU this is false for everyone: one composed stream carries the
        // room. It becomes true under an SFU, which is the swap §2.2 asks the model to
        // survive.
        hasVideoStream = thumbnailStreamAvailability,
        joinedAtEpochMillis = timeOfJoining.takeIf { it > 0 }?.times(MILLIS_PER_SECOND),
    )

    override fun placeCall(
        callKey: String,
        accountKey: String,
        destination: String,
        videoEnabled: Boolean,
    ) {
        val activeCore = core ?: run {
            logger.error(TAG, "placeCall before the core was started")
            return
        }
        val address = Factory.instance().createAddress(destination) ?: run {
            logger.error(TAG, "Unparseable destination for call $callKey")
            return
        }

        val params = activeCore.createCallParams(null) ?: run {
            logger.error(TAG, "The core refused to create call params")
            return
        }
        params.isVideoEnabled = videoEnabled
        // Bind the call to the requested identity rather than whichever account the core
        // considers default - a per-call account override (Task 36) is meaningless
        // otherwise.
        accountsByKey[accountKey]?.let { params.account = it }

        activeCore.inviteAddressWithParams(address, params)
            ?.let { callsByKey[callKey] = it }
            ?: logger.error(TAG, "The core refused the INVITE for $callKey")
    }

    override fun answerCall(callKey: String, videoEnabled: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Answer for a call the stack no longer has: $callKey")
            return
        }
        val activeCore = core ?: return

        // acceptWithParams, not accept(): a plain accept answers with whatever the core's
        // defaults say, which on a video-capable build can add a video stream the caller
        // never offered and the user never asked for.
        val params = activeCore.createCallParams(call) ?: run {
            logger.error(TAG, "The core refused to create answer params for $callKey")
            return
        }
        params.isVideoEnabled = videoEnabled
        call.acceptWithParams(params)
    }

    override fun rejectCall(callKey: String, busy: Boolean) {
        // Busy Here versus Decline. The caller hears the difference, so the choice is the
        // caller's and never a default picked here.
        callsByKey[callKey]?.decline(if (busy) Reason.Busy else Reason.Declined)
    }

    override fun setMicrophoneMuted(callKey: String, muted: Boolean) {
        // Per call, not on the core: a core-wide mute would silence a second call the
        // user never muted (Task 56).
        callsByKey[callKey]?.microphoneMuted = muted
    }

    /**
     * Holds by re-INVITE (Task 41).
     *
     * `pause()` and nothing else: the stack writes the SDP direction, which is `sendonly`
     * while only we hold and `inactive` once both ends do. Setting a direction by hand
     * through call params would re-derive a rule the stack already applies, and the
     * both-hold case is exactly where a hand-rolled version gets it wrong.
     *
     * A non-zero return means the stack refused — a call in a state that cannot be paused.
     * Logged rather than thrown: the engine's own state is unchanged, so the screen
     * continues to show a call that is not held, which is the truth.
     */
    override fun pauseCall(callKey: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Hold for a call the stack no longer has: $callKey")
            return
        }
        if (call.pause() != OK) logger.warn(TAG, "The stack refused to hold $callKey")
    }

    /** Resumes a call this app holds. The far end's own hold is not ours to lift. */
    override fun resumeCall(callKey: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Resume for a call the stack no longer has: $callKey")
            return
        }
        if (call.resume() != OK) logger.warn(TAG, "The stack refused to resume $callKey")
    }

    /**
     * Sends one DTMF digit (Task 43).
     *
     * The transport is set on the core immediately before the digit, because that is
     * where liblinphone keeps it — `setUseRfc2833ForDtmf` and `setUseInfoForDtmf` are
     * core-wide settings, not call params. Setting both on every digit is what makes a
     * change in Settings take effect on the next digit rather than the next call, and it
     * is the only way the two flags cannot be left in a stale combination.
     *
     * Exactly one is enabled. With both on, liblinphone sends the digit by both carriers
     * at once, and an IVR that counts keypresses hears two.
     */
    override fun sendDtmf(callKey: String, digit: Char, useInfo: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "DTMF for a call the stack no longer has: $callKey")
            return
        }
        core?.apply {
            useRfc2833ForDtmf = !useInfo
            useInfoForDtmf = useInfo
        }
        // The digit itself is never logged: a DTMF sequence is a PIN or a card number as
        // often as it is a menu choice (§7, DoD 12).
        if (call.sendDtmf(digit) != OK) logger.warn(TAG, "The stack refused a DTMF digit on $callKey")
    }

    override fun terminateCall(callKey: String) {
        // Idempotent: a call the stack has already released is one the caller wanted gone.
        callsByKey[callKey]?.terminate()
    }

    /**
     * Adds or drops the video stream, by re-INVITE (Tasks 53, 54).
     *
     * `update()` on the existing dialog rather than a new INVITE: audio is working and has
     * no interest in this change, and re-offering the whole session would interrupt it to
     * negotiate something it does not care about.
     */
    override fun setVideoEnabled(callKey: String, enabled: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Video change for a call the stack no longer has: $callKey")
            return
        }
        val params = call.core.createCallParams(call) ?: run {
            logger.error(TAG, "The core refused to create update params for $callKey")
            return
        }
        params.isVideoEnabled = enabled
        if (call.update(params) != OK) logger.warn(TAG, "The stack refused a video update on $callKey")
    }

    /**
     * Answers a re-INVITE that offered video (Task 54).
     *
     * `acceptUpdate` with video off is a **decline of the video, not of the call**: the
     * re-INVITE is answered and audio carries on. Refusing the update outright with a 488
     * would be legal SIP and would drop the call on several peers, which is not what
     * declining an escalation should mean.
     */
    override fun respondToVideoUpdate(callKey: String, accept: Boolean) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Video answer for a call the stack no longer has: $callKey")
            return
        }
        val params = call.core.createCallParams(call) ?: run {
            logger.error(TAG, "The core refused to create answer params for $callKey")
            return
        }
        params.isVideoEnabled = accept
        if (call.acceptUpdate(params) != OK) {
            logger.warn(TAG, "The stack refused the video answer on $callKey")
        }
    }

    /**
     * Points the encoder at the next camera (Task 53).
     *
     * Set on the core, which is where liblinphone keeps the capture device, and with no
     * re-negotiation: the same stream keeps running and only its source moves, so the far
     * end sees the picture change rather than a gap.
     *
     * Cycling the list rather than looking for a device called "front" — device names are
     * vendor strings and a device with one camera has one entry, where cycling correctly
     * does nothing.
     */
    override fun switchCamera(callKey: String) {
        val core = this.core ?: return
        val devices = core.videoDevicesList
        if (devices.size < 2) {
            logger.info(TAG, "Only one camera on this device; nothing to switch to")
            return
        }

        val next = devices[(devices.indexOf(core.videoDevice) + 1) % devices.size]
        if (core.setVideoDevice(next) != OK) logger.warn(TAG, "The stack refused the camera switch")
    }

    /**
     * Claims or releases the camera device (Task 51).
     *
     * Core-wide, because the camera is: one process, one capture device, whatever any
     * individual call has negotiated. `CameraPolicy` decides from the whole call list and
     * this obeys, which is what makes the release cover every way a call can end rather
     * than only the hangup button.
     */
    override fun setCameraCapturing(capturing: Boolean) {
        val core = this.core ?: return
        core.isVideoCaptureEnabled = capturing
    }

    /**
     * Attaches the views video is drawn into, or clears them (Task 52).
     *
     * Nulls are the important half. A surface handed to the stack and never taken back is
     * a texture it keeps writing into after the screen has gone — which on some devices is
     * a crash and on the rest is a leak of every frame of the call.
     */
    override fun setVideoWindows(remoteView: Any?, localPreview: Any?) {
        val core = this.core ?: return
        core.nativeVideoWindowId = remoteView
        core.nativePreviewWindowId = localPreview
    }

    /** `REFER` to [destination] — the transferor drops out (Task 55). */
    override fun transferCall(callKey: String, destination: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Transfer for a call the stack no longer has: $callKey")
            return
        }
        val address = Factory.instance().createAddress(destination) ?: run {
            logger.error(TAG, "Unparseable transfer target for $callKey")
            return
        }
        if (call.transferTo(address) != OK) logger.warn(TAG, "The stack refused the transfer of $callKey")
    }

    /**
     * `REFER` with `Replaces`, naming the consultation call (Task 57).
     *
     * `transferToAnother` rather than an address: the `Replaces` header identifies a
     * *dialog*, and an address cannot name one. This is the whole difference between an
     * attended transfer and a blind one to the same extension.
     */
    override fun transferCallToCall(callKey: String, consultationCallKey: String) {
        val call = callsByKey[callKey] ?: return
        val consultation = callsByKey[consultationCallKey] ?: run {
            logger.warn(TAG, "Attended transfer with no consultation call: $consultationCallKey")
            return
        }
        if (call.transferToAnother(consultation) != OK) {
            logger.warn(TAG, "The stack refused the attended transfer of $callKey")
        }
    }

    /**
     * Records this call's media to [filePath] (Task 58).
     *
     * The file goes on the call's own params — liblinphone has no per-recording API — and
     * the path comes from the encrypted store above. Nothing here chooses where a
     * recording lives, and nothing here decides whether one is allowed: `RecordingPolicy`
     * has already answered that, and this is only reached once it has.
     */
    override fun startRecording(callKey: String, filePath: String) {
        val call = callsByKey[callKey] ?: run {
            logger.warn(TAG, "Recording for a call the stack no longer has: $callKey")
            return
        }
        call.params.recordFile = filePath
        call.startRecording()
    }

    override fun stopRecording(callKey: String) {
        // Unconditional: `Call.isRecording` is deprecated in the SDK, and the stack's own
        // stop is already a no-op for a call that was not recording — which is exactly the
        // idempotence the gateway's contract asks for.
        callsByKey[callKey]?.stopRecording()
    }

    /**
     * liblinphone's call states, reduced to the ones this app branches on.
     *
     * Null for the states that carry no decision - `Released`, the `Updating` family, the
     * pausing intermediates. Emitting them would make every consumer re-check that they do
     * not matter.
     */
    private fun Call.State.toStackCallState(): StackCallState? = when (this) {
        // PushIncomingReceived is the same INVITE seen one step earlier - the core knows a
        // call is coming because a push woke it, before the INVITE itself has arrived
        // (Task 38). Both mean "a call is arriving", and the app has one answer to that.
        Call.State.IncomingReceived, Call.State.PushIncomingReceived ->
            StackCallState.INCOMING_RECEIVED

        Call.State.OutgoingInit -> StackCallState.OUTGOING_INIT
        Call.State.OutgoingProgress -> StackCallState.OUTGOING_PROGRESS
        Call.State.OutgoingRinging -> StackCallState.OUTGOING_RINGING
        Call.State.OutgoingEarlyMedia -> StackCallState.OUTGOING_EARLY_MEDIA
        Call.State.Connected -> StackCallState.CONNECTED
        Call.State.StreamsRunning -> StackCallState.STREAMS_RUNNING

        // Three states where there used to be one. Which end is holding decides which end
        // can resume, and collapsing them is how "resume did nothing" bugs happen (Task
        // 41). `Pausing` stays absent: it is the intermediate before `Paused`, and the app
        // has nothing different to do while a hold is in flight.
        Call.State.Paused -> StackCallState.PAUSED
        Call.State.PausedByRemote -> StackCallState.PAUSED_BY_REMOTE
        Call.State.Resuming -> StackCallState.RESUMING

        // The far end sent a re-INVITE and the stack is holding it for an answer. Carried
        // up rather than collapsed, because it is the one moment an escalation can be
        // declined (Task 54) - and with `automaticallyAccept` off it is a state the core
        // will sit in until this app answers.
        Call.State.UpdatedByRemote -> StackCallState.UPDATED_BY_REMOTE

        // A REFER arrived for this leg; it is being transferred away by the far end.
        Call.State.Referred -> StackCallState.REFERRED
        Call.State.End -> StackCallState.ENDED
        Call.State.Error -> StackCallState.ERROR
        else -> null
    }

    override fun start() {
        if (core != null) return

        val created = Factory.instance().createCore(
            File(context.filesDir, CONFIG_FILE).absolutePath,
            null,
            context,
        )
        created.addListener(listener)
        created.configureVideo()
        created.start()
        core = created
        logger.info(TAG, "SIP core started")
    }

    /**
     * Transport and media security, from the account's own settings (Task 62, §7, DoD 13).
     *
     * ## Certificate validation is never turned off
     *
     * `verifyServerCertificates(true)` and `verifyServerCn(true)`, unconditionally. There
     * is no branch here that disables them and there is not meant to be: §7 forbids a
     * permissive `TrustManager` outright, and the usual way one arrives is a debug flag
     * that outlives the debugging. An enterprise with its own PBX certificate authority is
     * served by [StackAccount.customCaPath], which **adds** a trust anchor rather than
     * removing the check.
     *
     * ## Mandatory means the stack refuses
     *
     * `setMediaEncryptionMandatory(true)` is what makes liblinphone fail a call it cannot
     * encrypt instead of continuing in the clear. The engine checks again once media is
     * running — see `LinphoneSipEngine.advance` — because the two catch different things:
     * this one stops the negotiation, and that one catches a call that negotiated
     * encryption and then did not have any.
     *
     * ## Core-wide, from a per-account setting
     *
     * liblinphone keeps media encryption on the `Core`, not on `AccountParams`, so with two
     * accounts configured differently the last one added wins. That is a real limitation of
     * this stack rather than a choice, and it is written down in `docs/security.md` instead
     * of being hidden behind an API that looks per-account.
     */
    private fun Core.applySecurity(account: StackAccount) {
        verifyServerCertificates(true)
        verifyServerCn(true)

        // Additive, and only when a deployment has explicitly configured one.
        account.customCaPath?.let { rootCa = it }

        setMediaEncryption(
            when (account.mediaEncryption) {
                StackMediaEncryption.NONE -> MediaEncryption.None
                StackMediaEncryption.OPTIONAL, StackMediaEncryption.MANDATORY -> MediaEncryption.SRTP
            },
        )
        isMediaEncryptionMandatory = account.mediaEncryption == StackMediaEncryption.MANDATORY
    }

    /**
     * The account's codec preferences, as the SDP offer (§5.1, §5.2).
     *
     * ## This is new behaviour, not a refactor
     *
     * `CodecPreferences` was modelled in `:domain`, validated, stored in its own two
     * columns and edited on a screen — and never read below this seam. Every account
     * therefore offered whatever liblinphone was built with, in whatever order it chose,
     * and the codec editor was a control wired to nothing. Both halves of that were
     * invisible: an SDP offer is not something the app displays.
     *
     * ## Enable and order, rather than replace
     *
     * The array handed back to the core keeps **every** payload type the stack knows, with
     * the preferred ones enabled and moved to the front and the rest disabled behind them.
     * Passing only the chosen ones would be shorter and is wrong: the array is the core's
     * whole payload-type table, and dropping an entry loses it until the core is rebuilt —
     * including entries this app never chooses but the stack still needs.
     *
     * [PayloadType.isSignalling] is the specific case that would have bitten. RFC 4733
     * `telephone-event` sits in the audio payload types and is not an audio codec; it is
     * how DTMF travels. Disabling it because it is absent from an account's codec list
     * would have left Task 43's keypad silently unable to reach an IVR.
     *
     * ## Core-wide, from a per-account setting
     *
     * The same limitation [applySecurity] carries and for the same reason: liblinphone
     * keeps payload types on the `Core`, not on `AccountParams`, so with two accounts
     * configured differently the last one added wins. Recorded here rather than hidden
     * behind an API that pretends otherwise.
     */
    private fun Core.applyCodecs(account: StackAccount) {
        // Before the video ordering, and the ordering does not survive without it.
        // `CodecPriorityPolicy.Auto` is the default and it re-sorts the video payload
        // types itself, by what the handset can encode in hardware. That is a reasonable
        // default for an app with no opinion, and silently overrules one that has: the
        // account's video preference would be written and then reordered underneath it,
        // with nothing to show the difference short of reading an SDP offer off the wire.
        // `Basic` is what makes the list below the answer.
        videoCodecPriorityPolicy = CodecPriorityPolicy.Basic

        audioPayloadTypes = audioPayloadTypes.prioritised(account.audioCodecs)
        videoPayloadTypes = videoPayloadTypes.prioritised(account.videoCodecs)
        logger.info(
            TAG,
            "Codecs for ${account.key}: audio=${account.audioCodecs} video=${account.videoCodecs}",
        )
    }

    /**
     * The same payload types, with [preferred] enabled and in front.
     *
     * Matching is case-insensitive because the two sides spell them differently: the domain
     * writes `opus` and `G722` as they appear in an `a=rtpmap` line, and the stack reports
     * whatever its own table holds.
     *
     * The sort is stable, so payload types that share a rank — every disabled one — keep
     * the order the stack gave them. Nothing here depends on that; it just means two runs
     * over an unchanged preference list produce an identical table.
     */
    private fun Array<PayloadType>.prioritised(preferred: List<String>): Array<PayloadType> {
        val rank = preferred.withIndex().associate { (index, name) -> name.lowercase() to index }
        forEach { payload ->
            payload.enable(payload.isSignalling || payload.mimeType.lowercase() in rank)
        }
        return sortedBy { rank[it.mimeType.lowercase()] ?: Int.MAX_VALUE }.toTypedArray()
    }

    /**
     * True for a payload type that carries signalling rather than media.
     *
     * `telephone-event` is RFC 4733 DTMF. It is listed among the audio payload types and is
     * not an audio codec, so it must survive a codec preference that does not name it —
     * see [applyCodecs].
     */
    private val PayloadType.isSignalling: Boolean
        get() = mimeType.equals(TELEPHONE_EVENT, ignoreCase = true)

    /**
     * The video defaults this app needs (Tasks 51, 54).
     *
     * ## Nothing is automatic
     *
     * `automaticallyInitiate` false: an outgoing call offers video only when the profile
     * asked for it, so a video-capable build does not quietly add a camera stream to every
     * call somebody places.
     *
     * `automaticallyAccept` false is the one that matters. Left on — and it is on by
     * default in some builds — the core answers an incoming re-INVITE offering video by
     * accepting it, and the first anyone knows about the escalation is their own camera
     * light. With it off the core reports `UpdatedByRemote` and waits, which is what makes
     * the prompt §5.2 requires possible at all (Task 54).
     *
     * ## Capture starts off
     *
     * The camera is claimed by `CameraPolicy` when a call needs it and not before
     * (Task 51). Display is left enabled: it costs nothing without a stream to draw, and
     * turning it on later would mean re-negotiating a running call to see it.
     */
    private fun Core.configureVideo() {
        videoActivationPolicy = Factory.instance().createVideoActivationPolicy().apply {
            automaticallyInitiate = false
            automaticallyAccept = false
        }
        isVideoCaptureEnabled = false
        isVideoDisplayEnabled = true

        // Capture is bounded rather than left at the stack's default, which negotiates the
        // best definition the camera and the link appear to allow. On a video call between
        // two handsets that produced an allocation rate the collector could not keep up
        // with: logcat showed back-to-back young collections freeing 109 MB and then
        // 171 MB, and a `WaitForGcToComplete blocked Alloc` of 75 ms — an allocating
        // thread stopped dead, mid-call, waiting for the heap.
        //
        // VGA at 24 fps is the compromise. It is the definition VP8 software-encodes
        // comfortably on a mid-range phone, and VP8 is what actually gets negotiated here:
        // the SDK ships no ffmpeg and no OpenH264 plugin, so H.264 has no encoder to
        // reach, whatever the codec list says.
        //
        // Named constants because these are the numbers to move first when the target
        // changes, and because a bare `640` in a media path says nothing about why.
        setPreferredVideoDefinition(
            Factory.instance().createVideoDefinition(CAPTURE_WIDTH, CAPTURE_HEIGHT),
        )
        preferredFramerate = CAPTURE_FRAMERATE

        // The other half: with a bounded capture, adaptive rate control is what walks the
        // bitrate down a congested link instead of holding the resolution and dropping the
        // frames that carry it.
        isAdaptiveRateControlEnabled = true
    }

    override fun addAccount(account: StackAccount) {
        val core = this.core ?: run {
            logger.error(TAG, "addAccount before start")
            return
        }
        val factory = Factory.instance()

        // Both addresses are parsed before anything is registered: a malformed one after
        // the auth info was stored would leave a credential in the core for an account
        // that never exists.
        val identity = factory.createAddress("sip:${account.username}@${account.domain}")
        val server = factory.createAddress(account.registrarUri)
        if (identity == null || server == null) {
            logger.error(TAG, "Account ${account.key} has an unusable address")
            return
        }

        // The previous credential goes first. The core looks auth entries up by realm and
        // username, so a password change that left the old entry in place would let the
        // stale password answer a challenge the new one should - and would keep it in
        // memory besides.
        authInfoByKey.remove(account.key)?.let(core::removeAuthInfo)

        // Credentials live in the core's auth store, keyed by realm and username, and are
        // looked up when a challenge arrives rather than attached to the params. Held by
        // key so `removeAccount` can take this exact entry back out again.
        val authInfo = factory.createAuthInfo(
            account.authUsername,
            null,
            account.password,
            null,
            null,
            account.domain,
        )
        core.addAuthInfo(authInfo)
        authInfoByKey[account.key] = authInfo

        // Applied before the account is added, so the first REGISTER already carries them,
        // and so the first INVITE offers the codecs this account actually asked for.
        core.applySecurity(account)
        core.applyCodecs(account)

        val params = core.createAccountParams().apply {
            identityAddress = identity
            // setServerAddress, not the deprecated setServerAddr(String): the typed form
            // parses once here rather than re-parsing inside the stack.
            serverAddress = server
            isRegisterEnabled = account.registerEnabled
            expires = account.expirySeconds
            account.proxyUri
                ?.let(factory::createAddress)
                ?.let { setRoutesAddresses(arrayOf(it)) }
        }

        val existing = accountsByKey[account.key]
        if (existing != null) {
            // Replace in place: adding a second account for the same identity would leave
            // two bindings fighting over one registrar record.
            existing.params = params
        } else {
            val created = core.createAccount(params)
            core.addAccount(created)
            accountsByKey[account.key] = created
        }
    }

    override fun removeAccount(accountKey: String) {
        val core = this.core ?: return
        val account = accountsByKey.remove(accountKey) ?: return

        // Turning registration off first is what produces the `Expires: 0`; removing the
        // account outright would drop the binding without telling the registrar, leaving
        // it to ring a device that is no longer listening until the binding expires.
        account.params = account.params.clone().apply { isRegisterEnabled = false }
        core.removeAccount(account)

        // The credential goes with it. Keeping it would mean a logged-out account's
        // password stayed decrypted in the core's auth store until the process died
        // (Task 29). Removed after the account, so the `Expires: 0` can still be signed.
        authInfoByKey.remove(accountKey)?.let(core::removeAuthInfo)
    }

    /**
     * Publishes RFC 8599 parameters on the `Contact` header (ADR-004, Task 38).
     *
     * Set on the core rather than assembled by hand: liblinphone owns the `Contact` header
     * and writes `pn-provider`, `pn-param` and `pn-prid` into it for every account that
     * allows push. Hand-writing them into `contactParameters` would fight the stack for
     * the same header.
     *
     * Each account is then re-registered, because a parameter the registrar has not seen
     * has no effect — the binding it must update is the one already on file.
     */
    override fun setPushParameters(parameters: StackPushParameters?) {
        val core = this.core ?: run {
            logger.warn(TAG, "Push parameters set before the core was started")
            return
        }

        core.isPushNotificationEnabled = parameters != null
        if (parameters != null) {
            // Nullable in the SDK: a core built without push support exposes no config,
            // and there is then nowhere to put the token. Warn rather than throw - the
            // registration itself is still valid, it just will not be woken by a push.
            val config = core.pushNotificationConfig
            if (config == null) {
                logger.warn(TAG, "The core exposes no push notification config")
            } else {
                config.provider = parameters.provider
                config.param = parameters.param
                config.prid = parameters.prid
            }
        }

        // The flag is per account and defaults off, so an account added before the token
        // arrived would never carry the parameters without this.
        accountsByKey.values.forEach { account ->
            account.params = account.params.clone().apply {
                pushNotificationAllowed = parameters != null
            }
        }
        core.refreshRegisters()
    }

    override fun refreshAccount(accountKey: String) {
        // liblinphone refreshes all registrations together; there is no per-account call.
        // Harmless: a refresh of an already-valid binding is a no-op at the registrar.
        core?.refreshRegisters()
    }

    override fun setNetworkReachable(reachable: Boolean) {
        // No-op before start(), which is correct: a stack that does not exist has no
        // sockets to rebind, and the first register will bind against whatever is up.
        core?.isNetworkReachable = reachable
    }

    override fun stop() {
        core?.let { running ->
            running.removeListener(listener)
            // Cleared while the core is still alive, because afterwards there is nothing
            // to clear them on: a held surface outlives its screen otherwise (Task 52).
            running.nativeVideoWindowId = null
            running.nativePreviewWindowId = null
            running.isVideoCaptureEnabled = false
            // Every credential this gateway handed over, taken back before the core is
            // released - the same rule as `removeAccount`, applied to a shutdown.
            authInfoByKey.values.forEach(running::removeAuthInfo)
            running.stop()
        }
        authInfoByKey.clear()
        accountsByKey.clear()
        // The calls go with the core that owned them. Keeping the references would leave
        // this gateway able to terminate calls belonging to a stack that no longer exists.
        callsByKey.clear()
        // The surfaces go before the core does. A view the stack still holds after its
        // owner has gone is a texture written into for the life of the process (Task 52).
        watchedConferences.clear()
        core = null
        logger.info(TAG, "SIP core stopped")
    }

    /**
     * Maps the SDK enum into this module's own.
     *
     * Exhaustive with no `else`, on purpose: if the SDK adds a state, this stops
     * compiling, which is the moment to decide what it means rather than defaulting it to
     * something plausible.
     */
    private fun RegistrationState.toStackState(): StackRegistrationState = when (this) {
        RegistrationState.None -> StackRegistrationState.NONE
        RegistrationState.Progress -> StackRegistrationState.PROGRESS
        RegistrationState.Ok -> StackRegistrationState.OK
        RegistrationState.Cleared -> StackRegistrationState.CLEARED
        RegistrationState.Failed -> StackRegistrationState.FAILED
        RegistrationState.Refreshing -> StackRegistrationState.REFRESHING
    }

    private companion object {
        const val TAG = "LinphoneGateway"
        const val CONFIG_FILE = "linphone.rc"
        const val EVENT_BUFFER = 64

        /** What liblinphone returns from a request it accepted; anything else is -1. */
        const val OK = 0

        /** Capture definition and rate; see `configureVideo` for why they are bounded. */
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
        const val CAPTURE_FRAMERATE = 24f

        /**
         * RFC 4733 DTMF, which lives among the audio payload types without being a codec.
         *
         * Named so a codec preference that does not mention it cannot switch it off. See
         * `applyCodecs`.
         */
        const val TELEPHONE_EVENT = "telephone-event"

        /** The stack reports a participant's join time in seconds; the app works in millis. */
        const val MILLIS_PER_SECOND = 1_000L
    }
}
