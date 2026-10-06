package com.whatsappv2.domain.voice

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profile, and the two ways it can be silently useless.
 *
 * A profile that is not normalised produces scores outside [-1, 1] and makes every
 * threshold meaningless; one built from a zero vector matches nothing and would mute the
 * user for the rest of the call. Neither fails loudly on a handset, so both fail here.
 */
class VoiceProfileTest {

    private fun vector(fill: Float = 1.0f) = FloatArray(VoiceProfile.DIMENSIONS) { fill }

    @Test
    fun `a profile is normalised on the way in`() {
        val profile = assertNotNull(VoiceProfile.of(vector(7.0f), enrolledSeconds = 90, createdAtEpochMillis = 1L))

        // Unit length, so similarityTo is a cosine and the thresholds mean what they say.
        var sumOfSquares = 0.0
        for (v in profile.embedding) sumOfSquares += v.toDouble() * v
        assertTrue(abs(sumOfSquares - 1.0) < 1e-4, "not unit length: $sumOfSquares")
    }

    @Test
    fun `a zero vector is refused rather than stored`() {
        // What the embedder returns when it was handed too little audio. Stored, it would
        // score 0 against everything and the gate would close on the user permanently.
        assertNull(VoiceProfile.of(FloatArray(VoiceProfile.DIMENSIONS), 90, 1L))
    }

    @Test
    fun `a vector of the wrong width is refused`() {
        val wrong = runCatching { VoiceProfile(FloatArray(128), 90, 1L) }
        assertTrue(wrong.isFailure, "a 128-dimension vector is not this model's output")
    }

    @Test
    fun `an identical profile read back from storage is equal`() {
        // FloatArray is an array: the generated equals compares references, so a profile
        // loaded from disk would never equal the one in memory and the UI would redraw
        // and re-save on every read.
        val one = assertNotNull(VoiceProfile.of(vector(), 90, 7L))
        val other = assertNotNull(VoiceProfile.of(vector(), 90, 7L))

        assertEquals(one, other)
        assertEquals(one.hashCode(), other.hashCode())
    }

    @Test
    fun `a profile is maximally similar to itself`() {
        val profile = assertNotNull(VoiceProfile.of(FloatArray(VoiceProfile.DIMENSIONS) { it.toFloat() }, 90, 1L))

        assertTrue(abs(profile.similarityTo(profile.embedding) - 1.0f) < 1e-4f)
    }

    @Test
    fun `an opposite voice scores minus one`() {
        val profile = assertNotNull(VoiceProfile.of(vector(), 90, 1L))
        val opposite = FloatArray(VoiceProfile.DIMENSIONS) { -profile.embedding[it] }

        assertTrue(abs(profile.similarityTo(opposite) + 1.0f) < 1e-4f)
    }

    @Test
    fun `enrolment needs a minute and asks for ninety seconds`() {
        assertTrue(!EnrolmentRules.isEnough(59))
        assertTrue(EnrolmentRules.isEnough(EnrolmentRules.MINIMUM_SECONDS))
        assertEquals(0f, EnrolmentRules.progress(0))
        assertEquals(1f, EnrolmentRules.progress(EnrolmentRules.TARGET_SECONDS))
        assertEquals(1f, EnrolmentRules.progress(EnrolmentRules.MAXIMUM_SECONDS), "progress past the target")
        assertTrue(EnrolmentRules.MINIMUM_SECONDS < EnrolmentRules.TARGET_SECONDS)
        assertTrue(EnrolmentRules.TARGET_SECONDS < EnrolmentRules.MAXIMUM_SECONDS)
    }
}
