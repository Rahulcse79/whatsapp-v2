package com.whatsappv2.domain.model

/**
 * Audio codecs the client may offer, in the order the SDP should list them (§5.1).
 *
 * [OPUS] first is deliberate: it is the only wideband codec here and degrades far
 * better on lossy mobile networks than the G.7xx family.
 */
enum class AudioCodec(val payloadName: String, val isWideband: Boolean) {
    OPUS("opus", isWideband = true),
    G722("G722", isWideband = true),
    PCMU("PCMU", isWideband = false),
    PCMA("PCMA", isWideband = false),
    G729("G729", isWideband = false),
    ILBC("iLBC", isWideband = false),
}

/** Video codecs the client may offer, in preference order (§5.2). */
enum class VideoCodec(val payloadName: String) {
    VP8("VP8"),
    H264("H264"),
    VP9("VP9"),
    H265("H265"),
    AV1("AV1"),
}

/**
 * Ordered codec preferences for one account.
 *
 * Order is meaningful — it is the SDP offer order — so these are lists, not sets.
 * An account with no audio codec cannot place a call, which is why [audio] must not
 * be empty; [video] may be, meaning the account is audio-only.
 */
data class CodecPreferences(
    val audio: List<AudioCodec>,
    val video: List<VideoCodec>,
) {
    init {
        require(audio.isNotEmpty()) { "At least one audio codec is required" }
        require(audio.distinct().size == audio.size) { "Duplicate audio codecs: $audio" }
        require(video.distinct().size == video.size) { "Duplicate video codecs: $video" }
    }

    /** True when this account can negotiate video at all. */
    val supportsVideo: Boolean get() = video.isNotEmpty()

    companion object {
        /** Wideband first, then the codecs every gateway understands. */
        val DEFAULT: CodecPreferences = CodecPreferences(
            audio = listOf(AudioCodec.OPUS, AudioCodec.G722, AudioCodec.PCMU, AudioCodec.PCMA),
            video = listOf(VideoCodec.VP8, VideoCodec.H264),
        )

        /** Audio only, for accounts on links that cannot carry video. */
        val AUDIO_ONLY: CodecPreferences = DEFAULT.copy(video = emptyList())
    }
}
