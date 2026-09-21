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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps registrations alive across network changes (Task 30, §6, DoD 6) — and across
 * sleep, which turned out to be the harder one.
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
 * ## Sleep, and the three timers that have to survive it
 *
 * A registration is a lease. The registrar forgets the binding when it expires, and
 * nothing on the wire says so — the next INVITE for the extension simply goes nowhere.
 * Keeping the lease means a REGISTER before every expiry, for as long as the account is
 * logged in, whatever the device is doing. Three timers are involved and every one of
 * them used to stop when the device slept:
 *
 * - **The refresh.** PJSIP re-registers at `expiry - 5 s` on its own timer heap, which
 *   runs on `CLOCK_MONOTONIC` — a clock that does not advance while the CPU is suspended.
 *   A handset in deep sleep is suspended almost all of the time, so the refresh fell due
 *   in *awake* time long after the lease had lapsed in *wall* time. Now there is a
 *   **keepalive** of this class's own, on a [WakeTimer], at half the granted expiry:
 *   early enough that an inexact alarm cannot land past the lease, and cheap enough
 *   (one datagram each way) that the extra REGISTERs while awake cost nothing. When it
 *   fires the account is re-registered and the keepalive is armed again.
 * - **The retry.** A failed attempt was retried after a coroutine `delay` — the same
 *   stalled clock — sampled from a window that grew to thirty minutes. Now the retry is a
 *   [WakeTimer] too, and the window stops at five minutes (`RegistrationBackoff`).
 * - **The moment somebody picks the phone up.** No timer is early enough for that. The
 *   screen coming on, the lock screen being passed, the app coming to the front and the
 *   platform leaving doze each re-register every account immediately — a pending retry
 *   is cancelled and run now, and a registration that reads as healthy is refreshed
 *   anyway, because "healthy" is what the lease looked like when the device went to
 *   sleep. Throttled per account by [WAKE_REFRESH_GAP], so a screen toggled on and off
 *   is one REGISTER and not ten.
 *
 * Measured on a Galaxy M14 on 2026-09-21, before any of this: ~2.5 h idle, Wi-Fi up, IP
 * unchanged, process alive — and the extension gone from the registrar, the screen on
 * "Reconnecting…" indefinitely, recoverable only by force-stopping the app.
 *
 * ## Where the state lives
 *
 * The policy is stateless; the bookkeeping it needs is here — consecutive failures per
 * account, the network each account's last REGISTER went out over, which timers are
 * armed. Keeping it out of the policy is what lets the rules be asserted one call at a
 * time. Everything that mutates it runs under [lock]: the observation collector, the
 * timers and the wake events arrive on different threads, and a `mutableMapOf` written
 * from two of them at once is a corrupted table on some later call.
 *
 * ## Why a failed retry schedules the next one itself
 *
 * The obvious design is to react only to registration state, and it stalls. A retry that
 * fails the same way as the last one produces an **equal** [RegistrationState], and a
 * `StateFlow` does not re-emit an equal value — so the chain would stop silently after one
 * attempt and the account would never come back. The next attempt is therefore scheduled
 * from the failure itself. The keepalive re-arms itself for the same reason: a refresh
 * that succeeds produces an equal `Registered`, which is no emission at all.
 *
 * ## Lifetime
 *
 * Owned by [com.whatsappv2.data.sip.PjsipSipEngine] and started and stopped with it,
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
     * Turns a delay into the wall-clock moment the UI counts down to, and measures the
     * gap between wake-driven refreshes.
     *
     * Defaulted rather than required: every existing test constructs this class without
     * one and none of them asserts a time, so making it mandatory would churn nine tests
     * to no purpose. The test that DOES assert a time passes a `MutableClock`.
     */
    private val clock: Clock = SystemClock,
    /**
     * Called once the link has gone, so the engine can stop publishing a registration the
     * device cannot hold any more.
     *
     * Here rather than in a second collector on the same flow: this class already watches
     * the network, already debounces it, and already knows the moment it went away. The
     * engine owns the state, so it supplies the action; what "gone" means stays one
     * decision in one place.
     *
     * Defaulted, like [clock], because the nine tests of this class construct it without
     * one and none of them asserts anything about registration state.
     */
    private val onNetworkLost: () -> Unit = {},
    /**
     * Every timer this class sets. See [WakeTimer] for why it cannot be a `delay`.
     *
     * Defaulted to the coroutine one so the JVM tests run on virtual time; the app binds
     * the `AlarmManager` one.
     */
    private val timer: WakeTimer = CoroutineWakeTimer(scope),
    /** The device coming back into use. See [DeviceWakeMonitor]. */
    private val wakeMonitor: DeviceWakeMonitor = DeviceWakeMonitor.NONE,
) : RegistrationRetrySchedule {

    /** Serialises every path that touches the maps below. See the class documentation. */
    private val lock = Mutex()

    /** Consecutive failures per account. Reset only by a successful registration. */
    private val attempts = mutableMapOf<AccountId, Int>()

    /** The network each account's most recent REGISTER went out over. */
    private val boundNetwork = mutableMapOf<AccountId, Long>()

    /** Accounts with a retry timer armed. */
    private val pendingRetries = mutableSetOf<AccountId>()

    /** Accounts with a keepalive timer armed. */
    private val keepalives = mutableSetOf<AccountId>()

    /** When each account was last re-registered from here, epoch millis, for [WAKE_REFRESH_GAP]. */
    private val lastRegisterAt = mutableMapOf<AccountId, Long>()

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
    private var wakeJob: Job? = null

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
                .collect { (network, states) -> lock.withLock { onObservation(network, states) } }
        }
        wakeJob = scope.launch {
            wakeMonitor.wakes.collect { reason -> lock.withLock { onDeviceWoke(reason) } }
        }
    }

    /** Stops observing. Every timer goes with it. */
    fun stop() {
        job?.cancel()
        job = null
        wakeJob?.cancel()
        wakeJob = null
        cancelAllRetries()
        keepalives.toList().forEach(::disarmKeepalive)
        attempts.clear()
        boundNetwork.clear()
        lastRegisterAt.clear()
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

        // An account that vanished from the map was removed; its lease is not ours to
        // keep any more.
        keepalives.filterNot { it in states }.forEach(::disarmKeepalive)
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
            // The keepalives too: there is no lease to keep on a link that is gone, and
            // a REGISTER fired into no network is exactly the wake DoD 6 forbids.
            keepalives.toList().forEach(::disarmKeepalive)
            rebinder.setNetworkReachable(false)
            onNetworkLost()
        }
    }

    private suspend fun evaluate(
        id: AccountId,
        state: RegistrationState,
        network: NetworkStatus,
    ) {
        if (state is RegistrationState.Registered) {
            // A working registration resets the escalation. Doing this only on success is
            // what stops a link that connects and drops repeatedly from holding the client
            // at the shortest delay for ever.
            attempts.remove(id)

            // An account already registered the first time we look at it was registered
            // over the network we can see now - there is nothing else it could have been.
            // Recording rather than acting stops a coordinator start from re-registering
            // everything that was already healthy.
            network.networkId?.let { boundNetwork.putIfAbsent(id, it) }

            if (network is NetworkStatus.Available) armKeepalive(id, state.grantedExpirySeconds)
        } else {
            // The lease is not held, so there is nothing to keep. The retry chain owns a
            // failed account; a logged-out one is nobody's.
            disarmKeepalive(id)
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
        if (id in pendingRetries) return

        val attempt = (attempts[id] ?: 0) + 1
        attempts[id] = attempt
        logger.info(TAG, "Registrar unreachable for $id: retry $attempt in ${after.inWholeSeconds}s")
        retryTimes.update { it + (id to clock.nowEpochMillis() + after.inWholeMilliseconds) }

        pendingRetries += id
        timer.schedule(retryKey(id), after) {
            scope.launch { lock.withLock { onRetryDue(id, network, attempt) } }
        }
    }

    private suspend fun onRetryDue(id: AccountId, network: NetworkStatus, attempt: Int) {
        // Cancelled between firing and running: the network went, or the account did.
        if (id !in pendingRetries) return
        reRegister(id, network, reason = "retry $attempt")
    }

    /**
     * Arms the lease refresh for [id], at half of [expirySeconds].
     *
     * Half, not "just before": the platform timer may be inexact (see the alarm
     * implementation of [WakeTimer]), and an inexact alarm can land up to three quarters
     * of its delay late. Half the lease plus three quarters of half is still inside the
     * lease. Floored at [KEEPALIVE_FLOOR] so a registrar that grants a very short expiry
     * does not turn the handset into a REGISTER generator.
     *
     * Idempotent: an account already armed is left alone, because the timer re-arms
     * itself and a second arm here would only move it.
     */
    private fun armKeepalive(id: AccountId, expirySeconds: Int) {
        if (id in keepalives) return
        val interval = maxOf((expirySeconds / 2).seconds, KEEPALIVE_FLOOR)
        keepalives += id
        logger.debug(TAG, "Keepalive for $id every ${interval.inWholeSeconds}s")
        timer.schedule(keepaliveKey(id), interval) {
            scope.launch { lock.withLock { onKeepaliveDue(id) } }
        }
    }

    private fun disarmKeepalive(id: AccountId) {
        if (keepalives.remove(id)) timer.cancel(keepaliveKey(id))
    }

    private suspend fun onKeepaliveDue(id: AccountId) {
        // Fired, so the timer is spent whatever happens next.
        keepalives -= id

        val network = lastNetwork as? NetworkStatus.Available ?: return
        val state = registrar.registrationState.value[id] as? RegistrationState.Registered ?: return

        reRegister(id, network, reason = "keepalive")
        // Armed again from the state that was read, not from whatever the refresh
        // produces: a refresh that succeeds yields an equal `Registered`, and an equal
        // value is no emission, so nothing downstream would re-arm it.
        armKeepalive(id, state.grantedExpirySeconds)
    }

    /**
     * The device is back in use. Every account is checked now rather than on its timer.
     *
     * A pending retry is run immediately: the backoff was earned against a registrar that
     * may have been back for an hour, and a person holding the phone should not wait it
     * out. A healthy-looking registration is refreshed as well, because the lease it
     * describes may have lapsed while the device slept and nothing on the wire says so.
     * That refresh is what [WAKE_REFRESH_GAP] throttles — the retry is not throttled,
     * because it is already late.
     */
    private suspend fun onDeviceWoke(reason: WakeReason) {
        val network = lastNetwork as? NetworkStatus.Available ?: return
        val now = clock.nowEpochMillis()

        registrar.registrationState.value.forEach { (id, state) ->
            when (state) {
                is RegistrationState.Failed -> {
                    if (id !in pendingRetries) return@forEach
                    cancelRetry(id)
                    reRegister(id, network, reason = "device woke ($reason)")
                }

                is RegistrationState.Registered -> {
                    val since = now - (lastRegisterAt[id] ?: 0L)
                    if (since < WAKE_REFRESH_GAP.inWholeMilliseconds) return@forEach
                    reRegister(id, network, reason = "device woke ($reason)")
                }

                RegistrationState.Registering, RegistrationState.Unregistered -> Unit
            }
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
        pendingRetries -= id
        retryTimes.update { it - id }
        lastRegisterAt[id] = clock.nowEpochMillis()
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
        if (pendingRetries.remove(id)) timer.cancel(retryKey(id))
        retryTimes.update { it - id }
    }

    private fun cancelAllRetries() {
        pendingRetries.toList().forEach { timer.cancel(retryKey(it)) }
        pendingRetries.clear()
        retryTimes.value = emptyMap()
    }

    private fun retryKey(id: AccountId) = "retry/${id.value}"

    private fun keepaliveKey(id: AccountId) = "keepalive/${id.value}"

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

        /** The shortest keepalive interval, whatever the registrar grants. */
        val KEEPALIVE_FLOOR: Duration = 30.seconds

        /**
         * The least time between two wake-driven refreshes of one account.
         *
         * A screen turned on, unlocked and the app opened is three wake events inside a
         * second, and every one of them is a valid reason. One REGISTER covers all three.
         * Thirty seconds is short enough that a phone woken, looked at, and woken again a
         * minute later is refreshed both times.
         */
        val WAKE_REFRESH_GAP: Duration = 30.seconds
    }
}
