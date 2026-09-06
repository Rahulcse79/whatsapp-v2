package com.whatsappv2.data.sip.registration

import com.whatsappv2.data.sip.call.LinphoneCallGateway
import com.whatsappv2.data.sip.call.LinphoneRecordingGateway
import com.whatsappv2.data.sip.call.LinphoneVideoGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackConferenceEvent
import com.whatsappv2.data.sip.call.StackParticipant
import com.whatsappv2.data.sip.call.StackTransferEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A [LinphoneCoreGateway] with no SIP stack behind it.
 *
 * Exists so the callback-to-Flow mapping can be exercised on the JVM. liblinphone cannot
 * run there, so without this the mapping could only be tested on a device - which is the
 * same as untested.
 *
 * It records what it was asked to do, so a test can assert that `removeAccount` was
 * called before the account was forgotten, and emits whatever states a test scripts.
 *
 * ## Two views of the same calls
 *
 * [addedAccounts] is the test's own **recording** of every call, kept for good. [held] is
 * what the stack would still be holding right now, and it is emptied by `removeAccount`
 * and `stop` exactly as the real core's account list and auth store are. Task 29 needs
 * the second: "no decrypted password remains after logout" is a question about what is
 * still held, and an append-only log could never answer it.
 */
internal class FakeLinphoneCoreGateway :
    LinphoneCoreGateway,
    LinphoneCallGateway,
    LinphoneVideoGateway,
    LinphoneRecordingGateway {

    private val events = MutableSharedFlow<StackRegistrationEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )
    override val registrationEvents: Flow<StackRegistrationEvent> = events.asSharedFlow()

    private val callEventFlow = MutableSharedFlow<StackCallEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )
    override val callEvents: Flow<StackCallEvent> = callEventFlow.asSharedFlow()

    private val transferEventFlow = MutableSharedFlow<StackTransferEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )
    override val transferEvents: Flow<StackTransferEvent> = transferEventFlow.asSharedFlow()

    private val conferenceEventFlow = MutableSharedFlow<StackConferenceEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER,
    )
    override val conferenceEvents: Flow<StackConferenceEvent> = conferenceEventFlow.asSharedFlow()

    /** Every video re-INVITE the engine asked for, in order (Tasks 53, 54). */
    val videoRequests: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Every answer to an escalation the far end offered, in order (Task 54). */
    val videoUpdateAnswers: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Every camera switch asked of the stack (Task 53). */
    val cameraSwitches: MutableList<String> = mutableListOf()

    /**
     * Whether the camera is capturing, and every change to it, in order (Task 51).
     *
     * A log rather than a flag, because the assertion that matters is a sequence: the
     * camera must be released after a call ends, and a flag that reads false at the end
     * cannot tell "released" from "never claimed".
     */
    val cameraCaptureChanges: MutableList<Boolean> = mutableListOf()

    /** The surfaces the stack was last given, if any (Task 52). */
    var videoWindows: Pair<Any?, Any?> = null to null
        private set

    /** Every blind transfer asked for, as call key to destination (Task 55). */
    val blindTransfers: MutableList<Pair<String, String>> = mutableListOf()

    /** Every attended transfer asked for, as call key to consultation key (Task 57). */
    val attendedTransfers: MutableList<Pair<String, String>> = mutableListOf()

    /** Every recording started, as call key to path, and every one stopped (Task 58). */
    val startedRecordings: MutableList<Pair<String, String>> = mutableListOf()
    val stoppedRecordings: MutableList<String> = mutableListOf()

    /** Every INVITE the engine asked for, in order. */
    val placedCalls: MutableList<PlacedCall> = mutableListOf()

    /** Every call the engine asked to end, in order. */
    val terminatedCalls: MutableList<String> = mutableListOf()

    /** Every call answered, with the media it was answered on. */
    val answeredCalls: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Every call rejected, with true for a 486 and false for a 603. */
    val rejectedCalls: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Microphone state per call, as the stack was last told it. */
    val mutedCalls: MutableMap<String, Boolean> = mutableMapOf()

    /**
     * Every hold and resume asked of the stack, in order (Task 41).
     *
     * A log rather than a flag: "held, then resumed, then held again" and "held once" are
     * different behaviours, and only an ordered record tells them apart.
     */
    val holdRequests: MutableList<Pair<String, Boolean>> = mutableListOf()

    /** Every DTMF digit sent, with true for SIP INFO and false for RFC 4733 (Task 43). */
    val sentDtmf: MutableList<SentDtmf> = mutableListOf()

    /** One `sendDtmf`, flattened so a test can assert digit and carrier in one equals. */
    data class SentDtmf(val callKey: String, val digit: Char, val useInfo: Boolean)

    /**
     * The push parameters currently published, or null when they are cleared.
     *
     * A field rather than a log: "what would the next REGISTER carry" is a question about
     * what is held now, and an append-only list could not answer it.
     */
    var pushParameters: StackPushParameters? = null
        private set

    /** One `placeCall`, flattened so a test can assert the whole request in one equals. */
    data class PlacedCall(
        val callKey: String,
        val accountKey: String,
        val destination: String,
        val videoEnabled: Boolean,
    )

    val addedAccounts: MutableList<StackAccount> = mutableListOf()
    val removedKeys: MutableList<String> = mutableListOf()
    val refreshedKeys: MutableList<String> = mutableListOf()

    /**
     * Every reachability signal, in order.
     *
     * Order is the assertion worth making: a rebind is `false` then `true`, and sending
     * only `true` leaves the stack on the sockets it already had.
     */
    val reachabilitySignals: MutableList<Boolean> = mutableListOf()

    private val held = mutableMapOf<String, StackAccount>()

    /**
     * The accounts, and therefore the credentials, the stack is still holding.
     *
     * Stands in for the real core's account list plus its auth store, which is where a
     * decrypted password actually lives once a REGISTER has been built.
     */
    val heldAccounts: Map<String, StackAccount> get() = held.toMap()
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun start() {
        startCount++
    }

    override fun addAccount(account: StackAccount) {
        addedAccounts += account
        held[account.key] = account
    }

    override fun removeAccount(accountKey: String) {
        removedKeys += accountKey
        // The credentials go with the account, which is what the seam's contract requires
        // and what the real gateway does by removing the core's matching auth info.
        held -= accountKey
    }

    override fun refreshAccount(accountKey: String) {
        refreshedKeys += accountKey
    }

    override fun setPushParameters(parameters: StackPushParameters?) {
        pushParameters = parameters
    }

    override fun setNetworkReachable(reachable: Boolean) {
        reachabilitySignals += reachable
    }

    override fun stop() {
        stopCount++
        held.clear()
    }

    override fun placeCall(
        callKey: String,
        accountKey: String,
        destination: String,
        videoEnabled: Boolean,
    ) {
        placedCalls += PlacedCall(callKey, accountKey, destination, videoEnabled)
    }

    override fun answerCall(callKey: String, videoEnabled: Boolean) {
        answeredCalls += callKey to videoEnabled
    }

    override fun rejectCall(callKey: String, busy: Boolean) {
        rejectedCalls += callKey to busy
    }

    override fun setMicrophoneMuted(callKey: String, muted: Boolean) {
        mutedCalls[callKey] = muted
    }

    override fun pauseCall(callKey: String) {
        holdRequests += callKey to true
    }

    override fun resumeCall(callKey: String) {
        holdRequests += callKey to false
    }

    override fun sendDtmf(callKey: String, digit: Char, useInfo: Boolean) {
        sentDtmf += SentDtmf(callKey, digit, useInfo)
    }

    override fun terminateCall(callKey: String) {
        terminatedCalls += callKey
    }

    override fun setVideoEnabled(callKey: String, enabled: Boolean) {
        videoRequests += callKey to enabled
    }

    override fun respondToVideoUpdate(callKey: String, accept: Boolean) {
        videoUpdateAnswers += callKey to accept
    }

    override fun switchCamera(callKey: String) {
        cameraSwitches += callKey
    }

    override fun setCameraCapturing(capturing: Boolean) {
        cameraCaptureChanges += capturing
    }

    override fun setVideoWindows(remoteView: Any?, localPreview: Any?) {
        videoWindows = remoteView to localPreview
    }

    override fun transferCall(callKey: String, destination: String) {
        blindTransfers += callKey to destination
    }

    override fun transferCallToCall(callKey: String, consultationCallKey: String) {
        attendedTransfers += callKey to consultationCallKey
    }

    override fun startRecording(callKey: String, filePath: String) {
        startedRecordings += callKey to filePath
    }

    override fun stopRecording(callKey: String) {
        stoppedRecordings += callKey
    }

    /** Emits transfer progress as the stack would (Task 55). */
    fun emitTransfer(callKey: String, state: StackCallState, statusCode: Int? = null) {
        transferEventFlow.tryEmit(StackTransferEvent(callKey, state, statusCode))
    }

    /** Emits a conference roster as the bridge would (Task 60). */
    fun emitConference(
        callKey: String,
        participants: List<StackParticipant> = emptyList(),
        rosterAvailable: Boolean = participants.isNotEmpty(),
    ) {
        conferenceEventFlow.tryEmit(StackConferenceEvent(callKey, participants, rosterAvailable))
    }

    /** Emits a call-state change as the stack would. */
    fun emitCall(
        callKey: String,
        state: StackCallState,
        accountKey: String = "acct-1",
        remoteUri: String = "sip:bob@sip.example.com",
        statusCode: Int? = null,
        displayName: String? = null,
        videoOffered: Boolean = false,
        videoActive: Boolean = false,
        // Encrypted by default: the interesting assertion is the call that is NOT, and a
        // default of false would make every unrelated test look like a security failure.
        mediaEncrypted: Boolean = true,
    ) {
        callEventFlow.tryEmit(
            StackCallEvent(
                callKey = callKey,
                accountKey = accountKey,
                remoteUri = remoteUri,
                remoteDisplayName = displayName,
                state = state,
                statusCode = statusCode,
                message = null,
                videoOffered = videoOffered,
                videoActive = videoActive,
                mediaEncrypted = mediaEncrypted,
            ),
        )
    }

    /** Emits a state change as the stack would. */
    fun emit(
        accountKey: String,
        state: StackRegistrationState,
        statusCode: Int? = null,
        message: String? = null,
    ) {
        events.tryEmit(StackRegistrationEvent(accountKey, state, statusCode, message))
    }

    private companion object {
        const val BUFFER = 64
    }
}
