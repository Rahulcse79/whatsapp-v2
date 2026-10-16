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
     * The frame rate this leg's **encoder** was configured for, or null when unreadable.
     *
     * Kept for the trace and for callers that want it; **not** the denominator for "low".
     * It is this device's own outgoing rate, and this reading is about the *remote's*
     * picture — see [VideoTileStateReducer.LOW_QUALITY_FPS] for why the two cannot be
     * divided by one another.
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
     * Decoded frames per second below which the remote picture is [VideoTileState.LOW_QUALITY].
     *
     * ## Why this is an absolute rate and not a fraction of our own
     *
     * It was `1/3 of the local encoder's configured rate`, and that denominator is unsound:
     * the reading is about the picture the *far end* is sending, and the far end's intended
     * rate is not knowable here. VP8 does not carry it — `vpx.c` writes a fixed
     * `max-fr=30` into `dec_fmtp` whatever the sender has chosen, so neither the SDP nor
     * `decFmt` says anything about it — and nothing else on the wire does either.
     *
     * Dividing by our own rate therefore badged a *healthy* peer as soon as the two devices
     * disagreed: with this handset on 30 fps and the peer deliberately on 5 or 10, every
     * tile read "Low video quality" for ever, with nothing wrong anywhere. The frame-rate
     * setting is per device, so two devices disagreeing is the normal case, not the corner.
     *
     * 4 fps, which is just under the lowest rate the app offers (see `VideoFrameRate`, whose
     * floor is 5 fps). Below the floor nobody *chose* this rate, so it is the one claim that
     * holds without knowing the sender's intent — and 4 rather than 5 so that a leg
     * deliberately at the floor, measured at 4.8 fps by a tick that did not land cleanly,
     * is not badged for arriving on time.
     *
     * What is given up is the case where both ends are on 30 and one delivers 9: that now
     * reads [VideoTileState.LIVE]. It is a real loss and the right trade — 9 fps reads as
     * motion, the *sender's* adaptive policy is what answers a collapse on its own uplink,
     * and a badge that cries wolf on every healthy mixed-rate call costs every other tile
     * status its credibility.
     */
    const val LOW_QUALITY_FPS: Double = 4.0

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

    @Suppress("UnusedReceiverParameter")
    private fun VideoTileReading.verdict(fps: Double): VideoTileState = when {
        fps < FROZEN_FPS -> VideoTileState.UNAVAILABLE
        fps < LOW_QUALITY_FPS -> VideoTileState.LOW_QUALITY
        else -> VideoTileState.LIVE
    }
}
