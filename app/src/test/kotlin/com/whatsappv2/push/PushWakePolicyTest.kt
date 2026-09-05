package com.whatsappv2.push

import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The push wake path's decisions (Task 38, ADR-004).
 *
 * FCM cannot be driven from a JVM test, so everything decidable without it is decided in
 * [PushWakePolicy] and asserted here. What is left on a device is "start a service and
 * register", which is the part a device is genuinely needed for.
 */
class PushWakePolicyTest {

    @Test
    fun `the ADR-004 payload parses exactly as documented`() {
        val payload = PushPayload.from(
            mapOf(
                "call_id" to "abc123",
                "account_id" to "acct-1",
                "sent_at" to "$NOW",
                "type" to "incoming_call",
            ),
        )

        assertEquals(
            PushPayload("abc123", "acct-1", NOW, PushType.INCOMING_CALL),
            payload,
        )
    }

    @Test
    fun `a payload missing any field is not a payload`() {
        // Strict on purpose: waking the device and re-registering for something this app
        // cannot interpret is exactly the battery cost push exists to avoid.
        val complete = mapOf(
            "call_id" to "abc123",
            "account_id" to "acct-1",
            "sent_at" to "$NOW",
            "type" to "incoming_call",
        )

        for (missing in complete.keys) {
            assertNull(PushPayload.from(complete - missing), "$missing must be required")
        }
    }

    @Test
    fun `the payload never prints the call id or the account it names`() {
        // §7, DoD 12: a Call-ID correlates a call and an account id names an identity.
        val rendered = PushPayload("abc123", "acct-1", NOW, PushType.INCOMING_CALL).toString()

        assertEquals(false, rendered.contains("abc123"))
        assertEquals(false, rendered.contains("acct-1"))
    }

    @Test
    fun `an unregistered account is woken and re-registered`() {
        // The normal case after Doze or process death: the binding is gone, so the INVITE
        // has nowhere to land until this client registers again.
        val decision = PushWakePolicy.decide(payload(), RegistrationState.Unregistered, NOW)

        assertEquals(PushDecision.WAKE_AND_REGISTER, decision)
    }

    @Test
    fun `an account that has never been seen is woken and registered`() {
        assertEquals(PushDecision.WAKE_AND_REGISTER, PushWakePolicy.decide(payload(), null, NOW))
    }

    @Test
    fun `a registered account is woken but not re-registered`() {
        // Re-registering would cost a round trip for a binding that is already good, and
        // would race the INVITE already on its way.
        val decision = PushWakePolicy.decide(payload(), RegistrationState.Registered(600), NOW)

        assertEquals(PushDecision.WAKE_ONLY, decision)
    }

    @Test
    fun `a failed registration is treated as one to restore`() {
        val failed = RegistrationState.Failed(RegistrationFailure.NETWORK_UNAVAILABLE, retryScheduled = true)

        assertEquals(PushDecision.WAKE_AND_REGISTER, PushWakePolicy.decide(payload(), failed, NOW))
    }

    @Test
    fun `a push older than the ring timeout is dropped`() {
        val late = NOW + PushWakePolicy.RING_TIMEOUT_MILLIS + 1

        assertEquals(PushDecision.IGNORE_STALE, PushWakePolicy.decide(payload(), null, late))
    }

    @Test
    fun `a push from a clock that is slightly ahead is still answered`() {
        // Clocks disagree, and the safe reading of a disagreement is to answer the call.
        val early = NOW - 5_000

        assertEquals(PushDecision.WAKE_AND_REGISTER, PushWakePolicy.decide(payload(), null, early))
    }

    @Test
    fun `an extensible type this version does not act on is ignored quietly`() {
        val decision = PushWakePolicy.decide(payload(PushType.MESSAGE_WAITING), null, NOW)

        assertEquals(PushDecision.IGNORE_UNSUPPORTED, decision)
    }

    @Test
    fun `something that is not our payload at all is ignored`() {
        assertEquals(PushDecision.IGNORE_MALFORMED, PushWakePolicy.decide(null, null, NOW))
    }

    private fun payload(type: PushType = PushType.INCOMING_CALL) =
        PushPayload("abc123", "acct-1", NOW, type)

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
