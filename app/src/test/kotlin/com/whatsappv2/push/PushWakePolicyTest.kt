package com.whatsappv2.push

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The push wake path's decisions (Task 38, ADR-004).
 *
 * FCM cannot be driven from a JVM test, so everything decidable without it is decided in
 * [PushWakePolicy] and asserted here: whether to wake, and which REGISTER to send once
 * awake. What is left on a device is "start a service and register", which is the part a
 * device is genuinely needed for.
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
    fun `an incoming call wakes the device`() {
        assertEquals(PushDecision.WAKE, PushWakePolicy.decide(payload(), NOW))
    }

    @Test
    fun `an unregistered account is logged in again`() {
        // The normal case after Doze or process death: the binding is gone, so the INVITE
        // has nowhere to land until this client registers again.
        val step = PushWakePolicy.registerStep(ACCOUNT, RegistrationState.Unregistered)

        assertEquals(RegisterStep.LOGIN, step)
    }

    @Test
    fun `an account this process has no state for is logged in`() {
        assertEquals(RegisterStep.LOGIN, PushWakePolicy.registerStep(ACCOUNT, null))
    }

    @Test
    fun `a registered account still sends a REGISTER - as a refresh`() {
        // "Registered" says the last REGISTER succeeded, not that the socket behind it
        // survived Doze. The gateway holds the call until it sees a REGISTER arrive after
        // the push, so staying quiet here would leave the caller on ringback until it gave
        // up. A refresh re-sends on the account as it stands, without rebuilding it.
        val step = PushWakePolicy.registerStep(ACCOUNT, RegistrationState.Registered(600))

        assertEquals(RegisterStep.REFRESH, step)
    }

    @Test
    fun `a failed registration is treated as one to restore`() {
        val failed = RegistrationState.Failed(RegistrationFailure.NETWORK_UNAVAILABLE, retryScheduled = true)

        assertEquals(RegisterStep.LOGIN, PushWakePolicy.registerStep(ACCOUNT, failed))
    }

    @Test
    fun `an account id this device cannot place re-registers everything it wants registered`() {
        // The gateway names the SIP identity it saw register; if that maps to nothing
        // stored here, the safe answer is what process start does: log every wanted
        // account in again. A spare REGISTER is cheaper than a missed call.
        assertEquals(RegisterStep.RESTORE_ALL, PushWakePolicy.registerStep(null, null))
    }

    @Test
    fun `a push older than the ring timeout is dropped`() {
        val late = NOW + PushWakePolicy.RING_TIMEOUT_MILLIS + 1

        assertEquals(PushDecision.IGNORE_STALE, PushWakePolicy.decide(payload(), late))
    }

    @Test
    fun `a push from a clock that is slightly ahead is still answered`() {
        // Clocks disagree, and the safe reading of a disagreement is to answer the call.
        val early = NOW - 5_000

        assertEquals(PushDecision.WAKE, PushWakePolicy.decide(payload(), early))
    }

    @Test
    fun `an extensible type this version does not act on is ignored quietly`() {
        val decision = PushWakePolicy.decide(payload(PushType.MESSAGE_WAITING), NOW)

        assertEquals(PushDecision.IGNORE_UNSUPPORTED, decision)
    }

    @Test
    fun `something that is not our payload at all is ignored`() {
        assertEquals(PushDecision.IGNORE_MALFORMED, PushWakePolicy.decide(null, NOW))
    }

    private fun payload(type: PushType = PushType.INCOMING_CALL) =
        PushPayload("abc123", "acct-1", NOW, type)

    private companion object {
        const val NOW = 1_700_000_000_000L
        val ACCOUNT = AccountId("acct-1")
    }
}
