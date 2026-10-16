package com.whatsappv2.domain.registration

import com.whatsappv2.domain.model.RegistrationState
import kotlin.random.Random
import kotlin.time.Duration

/** What the recovery loop should do about one account, right now. */
sealed interface RecoveryAction {

    /** Nothing to do: the account is healthy, or something is already in flight. */
    data object Idle : RecoveryAction

    /**
     * Re-register immediately.
     *
     * Used when the network underneath changed. There is no point waiting out a backoff
     * computed for a link that no longer exists, and the binding on the old one is
     * already dead.
     */
    data object RegisterNow : RecoveryAction

    /** Re-register after [delay], as [RegistrationBackoff] decided. */
    data class RetryAfter(val delay: Duration) : RecoveryAction

    /**
     * Do nothing until a network appears.
     *
     * Distinct from [Idle] on purpose. Both mean "not now", but this one is the rule DoD 6
     * asks to be proved — with no network the client stops entirely rather than burning
     * battery on attempts that cannot succeed — and a decision with its own name can be
     * asserted and logged as itself.
     */
    data object AwaitNetwork : RecoveryAction
}

/**
 * Whether, and when, to re-register an account as the network changes underneath it.
 *
 * Pure and stateless, like the two policies beside it: the caller owns the attempt count
 * and the timers, and this decides only what should happen. That is what makes airplane
 * mode, a Wi-Fi to cellular handover and a registrar outage testable without a device —
 * each is a different set of arguments to one function.
 *
 * ## The distinction the task is built around
 *
 * "No network" and "network but the registrar is down" look identical to the account —
 * both leave it unregistered — and they call for opposite behaviour:
 *
 * - **No network:** stop. Nothing will succeed, and each attempt wakes the radio.
 *   [RecoveryAction.AwaitNetwork]; the platform's callback is what restarts things.
 * - **Registrar down:** keep trying, but with [RegistrationBackoff] behind it, so 5,000
 *   clients do not knock the server over again the moment it comes back (§2.1).
 *
 * ## A changed network resets the reasoning, not the counter
 *
 * When the connection identity changes, a failed account is retried **immediately**
 * rather than after its backoff: the failures were caused by a link that is gone, and
 * making the user wait out a delay earned by a dead network is punishing them for the
 * platform's behaviour.
 *
 * The attempt counter is not reset, though. Only a successful registration does that, so
 * a network that connects and drops repeatedly still escalates instead of holding the
 * client in a fast loop. Flap protection proper is the caller's debounce — a status that
 * never settles never reaches this function at all.
 *
 * ## What it will not do
 *
 * - It never re-registers an account that is [RegistrationState.Unregistered]. That state
 *   means the user logged out (Task 29), and a network change is not permission to log
 *   them back in.
 * - It never retries a failure the user has to fix. A wrong password does not become
 *   right on cellular, and retrying it is exactly the loop DoD 6 forbids — the account
 *   waits for the edit that corrects it, which re-registers on its own.
 */
class RegistrationRecoveryPolicy(
    private val backoff: RegistrationBackoff = RegistrationBackoff(),
) {

    /**
     * @param network the network as it is now.
     * @param state the account's registration state as the engine last reported it.
     * @param boundNetworkId the [NetworkStatus.Available.networkId] the account last
     *   registered over, or `null` if it has never registered.
     * @param attempt consecutive failures for this account; feeds the backoff window.
     * @param random injected so a test is deterministic (see [RegistrationBackoff]).
     */
    fun decide(
        network: NetworkStatus,
        state: RegistrationState,
        boundNetworkId: Long?,
        attempt: Int,
        random: Random = Random.Default,
    ): RecoveryAction {
        if (network !is NetworkStatus.Available) return RecoveryAction.AwaitNetwork

        val movedNetwork = boundNetworkId != network.networkId

        return when (state) {
            // Logged out deliberately. Recovery is for connections that broke, not for
            // decisions the user made.
            RegistrationState.Unregistered -> RecoveryAction.Idle

            // A REGISTER is already on the wire. Sending a second would leave two
            // transactions competing over one binding; the transport rebind that
            // accompanies a network change already abandons the stale one.
            RegistrationState.Registering -> RecoveryAction.Idle

            is RegistrationState.Registered ->
                if (movedNetwork) RecoveryAction.RegisterNow else RecoveryAction.Idle

            is RegistrationState.Failed -> when {
                // Nothing about a new network makes a rejected password correct.
                state.reason.requiresUserAction -> RecoveryAction.Idle
                movedNetwork -> RecoveryAction.RegisterNow
                else -> RecoveryAction.RetryAfter(backoff.delayFor(attempt, random = random))
            }
        }
    }
}
