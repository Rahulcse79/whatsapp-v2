package com.whatsappv2.domain.voice

/**
 * The user's voice, as one vector (ADR-012).
 *
 * A profile is 192 floats — a WeSpeaker ECAPA-TDNN embedding of a minute or two of the
 * user speaking — and nothing else. It is not a recording: the audio that produced it is
 * discarded as soon as the vector exists, which is both a privacy property worth having
 * and the reason a profile is a few hundred bytes rather than a few megabytes.
 *
 * ## One at a time, replaced rather than merged
 *
 * There is exactly one active profile. Re-enrolling computes a new vector from the new
 * recording and **replaces** the old one; nothing is averaged across sessions. That is a
 * decision and not a simplification: a profile averaged over a session where the user had
 * a cold, or used a different headset, drifts toward whatever the two have in common and
 * away from both. Replacing means a user whose profile stops working has one obvious,
 * effective remedy — record it again, here, now — rather than a profile that slowly gets
 * worse in a way they cannot undo.
 *
 * ## Why the vector is normalised on the way in
 *
 * [similarityTo] is a dot product, which is only a cosine similarity if both sides have
 * unit length. Normalising at construction means no caller can produce a score outside
 * [-1, 1] by handing over a raw embedding, and the comparison is one multiply-add per
 * dimension on the audio path's budget.
 */
data class VoiceProfile(
    /** The L2-normalised embedding. Always [DIMENSIONS] long. */
    val embedding: FloatArray,

    /** Seconds of speech the vector was computed from — see [EnrolmentRules]. */
    val enrolledSeconds: Int,

    /** When it was made, so the UI can say how old it is and offer to redo it. */
    val createdAtEpochMillis: Long,
) {
    init {
        require(embedding.size == DIMENSIONS) {
            "a voice profile is $DIMENSIONS dimensions, got ${embedding.size}"
        }
    }

    /**
     * Cosine similarity with a live embedding, in [-1, 1]. Higher is more like the user.
     *
     * @param other an embedding from the same model, already normalised.
     */
    fun similarityTo(other: FloatArray): Float {
        require(other.size == DIMENSIONS) {
            "compared against $DIMENSIONS dimensions, got ${other.size}"
        }
        var sum = 0.0f
        for (i in embedding.indices) sum += embedding[i] * other[i]
        return sum
    }

    // FloatArray is an array, so the generated equals/hashCode compare references and a
    // profile would never equal an identical one read back from storage.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceProfile) return false
        return enrolledSeconds == other.enrolledSeconds &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = embedding.contentHashCode()
        result = 31 * result + enrolledSeconds
        result = 31 * result + createdAtEpochMillis.hashCode()
        return result
    }

    companion object {
        /** WeSpeaker's `voxceleb_ECAPA512_LM` output width. */
        const val DIMENSIONS = 192

        /**
         * A profile from a raw embedding, normalised here so callers cannot forget.
         *
         * Returns null for a zero vector, which is what the embedder returns when it was
         * handed too little audio to produce anything — a profile that matches nothing is
         * worse than no profile, because the gate would close on the user for ever.
         */
        fun of(embedding: FloatArray, enrolledSeconds: Int, createdAtEpochMillis: Long): VoiceProfile? {
            var sumOfSquares = 0.0
            for (value in embedding) sumOfSquares += value.toDouble() * value
            val norm = kotlin.math.sqrt(sumOfSquares).toFloat()
            if (!norm.isFinite() || norm <= 0f) return null
            return VoiceProfile(
                embedding = FloatArray(embedding.size) { embedding[it] / norm },
                enrolledSeconds = enrolledSeconds,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        }
    }
}

/**
 * How much speech an enrolment needs, and what the UI may tell the user.
 *
 * The lower bound is not arbitrary. `tools/speech-enhancement/enrolment_bench.py` enrols
 * on four utterances — around 25 seconds — and separates twelve speakers with a 0.0%
 * equal error rate on two-second windows of clean speech. Sixty seconds is comfortably
 * past that and is the shortest round number a user will believe is "a voice sample".
 *
 * The upper bound exists because the embedding is a mean: past a couple of minutes each
 * extra second moves the vector less than the user's own day-to-day variation, so asking
 * for more is asking for patience that buys nothing.
 */
object EnrolmentRules {
    /** Below this the profile is refused. */
    const val MINIMUM_SECONDS = 60

    /** What the UI asks for, and where the progress bar fills. */
    const val TARGET_SECONDS = 90

    /** Recording stops here; more speech does not make a better vector. */
    const val MAXIMUM_SECONDS = 120

    /** Whether [seconds] of speech is enough to build a profile from. */
    fun isEnough(seconds: Int): Boolean = seconds >= MINIMUM_SECONDS

    /** How far through enrolment [seconds] is, in 0..1, for a progress indicator. */
    fun progress(seconds: Int): Float =
        (seconds.toFloat() / TARGET_SECONDS).coerceIn(0f, 1f)
}
