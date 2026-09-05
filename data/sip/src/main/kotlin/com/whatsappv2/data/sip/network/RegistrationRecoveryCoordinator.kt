package com.whatsappv2.data.sip.network

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.core.common.time.SystemClock
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.registration.NetworkStatus
import com.whatsappv2.domain.registration.RecoveryAction
import com.whatsappv2.domain.registration.RegistrationRecoveryPolicy
import com.whatsappv2.domain.registration.RegistrationRetrySchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps registrations alive across network changes (Task 30, §6, DoD 6).
 *
 * Three things happen here, and only the third is a decision:
 *
 * 1. **Debounce.** A flapping link is the normal case at the edge of Wi-Fi coverage, and
 *    reacting to every callback would mean a transport rebind and a REGISTER per flap.
 *    Nothing is believed until the status has held still for [DEBOUNCE_WINDOW], so a link
 *    that never settles produces no traffic at all. That is the flap defence.
 * 2. **Rebind.** The stack's sockets are bound to the source address it had; a REGISTER
 *    issued before they are re-created leaves from an interface the device no longer owns
 *    and never reaches the wire. [TransportRebinder] is told `false` then `true`.
 * 3. **Decide, per account.** [RegistrationRecoveryPolicy] owns that, and it is pure — so
 *    airplane mode, a Wi-Fi to cellular handover and a registrar outage are each a set of
 *    arguments rather than a handset and a stopwatch.
 *
 * ## Where the state lives
 *
 * The policy is stateless; the bookkeeping it needs is here — consecutive failures per
 * account, the network each account's last REGISTER went out over, and whether a retry is
 * already pending. Keeping it out of the policy is what lets the rules be asserted one
 * call at a time.
 *
 * ## Why a failed retry schedules the next one itself
 *
 * The obvious design is to react only to registration state, and it stalls. A retry that
 * fails the same way as the last one produces an **equal** [RegistrationState], and a
 * `StateFlow` does not re-emit an equal value — so the chain would stop silently after one
 * attempt and the account would never come back. The next attempt is therefore scheduled
 * from the failure itself.
 *
 * ## Lifetime
 *
 * Owned by [com.whatsappv2.data.sip.LinphoneSipEngine] and started and stopped with it,
 * rather than injected and started from `:app`. That is not incidental: it must outlive
 * the foreground service, because the case it exists for — no network, so nothing
 * registered, so the service stops itself (§6) — is exactly when the service is gone.
 *
 * When the **process** is dead nothing here runs, and recovery is then push's job
 * (ADR-004, Task 38). No `ConnectivityManager` callback can help a process that does not
 * exist, and no arrangement of this class changes that.
 */
