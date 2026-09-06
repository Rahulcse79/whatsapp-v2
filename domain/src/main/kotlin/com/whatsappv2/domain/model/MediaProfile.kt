package com.whatsappv2.domain.model

/**
 * Which media streams a call offers.
 *
 * A profile with neither audio nor video is meaningless, so it cannot be built —
 * the constructor is private and the factory rejects it.
 */
class MediaProfile private constructor(
    val hasAudio: Boolean,
    val hasVideo: Boolean,
) {
    /** True when video may be negotiated, so the camera must be acquired. */
    val requiresCamera: Boolean get() = hasVideo

    /**
     * The same call without its video stream (Tasks 51, 54).
     *
     * Two callers, and they want the same thing for different reasons: a device with no
     * usable camera downgrades rather than failing the call, and a de-escalation drops
     * video from a call that keeps running. Audio-only is always a legal profile, so this
     * never has to answer "and what if there is nothing left".
     */
    fun withoutVideo(): MediaProfile = if (hasVideo) AUDIO else this

    /** The same call with a video stream added (Task 54's escalation). */
    fun withVideo(): MediaProfile = if (hasVideo) this else AUDIO_VIDEO

    /**
     * This profile, or its audio-only form when the camera cannot be used (Task 51).
     *
     * **Downgrade, never refuse.** A user who declined the camera permission, or a device
     * that has no camera at all, still wants the call — Task 51's second done-when says so
     * explicitly. Failing the call instead would punish someone for a privacy choice the
     * app told them was safe to make, and a video call that quietly becomes an audio call
     * is the outcome they would have chosen anyway.
     *
     * A pure function of two values rather than a check inside the engine, so the rule is
     * asserted once here instead of at every place a profile is built.
     */
    fun downgradedWhenCameraUnavailable(cameraUsable: Boolean): MediaProfile =
        if (!cameraUsable) withoutVideo() else this

    override fun equals(other: Any?): Boolean =
        this === other || (other is MediaProfile && hasAudio == other.hasAudio && hasVideo == other.hasVideo)

    override fun hashCode(): Int = 31 * hasAudio.hashCode() + hasVideo.hashCode()

    override fun toString(): String = "MediaProfile(audio=$hasAudio, video=$hasVideo)"

    companion object {
        /** Audio only — the default for a voice call. */
        val AUDIO: MediaProfile = MediaProfile(hasAudio = true, hasVideo = false)

        /** Audio and video. */
        val AUDIO_VIDEO: MediaProfile = MediaProfile(hasAudio = true, hasVideo = true)

        /**
         * Returns a profile, or `null` when both streams are disabled. A call with no
         * media is not a call.
         */
        fun of(audio: Boolean, video: Boolean): MediaProfile? = when {
            !audio && !video -> null
            audio && video -> AUDIO_VIDEO
            audio -> AUDIO
            else -> MediaProfile(hasAudio = false, hasVideo = true)
        }
    }
}
