package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.CallStateMachine
import com.whatsappv2.domain.call.TransitionResult
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.ConferenceParticipant
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.toHangupReason
import com.whatsappv2.domain.engine.toRegistrationFailure
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory [SipEngine] that needs no SIP server, no network and no device.
 *
 * This is what makes DoD 4 achievable: the entire application — account management,
 * registration UI, dialer, in-call screen, call history — can be built, run and tested
 * against this. Every downstream task depends on it, which is why it is built early.
 *
 * ## Two ways to drive it
 *
 * **Outcomes of things the app does** are scripted with [failNext] and [alwaysFail]:
 * they decide what [register], [placeCall] and friends return.
 *
 * **Things the network does to the app** are triggered with the `simulate*` methods:
 * [simulateIncomingCall], [simulateRemoteAnswer], [simulateRemoteHangup],
 * [simulateRemoteHold], [simulateNetworkLoss] and so on.
 *
 * ## Fidelity
 *
 * Call transitions run through the real [CallStateMachine], so a test written against
 * this fake exercises the same rules production will. A fake with its own hand-rolled
 * state logic would pass tests the real engine fails.
 *
 * ## Determinism
 *
 * Time comes from an injected [Clock] that only moves when a test moves it. There is no
 * `Thread.sleep`, no `delay`, and no reading of the system clock anywhere in this class.
 *
 * Not thread-safe by design: tests drive it from one coroutine, and locking would hide
 * ordering bugs rather than expose them.
 */
