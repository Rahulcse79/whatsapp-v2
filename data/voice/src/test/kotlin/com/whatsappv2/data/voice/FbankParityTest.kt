package com.whatsappv2.data.voice

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [Fbank] against the Python it was ported from.
 *
 * ## Why this test is the important one in this module
 *
 * A voice profile is enrolled through this code and compared against live audio through
 * the same code, so a front end that drifts from the reference does not break anything
 * visibly — it just returns worse embeddings, and the thresholds in `SpeakerGate` stop
 * describing reality. Every number in `tools/speech-enhancement/VOICE-PROFILE.md` was
 * measured through `speaker.py`; this is what entitles the Kotlin to those numbers.
 *
 * The waveform is defined by a formula rather than a fixture so both languages can
 * produce it bit for bit, and the expected values below were printed by `speaker.fbank`
 * on exactly that input.
 */
class FbankParityTest {

    /** The same signal `speaker.py` was given: two tones, rounded to int16. */
    private fun reference(): ShortArray = ShortArray(16_000) { n ->
        val a = 8000.0 * sin(2.0 * Math.PI * 220.0 * n / 16_000.0)
        val b = 3000.0 * sin(2.0 * Math.PI * 1375.0 * n / 16_000.0)
        (a + b).roundToInt().toShort()
    }

    private val firstFrame =
        floatArrayOf(1.433005f, 0.320404f, 0.216909f, 0.644896f, 0.027397f, 0.006868f, 0.003529f, -0.006440f)
    private val frameFifty =
        floatArrayOf(0.909763f, 0.157944f, -1.316044f, 0.352043f, 0.134961f, -0.007647f, -0.011561f, 0.003776f)

    // Generous against float-vs-double accumulation across a 512-point FFT and 80 filters,
    // tight enough that any real difference - a window, a mel scale, a missing
    // pre-emphasis - moves a value by far more than this.
    private val tolerance = 2e-3f

    @Test
    fun `the frame geometry matches`() {
        val feats = Fbank.of(reference())

        // 1 + (16000 - 400) / 160, snip_edges: no padding and the partial tail dropped.
        assertEquals(98, feats.size, "frame count")
        assertEquals(Fbank.N_MELS, feats[0].size, "mel bins")
    }

    @Test
    fun `the first frame matches the Python`() {
        val feats = Fbank.of(reference())

        firstFrame.forEachIndexed { i, expected ->
            assertTrue(
                abs(feats[0][i] - expected) < tolerance,
                "bin $i: expected $expected, got ${feats[0][i]}",
            )
        }
    }

    @Test
    fun `a frame from the middle matches the Python`() {
        // Not just the first: the first frame is the one a missing pre-emphasis special
        // case would still get right.
        val feats = Fbank.of(reference())

        frameFifty.forEachIndexed { i, expected ->
            assertTrue(
                abs(feats[50][i] - expected) < tolerance,
                "frame 50 bin $i: expected $expected, got ${feats[50][i]}",
            )
        }
    }

    @Test
    fun `the whole matrix has the same distribution`() {
        // Eight values from two frames would not catch a filterbank that is subtly wrong
        // in its upper half. These four statistics cover all 7,840 of them.
        val feats = Fbank.of(reference())
        var sum = 0.0
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        feats.forEach { row ->
            row.forEach {
                sum += it
                if (it < min) min = it
                if (it > max) max = it
            }
        }
        val count = feats.size * Fbank.N_MELS
        val mean = (sum / count).toFloat()
        var variance = 0.0
        feats.forEach { row -> row.forEach { variance += (it - mean).toDouble() * (it - mean) } }
        val std = kotlin.math.sqrt(variance / count).toFloat()

        // Cepstral mean normalisation makes the per-bin mean zero, so the overall mean is
        // zero too - and that is itself worth asserting, because a CMN that ran over the
        // wrong axis would still produce plausible-looking features.
        assertTrue(abs(mean) < 1e-4f, "mean should be ~0 after CMN, was $mean")
        assertTrue(abs(std - 0.525370f) < 5e-3f, "std: expected 0.525370, got $std")
        assertTrue(abs(min - (-3.486070f)) < tolerance, "min: expected -3.486070, got $min")
        assertTrue(abs(max - 1.433005f) < tolerance, "max: expected 1.433005, got $max")
    }

    @Test
    fun `too little audio for one frame produces nothing rather than guessing`() {
        // The embedder turns this into a null embedding, which the gate reads as "no
        // opinion" and therefore as the user. Padding to a frame instead would invent
        // features and score them.
        assertTrue(Fbank.of(ShortArray(399)).isEmpty())
        assertEquals(1, Fbank.of(ShortArray(400)).size)
    }
}
