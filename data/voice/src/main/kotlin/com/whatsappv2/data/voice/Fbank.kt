package com.whatsappv2.data.voice

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kaldi's `compute-fbank-feats`, as WeSpeaker drives it, in Kotlin (ADR-013).
 *
 * ## This file is a port, and it has a reference implementation
 *
 * `tools/speech-enhancement/speaker.py` is the same transform in Python, and every number
 * in `VOICE-PROFILE.md` was measured through it. The two have to agree, because a profile
 * enrolled on this device is compared against live audio processed by this device: if the
 * front end drifts, the model does not fail, it quietly returns worse embeddings and the
 * gate's measured thresholds stop meaning anything. `FbankParityTest` is what holds them
 * together — it checks this against vectors the Python produced.
 *
 * ## Every step below changes the answer
 *
 * The model takes `[T, 80]` and has no opinion about where those numbers came from. It
 * will return a confident-looking embedding from features computed slightly differently,
 * so each of these is load-bearing rather than incidental:
 *
 *  - samples are **int16 scale**, not normalised to [-1, 1]. Kaldi's epsilon floor is
 *    defined in those units; normalising shifts every log-mel value by a constant;
 *  - **DC removed per frame**, then **pre-emphasis 0.97** with the first sample repeated;
 *  - 25 ms frames every 10 ms, **snip_edges** — no padding, the partial tail is dropped;
 *  - a **512-point** FFT, the next power of two above 400, and its power spectrum;
 *  - 80 triangular mel filters from 20 Hz to Nyquist on **Kaldi's** mel scale
 *    (`1127 ln(1 + f/700)`), **unnormalised** — not Slaney's, which scales by filter width;
 *  - `log(max(x, eps))`, then **cepstral mean normalisation over the whole window**,
 *    which is what makes the embedding about the speaker and not the microphone.
 */
internal object Fbank {

    const val SAMPLE_RATE = 16_000
    const val N_MELS = 80
    private const val FRAME_LENGTH = 400 // 25 ms
    private const val FRAME_SHIFT = 160 // 10 ms
    private const val N_FFT = 512
    private const val PREEMPH = 0.97f
    private const val LOW_FREQ = 20.0
    private const val MEL_SCALE = 1127.0
    private const val MEL_BREAK = 700.0

    /** `log(max(x, eps))`, with Kaldi's float epsilon. */
    private const val LOG_FLOOR = 1.1920929e-7

    /** Guards a divide by zero when two mel edges land in the same FFT bin. */
    private const val EDGE_FLOOR = 1e-10

    /** One full turn, negative: the FFT's twiddle angle is `-2 pi / len`. */
    private const val TURN = -2.0 * Math.PI

    private val window = FloatArray(FRAME_LENGTH) {
        // Hamming, which is what WeSpeaker's recipe asks Kaldi for. Not Kaldi's own
        // default ("povey"), and the difference is audible to the model.
        (0.54 - 0.46 * cos(2.0 * Math.PI * it / (FRAME_LENGTH - 1))).toFloat()
    }

    private val bank: Array<FloatArray> = melFilterbank()

    private fun mel(hz: Double) = MEL_SCALE * ln(1.0 + hz / MEL_BREAK)

    private fun melFilterbank(): Array<FloatArray> {
        val nyquist = SAMPLE_RATE / 2.0
        val lo = mel(LOW_FREQ)
        val hi = mel(nyquist)
        val bins = DoubleArray(N_MELS + 2) {
            val m = lo + (hi - lo) * it / (N_MELS + 1)
            (MEL_BREAK * (exp(m / MEL_SCALE) - 1.0)) * N_FFT / SAMPLE_RATE
        }
        val half = N_FFT / 2 + 1
        return Array(N_MELS) { m ->
            val left = bins[m]
            val centre = bins[m + 1]
            val right = bins[m + 2]
            FloatArray(half) { f ->
                val rising = (f - left) / max(centre - left, EDGE_FLOOR)
                val falling = (right - f) / max(right - centre, EDGE_FLOOR)
                max(0.0, min(rising, falling)).toFloat()
            }
        }
    }

    /**
     * `[T, 80]` log-mel features for 16 kHz 16-bit samples, mean-normalised.
     *
     * @param samples int16 PCM. Empty when there is not one whole frame.
     */
    fun of(samples: ShortArray): Array<FloatArray> {
        val count = if (samples.size >= FRAME_LENGTH) {
            1 + (samples.size - FRAME_LENGTH) / FRAME_SHIFT
        } else {
            0
        }
        if (count <= 0) return emptyArray()

        val half = N_FFT / 2 + 1
        val feats = Array(count) { FloatArray(N_MELS) }
        val frame = FloatArray(FRAME_LENGTH)
        val real = FloatArray(N_FFT)
        val imag = FloatArray(N_FFT)

        for (t in 0 until count) {
            val offset = t * FRAME_SHIFT
            var mean = 0.0f
            for (i in 0 until FRAME_LENGTH) mean += samples[offset + i].toFloat()
            mean /= FRAME_LENGTH

            // Pre-emphasis reads the *previous* sample, so it runs backwards over the
            // frame to avoid needing a copy of the un-emphasised values.
            for (i in FRAME_LENGTH - 1 downTo 1) {
                frame[i] = (samples[offset + i] - mean) - PREEMPH * (samples[offset + i - 1] - mean)
            }
            frame[0] = (samples[offset] - mean) * (1f - PREEMPH)

            java.util.Arrays.fill(real, 0f)
            java.util.Arrays.fill(imag, 0f)
            for (i in 0 until FRAME_LENGTH) real[i] = frame[i] * window[i]
            fft(real, imag)

            for (m in 0 until N_MELS) {
                val filter = bank[m]
                var energy = 0.0
                for (f in 0 until half) {
                    val power = real[f].toDouble() * real[f] + imag[f].toDouble() * imag[f]
                    energy += power * filter[f]
                }
                feats[t][m] = ln(max(energy, LOG_FLOOR)).toFloat()
            }
        }

        // Cepstral mean normalisation, over the window the caller gave us.
        for (m in 0 until N_MELS) {
            var sum = 0.0
            for (t in 0 until count) sum += feats[t][m]
            val mean = (sum / count).toFloat()
            for (t in 0 until count) feats[t][m] -= mean
        }
        return feats
    }

    /**
     * In-place radix-2 FFT.
     *
     * Written out rather than pulled in: the only sizes this ever sees are 512, it runs
     * ~200 times per two-second window on a background thread, and a dependency for
     * eighty lines of arithmetic is a dependency to keep up to date for ever.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = real[i]
                real[i] = real[j]
                real[j] = tmp
                tmp = imag[i]
                imag[i] = imag[j]
                imag[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val angle = TURN / len
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vImag = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal
                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }
}
