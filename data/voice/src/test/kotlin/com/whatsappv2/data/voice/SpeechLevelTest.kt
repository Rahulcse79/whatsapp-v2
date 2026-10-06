package com.whatsappv2.data.voice

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The speech test has to survive one loud noise.
 *
 * Regression test for a defect measured on a handset on 2026-10-06. The reference level was
 * the loudest chunk seen so far in the recording and could only rise, so a single transient
 * at the start — picking the handset up, a door, a headset pairing tone — put the bar above
 * the user's ordinary speaking level and held it there. The same person on the same phone
 * counted 26 seconds of speech in 48 after a quiet start and 5 seconds in 180 after a loud
 * one, with the microphone open and working the whole time. Nothing on screen said why: the
 * counter simply stopped.
 */
class SpeechLevelTest {

    /** RMS of ordinary speech on a handset held at the ear, give or take. */
    private val speech = 2_500.0

    /** A clipping transient: a full-scale 16-bit sample. */
    private val bang = 32_000.0

    @Test
    fun `ordinary speech counts from a quiet start`() {
        var reference = 0.0
        repeat(20) { reference = SpeechLevel.nextReference(reference, speech) }
        assertTrue(SpeechLevel.isSpeech(speech, reference))
    }

    @Test
    fun `one bang does not silence the rest of the recording`() {
        var reference = SpeechLevel.nextReference(0.0, bang)

        // Immediately after, the bar is genuinely above ordinary speech. That part is fine
        // and is what the fraction is for; the bug was that it stayed that way for ever.
        assertFalse(
            SpeechLevel.isSpeech(speech, reference),
            "right after a full-scale transient the bar is above speech",
        )

        // Thirty seconds of someone talking normally. With the old all-time maximum this
        // reference never moved and every one of these chunks was discarded.
        repeat(SECONDS_30) { reference = SpeechLevel.nextReference(reference, speech) }

        assertTrue(
            SpeechLevel.isSpeech(speech, reference),
            "the reference decays back to the speaker, so speech counts again",
        )
    }

    @Test
    fun `the reference decays toward the speaker rather than collapsing to zero`() {
        var reference = SpeechLevel.nextReference(0.0, bang)
        repeat(SECONDS_30) { reference = SpeechLevel.nextReference(reference, speech) }

        // It settles at the speaker's own level, not below it: a chunk at a tenth of their
        // speaking level is still not speech, which is what keeps room tone out.
        assertTrue(reference >= speech, "the speaker's own level is the floor it decays to")
        assertFalse(SpeechLevel.isSpeech(speech / 10, reference), "room tone still does not count")
    }

    @Test
    fun `a silent room cannot enrol itself whatever the reference says`() {
        // The absolute floor does not decay, so even a reference that has fallen all the way
        // to the room's own hiss cannot promote that hiss to speech.
        val hiss = SpeechLevel.ABSOLUTE_FLOOR / 2
        var reference = 0.0
        repeat(SECONDS_30) { reference = SpeechLevel.nextReference(reference, hiss) }
        assertFalse(SpeechLevel.isSpeech(hiss, reference))
    }

    private companion object {
        /** Chunks in thirty seconds, at a tenth of a second each. */
        const val SECONDS_30 = 300
    }
}
