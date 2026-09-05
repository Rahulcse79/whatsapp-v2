package com.whatsappv2.data.sip

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.core.common.time.SystemClock
import com.whatsappv2.data.sip.call.CallStateMapper
import com.whatsappv2.data.sip.call.LinphoneCallGateway
import com.whatsappv2.data.sip.call.StackCallEvent
import com.whatsappv2.data.sip.di.SipStackScope
import com.whatsappv2.data.sip.network.NetworkMonitor
import com.whatsappv2.data.sip.network.RegistrationRecoveryCoordinator
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.RegistrationStateMapper
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.CallStateMachine
import com.whatsappv2.domain.call.TransitionResult
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.engine.PlatformCallRegistry
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.engine.UnmanagedCallRegistry
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.registration.RegistrationRetrySchedule
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registration and calling, backed by the real SIP stack (Tasks 27, 35, 37, 40).
 *
 * Registration, placing and answering calls, mute and audio routing are implemented here.
 * Hold, DTMF, transfer and conferencing still report [SipError.EngineUnavailable] until the
 * tasks that implement them (41, 43, 55, 60) — stubbing them to "succeed" would let a
 * screen be built against behaviour that does not exist.
 *
 * ## Where the logic lives
 *
 * Everything decidable without the stack is kept out of here: backoff and refresh timing
 * in `:domain`, state translation in [RegistrationStateMapper], and the SDK itself behind
 * [LinphoneCoreGateway]. What remains is bookkeeping — which accounts exist, what their
 * last known state was — and that is what the tests exercise.
 *
 * ## Network changes
 *
 * [RegistrationRecoveryCoordinator] watches the link and re-registers across handovers and
 * outages. It lives here, not in `:app`, because its lifetime is the stack's: the case it
 * exists for - no network, so nothing registered, so the foreground service stops itself -
 * is precisely when the service is not there to host it.
 *
 * ## The platform is asked before the network
 *
 * [PlatformCallRegistry] is Telecom, behind a `:domain` interface. The engine publishes a
 * call, asks the platform, and only then sends the INVITE — because only the platform knows
 * whether the user is already on a cellular call, and §3 requires that call to be honoured
 * rather than talked over. The same seam answers for an inbound INVITE, where a refusal
 * means 486 and nothing shown at all.
 *
 * ## Credentials
 *
 * Fetched from the repository immediately before a REGISTER and handed straight to the
 * gateway. They are never stored on this object: the engine outlives every call, and a
 * decrypted password held here would sit in memory for the life of the process rather
 * than the life of a registration.
 */
