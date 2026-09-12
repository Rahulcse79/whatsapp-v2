package com.whatsappv2.feature.calls

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
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
    fun `mute needs media, which a ringing call does not have`() {
        // Muting a call that is still ringing mutes nothing, and reporting success for it
        // would hide the moment the real microphone was never muted.
        assertFalse(CallControlAvailability.of(CallPhase.RINGING).canMute)
        assertFalse(CallControlAvailability.of(CallPhase.INCOMING).canMute)
        assertTrue(CallControlAvailability.of(CallPhase.CONNECTED).canMute)
        assertTrue(CallControlAvailability.of(CallPhase.ON_HOLD).canMute)
    }

    @Test
    fun `the route can be chosen from the first ring, because the ringback is already routed`() {
        // Measured on a TC15, 2026-09-10: with this disabled, a Speaker press while the far
        // end rang went nowhere, and the answered call came up on the earpiece.
        for (phase in listOf(CallPhase.CALLING, CallPhase.RINGING, CallPhase.EARLY_MEDIA, CallPhase.INCOMING)) {
            assertTrue(CallControlAvailability.of(phase).canChangeRoute, "$phase")
        }
        assertFalse(CallControlAvailability.of(CallPhase.ENDED).canChangeRoute)
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

    @Test
    fun `a route chosen while ringing shows on the button before the call connects`() {
        // The platform is already honouring it, so a button that still read "turn on
        // speakerphone" would be offering to do what was just done.
        val ringing = snapshot(CallState.Outgoing.Ringing, requestedAudioRoute = AudioRoute.SPEAKER)

        assertEquals(AudioRoute.SPEAKER, ringing.toDisplay(nowEpochMillis = 0L).controls.audioRoute)
        assertEquals(
            CallControls.DEFAULT.audioRoute,
            snapshot(CallState.Outgoing.Ringing).toDisplay(nowEpochMillis = 0L).controls.audioRoute,
        )
    }

    private fun snapshot(state: CallState, requestedAudioRoute: AudioRoute? = null) = CallSnapshot(
        callId = CallId("call-1"),
        accountId = AccountId("acct-1"),
        remote = REMOTE,
        remoteDisplayName = null,
        direction = CallDirection.OUTGOING,
        state = state,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 0L,
        connectedAtEpochMillis = null,
        requestedAudioRoute = requestedAudioRoute,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
    }
}
