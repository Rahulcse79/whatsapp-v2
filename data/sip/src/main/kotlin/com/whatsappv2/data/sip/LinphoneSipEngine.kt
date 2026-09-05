package com.whatsappv2.data.sip

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.sip.di.SipStackScope
import com.whatsappv2.data.sip.network.NetworkMonitor
import com.whatsappv2.data.sip.network.RegistrationRecoveryCoordinator
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.RegistrationStateMapper
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipEngine
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registration, backed by the real SIP stack (Task 27).
 *
 * Only registration. Calls, media and conferencing still report
 * [SipError.EngineUnavailable] until the tasks that implement them — stubbing them to
 * "succeed" would let the dialer be built against behaviour that does not exist.
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
    private val accounts: SipAccountRepository,
    private val networkMonitor: NetworkMonitor,
    @SipStackScope private val scope: CoroutineScope,
    private val logger: Logger,
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

    /** Requested expiry per account, so a state event can report the right figure. */
    private val requestedExpiry = mutableMapOf<String, Int>()

    private var started = false

    /**
     * The event collection job.
     *
     * Held so [stop] can end it. Without this the collector outlives the stack it is
     * listening to, keeping the engine and everything it references alive for the life of
     * the scope - and in a test, keeping `runTest` waiting forever.
     */
    private var collectJob: Job? = null

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

    override suspend fun setPushToken(
        token: com.whatsappv2.domain.engine.PushToken?,
    ): Outcome<Unit, SipError> = failure(SipError.EngineUnavailable)

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
        gateway.stop()
        requestedExpiry.clear()
        states.value = emptyMap()
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
         * How long to wait for the registrar's answer to `Expires: 0`.
         *
         * Five seconds: long enough for a round trip on a slow mobile network, short
         * enough that logging out of a dead server does not feel broken.
         */
        const val UNREGISTER_ACK_TIMEOUT_MILLIS = 5_000L
    }
}
