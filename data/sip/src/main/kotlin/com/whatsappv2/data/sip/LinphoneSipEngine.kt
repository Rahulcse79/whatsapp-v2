package com.whatsappv2.data.sip

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.RegistrationStateMapper
import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.SipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val scope: CoroutineScope,
    private val logger: Logger,
) : SipRegistrar {

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

    override suspend fun unregister(accountId: AccountId): Outcome<Unit, SipError> {
        if (!started) return failure(SipError.EngineUnavailable)

        // Idempotent per the SipEngine contract: unregistering an unknown account
        // succeeds quietly rather than reporting a problem the caller cannot act on.
        gateway.removeAccount(accountId.value)
        requestedExpiry -= accountId.value
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

    /** Releases the stack. Every account is forgotten; nothing stays registered. */
    fun stop() {
        if (!started) return
        started = false
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

    private companion object {
        const val TAG = "LinphoneSipEngine"
        const val DEFAULT_EXPIRY_SECONDS = 3_600
    }
}
