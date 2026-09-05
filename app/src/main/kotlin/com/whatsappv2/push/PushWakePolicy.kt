package com.whatsappv2.push

import com.whatsappv2.domain.model.RegistrationState

/** What to do about a push that just arrived. */
enum class PushDecision {
    /**
     * Bring the registration back and hold the process up for the INVITE.
     *
     * The normal case after Doze or process death: the binding the server has is stale or
     * gone, so the INVITE has nowhere to land until this client re-registers (ADR-004).
     */
    WAKE_AND_REGISTER,

    /**
     * Already registered. Start the foreground service and let the INVITE arrive.
     *
     * Re-registering here would cost a round trip and a battery wake for a binding that is
     * already good, and would race the INVITE that is already on its way.
     */
    WAKE_ONLY,

    /** Older than a call could still be ringing. Waking for it would wake for nothing. */
    IGNORE_STALE,

    /** A type this client does not act on — a missed call, a waiting message. */
    IGNORE_UNSUPPORTED,

    /** Not a payload this app understands, so not one it will wake for. */
    IGNORE_MALFORMED,
}

/**
 * Whether a push is worth waking the device for (Task 38).
 *
 * Pure, which is the point: FCM cannot be driven from a JVM test, so every decision that
 * can be made without it is made here — staleness, type, and whether the registration
 * still needs restoring. What is left in the service is "do the thing", and that is the
 * part a device is genuinely required to verify.
 */
object PushWakePolicy {

    /**
     * @param payload the parsed message, or null when it did not parse.
     * @param registration the current state of the account the push names.
     * @param nowEpochMillis the current time, injected so staleness is testable.
     */
    fun decide(
        payload: PushPayload?,
        registration: RegistrationState?,
        nowEpochMillis: Long,
    ): PushDecision = when {
        payload == null -> PushDecision.IGNORE_MALFORMED
        payload.type != PushType.INCOMING_CALL -> PushDecision.IGNORE_UNSUPPORTED
        isStale(payload, nowEpochMillis) -> PushDecision.IGNORE_STALE
        registration?.isUsable == true -> PushDecision.WAKE_ONLY
        else -> PushDecision.WAKE_AND_REGISTER
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
