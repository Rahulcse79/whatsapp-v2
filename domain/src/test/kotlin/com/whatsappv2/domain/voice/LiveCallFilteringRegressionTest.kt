package com.whatsappv2.domain.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two properties live call filtering shipped without, kept here so they cannot go again.
 *
 * Neither is a new rule. Both were promised by the original design and neither held on a
 * handset: the gate could not reopen once closed, and the microphone had two owners that
 * could undo each other. The first was a wiring bug — the gate attenuated the capture slot
 * its own analysis tap listened to, so closing it fed the embedder silence for ever — and
 * the second was latent, waiting for the gate to actually start working.
 *
 * The wiring itself needs pjmedia and cannot run here. What *can* be pinned on the JVM is
 * that the state machine supports recovery at all, and that the rule deciding the
 * microphone is a function of both reasons rather than a race between them.
 */
class LiveCallFilteringRegressionTest {

    // ---------------------------------------------------------------- the gate recovers

    @Test
    fun `a closed gate reopens on a single matching window`() {
        // The promise the shipped version could not keep. It closed, the tap then read the
        // zeroes the gate had written, every subsequent window scored silence, and the
        // score could never climb back over the threshold.
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(BELOW) }
        assertFalse(gate.isOpen, "it should be closed after the configured run of misses")

        assertTrue(gate.onWindow(ABOVE), "one matching window must be enough to reopen")
        assertTrue(gate.isOpen)
    }

    @Test
    fun `closing does not latch - the gate can close and reopen repeatedly`() {
        // A whole conversation: the user talks, somebody else does, the user again.
        val gate = SpeakerGate()
        repeat(3) {
            repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(BELOW) }
            assertFalse(gate.isOpen, "closed on round $it")
            gate.onWindow(ABOVE)
            assertTrue(gate.isOpen, "reopened on round $it")
        }
    }

    @Test
    fun `a window that cannot be scored reopens the gate rather than holding it shut`() {
        // The embedder failing, or a window too quiet to embed, must never be the thing
        // that keeps somebody muted.
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(BELOW) }
        assertFalse(gate.isOpen)

        assertTrue(gate.onWindow(null), "an unscoreable window fails open")
    }

    @Test
    fun `a near miss does not close the gate on its own`() {
        // One stray window below the threshold - a cough, a turn of the head - is not
        // somebody else talking.
        val gate = SpeakerGate()
        gate.onWindow(BELOW)
        assertTrue(gate.isOpen, "one miss is not a speaker change")
    }

    // ------------------------------------------------- mute and the gate cannot undo each other

    @Test
    fun `the microphone is sent only when nobody wants it silent`() {
        assertTrue(CaptureRouting.shouldTransmit(microphoneMuted = false, gateOpen = true))
        assertFalse(CaptureRouting.shouldTransmit(microphoneMuted = true, gateOpen = true))
        assertFalse(CaptureRouting.shouldTransmit(microphoneMuted = false, gateOpen = false))
        assertFalse(CaptureRouting.shouldTransmit(microphoneMuted = true, gateOpen = false))
    }

    @Test
    fun `the gate reopening does not un-mute a user who muted themselves`() {
        // The failure that would have arrived the moment the gate started working: the gate
        // owns the same connection mute does, and a reopen that wrote it directly would put
        // a deliberately muted microphone back on the call.
        val mutedAndGateReopens = CaptureRouting.shouldTransmit(microphoneMuted = true, gateOpen = true)
        assertFalse(mutedAndGateReopens, "mute is the user's decision and outranks the gate")
    }

    @Test
    fun `un-muting does not defeat a closed gate`() {
        val unmutedWhileSomebodyElseTalks =
            CaptureRouting.shouldTransmit(microphoneMuted = false, gateOpen = false)
        assertFalse(unmutedWhileSomebodyElseTalks, "the gate still holds a microphone nobody muted")
    }

    @Test
    fun `filtering switched off leaves the microphone exactly as mute left it`() {
        // Switching the feature off is modelled as the gate being permanently open, so the
        // only thing left deciding is the user's own mute.
        assertTrue(CaptureRouting.shouldTransmit(microphoneMuted = false, gateOpen = true))
        assertFalse(CaptureRouting.shouldTransmit(microphoneMuted = true, gateOpen = true))
    }

    private companion object {
        /** Comfortably under [SpeakerGate.DEFAULT_THRESHOLD] — somebody else. */
        const val BELOW = 0.10f

        /** Comfortably over it — the enrolled user. */
        const val ABOVE = 0.90f
    }
}
