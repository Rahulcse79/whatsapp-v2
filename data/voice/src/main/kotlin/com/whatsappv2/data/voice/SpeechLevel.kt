package com.whatsappv2.data.voice

/**
 * Decides which recorded chunks carry speech, by level alone (ADR-013).
 *
 * Pulled out of [VoiceEnroller] so it can be tested without a microphone. The rule is two
 * lines of arithmetic and it has been wrong twice, both times in a way that is invisible
 * from the outside: the counter on screen simply stops moving while the microphone is open
 * and working, and the user is left to guess.
 *
 * ## Relative to a reference that can fall again
 *
 * The bar is a fraction of how loud this person has recently been, because a fixed one is
 * wrong on every microphone gain. The reference used to be the loudest chunk ever seen in
 * the recording, which is the bug: it can only rise, so one transient — the handset being
 * picked up, a door, a headset's pairing tone — pins the bar above ordinary speech for the
 * rest of the session. Measured on one handset: 26 seconds of speech counted in 48 after a
 * quiet start, 5 in 180 after a loud one.
 *
 * Decaying it fixes that without losing what it was for. [ABSOLUTE_FLOOR] is still what
 * stops a silent room enrolling a profile out of its own hiss, and it does not decay.
 */
internal object SpeechLevel {

    /**
     * The reference level for the next chunk, given [level] of this one.
     *
     * Rises instantly to anything louder and falls slowly otherwise, so it tracks how
     * loudly somebody is speaking rather than the loudest thing that ever happened.
     */
    fun nextReference(reference: Double, level: Double): Double =
        maxOf(level, reference * DECAY)

    /** Whether a chunk at [level] is speech, against a [reference] from [nextReference]. */
    fun isSpeech(level: Double, reference: Double): Boolean =
        level > reference * FRACTION && level > ABSOLUTE_FLOOR

    /**
     * How much of the reference a chunk must reach to count as speech.
     *
     * Low, because the cost of the two mistakes is not equal: counting a little room tone
     * dilutes the embedding slightly, while refusing real speech stops enrolment finishing
     * at all and tells the user nothing about why.
     */
    const val FRACTION = 0.12

    /**
     * What the reference is multiplied by per chunk when nothing louder arrives.
     *
     * A chunk is a tenth of a second, so this is 0.951 per second and the reference halves
     * in about fourteen. Long enough to track a speaker rather than follow each syllable
     * down; short enough that one bang stops mattering within a sentence or two.
     */
    const val DECAY = 0.995

    /** Below this nothing is speech, whatever the reference says. Room tone sits here. */
    const val ABSOLUTE_FLOOR = 150.0
}
