package com.whatsappv2.domain.model

/**
 * Audio codecs the client may offer, in the order the SDP should list them (§5.1).
 *
 * [OPUS] first is deliberate: it is the only wideband codec here and degrades far
 * better on lossy mobile networks than the G.7xx family.
 */
enum class AudioCodec(val payloadName: String, val isWideband: Boolean) {
    OPUS("opus", isWideband = true),

    /**
     * Google's neural speech codec, and the reason it is worth having: it carries
     * intelligible wideband speech at **3.2, 6 or 9.2 kbps**, an order of magnitude below
     * anything else in this list. On a congested mobile uplink that is the difference
     * between a call and no call.
     *
     * `payloadName` is lowercase deliberately. pjproject registers the codec as `lyra`
     * (`pjmedia/src/pjmedia-codec/lyra.cpp`), and the stack matches a preference against a
     * codec id by prefix — `lyra/16000/1`. `LYRA` would match nothing, silently.
     *
     * **Not in [CodecPreferences.DEFAULT], and that is deliberate.** Three things have to
     * be true before this negotiates, and only the first is done:
     *
     *  1. It is selectable here.
     *  2. The native library is built with `PJMEDIA_HAS_LYRA_CODEC` — it defaults to `0`,
     *     and enabling it means cross-compiling Google's Lyra, which pulls in Bazel and
     *     TensorFlow Lite.
     *  3. The four model files (`lyra_config.binarypb`, `lyragan.tflite`,
     *     `quantizer.tflite`, `soundstream_encoder.tflite`) ship on the device and
     *     `CodecLyraConfig.modelPath` points at them. Without them the codec registers and
     *     then fails to open a stream.
     *
     * Until (2) and (3), selecting it is a no-op that the stack logs as "preferred but not
     * in this build". Marked wideband because the default clock rate is 16 kHz; pjproject
     * also offers 8, 32 and 48 kHz, and only 16 kHz is enabled by default.
     */
    LYRA("lyra", isWideband = true),

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
