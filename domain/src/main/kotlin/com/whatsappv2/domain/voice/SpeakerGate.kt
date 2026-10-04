package com.whatsappv2.domain.voice

/**
 * Decides, moment to moment, whether the microphone is carrying the user (ADR-012).
 *
 * The capture path already removes *noise* — RNNoise, in `pjmedia_snd_port` — and the one
 * thing a noise suppressor structurally cannot remove is other people's speech, because
 * speech is what it is built to keep. Measured: babble is RNNoise's worst class by a
 * distance (`tools/speech-enhancement/RESULTS.md`). This is what closes that gap: when
 * the voice in the microphone is not the enrolled user's, nothing goes out at all.
 *
 * ## Asymmetric on purpose, because the two failures are not equal
 *
 * Opening takes one window. Closing takes [closeAfter] consecutive windows that do not
 * match. A colleague leaking for a second is a glitch nobody reports; the user being cut
 * mid-word is the feature ruining the call, and it is the failure they cannot diagnose or
 * work around. So the gate is biased open everywhere it can be: it **starts** open, it
 * opens on a single matching window, and it treats a window it cannot score at all as the
 * user's.
 *
 * ## The numbers are measured, not chosen
 *
 * From `tools/speech-enhancement/gate_bench.py`, twelve speakers, each in turn the user
 * with every other as the interferer, street noise at 10 dB, scored on how much of a real
 * timeline each failure costs:
 *
 * ```
 *   close after   threshold   user cut   others let through
 *         3         0.35        1.5%            4.6%
 *         4         0.35        0.0%            6.9%      <- shipped
 *         6         0.35        0.0%           11.4%
 * ```
 *
 * Two things fall out. **"Others let through" does not move with the threshold** — it is
 * identical at 0.35, 0.40 and 0.45 — because the leak is not misclassification, it is the
 * closing delay itself: one second of a ten-second interfering turn. And **three windows
 * is too few**: it starts cutting the user, which is the line this will not cross.
 *
 * So the threshold is set by where the user stops being cut, and the delay by how much of
 * somebody else's sentence is tolerable. One second is the shortest that never cut the
 * user in twelve speakers.
 *
 * ## What it cannot do
 *
 * Two people talking at once. A gate is a switch, not a separator: it can only decide
 * whether to send the microphone, and during overlap the microphone has both voices in
 * it. Measured at 82-94% let through, which is the *correct* behaviour — the alternative
 * is cutting the user off whenever somebody talks over them. Separating overlapped
 * speakers needs target-speaker extraction, which is a model this does not have.
 *
 * ## Pure, and therefore testable
 *
 * No audio, no model, no clock. It takes a similarity score and returns a decision, so
 * every case that matters — a user who pauses, an interferer who pauses, a score that
 * cannot be computed, a profile that has just been replaced mid-call — is a JVM test.
 */
class SpeakerGate(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val closeAfter: Int = DEFAULT_CLOSE_AFTER,
) {
    init {
        require(closeAfter >= 1) { "closing takes at least one window, got $closeAfter" }
    }

    private var consecutiveMisses = 0

    /**
     * Whether the microphone should currently be sent.
     *
     * Starts true. A gate that began closed would cut the first word of every call while
     * it waited for a window long enough to score, and the first word is the one that
     * carries "hello".
     */
    var isOpen: Boolean = true
        private set

    /**
     * Feeds one window's similarity score and returns the new state.
     *
     * @param similarity cosine similarity with the profile, in [-1, 1]; or null when the
     *   window could not be scored — too short, silent, or the embedder failed. A window
     *   with no score is **treated as the user's**, which is the same bias as everywhere
     *   else here: an embedder that stops working must not mute the user.
     */
    fun onWindow(similarity: Float?): Boolean {
        if (similarity == null || similarity >= threshold) {
            consecutiveMisses = 0
            isOpen = true
            return isOpen
        }
        consecutiveMisses++
        if (consecutiveMisses >= closeAfter) isOpen = false
        return isOpen
    }

    /**
     * Reopens the gate and forgets what it has seen.
     *
     * For anything that makes the recent past meaningless: a new call, a profile replaced
     * mid-call, the feature being switched on. Reopening rather than merely clearing the
     * count, because the state this returns to has to be the safe one.
     */
    fun reset() {
        consecutiveMisses = 0
        isOpen = true
    }

    companion object {
        /**
         * Cosine similarity above which a window is the user's.
         *
         * 0.35 is where the measured false-reject rate reaches zero and stays there; see
         * the table on this class. It is deliberately *not* tuned for the lowest leak,
         * because the leak does not depend on it.
         */
        const val DEFAULT_THRESHOLD = 0.35f

        /** Windows of disagreement before the gate closes — one second at [HOP_MILLIS]. */
        const val DEFAULT_CLOSE_AFTER = 4

        /**
         * Audio each window covers, in milliseconds.
         *
         * Two seconds, because that is where speaker verification becomes reliable on
         * this model: the equal error rate over twelve speakers is 10.7% at half a second,
         * 0.8% at one, and 0.0% at two (`enrolment_bench.py`). The window is rolling, so
         * this is not latency.
         */
        const val WINDOW_MILLIS = 2_000

        /**
         * How often a decision is made. This *is* the latency.
         *
         * 250 ms: four decisions a second, each over the trailing two seconds. The
         * embedding costs far less than the hop on an arm64 core, so the limit is how
         * quickly the gate should be allowed to change its mind rather than compute.
         */
        const val HOP_MILLIS = 250
    }
}
