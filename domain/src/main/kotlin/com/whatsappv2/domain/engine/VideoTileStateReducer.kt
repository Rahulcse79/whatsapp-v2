package com.whatsappv2.domain.engine

/**
 * One tile's decode side, between two telemetry ticks.
 *
 * Counters rather than a rate, because the question is whether the picture *moved* and a
 * rate computed elsewhere would already have decided that.
 */
data class VideoTileReading(
    /** True while the call has a video stream that is negotiated and running. */
    val streamActive: Boolean,

    /** Whether a decoded frame shape has ever been published for this call. */
    val frameKnown: Boolean,

    /** Cumulative frames decoded, as the native counter reports them. */
    val decoded: Long,

    /** When this reading was taken. */
    val atMillis: Long,

    /**
     * The frame rate this leg's encoder was configured for, or null when unreadable.
     *
     * The denominator for "low", so a call deliberately running at 5 fps is not reported as
     * degraded for hitting 5 fps. See `VideoFrameRate`.
     */
    val configuredFps: Int? = null,
)

/**
 * Decides what a tile should say, from two readings of the same call.
 *
 * ## Why this is a pure function in `:domain`
 *
 * Because the thresholds are a product decision and the counters are the only input. The
 * gateway reads PJSIP and this decides, which is the same split `AdaptiveVideoPolicy` draws
 * — and it means the awkward cases (a counter that resets when a stream is rebuilt, a tick
 * that arrives twice in the same millisecond) are testable on the JVM rather than only on a
 * handset with a camera pointed at something.
 */
object VideoTileStateReducer {

    /**
     * Below this fraction of the configured rate, the picture is [VideoTileState.LOW_QUALITY].
     *
     * A third rather than a half: the adaptive ladder legitimately runs a leg at the rung
     * below the one requested, and calling that "low quality" in the UI would contradict the
     * app's own policy on most conference calls. A third is the point where motion stops
     * reading as motion — 5 fps against a configured 15.
     */
    const val LOW_QUALITY_FRACTION: Double = 1.0 / 3.0

    /** Decoded frames per second below which a moving picture is not moving at all. */
    private const val FROZEN_FPS = 0.5

    private const val MILLIS_PER_SECOND = 1_000.0

    /**
     * [current]'s state, given the previous reading of the same call.
     *
     * [previous] is null on the first tick of a call, when there is no interval to measure
     * and the only honest answers are "connecting" or "no stream".
     */
    fun reduce(previous: VideoTileReading?, current: VideoTileReading): VideoTileState = when {
        // Nothing negotiated, or negotiated and torn down. No amount of decode history makes
        // a tile with no stream behind it live.
        !current.streamActive -> VideoTileState.UNAVAILABLE

        // A stream that has never produced a shape is still coming up, and so is one with no
        // earlier reading to difference against. This is the case the grid used to infer from
        // the frame size alone, kept because it is the right answer.
        !current.frameKnown || previous == null -> VideoTileState.CONNECTING

        else -> rateOf(previous, current)?.let { current.verdict(it) } ?: VideoTileState.LIVE
    }

    /**
     * Decoded frames per second between two readings, or null when they measure nothing.
     *
     * Null for two readings sharing a timestamp, and for a counter that went backwards — a
     * stream rebuilt under us starts a fresh one at zero, and differencing across that
     * boundary reports a negative rate. Neither is evidence about the picture.
     */
    private fun rateOf(previous: VideoTileReading, current: VideoTileReading): Double? {
        val seconds = (current.atMillis - previous.atMillis) / MILLIS_PER_SECOND
        if (seconds <= 0.0 || current.decoded < previous.decoded) return null
        return (current.decoded - previous.decoded) / seconds
    }

    private fun VideoTileReading.verdict(fps: Double): VideoTileState {
        val configured = configuredFps?.takeIf { it > 0 }
        return when {
            fps < FROZEN_FPS -> VideoTileState.UNAVAILABLE
            configured != null && fps < configured * LOW_QUALITY_FRACTION -> VideoTileState.LOW_QUALITY
            else -> VideoTileState.LIVE
        }
    }
}
