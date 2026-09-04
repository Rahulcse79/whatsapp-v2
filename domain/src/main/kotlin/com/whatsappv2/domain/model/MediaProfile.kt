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
