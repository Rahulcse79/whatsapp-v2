package com.whatsappv2.push

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationState

/** What to do about a push that just arrived. */
enum class PushDecision {
    /**
     * Hold the process up with the foreground service and send a REGISTER.
     *
     * Every `incoming_call` push ends here, whatever this process believes about its
     * registration. The server holds the call until it sees a REGISTER arrive *after* the
     * push (ADR-004): "Registered" in memory says the last REGISTER succeeded, not that
     * the socket behind it survived Doze or that the NAT still maps it — which is exactly
     * the state the push exists to repair. A REGISTER that was not needed costs one round
     * trip; one that was skipped costs the call.
     */
    WAKE,

    /** Older than a call could still be ringing. Waking for it would wake for nothing. */
    IGNORE_STALE,

    /** A type this client does not act on — a missed call, a waiting message. */
    IGNORE_UNSUPPORTED,

    /** Not a payload this app understands, so not one it will wake for. */
    IGNORE_MALFORMED,
}

/** How the REGISTER a [PushDecision.WAKE] promises is sent. */
enum class RegisterStep {
    /**
     * The push named an account this process is not currently registered on — the normal
     * case after process death or a failed refresh — so it is logged in again.
     */
    LOGIN,

    /**
     * The push named an account that is registered as far as this process knows. A refresh
     * re-sends the REGISTER on the account as it stands, without rebuilding it or flashing
     * the account through `Registering` on screen.
     */
    REFRESH,

    /**
     * The push named nothing this device holds — an `account_id` this app cannot map to a
     * stored account. Every account the user wants registered is logged in again, which
     * is what process start does, and the cost of getting it wrong (a missed call) is
     * higher than the cost of a spare REGISTER on a second account.
     */
    RESTORE_ALL,
}

/**
 * Whether a push is worth waking the device for, and how to register once awake (Task 38).
 *
 * Pure, which is the point: FCM cannot be driven from a JVM test, so every decision that
 * can be made without it is made here — staleness, type, and which REGISTER to send. What
 * is left in the service is "do the thing", and that is the part a device is genuinely
 * required to verify.
 */
object PushWakePolicy {

    /**
     * @param payload the parsed message, or null when it did not parse.
     * @param nowEpochMillis the current time, injected so staleness is testable.
     */
    fun decide(payload: PushPayload?, nowEpochMillis: Long): PushDecision = when {
        payload == null -> PushDecision.IGNORE_MALFORMED
        payload.type != PushType.INCOMING_CALL -> PushDecision.IGNORE_UNSUPPORTED
        isStale(payload, nowEpochMillis) -> PushDecision.IGNORE_STALE
        else -> PushDecision.WAKE
    }

    /**
     * @param target the account the push's `account_id` resolved to, or null when it
     *   resolved to nothing — see [PushAccountResolver].
     * @param registration the current state of that account, if any.
     */
    fun registerStep(target: AccountId?, registration: RegistrationState?): RegisterStep = when {
        target == null -> RegisterStep.RESTORE_ALL
        registration?.isUsable == true -> RegisterStep.REFRESH
        else -> RegisterStep.LOGIN
    }

    /**
     * True when the call this push announces can no longer be ringing.
     *
     * Measured against the ring timeout, as ADR-004 specifies. A push delayed past it is
     * announcing a call the caller has already given up on, and waking a Dozing device for
     * that is the cost push was adopted to avoid.
     *
     * A payload from the future is not stale — clocks disagree, and the safe reading of a
     * disagreement is to answer the call.
     */
    private fun isStale(payload: PushPayload, nowEpochMillis: Long): Boolean =
        nowEpochMillis - payload.sentAtEpochMillis > RING_TIMEOUT_MILLIS

    /**
     * How long a call rings before the caller gives up.
     *
     * Sixty seconds, which is the SIP default and what the FreeSWITCH target uses. Past
     * it, there is nothing left to answer.
     */
    const val RING_TIMEOUT_MILLIS = 60_000L
}