internal class RegistrationRecoveryCoordinator(
    private val networkMonitor: NetworkMonitor,
    private val registrar: SipRegistrar,
    private val rebinder: TransportRebinder,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val policy: RegistrationRecoveryPolicy = RegistrationRecoveryPolicy(),
    /**
     * The source of the backoff's jitter.
     *
     * Injected for the same reason [com.whatsappv2.domain.registration.RegistrationBackoff]
     * takes one: a deliberately random delay is untestable otherwise, and a test that
     * asserts a range instead of a value stops catching the bug where the attempt count is
     * never actually incremented.
     */
    private val random: Random = Random.Default,
    /**
     * Turns a delay into the wall-clock moment the UI counts down to.
     *
     * Defaulted rather than required: every existing test constructs this class without
     * one and none of them asserts a time, so making it mandatory would churn nine tests
     * to no purpose. The test that DOES assert a time passes a `MutableClock`.
     */
    private val clock: Clock = SystemClock,
) : RegistrationRetrySchedule {

    /** Consecutive failures per account. Reset only by a successful registration. */
    private val attempts = mutableMapOf<AccountId, Int>()

    /** The network each account's most recent REGISTER went out over. */
    private val boundNetwork = mutableMapOf<AccountId, Long>()

    private val pendingRetries = mutableMapOf<AccountId, Job>()

    /**
     * When each pending retry is due, for the screen that shows it (Task 31).
     *
     * A separate flow rather than a field on `RegistrationState`: the engine publishes
     * that, and the engine does not schedule retries — this class does. See
     * [RegistrationRetrySchedule].
     *
     * Kept in step with [pendingRetries] on every path that changes it, including the
     * cancellations. A countdown that keeps running after its retry was called off is
     * worse than no countdown: it says the app is about to do something it has decided
     * not to do.
     */
    private val retryTimes = MutableStateFlow<Map<AccountId, Long>>(emptyMap())
    override val nextRetryAt: StateFlow<Map<AccountId, Long>> = retryTimes.asStateFlow()

    /** Null until the first status arrives, so starting up is not reported as a change. */
    private var lastNetwork: NetworkStatus? = null

    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        if (job != null) return

        job = scope.launch {
            combine(
                networkMonitor.status
                    // Debounce before distinctUntilChanged, not after: a flap that lands
                    // back where it started emits the value it began at, which is then
                    // dropped as unchanged. The round trip costs nothing.
                    .debounce(DEBOUNCE_WINDOW)
                    .distinctUntilChanged(),
                registrar.registrationState,
            ) { network, states -> network to states }
                .collect { (network, states) -> onObservation(network, states) }
        }
    }

    /** Stops observing. Any scheduled retry goes with it. */
    fun stop() {
        job?.cancel()
        job = null
        cancelAllRetries()
        attempts.clear()
        boundNetwork.clear()
        lastNetwork = null
    }

    private suspend fun onObservation(
        network: NetworkStatus,
        states: Map<AccountId, RegistrationState>,
    ) {
        if (network != lastNetwork) {
            onNetworkChanged(from = lastNetwork, to = network)
            lastNetwork = network
        }

        states.forEach { (id, state) -> evaluate(id, state, network) }
    }

    private fun onNetworkChanged(from: NetworkStatus?, to: NetworkStatus) {
        // The first observation is not a change - it is the app finding out where it is.
        if (from == null) {
            logger.info(TAG, "Network is ${to.describe()}")
            return
        }

        // Every scheduled retry was timed for a link that no longer exists. Cancelling
        // them is not tidiness: on the way back up the policy re-registers immediately,
        // and a stale timer would fire a second REGISTER on top of it.
        cancelAllRetries()

        if (to is NetworkStatus.Available) {
            logger.info(TAG, "Network changed: ${from.describe()} -> ${to.describe()}")
            // Down and up, in that order. Anything less and the stack keeps the sockets it
            // has, which are bound to an address the device no longer holds.
            rebinder.setNetworkReachable(false)
            rebinder.setNetworkReachable(true)
        } else {
            // DoD 6, stated in the log because it is a rule and not an absence: with no
            // network every attempt wakes the radio and none can succeed. What restarts
            // things is the platform's callback, not a timer of ours.
            logger.info(TAG, "No network: retries stopped until one returns")
            rebinder.setNetworkReachable(false)
        }
    }

    private suspend fun evaluate(
        id: AccountId,
        state: RegistrationState,
        network: NetworkStatus,
    ) {
        if (state.isUsable) {
            // A working registration resets the escalation. Doing this only on success is
            // what stops a link that connects and drops repeatedly from holding the client
            // at the shortest delay for ever.
            attempts.remove(id)

            // An account already registered the first time we look at it was registered
            // over the network we can see now - there is nothing else it could have been.
            // Recording rather than acting stops a coordinator start from re-registering
            // everything that was already healthy.
            network.networkId?.let { boundNetwork.putIfAbsent(id, it) }
        }

        when (val action = policy.decide(network, state, boundNetwork[id], attempts[id] ?: 0, random)) {
            RecoveryAction.Idle -> Unit

            // Quiet: the reason was logged once for the device, not once per account.
            RecoveryAction.AwaitNetwork -> cancelRetry(id)

            RecoveryAction.RegisterNow -> {
                cancelRetry(id)
                reRegister(id, network, reason = "network changed")
            }

            is RecoveryAction.RetryAfter -> scheduleRetry(id, network, action.delay)
        }
    }

    private fun scheduleRetry(id: AccountId, network: NetworkStatus, after: Duration) {
        // The combine re-fires on every registration state change, and a failing account
        // emits more than once. Without this, each emission would stack another timer.
        if (pendingRetries[id]?.isActive == true) return

        val attempt = (attempts[id] ?: 0) + 1
        attempts[id] = attempt
        logger.info(TAG, "Registrar unreachable for $id: retry $attempt in ${after.inWholeSeconds}s")
        retryTimes.update { it + (id to clock.nowEpochMillis() + after.inWholeMilliseconds) }

        pendingRetries[id] = scope.launch {
            delay(after)
            reRegister(id, network, reason = "retry $attempt")
        }
    }

    private suspend fun reRegister(id: AccountId, network: NetworkStatus, reason: String) {
        // Recorded before the attempt, not after it succeeds. If this attempt fails, the
        // account must not still look like it has "moved network" - it would be decided
        // as RegisterNow again, immediately, for ever. Recording here turns the second
        // decision into a backoff, which is what a failure on a live link deserves.
        network.networkId?.let { boundNetwork[id] = it }
        // Dropped before the attempt, not after: the retry this coroutine *is* must not
        // count as one already pending when the next one is scheduled below.
        pendingRetries.remove(id)
        retryTimes.update { it - id }
        logger.info(TAG, "Re-registering $id ($reason)")

        when (val result = registrar.refreshRegistration(id)) {
            is Outcome.Success -> Unit
            is Outcome.Failure -> {
                // The account id only - never its identity or a credential (§7, DoD 12).
                logger.warn(TAG, "Re-register of $id refused: ${result.error}")
                // See the class documentation: a repeat failure may produce no state
                // change at all, so the chain is continued from here rather than from an
                // event that will not arrive.
                registrar.registrationState.value[id]?.let { evaluate(id, it, network) }
            }
        }
    }

    private fun cancelRetry(id: AccountId) {
        pendingRetries.remove(id)?.cancel()
        retryTimes.update { it - id }
    }

    private fun cancelAllRetries() {
        pendingRetries.values.forEach(Job::cancel)
        pendingRetries.clear()
        retryTimes.value = emptyMap()
    }

    private fun NetworkStatus.describe(): String = when (this) {
        NetworkStatus.Unavailable -> "unavailable"
        is NetworkStatus.Available -> "$transport#$networkId"
    }

    private companion object {
        const val TAG = "SipNetworkRecovery"

        /**
         * How long a network status must hold still before it is believed.
         *
         * One second: long enough to swallow the burst of callbacks a handover produces,
         * short enough that a genuine change is acted on before the user notices calls
         * are not arriving.
         */
        val DEBOUNCE_WINDOW: Duration = 1.seconds
    }
}