@Singleton
internal class LinphoneSipEngine @Inject constructor(
    private val gateway: LinphoneCoreGateway,
    private val callGateway: LinphoneCallGateway,
    private val accounts: SipAccountRepository,
    private val networkMonitor: NetworkMonitor,
    @SipStackScope private val scope: CoroutineScope,
    private val logger: Logger,
    /** Call start and connect timestamps, injected so a test asserts them. */
    private val clock: Clock = SystemClock,
    /**
     * Telecom, behind a domain interface (Task 34, §3).
     *
     * The engine asks it before an INVITE goes out and before anything rings, because it
     * is the only thing that knows whether the user is already on a cellular call.
     * Defaulted to the permissive registry so a JVM test that is not exercising the
     * platform need not supply one; Dagger ignores the default and injects `:app`'s.
     */
    private val platform: PlatformCallRegistry = UnmanagedCallRegistry,
    /**
     * Everything this engine does not implement yet, delegated rather than restubbed.
     *
     * Task 27 said calls, media and conferencing keep reporting
     * [SipError.EngineUnavailable], and [UnavailableSipEngine] is already exactly that.
     * Delegating to it means there is one set of "not built yet" answers instead of two
     * that can drift apart, and each role drops out of this class the moment the task
     * that implements it overrides the member.
     *
     * Defaulted so the tests that construct this directly keep compiling; Dagger ignores
     * the default and injects the singleton.
     */
    unimplemented: UnavailableSipEngine = UnavailableSipEngine(),
) : SipEngine,
    RegistrationRetrySchedule,
    // Calls are implemented below - place, answer, reject, hang up, mute and route.
    // DTMF (Task 43), hold (Task 41), transfer (Task 55) and conferencing (Task 60) are
    // still EngineUnavailable, which is what those tasks replace.
    SipCallController by unimplemented,
    SipMediaController by unimplemented,
    SipConferenceController by unimplemented {

    /**
     * Network-change recovery (Task 30).
     *
     * Constructed here rather than injected, because it needs this engine as its
     * registrar and injecting it would be a cycle. Owning it also settles its lifetime:
     * recovery starts and stops with the stack, which is the only span over which it
     * means anything.
     */
    private val recovery = RegistrationRecoveryCoordinator(
        networkMonitor = networkMonitor,
        registrar = this,
        rebinder = gateway,
        scope = scope,
        logger = logger,
    )

    private val states = MutableStateFlow<Map<AccountId, RegistrationState>>(emptyMap())
    override val registrationState: StateFlow<Map<AccountId, RegistrationState>> = states.asStateFlow()

    /**
     * Forwarded from [recovery], which is the thing that actually schedules retries.
     *
     * The engine implements the interface only so there is one object for the graph to
     * bind; it holds no schedule of its own and deliberately reports `retryScheduled =
     * false` on every state it publishes.
     */
    override val nextRetryAt: StateFlow<Map<AccountId, Long>> get() = recovery.nextRetryAt

    /** Requested expiry per account, so a state event can report the right figure. */
    private val requestedExpiry = mutableMapOf<String, Int>()

    /**
     * Calls this engine currently knows about, keyed by the app's own call id.
     *
     * The engine owns the snapshot; `CallStateMachine` owns the transitions. Keeping the
     * two apart is what lets every legality question - can this call be held, is this
     * event valid here - be asked of a pure function that the stack cannot reach.
     */
    private val calls = MutableStateFlow<Map<CallId, CallSnapshot>>(emptyMap())

    override val activeCalls: StateFlow<List<CallSnapshot>> =
        calls.map { it.values.toList() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Inbound INVITEs (Task 37).
     *
     * Buffered and never replayed, as [SipCallController.incomingCalls] requires. The
     * buffer is the part that matters: a call arriving while the app is starting up is
     * precisely the case worth getting right (§2.5), and an emission dropped for want of
     * a collector at that instant is a call the user never hears about. Replay would be
     * the opposite mistake — re-ringing a call that was answered minutes ago.
     */
    private val incoming = MutableSharedFlow<IncomingCall>(
        replay = 0,
        extraBufferCapacity = INCOMING_BUFFER,
    )
    override val incomingCalls: Flow<IncomingCall> = incoming.asSharedFlow()

    private var started = false

    /**
     * The event collection job.
     *
     * Held so [stop] can end it. Without this the collector outlives the stack it is
     * listening to, keeping the engine and everything it references alive for the life of
     * the scope - and in a test, keeping `runTest` waiting forever.
     */
    private var collectJob: Job? = null

    /** The call-event collector. Held for the same reason as [collectJob]. */
    private var callCollectJob: Job? = null

    /**
     * Begins consuming stack events.
     *
     * Separate from construction so nothing starts a native stack as a side effect of
     * dependency injection - which would run before the app has decided it needs one.
     */
    fun start() {
        if (started) return
        started = true

        gateway.start()
        recovery.start()
        callCollectJob = scope.collectCallEvents()
        collectJob = scope.launch {
            gateway.registrationEvents.collect { event ->
                val id = AccountId(event.accountKey)
                val expiry = requestedExpiry[event.accountKey] ?: DEFAULT_EXPIRY_SECONDS

                if (event.state == StackRegistrationState.FAILED) {
                    val error = RegistrationStateMapper.toSipError(event)
                    // The account id only - never the SIP identity or a credential (§7).
                    logger.warn(TAG, "Registration failed for ${event.accountKey}: $error")
                }

                states.update { current ->
                    current + (
                        id to RegistrationStateMapper.toDomain(
                            event = event,
                            requestedExpirySeconds = expiry,
                            // Retry scheduling belongs to the caller that owns the
                            // backoff; the engine reports what happened, not what is next.
                            retryScheduled = false,
                        )
                        )
                }
            }
        }
    }

    /**
     * Consumes call events and moves each call through the FSM.
     *
     * Started from [start] beside the registration collector. Three kinds of event arrive
     * here and they are handled differently on purpose: an inbound INVITE creates a call,
     * a progress event moves one, and an event naming a call this engine has never seen is
     * dropped rather than inventing a snapshot for it.
     */
    private fun CoroutineScope.collectCallEvents() = launch {
        callGateway.callEvents.collect { event ->
            val id = CallId(event.callKey)
            val known = calls.value[id]

            when {
                known != null -> advance(id, known, event)
                CallStateMapper.isNewIncoming(event.state) -> onIncomingInvite(id, event)
                // Nothing to move and nothing to create. Logged at debug rather than
                // warned: a late event for a call the user already hung up is normal.
                else -> logger.debug(TAG, "Ignoring ${event.state} for unknown call $id")
            }
        }
    }

    /**
     * Moves a call this engine already knows about.
     *
     * The FSM is the authority on legality: an event it rejects is dropped and logged
     * rather than forced through, because a snapshot in an impossible state is worse than
     * one that missed a transition.
     */
    private fun advance(id: CallId, current: CallSnapshot, event: StackCallEvent) {
        if (CallStateMapper.isTerminal(event.state)) {
            val reason = CallStateMapper.toHangupReason(event)
            calls.update { it - id }
            // Telecom is told, because it did not cause this: without it the platform
            // keeps audio focus for a call that is over (Task 34).
            platform.onEnded(id, reason)
            return
        }

        val next = CallStateMapper.toCallEvent(event, current.direction)
            ?.let { CallStateMachine.transition(current.state, it) }
            ?.let { result ->
                when (result) {
                    is TransitionResult.Moved -> result.state
                    is TransitionResult.Rejected -> {
                        logger.warn(TAG, "Call $id: ${result.event} rejected from ${result.from}")
                        null
                    }
                }
            }

        val justConnected = current.connectedAtEpochMillis == null &&
            CallStateMapper.isConnected(event.state)
        if (justConnected) platform.onConnected(id)

        calls.update { live ->
            live + (
                id to current.copy(
                    state = next ?: current.state,
                    connectedAtEpochMillis = current.connectedAtEpochMillis
                        ?: clock.nowEpochMillis().takeIf { justConnected },
                )
                )
        }
    }

    /**
     * An inbound INVITE (Task 37).
     *
     * The snapshot is created synchronously so that a later event for the same call — an
     * immediate CANCEL, say — finds something to move. Telecom is then asked, and only
     * then does anything ring: §3 requires a cellular call in progress to be honoured, so
     * a refusal answers **486 Busy** and shows nothing at all rather than forcing this
     * app's full-screen UI over a call the user is already on.
     */
    private fun CoroutineScope.onIncomingInvite(id: CallId, event: StackCallEvent) {
        val from = SipUri.parse(event.remoteUri).getOrNull()
        if (from == null) {
            // A caller we cannot address is one we cannot show, hold, or call back.
            logger.warn(TAG, "Rejecting an inbound call whose remote address will not parse")
            callGateway.rejectCall(event.callKey, busy = false)
            return
        }

        val media = if (event.videoOffered) MediaProfile.AUDIO_VIDEO else MediaProfile.AUDIO
        val receivedAt = clock.nowEpochMillis()
        calls.update {
            it + (
                id to CallSnapshot(
                    callId = id,
                    accountId = AccountId(event.accountKey),
                    remote = from,
                    remoteDisplayName = event.remoteDisplayName,
                    direction = CallDirection.INCOMING,
                    state = CallState.Incoming(from),
                    media = media,
                    startedAtEpochMillis = receivedAt,
                    connectedAtEpochMillis = null,
                )
                )
        }

        val call = IncomingCall(
            callId = id,
            accountId = AccountId(event.accountKey),
            from = from,
            fromDisplayName = event.remoteDisplayName,
            offeredMedia = media,
            receivedAtEpochMillis = receivedAt,
        )

        // Launched rather than awaited inline: registering with Telecom is a round trip
        // through the platform, and the collector must stay free to deliver the next
        // event for this same call while it happens.
        launch {
            if (platform.registerIncoming(call)) {
                incoming.emit(call)
            } else {
                logger.info(TAG, "Telecom refused an inbound call; answering busy")
                callGateway.rejectCall(event.callKey, busy = true)
                calls.update { it - id }
            }
        }
    }

    /**
     * Places a call (Task 35).
     *
     * The snapshot is created and published **before** the INVITE goes out, in
     * `Outgoing.Calling`. Two reasons, and they are the same reason: a call that exists in
     * the UI before the network is involved cannot be missed by a collector that attached
     * late, and Telecom requires a `Connection` to exist before the INVITE so the platform
     * can refuse it if the user is already on a cellular call (Task 34, §3).
     *
     * Returns as soon as the INVITE is handed to the stack. Everything after that arrives
     * on [activeCalls] — the contract says explicitly not to wait on this for the call to
     * connect.
     */
    override suspend fun placeCall(
        accountId: AccountId,
        target: SipUri,
        media: MediaProfile,
    ): Outcome<CallId, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        // Not registered means the INVITE has nowhere to go. Failing here rather than
        // letting the stack time out is the difference between an immediate, accurate
        // message and thirty seconds of nothing.
        val registration = states.value[accountId]
        if (registration?.isUsable != true) return failure(SipError.NotRegistered)

        val callId = CallId(UUID.randomUUID().toString())
        val snapshot = CallSnapshot(
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
        calls.update { it + (callId to snapshot) }

        // Telecom, before the INVITE. It knows about the cellular call this app cannot
        // see, and a refusal is honoured rather than worked around (Task 34, §3). The
        // snapshot goes back out again on refusal: a call that will never exist must not
        // be left on screen.
        if (!platform.registerOutgoing(snapshot)) {
            calls.update { it - callId }
            logger.info(TAG, "Telecom refused an outgoing call")
            return failure(SipError.CallNotPermitted)
        }

        callGateway.placeCall(
            callKey = callId.value,
            accountKey = accountId.value,
            destination = target.render(),
            videoEnabled = media.hasVideo,
        )
        return success(callId)
    }

    /**
     * Ends a call.
     *
     * Idempotent per the [SipEngine] contract: hanging up a call that is already gone
     * succeeds quietly, because the caller cannot act on being told otherwise and the
     * outcome they wanted is already true.
     */
    override suspend fun hangup(callId: CallId, reason: HangupReason): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)
        if (callId !in calls.value) return success(Unit)

        callGateway.terminateCall(callId.value)
        // Removed locally rather than waiting for the stack's ENDED event. The user
        // pressed hang up; a row that lingers until a BYE is acknowledged reads as a
        // button that did nothing.
        calls.update { it - callId }
        platform.onEnded(callId, reason)
        return success(Unit)
    }

    /**
     * Answers a ringing inbound call (Task 37).
     *
     * Returns as soon as the acceptance is handed to the stack. The FSM moves when the
     * stack reports the call connected, not here: reporting `Connected` optimistically
     * would show a running call timer for a call whose media never came up.
     */
    override suspend fun answer(callId: CallId, media: MediaProfile): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)
        val call = calls.value[callId] ?: return failure(SipError.UnknownCall)
        if (call.state !is CallState.Incoming) {
            return failure(SipError.InvalidState("call is ${call.state}, not ringing"))
        }

        callGateway.answerCall(callId.value, videoEnabled = media.hasVideo)
        return success(Unit)
    }

    /**
     * Rejects a ringing inbound call.
     *
     * [HangupReason.BUSY] sends 486 and anything else sends 603 Decline, per the
     * [SipCallController] contract. The distinction reaches the caller, so it must reflect
     * what the user actually chose rather than one convenient default.
     */
    override suspend fun reject(callId: CallId, reason: HangupReason): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)
        // Idempotent, like hangup: rejecting a call that has already gone away succeeds,
        // because the outcome the caller wanted is already true.
        if (callId !in calls.value) return success(Unit)

        callGateway.rejectCall(callId.value, busy = reason == HangupReason.BUSY)
        calls.update { it - callId }
        platform.onEnded(callId, reason)
        return success(Unit)
    }

    /**
     * Mutes or unmutes the microphone (Task 40, DoD 8).
     *
     * The FSM decides whether this is legal at all: muting a call that is still ringing is
     * a UI bug, and accepting it would hide the fact that the real microphone was never
     * muted. The stack is only touched once the transition is allowed.
     */
    override suspend fun setMuted(callId: CallId, muted: Boolean): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        return applyControl(callId, CallEvent.SetMuted(muted)) {
            callGateway.setMicrophoneMuted(callId.value, muted)
        }
    }

    /**
     * Selects the audio output (Task 40, DoD 8).
     *
     * Routing is the platform's, not the stack's: Telecom arbitrates between apps, owns
     * the SCO link, and is what a headset's own buttons talk to. So this asks and reports
     * the answer — a [AudioRoute.BLUETOOTH] request with no headset connected fails rather
     * than silently playing on the earpiece, exactly as [SipMediaController] requires.
     *
     * The route reaches the FSM only once the platform has accepted it, which is what
     * keeps the in-call screen showing where audio actually is rather than where it was
     * asked to go.
     */
    override suspend fun setAudioRoute(callId: CallId, route: AudioRoute): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)
        if (callId !in calls.value) return failure(SipError.UnknownCall)

        if (!platform.requestAudioRoute(callId, route)) {
            return failure(SipError.InvalidState("$route is not available"))
        }
        return applyControl(callId, CallEvent.SetAudioRoute(route))
    }

    /**
     * Runs a control event through the FSM and, if it is legal, does the thing.
     *
     * The order is the point. `CallStateMachine` is asked first, so an action the call
     * cannot perform right now never reaches the stack, and the failure the caller gets
     * back names the state that refused it.
     */
    private fun applyControl(
        callId: CallId,
        event: CallEvent,
        onAccepted: () -> Unit = {},
    ): Outcome<Unit, SipError> {
        val call = calls.value[callId] ?: return failure(SipError.UnknownCall)

        return when (val result = CallStateMachine.transition(call.state, event)) {
            is TransitionResult.Rejected ->
                failure(SipError.InvalidState("$event is not allowed in ${call.state}"))

            is TransitionResult.Moved -> {
                onAccepted()
                calls.update { it + (callId to call.copy(state = result.state)) }
                success(Unit)
            }
        }
    }

    override suspend fun register(account: SipAccount): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        val credentials = when (val result = accounts.credentialsFor(account.id)) {
            is Outcome.Failure -> return failure(SipError.InvalidState("credentials unavailable"))
            is Outcome.Success -> result.value
        }

        requestedExpiry[account.id.value] = account.registrationExpirySeconds

        // Reported immediately rather than waiting for the stack's first event: the UI
        // must show that something is happening the moment the user presses save.
        states.update { it + (account.id to RegistrationState.Registering) }

        gateway.addAccount(account.toStackAccount(credentials.password.reveal()))
        return success(Unit)
    }

    /**
     * Unregisters and waits for the registrar to acknowledge (Task 29).
     *
     * The waiting is the part that matters. [SipRegistrar.unregister] promises to return
     * only once the request has been answered, precisely so that logout can stop the
     * foreground service next without cutting the `Expires: 0` off mid-flight — a
     * registrar that never hears it keeps ringing this device until the binding lapses.
     *
     * The wait is bounded, because the alternative is a logout that hangs on an
     * unreachable server. On expiry it gives up and says so in the log rather than
     * failing: locally the binding is gone and the credentials are wiped either way, so
     * reporting failure would leave the UI claiming an account is still logged in when it
     * is not.
     *
     * Either way the state is forced to [RegistrationState.Unregistered] before
     * returning. Leaving a stale `Registered` behind would keep the service alive with
     * nothing to hold open (§6) and show the user a registration that no longer exists.
     */
    override suspend fun unregister(accountId: AccountId): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        // Idempotent per the SipEngine contract: unregistering an unknown account
        // succeeds quietly rather than reporting a problem the caller cannot act on.
        // Removing the account is also what drops the credentials the stack held for it.
        gateway.removeAccount(accountId.value)
        requestedExpiry -= accountId.value

        val acknowledged = withTimeoutOrNull(UNREGISTER_ACK_TIMEOUT_MILLIS) {
            // Read from the state flow rather than the event stream: a StateFlow always
            // has a current value, so an acknowledgement that arrived while this was
            // being set up is seen rather than missed.
            states.first { it[accountId].isGone }
        } != null

        if (!acknowledged) {
            logger.warn(TAG, "Unregister for $accountId was not acknowledged; dropping it locally")
        }

        states.update { it + (accountId to RegistrationState.Unregistered) }
        return success(Unit)
    }

    override suspend fun refreshRegistration(accountId: AccountId): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)
        if (accountId.value !in requestedExpiry) return failure(SipError.UnknownAccount)

        gateway.refreshAccount(accountId.value)
        return success(Unit)
    }

    /**
     * Publishes RFC 8599 push parameters on every subsequent REGISTER (ADR-004, Task 38).
     *
     * Sent unconditionally, whether or not the deployed server is known to store them: it
     * is the standard mechanism, it costs nothing when ignored, and it means the token
     * reaches the server without a side channel of its own. Clearing them with `null` is
     * how logout stops a device being woken for an account it no longer holds.
     */
    override suspend fun setPushToken(token: PushToken?): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        gateway.setPushParameters(
            token?.let { StackPushParameters(provider = it.provider, param = it.param, prid = it.prid) },
        )
        // The token is never logged, not even truncated: it identifies a device (§7).
        logger.info(TAG, if (token == null) "Push parameters cleared" else "Push parameters published")
        return success(Unit)
    }

    /**
     * The [SipEngine] contract's release. Same thing as [stop], which the tests and
     * [SipEngineLifecycle] call directly — this is the name the domain uses for it.
     */
    override suspend fun shutdown() = stop()

    /** Releases the stack. Every account is forgotten; nothing stays registered. */
    fun stop() {
        if (!started) return
        started = false
        recovery.stop()
        collectJob?.cancel()
        collectJob = null
        callCollectJob?.cancel()
        callCollectJob = null
        gateway.stop()
        requestedExpiry.clear()
        states.value = emptyMap()

        // Every live call goes down with the stack, and Telecom is told so - a connection
        // left behind keeps audio focus for a call that no longer exists anywhere.
        val live = calls.value.keys
        calls.value = emptyMap()
        live.forEach { platform.onEnded(it, HangupReason.NETWORK_FAILURE) }
    }

    private fun SipAccount.toStackAccount(password: String) = StackAccount(
        key = id.value,
        username = username,
        authUsername = effectiveAuthUsername,
        password = password,
        domain = domain,
        registrarUri = "sip:$effectiveRegistrar",
        proxyUri = outboundProxy?.let { "sip:${it.render()}" },
        transport = transport.token,
        expirySeconds = registrationExpirySeconds,
    )

    /**
     * True when this account no longer holds a registration.
     *
     * `null` counts: an account the engine has never seen is not registered, which is the
     * state an unregister is trying to reach.
     */
    private val RegistrationState?.isGone: Boolean
        get() = this == null || this == RegistrationState.Unregistered

    private companion object {
        const val TAG = "LinphoneSipEngine"
        const val DEFAULT_EXPIRY_SECONDS = 3_600

        /**
         * How many inbound INVITEs may queue for a collector that is not there yet.
         *
         * Sixty-four is far more than a phone will ever have at once, and that is the
         * point: the buffer exists so a call arriving during app startup is not dropped,
         * not to bound anything.
         */
        const val INCOMING_BUFFER = 64

        /**
         * How long to wait for the registrar's answer to `Expires: 0`.
         *
         * Five seconds: long enough for a round trip on a slow mobile network, short
         * enough that logging out of a dead server does not feel broken.
         */
        const val UNREGISTER_ACK_TIMEOUT_MILLIS = 5_000L
    }
}
