package com.whatsappv2.feature.calls

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the call screen may offer, per phase (Task 39, second done-when).
 *
 * The done-when says hold must be unavailable before `Connected` and that the *state* must
 * be what enforces it. This is that enforcement, asserted without a screen.
 */
class CallUiStateTest {

    @Test
    fun `every FSM state maps to a phase, with none left over`() {
        // Task 39's first done-when starts here: a state with no phase is a state the
        // screen cannot render, and the compiler cannot catch it from inside a composable.
        val states = listOf(
            CallState.Idle to CallPhase.ENDED,
            CallState.Outgoing.Calling to CallPhase.CALLING,
            CallState.Outgoing.Ringing to CallPhase.RINGING,
            CallState.Outgoing.EarlyMedia to CallPhase.EARLY_MEDIA,
            CallState.Incoming(REMOTE) to CallPhase.INCOMING,
            CallState.Connected() to CallPhase.CONNECTED,
            CallState.Held(HoldParty.LOCAL) to CallPhase.ON_HOLD,
            CallState.Held(HoldParty.REMOTE) to CallPhase.HELD_BY_REMOTE,
            CallState.Held(HoldParty.BOTH) to CallPhase.HELD_BY_BOTH,
            CallState.Resuming() to CallPhase.RESUMING,
            CallState.Transferring(TransferType.BLIND) to CallPhase.TRANSFERRING,
            CallState.Terminated(HangupReason.REMOTE_HANGUP) to CallPhase.ENDED,
        )

        for ((state, phase) in states) {
            assertEquals(phase, CallPhase.of(state), "for $state")
        }
    }

    @Test
    fun `hold is unavailable before the call connects`() {
        for (phase in listOf(CallPhase.CALLING, CallPhase.RINGING, CallPhase.EARLY_MEDIA, CallPhase.INCOMING)) {
            val availability = CallControlAvailability.of(phase)

            assertFalse(availability.canHold, "$phase has no dialog to re-INVITE")
            assertFalse(availability.canResume, "$phase cannot resume what was never held")
        }

        assertTrue(CallControlAvailability.of(CallPhase.CONNECTED).canHold)
    }

    @Test
    fun `mute and routing need media, which a ringing call does not have`() {
        // Muting a call that is still ringing mutes nothing, and reporting success for it
        // would hide the moment the real microphone was never muted.
        assertFalse(CallControlAvailability.of(CallPhase.RINGING).canMute)
        assertFalse(CallControlAvailability.of(CallPhase.INCOMING).canChangeRoute)
        assertTrue(CallControlAvailability.of(CallPhase.CONNECTED).canMute)
        assertTrue(CallControlAvailability.of(CallPhase.ON_HOLD).canMute)
    }

    @Test
    fun `an unanswered inbound call is rejected, not hung up`() {
        // They send different responses - 603 versus a BYE for a dialog that never
        // existed - so the screen must not offer the wrong one.
        val incoming = CallControlAvailability.of(CallPhase.INCOMING)

        assertTrue(incoming.canAnswer)
        assertTrue(incoming.canReject)
        assertFalse(incoming.canHangUp)
    }

    @Test
    fun `a resume is offered only where a local hold can be lifted`() {
        assertTrue(CallControlAvailability.of(CallPhase.ON_HOLD).canResume)
        assertTrue(CallControlAvailability.of(CallPhase.HELD_BY_BOTH).canResume)
        // The far end is holding us; resuming locally would change nothing.
        assertFalse(CallControlAvailability.of(CallPhase.HELD_BY_REMOTE).canResume)
    }

    @Test
    fun `the keypad is offered on a connected call and nowhere else`() {
        // RFC 4733 digits ride the RTP stream, and a held call's stream is paused, so a
        // keypad offered there would send tones into a media path that is not running.
        assertTrue(CallControlAvailability.of(CallPhase.CONNECTED).canSendDtmf)

        for (phase in CallPhase.entries.filterNot { it == CallPhase.CONNECTED }) {
            assertFalse(
                CallControlAvailability.of(phase).canSendDtmf,
                "$phase has no running media path to carry a tone",
            )
        }
    }

    @Test
    fun `an ended call offers nothing at all`() {
        val ended = CallControlAvailability.of(CallPhase.ENDED)

        assertFalse(ended.canHangUp)
        assertFalse(ended.canMute)
        assertFalse(ended.canAnswer)
    }

    @Test
    fun `the controls come from the state, and default before media exists`() {
        val connected = CallState.Connected(CallControls(isMuted = true))

        assertEquals(true, connected.controlsOrNull?.isMuted)
        assertEquals(null, CallState.Outgoing.Ringing.controlsOrNull)
    }

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
    }
}