class FakeSipEngine(
    /** Advance with [MutableClock.advanceBy] to control call durations exactly. */
    val clock: MutableClock = MutableClock(),
) : SipEngine {

    /** Operations that can be made to fail. */
    enum class Operation {
        REGISTER,
        UNREGISTER,
        REFRESH_REGISTRATION,
        SET_PUSH_TOKEN,
        PLACE_CALL,
        ANSWER,
        REJECT,
        HANGUP,
        SET_HOLD,
        SET_MUTED,
        SET_AUDIO_ROUTE,
        SET_VIDEO_ENABLED,
        SWITCH_CAMERA,
        SEND_DTMF,
        TRANSFER,
        JOIN_CONFERENCE,
    }

    /** One call into the engine, recorded so a test can assert on ordering. */
    data class Invocation(val operation: Operation, val detail: String)

    // ------------------------------------------------------------------ state

    private val registrations = MutableStateFlow<Map<AccountId, RegistrationState>>(emptyMap())
    override val registrationState: StateFlow<Map<AccountId, RegistrationState>> = registrations.asStateFlow()

    private val calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    override val activeCalls: StateFlow<List<CallSnapshot>> = calls.asStateFlow()

    // Buffered so an emission is not lost when nothing is collecting at that instant -
    // a call arriving during startup is exactly the case that matters (§2.5).
    private val incoming = MutableSharedFlow<IncomingCall>(replay = 0, extraBufferCapacity = INCOMING_BUFFER)
    override val incomingCalls: Flow<IncomingCall> = incoming.asSharedFlow()

    private val conferenceSessions = MutableStateFlow<List<ConferenceSession>>(emptyList())
    override val conferences: StateFlow<List<ConferenceSession>> = conferenceSessions.asStateFlow()

    private val knownAccounts = mutableMapOf<AccountId, SipAccount>()
    private val oneShotFailures = mutableMapOf<Operation, ArrayDeque<SipError>>()
    private val persistentFailures = mutableMapOf<Operation, SipError>()
    private val recorded = mutableListOf<Invocation>()
    private val ended = mutableListOf<CallSnapshot>()
    private var nextCallNumber = 0
    private var isShutDown = false

    /** Every operation invoked, in order. Lets a test assert unregister-then-register. */
    val invocations: List<Invocation> get() = recorded.toList()

    /**
     * Calls that have ended, in order, with their terminal state and reason.
     *
     * They leave [activeCalls] on termination - which is what that name promises - but
     * are kept here so a test can assert how a call finished, which is exactly what the
     * call log will record (Task 47).
     */
    val terminatedCalls: List<CallSnapshot> get() = ended.toList()

    /** The push token most recently published, or `null` if none (ADR-004). */
    var publishedPushToken: PushToken? = null
        private set

    // ------------------------------------------------------------------ scripting

    /** Makes the next call to [operation] fail with [error], once. */
    fun failNext(operation: Operation, error: SipError): FakeSipEngine = apply {
        oneShotFailures.getOrPut(operation) { ArrayDeque() }.addLast(error)
    }

    /** Makes every call to [operation] fail until [clearFailures]. */
    fun alwaysFail(operation: Operation, error: SipError): FakeSipEngine = apply {
        persistentFailures[operation] = error
    }

    /** Removes all scripted failures. */
    fun clearFailures(): FakeSipEngine = apply {
        oneShotFailures.clear()
        persistentFailures.clear()
    }

    /** Forgets recorded invocations, so a test can assert on a later phase alone. */
    fun clearInvocations(): FakeSipEngine = apply { recorded.clear() }

    // ------------------------------------------------------------------ registration

    override suspend fun register(account: SipAccount): Outcome<Unit, SipError> {
        record(Operation.REGISTER, account.id.value)
        knownAccounts[account.id] = account

        if (isShutDown) return failure(SipError.EngineUnavailable)

        scriptedFailure(Operation.REGISTER)?.let { error ->
            // A failed REGISTER must leave an observable Failed state, not just return
            // an error: the account list renders from registrationState (Task 31).
            registrations.update {
                it + (account.id to RegistrationState.Failed(error.toRegistrationFailure(), retryScheduled = true))
            }
            return failure(error)
        }

        registrations.update { it + (account.id to RegistrationState.Registering) }
        registrations.update {
            it + (account.id to RegistrationState.Registered(account.registrationExpirySeconds))
        }
        return success(Unit)
    }

    override suspend fun unregister(accountId: AccountId): Outcome<Unit, SipError> {
        record(Operation.UNREGISTER, accountId.value)
        return guard(Operation.UNREGISTER) {
            knownAccounts -= accountId
            registrations.update { it + (accountId to RegistrationState.Unregistered) }
            success(Unit)
        }
    }

    override suspend fun refreshRegistration(accountId: AccountId): Outcome<Unit, SipError> {
        record(Operation.REFRESH_REGISTRATION, accountId.value)
        val account = knownAccounts[accountId] ?: return failure(SipError.UnknownAccount)
        return guard(Operation.REFRESH_REGISTRATION) {
            registrations.update {
                it + (accountId to RegistrationState.Registered(account.registrationExpirySeconds))
            }
            success(Unit)
        }
    }

    override suspend fun setPushToken(token: PushToken?): Outcome<Unit, SipError> {
        record(Operation.SET_PUSH_TOKEN, token?.provider ?: "cleared")
        return guard(Operation.SET_PUSH_TOKEN) {
            publishedPushToken = token
            success(Unit)
        }
    }

    // ------------------------------------------------------------------ calls

    override suspend fun placeCall(
        accountId: AccountId,
        target: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError> {
        record(Operation.PLACE_CALL, target.render())
        if (accountId !in knownAccounts) return failure(SipError.UnknownAccount)
        if (registrations.value[accountId]?.isUsable != true) return failure(SipError.NotRegistered)

        return guard(Operation.PLACE_CALL) {
            val callId = nextCallId()
            calls.update {
                it + CallSnapshot(
                    callId = callId,
                    accountId = accountId,
                    remote = target,
                    remoteDisplayName = null,
                    direction = CallDirection.OUTGOING,
                    state = CallState.Outgoing.Calling,
                    media = media,
                    startedAtEpochMillis = clock.nowEpochMillis(),
                    connectedAtEpochMillis = null,
                )
            }
            success(callId)
        }
    }

    override suspend fun answer(callId: CallId, media: MediaProfile): Outcome<Unit, SipError> {
        record(Operation.ANSWER, callId.value)
        return guard(Operation.ANSWER) {
            apply(callId, CallEvent.LocalAnswered()) {
                it.copy(media = media, connectedAtEpochMillis = clock.nowEpochMillis())
            }
        }
    }

    override suspend fun reject(callId: CallId, reason: HangupReason): Outcome<Unit, SipError> {
        record(Operation.REJECT, "${callId.value}:$reason")
        return guard(Operation.REJECT) { apply(callId, CallEvent.Terminate(reason)) }
    }

    override suspend fun hangup(callId: CallId, reason: HangupReason): Outcome<Unit, SipError> {
        record(Operation.HANGUP, "${callId.value}:$reason")
        // Idempotent per the SipEngine contract: hanging up an ended call succeeds.
        if (snapshot(callId) == null) return success(Unit)
        return guard(Operation.HANGUP) { apply(callId, CallEvent.Terminate(reason)) }
    }

    override suspend fun setHold(callId: CallId, held: Boolean): Outcome<Unit, SipError> {
        record(Operation.SET_HOLD, "${callId.value}:$held")
        val event = if (held) CallEvent.LocalHold else CallEvent.LocalResume
        return guard(Operation.SET_HOLD) {
            when (val result = apply(callId, event)) {
                is Outcome.Failure -> result
                is Outcome.Success -> if (held) result else apply(callId, CallEvent.ResumeConfirmed)
            }
        }
    }

    override suspend fun sendDtmf(callId: CallId, digit: DtmfDigit): Outcome<Unit, SipError> {
        record(Operation.SEND_DTMF, "${callId.value}:$digit")
        return guard(Operation.SEND_DTMF) { requireEstablished(callId) }
    }

    override suspend fun transfer(
        callId: CallId,
        target: SipUri,
        type: TransferType,
        consultationCallId: CallId?,
    ): Outcome<Unit, SipError> {
        record(Operation.TRANSFER, "${callId.value}:$type:${target.render()}")
        if (type == TransferType.ATTENDED && consultationCallId == null) {
            return failure(SipError.InvalidState("attended transfer requires a consultation call"))
        }
        return guard(Operation.TRANSFER) { apply(callId, CallEvent.StartTransfer(type)) }
    }

    // ------------------------------------------------------------------ media

    override suspend fun setMuted(callId: CallId, muted: Boolean): Outcome<Unit, SipError> {
        record(Operation.SET_MUTED, "${callId.value}:$muted")
        return guard(Operation.SET_MUTED) { apply(callId, CallEvent.SetMuted(muted)) }
    }

    override suspend fun setAudioRoute(callId: CallId, route: AudioRoute): Outcome<Unit, SipError> {
        record(Operation.SET_AUDIO_ROUTE, "${callId.value}:$route")
        return guard(Operation.SET_AUDIO_ROUTE) { apply(callId, CallEvent.SetAudioRoute(route)) }
    }

    override suspend fun setVideoEnabled(callId: CallId, enabled: Boolean): Outcome<Unit, SipError> {
        record(Operation.SET_VIDEO_ENABLED, "${callId.value}:$enabled")
        return guard(Operation.SET_VIDEO_ENABLED) {
            apply(callId, CallEvent.SetVideoEnabled(enabled)) {
                it.copy(media = MediaProfile.of(audio = true, video = enabled) ?: MediaProfile.AUDIO)
            }
        }
    }

    override suspend fun switchCamera(callId: CallId): Outcome<Unit, SipError> {
        record(Operation.SWITCH_CAMERA, callId.value)
        return guard(Operation.SWITCH_CAMERA) { requireEstablished(callId) }
    }

    // ------------------------------------------------------------------ conference

    override suspend fun joinConference(
        accountId: AccountId,
        conferenceUri: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError> {
        record(Operation.JOIN_CONFERENCE, conferenceUri.render())
        return when (val placed = placeCall(accountId, conferenceUri, media)) {
            is Outcome.Failure -> placed
            is Outcome.Success -> guard(Operation.JOIN_CONFERENCE) {
                updateCall(placed.value) { it.copy(isConference = true) }
                conferenceSessions.update {
                    it + ConferenceSession(
                        callId = placed.value,
                        accountId = accountId,
                        conferenceUri = conferenceUri,
                        participants = emptyList(),
                        rosterAvailable = false,
                    )
                }
                success(placed.value)
            }
        }
    }

    override suspend fun shutdown() {
        isShutDown = true
        ended.clear()
        calls.value = emptyList()
        conferenceSessions.value = emptyList()
        registrations.value = emptyMap()
    }


    // ------------------------------------------------------------------ simulation
    //
    // Everything below is something the NETWORK does to the app, as opposed to
    // something the app asks the engine to do. None of it suspends, so a test reads as
    // a straight sequence of events.

    /** Puts an account into the registered state without going through [register]. */
    fun givenRegistered(account: SipAccount): FakeSipEngine = apply {
        knownAccounts[account.id] = account
        registrations.update {
            it + (account.id to RegistrationState.Registered(account.registrationExpirySeconds))
        }
    }

    /** Delivers an inbound INVITE and returns it, so the test can use its [CallId]. */
    fun simulateIncomingCall(
        accountId: AccountId,
        from: SipUri,
        media: MediaProfile = MediaProfile.AUDIO,
        displayName: String? = null,
        viaPush: Boolean = false,
    ): IncomingCall {
        val callId = nextCallId()
        calls.update {
            it + CallSnapshot(
                callId = callId,
                accountId = accountId,
                remote = from,
                remoteDisplayName = displayName,
                direction = CallDirection.INCOMING,
                state = CallState.Incoming(from),
                media = media,
                startedAtEpochMillis = clock.nowEpochMillis(),
                connectedAtEpochMillis = null,
            )
        }
        val event = IncomingCall(
            callId = callId,
            accountId = accountId,
            from = from,
            fromDisplayName = displayName,
            offeredMedia = media,
            receivedAtEpochMillis = clock.nowEpochMillis(),
            viaPush = viaPush,
        )
        incoming.tryEmit(event)
        return event
    }

    /** 180 Ringing from the far end. */
    fun simulateRemoteRinging(callId: CallId) = apply { apply(callId, CallEvent.RemoteRinging) }

    /** 183 Session Progress with SDP — ringback or an announcement is now audible. */
    fun simulateRemoteEarlyMedia(callId: CallId) = apply { apply(callId, CallEvent.RemoteEarlyMedia) }

    /** 200 OK — the far end answered our outgoing call. */
    fun simulateRemoteAnswer(callId: CallId) = apply {
        apply(callId, CallEvent.RemoteAnswered) { it.copy(connectedAtEpochMillis = clock.nowEpochMillis()) }
    }

    /** The far end hung up, or the server tore the call down. */
    fun simulateRemoteHangup(callId: CallId, reason: HangupReason = HangupReason.REMOTE_HANGUP) =
        apply { apply(callId, CallEvent.Terminate(reason)) }

    /** The far end rejected our outgoing call, e.g. 486 Busy or 603 Decline. */
    fun simulateRemoteRejection(callId: CallId, error: SipError) =
        apply { apply(callId, CallEvent.Terminate(error.toHangupReason())) }

    /** A re-INVITE from the far end put us on hold. */
    fun simulateRemoteHold(callId: CallId) = apply { apply(callId, CallEvent.RemoteHold) }

    /** The far end resumed. */
    fun simulateRemoteResume(callId: CallId) = apply { apply(callId, CallEvent.RemoteResume) }

    /** The transferee accepted; this leg can be released. */
    fun simulateTransferSucceeded(callId: CallId) = apply { apply(callId, CallEvent.TransferSucceeded) }

    /** The transfer failed. The original call must survive (§5.2). */
    fun simulateTransferFailed(callId: CallId) = apply { apply(callId, CallEvent.TransferFailed) }

    /**
     * The network went away: every registration fails and every call drops.
     *
     * Accounts are remembered, so [simulateNetworkRestored] can bring them back the way
     * the real recovery path does (Task 30).
     */
    fun simulateNetworkLoss(): FakeSipEngine = apply {
        val offline = RegistrationState.Failed(
            RegistrationFailure.NETWORK_UNAVAILABLE,
            retryScheduled = true,
        )
        registrations.update { current -> current.mapValues { offline } }
        calls.value.map { it.callId }.forEach { apply(it, CallEvent.Terminate(HangupReason.NETWORK_FAILURE)) }
    }

    /** The network came back and every known account re-registered. */
    fun simulateNetworkRestored(): FakeSipEngine = apply {
        knownAccounts.forEach { (id, account) ->
            registrations.update {
                it + (id to RegistrationState.Registered(account.registrationExpirySeconds))
            }
        }
    }

    /** The registration lapsed without being refreshed. */
    fun simulateRegistrationExpiry(accountId: AccountId): FakeSipEngine = apply {
        registrations.update { it + (accountId to RegistrationState.Unregistered) }
    }

    /** The conference bridge published a roster (Task 60). */
    fun simulateConferenceRoster(
        callId: CallId,
        participants: List<ConferenceParticipant>,
    ): FakeSipEngine = apply {
        conferenceSessions.update { sessions ->
            sessions.map {
                if (it.callId == callId) it.copy(participants = participants, rosterAvailable = true) else it
            }
        }
    }

    /** Forgets ended calls, for a test that wants to assert on a later phase alone. */
    fun clearTerminated(): FakeSipEngine = apply { ended.clear() }

    // ------------------------------------------------------------------ internals

    private fun record(operation: Operation, detail: String) {
        recorded += Invocation(operation, detail)
    }

    private fun nextCallId(): CallId = CallId("call-${++nextCallNumber}")

    private fun snapshot(callId: CallId): CallSnapshot? = calls.value.firstOrNull { it.callId == callId }

    private fun scriptedFailure(operation: Operation): SipError? =
        oneShotFailures[operation]?.removeFirstOrNull() ?: persistentFailures[operation]

    private inline fun <T> guard(
        operation: Operation,
        block: () -> Outcome<T, SipError>,
    ): Outcome<T, SipError> {
        if (isShutDown) return failure(SipError.EngineUnavailable)
        scriptedFailure(operation)?.let { return failure(it) }
        return block()
    }

    private fun updateCall(callId: CallId, transform: (CallSnapshot) -> CallSnapshot) {
        calls.update { list -> list.map { if (it.callId == callId) transform(it) else it } }
    }

    private fun requireEstablished(callId: CallId): Outcome<Unit, SipError> {
        val current = snapshot(callId) ?: return failure(SipError.UnknownCall)
        return if (current.state.isEstablished) {
            success(Unit)
        } else {
            failure(SipError.InvalidState("call is ${current.state}, not established"))
        }
    }

    /**
     * Runs [event] through the REAL [CallStateMachine].
     *
     * This is the fidelity that matters: a fake with its own hand-rolled transition
     * logic would accept sequences production rejects, and the tests built on it would
     * be worse than no tests at all.
     */
    private fun apply(
        callId: CallId,
        event: CallEvent,
        transform: (CallSnapshot) -> CallSnapshot = { it },
    ): Outcome<Unit, SipError> {
        val current = snapshot(callId) ?: return failure(SipError.UnknownCall)

        return when (val result = CallStateMachine.transition(current.state, event)) {
            is TransitionResult.Rejected ->
                failure(SipError.InvalidState("$event is not allowed in ${current.state}"))

            is TransitionResult.Moved -> {
                val updated = transform(current.copy(state = result.state))
                if (result.state is CallState.Terminated) {
                    // Ended calls leave activeCalls, which is what the name promises,
                    // but are retained in `ended` so a test can assert the final state
                    // and reason - which the call log (Task 47) will need.
                    ended += updated
                    calls.update { list -> list.filterNot { it.callId == callId } }
                    conferenceSessions.update { list -> list.filterNot { it.callId == callId } }
                } else {
                    calls.update { list -> list.map { if (it.callId == callId) updated else it } }
                }
                success(Unit)
            }
        }
    }

    private companion object {
        const val INCOMING_BUFFER = 64
    }
}
