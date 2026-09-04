package com.whatsappv2.domain.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class CallStateMachineTest {

    private val alice = requireNotNull(SipUri.parse("sip:alice@example.com").getOrNull())

    private val busyControls = CallControls(
        isMuted = true,
        audioRoute = AudioRoute.BLUETOOTH,
        isVideoEnabled = true,
        isRecording = true,
    )

    private fun move(state: CallState, event: CallEvent): CallState =
        when (val result = CallStateMachine.transition(state, event)) {
            is TransitionResult.Moved -> result.state
            is TransitionResult.Rejected -> fail("expected $event to be legal in $state")
        }

    private fun assertRejected(state: CallState, event: CallEvent) {
        val result = CallStateMachine.transition(state, event)
        assertIs<TransitionResult.Rejected>(result, "expected $event to be rejected in $state, got $result")
    }

    // ================================================================ the table
    //
    // Every legal (state, event) pair, with the state it must produce. The exhaustive
    // test below asserts that EVERY pair absent from this table is rejected, so adding
    // a transition to the machine without adding it here fails the build.

    private data class Legal(val from: CallState, val event: CallEvent, val to: CallState)

    private fun legalTransitions(): List<Legal> = listOf(
        // --- placing and receiving
        Legal(CallState.Idle, CallEvent.Dial(alice), CallState.Outgoing.Calling),
        Legal(CallState.Idle, CallEvent.IncomingInvite(alice), CallState.Incoming(alice)),

        // --- outgoing progress; each provisional state accepts the others because a
        //     network may send 180 after 183, or repeat either.
        Legal(CallState.Outgoing.Calling, CallEvent.RemoteRinging, CallState.Outgoing.Ringing),
        Legal(CallState.Outgoing.Calling, CallEvent.RemoteEarlyMedia, CallState.Outgoing.EarlyMedia),
        Legal(CallState.Outgoing.Calling, CallEvent.RemoteAnswered, CallState.Connected()),
        Legal(CallState.Outgoing.Ringing, CallEvent.RemoteRinging, CallState.Outgoing.Ringing),
        Legal(CallState.Outgoing.Ringing, CallEvent.RemoteEarlyMedia, CallState.Outgoing.EarlyMedia),
        Legal(CallState.Outgoing.Ringing, CallEvent.RemoteAnswered, CallState.Connected()),
        Legal(CallState.Outgoing.EarlyMedia, CallEvent.RemoteRinging, CallState.Outgoing.Ringing),
        Legal(CallState.Outgoing.EarlyMedia, CallEvent.RemoteEarlyMedia, CallState.Outgoing.EarlyMedia),
        Legal(CallState.Outgoing.EarlyMedia, CallEvent.RemoteAnswered, CallState.Connected()),

        // --- answering
        Legal(CallState.Incoming(alice), CallEvent.LocalAnswered(), CallState.Connected()),

        // --- hold
        Legal(CallState.Connected(), CallEvent.LocalHold, CallState.Held(HoldParty.LOCAL)),
        Legal(CallState.Connected(), CallEvent.RemoteHold, CallState.Held(HoldParty.REMOTE)),
        Legal(CallState.Held(HoldParty.LOCAL), CallEvent.RemoteHold, CallState.Held(HoldParty.BOTH)),
        Legal(CallState.Held(HoldParty.REMOTE), CallEvent.LocalHold, CallState.Held(HoldParty.BOTH)),
        Legal(CallState.Held(HoldParty.LOCAL), CallEvent.LocalResume, CallState.Resuming()),
        Legal(CallState.Held(HoldParty.REMOTE), CallEvent.RemoteResume, CallState.Connected()),
        Legal(CallState.Held(HoldParty.BOTH), CallEvent.LocalResume, CallState.Held(HoldParty.REMOTE)),
        Legal(CallState.Held(HoldParty.BOTH), CallEvent.RemoteResume, CallState.Held(HoldParty.LOCAL)),
        Legal(CallState.Resuming(), CallEvent.ResumeConfirmed, CallState.Connected()),
        Legal(CallState.Resuming(), CallEvent.RemoteHold, CallState.Held(HoldParty.REMOTE)),

        // --- transfer
        Legal(
            CallState.Connected(),
            CallEvent.StartTransfer(TransferType.BLIND),
            CallState.Transferring(TransferType.BLIND),
        ),
        Legal(
            CallState.Transferring(TransferType.BLIND),
            CallEvent.TransferSucceeded,
            CallState.Terminated(HangupReason.LOCAL_HANGUP),
        ),
        Legal(
            CallState.Transferring(TransferType.BLIND),
            CallEvent.TransferFailed,
            CallState.Connected(),
        ),
    ) + terminationTransitions() + controlTransitions()

    /** Terminate is legal from every active state. */
    private fun terminationTransitions(): List<Legal> =
        representativeStates().filter { it.isActive }.map { state ->
            Legal(
                state,
                CallEvent.Terminate(HangupReason.LOCAL_HANGUP),
                CallState.Terminated(HangupReason.LOCAL_HANGUP),
            )
        }

    /** Control events are legal in every established state and change only controls. */
    private fun controlTransitions(): List<Legal> =
        representativeStates().filter { it.isEstablished }.flatMap { state ->
            controlEvents().map { event ->
                Legal(state, event, state.withControls(expectedControls(state, event)))
            }
        }

    private fun expectedControls(state: CallState, event: CallEvent): CallControls {
        val current = requireNotNull(state.controlsOrNull)
        return when (event) {
            is CallEvent.SetMuted -> current.copy(isMuted = event.muted)
            is CallEvent.SetAudioRoute -> current.copy(audioRoute = event.route)
            is CallEvent.SetVideoEnabled -> current.copy(isVideoEnabled = event.enabled)
            is CallEvent.SetRecording -> current.copy(isRecording = event.recording)
            else -> error("not a control event: $event")
        }
    }

    private fun CallState.withControls(controls: CallControls): CallState = when (this) {
        is CallState.Connected -> copy(controls = controls)
        is CallState.Held -> copy(controls = controls)
        is CallState.Resuming -> copy(controls = controls)
        is CallState.Transferring -> copy(controls = controls)
        else -> error("$this has no controls")
    }

    // ================================================================ the universe

    private fun representativeStates(): List<CallState> = listOf(
        CallState.Idle,
        CallState.Outgoing.Calling,
        CallState.Outgoing.Ringing,
        CallState.Outgoing.EarlyMedia,
        CallState.Incoming(alice),
        CallState.Connected(),
        CallState.Held(HoldParty.LOCAL),
        CallState.Held(HoldParty.REMOTE),
        CallState.Held(HoldParty.BOTH),
        CallState.Resuming(),
        CallState.Transferring(TransferType.BLIND),
        CallState.Terminated(HangupReason.REMOTE_HANGUP),
    )

    private fun controlEvents(): List<CallEvent> = listOf(
        CallEvent.SetMuted(true),
        CallEvent.SetAudioRoute(AudioRoute.SPEAKER),
        CallEvent.SetVideoEnabled(true),
        CallEvent.SetRecording(true),
    )

    private fun representativeEvents(): List<CallEvent> = listOf(
        CallEvent.Dial(alice),
        CallEvent.RemoteRinging,
        CallEvent.RemoteEarlyMedia,
        CallEvent.RemoteAnswered,
        CallEvent.IncomingInvite(alice),
        CallEvent.LocalAnswered(),
        CallEvent.LocalHold,
        CallEvent.RemoteHold,
        CallEvent.LocalResume,
        CallEvent.ResumeConfirmed,
        CallEvent.RemoteResume,
        CallEvent.StartTransfer(TransferType.BLIND),
        CallEvent.TransferSucceeded,
        CallEvent.TransferFailed,
        CallEvent.Terminate(HangupReason.LOCAL_HANGUP),
    ) + controlEvents()

    // ================================================================ tests

    @Test
    fun `every legal transition produces the expected state`() {
        for ((from, event, to) in legalTransitions()) {
            assertEquals(to, move(from, event), "transition $from --$event-->")
        }
    }

    @Test
    fun `every pair absent from the table is rejected`() {
        val legal = legalTransitions().map { it.from to it.event }.toSet()
        var checked = 0

        for (state in representativeStates()) {
            for (event in representativeEvents()) {
                if (state to event in legal) continue
                assertRejected(state, event)
                checked++
            }
        }

        // Sanity: the exhaustive sweep must actually be sweeping something. Without
        // this, a bug that emptied the universe would make the test vacuously pass.
        assertTrue(checked > 100, "expected a large rejection sweep, only checked $checked")
    }

    @Test
    fun `Terminated is absorbing`() {
        val terminated = CallState.Terminated(HangupReason.REMOTE_HANGUP)
        for (event in representativeEvents()) {
            assertRejected(terminated, event)
        }
    }

    @Test
    fun `Idle accepts nothing but dialling and an inbound invite`() {
        for (event in representativeEvents()) {
            if (event is CallEvent.Dial || event is CallEvent.IncomingInvite) continue
            assertRejected(CallState.Idle, event)
        }
    }

    // ---------------------------------------------------------------- hold semantics

    @Test
    fun `resuming while the remote side still holds does not connect`() {
        // The bug this guards: treating hold as one boolean, so the local resume
        // reports the call live while the far end is still holding and no media flows.
        val both = CallState.Held(HoldParty.BOTH)
        assertEquals(CallState.Held(HoldParty.REMOTE), move(both, CallEvent.LocalResume))
    }

    @Test
    fun `a full hold and resume cycle returns to connected`() {
        var state: CallState = CallState.Connected()
        state = move(state, CallEvent.LocalHold)
        assertEquals(CallState.Held(HoldParty.LOCAL), state)
        state = move(state, CallEvent.LocalResume)
        assertEquals(CallState.Resuming(), state)
        state = move(state, CallEvent.ResumeConfirmed)
        assertEquals(CallState.Connected(), state)
    }

    @Test
    fun `holding from a side that already holds is rejected`() {
        assertRejected(CallState.Held(HoldParty.LOCAL), CallEvent.LocalHold)
        assertRejected(CallState.Held(HoldParty.REMOTE), CallEvent.RemoteHold)
        assertRejected(CallState.Held(HoldParty.BOTH), CallEvent.LocalHold)
        assertRejected(CallState.Held(HoldParty.BOTH), CallEvent.RemoteHold)
    }

    @Test
    fun `resuming from a side that is not holding is rejected`() {
        // Found by the exhaustive sweep: these were accepted as silent no-ops, which
        // is indistinguishable at the call site from a resume that actually worked.
        assertRejected(CallState.Held(HoldParty.REMOTE), CallEvent.LocalResume)
        assertRejected(CallState.Held(HoldParty.LOCAL), CallEvent.RemoteResume)
    }

    @Test
    fun `hold party arithmetic is total`() {
        assertEquals(HoldParty.BOTH, HoldParty.LOCAL.withRemote())
        assertEquals(HoldParty.BOTH, HoldParty.REMOTE.withLocal())
        assertEquals(HoldParty.BOTH, HoldParty.BOTH.withLocal())
        assertEquals(HoldParty.BOTH, HoldParty.BOTH.withRemote())
        assertEquals(HoldParty.LOCAL, HoldParty.LOCAL.withLocal())
        assertEquals(HoldParty.REMOTE, HoldParty.REMOTE.withRemote())

        assertEquals(null, HoldParty.LOCAL.withoutLocal())
        assertEquals(null, HoldParty.REMOTE.withoutRemote())
        assertEquals(HoldParty.REMOTE, HoldParty.REMOTE.withoutLocal())
        assertEquals(HoldParty.LOCAL, HoldParty.LOCAL.withoutRemote())
        assertEquals(HoldParty.REMOTE, HoldParty.BOTH.withoutLocal())
        assertEquals(HoldParty.LOCAL, HoldParty.BOTH.withoutRemote())
    }

    // ---------------------------------------------------------------- controls

    @Test
    fun `controls survive a hold and resume cycle`() {
        // Losing mute or the audio route on resume is a real and very visible bug.
        var state: CallState = CallState.Connected(busyControls)
        state = move(state, CallEvent.LocalHold)
        state = move(state, CallEvent.LocalResume)
        state = move(state, CallEvent.ResumeConfirmed)
        assertEquals(busyControls, state.controlsOrNull)
    }

    @Test
    fun `controls survive a failed transfer`() {
        val transferring = CallState.Transferring(TransferType.ATTENDED, busyControls)
        assertEquals(busyControls, move(transferring, CallEvent.TransferFailed).controlsOrNull)
    }

    @Test
    fun `controls are rejected before media exists`() {
        for (state in listOf(CallState.Idle, CallState.Outgoing.Calling, CallState.Incoming(alice))) {
            for (event in controlEvents()) {
                assertRejected(state, event)
            }
        }
    }

    @Test
    fun `answering carries the chosen controls through`() {
        val state = move(CallState.Incoming(alice), CallEvent.LocalAnswered(busyControls))
        assertEquals(busyControls, state.controlsOrNull)
    }

    // ---------------------------------------------------------------- predicates

    @Test
    fun `isActive excludes only Idle and Terminated`() {
        for (state in representativeStates()) {
            val expected = state !is CallState.Idle && state !is CallState.Terminated
            assertEquals(expected, state.isActive, "isActive of $state")
        }
    }

    @Test
    fun `isEstablished matches exactly the states that carry controls`() {
        for (state in representativeStates()) {
            assertEquals(state.isEstablished, state.controlsOrNull != null, "isEstablished of $state")
        }
    }

    @Test
    fun `the machine starts idle`() {
        assertEquals(CallState.Idle, CallStateMachine.initialState)
    }

    @Test
    fun `termination records the reason it was given`() {
        for (reason in HangupReason.entries) {
            assertEquals(
                CallState.Terminated(reason),
                move(CallState.Connected(), CallEvent.Terminate(reason)),
            )
        }
    }
}
