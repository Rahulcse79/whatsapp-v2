package com.whatsappv2.domain.voice

import kotlinx.coroutines.flow.Flow

/** What enrolment is doing, for the screen showing it (ADR-013). */
sealed interface EnrolmentProgress {

    /** Seconds of **speech** captured, and how far that is through the target. */
    data class Recording(val seconds: Int, val fraction: Float) : EnrolmentProgress

    /** Recording finished; the embedding is being computed. A second or two. */
    data object Building : EnrolmentProgress

    /**
     * Done. Nothing has been stored yet — the caller hands this to
     * [VoiceProfileRepository.replace], so there is exactly one place a profile is
     * written.
     */
    data class Ready(val profile: VoiceProfile) : EnrolmentProgress

    /** Nothing was stored. */
    data class Failed(val reason: Reason) : EnrolmentProgress {
        enum class Reason {
            /** The user did not speak for long enough — see [EnrolmentRules]. */
            TOO_LITTLE_SPEECH,

            /** The microphone could not be opened, usually because a call has it. */
            NO_MICROPHONE,

            /** No speaker model in this build, so no profile can be made. */
            NO_MODEL,

            /** Anything else. */
            FAILED,
        }
    }
}

/**
 * Records the user and builds a voice profile from them, on the device (ADR-013).
 *
 * A port so the settings screen can drive enrolment without depending on the module that
 * carries ONNX Runtime and 24 MB of model weights.
 *
 * ## It cannot store a profile, deliberately
 *
 * [enrol] ends at [EnrolmentProgress.Ready] and hands the profile back. Writing it is
 * [VoiceProfileRepository.replace]'s job. Splitting them means there is one place a
 * profile is replaced and one place it can be reasoned about, rather than two paths that
 * have to agree about what "replace" means.
 */
interface VoiceEnrolment {

    /**
     * Records until [EnrolmentRules.MAXIMUM_SECONDS] of speech, then builds the profile.
     *
     * Cancel the collecting coroutine to abandon it: the microphone is released either
     * way and nothing is stored. The audio never leaves memory and never outlives the
     * call to this function.
     *
     * Needs `RECORD_AUDIO`, which this app already holds for calls.
     */
    fun enrol(): Flow<EnrolmentProgress>
}
