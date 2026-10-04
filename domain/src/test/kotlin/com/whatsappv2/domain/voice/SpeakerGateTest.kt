package com.whatsappv2.domain.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate's asymmetry, which is the whole design.
 *
 * Every case here is one that decides whether a call is usable, and none of them needs a
 * model or a microphone: the gate takes a score and returns a decision. The measured
 * behaviour that justifies the constants lives in `tools/speech-enhancement/gate_bench.py`;
 * these are the rules that behaviour depends on.
 */
class SpeakerGateTest {

    private val match = 0.6f // comfortably the user
    private val miss = 0.1f // comfortably somebody else

    @Test
    fun `it starts open, so the first word of a call is never cut`() {
        assertTrue(SpeakerGate().isOpen)
    }

    @Test
    fun `one matching window opens it`() {
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(miss) }
        assertFalse(gate.isOpen, "it should have closed")

        assertTrue(gate.onWindow(match), "a single matching window must reopen it")
    }

    @Test
    fun `closing takes the full run of misses, and the run must be consecutive`() {
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER - 1) {
            assertTrue(gate.onWindow(miss), "closed after only ${it + 1} miss(es)")
        }
        // One match in the middle resets the count entirely. Without this a user who
        // pauses briefly between words accumulates misses across the pauses and is cut
        // on a sentence they are in the middle of.
        gate.onWindow(match)
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER - 1) {
            assertTrue(gate.onWindow(miss), "the run was not reset by the matching window")
        }
        assertFalse(gate.onWindow(miss))
    }

    @Test
    fun `a window that cannot be scored counts as the user`() {
        // The embedder returning nothing - too short a window, silence, a failure - must
        // never be the reason somebody is muted. Every bias in this class points the
        // same way.
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(miss) }
        assertFalse(gate.isOpen)

        assertTrue(gate.onWindow(null), "an unscoreable window closed the gate")
    }

    @Test
    fun `a score exactly on the threshold is the user's`() {
        // The boundary belongs to the user, for the same reason as everything else here.
        val gate = SpeakerGate(threshold = 0.35f)
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(0.0f) }
        assertFalse(gate.isOpen)

        assertTrue(gate.onWindow(0.35f))
    }

    @Test
    fun `reset reopens it`() {
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER) { gate.onWindow(miss) }
        assertFalse(gate.isOpen)

        gate.reset()

        assertTrue(gate.isOpen, "a new call must not inherit the last one's decision")
    }

    @Test
    fun `it stays closed while the interferer keeps talking`() {
        val gate = SpeakerGate()
        repeat(SpeakerGate.DEFAULT_CLOSE_AFTER + 20) { gate.onWindow(miss) }
        assertFalse(gate.isOpen, "it reopened on its own")
    }

    @Test
    fun `the shipped delay is one second of windows`() {
        // The constants have to agree with each other: four hops of 250 ms is the one
        // second the measurement says never cuts the user.
        assertEquals(
            1_000,
            SpeakerGate.DEFAULT_CLOSE_AFTER * SpeakerGate.HOP_MILLIS,
            "the close delay is no longer the measured one second",
        )
        assertEquals(2_000, SpeakerGate.WINDOW_MILLIS, "2s is where the EER reaches zero")
    }

    @Test
    fun `a shorter close is allowed but must still be at least one window`() {
        assertTrue(SpeakerGate(closeAfter = 1).onWindow(miss).not())
        val refused = runCatching { SpeakerGate(closeAfter = 0) }
        assertTrue(refused.isFailure, "a gate that closes after no windows is not a gate")
    }
}
