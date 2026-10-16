package com.whatsappv2.domain.engine

/**
 * What a participant's tile should be showing, when it should not be showing a picture.
 *
 * ## Why a frame size was not enough
 *
 * The grid already hides its placeholder the moment a stream reports a shape, which answers
 * "has anything arrived yet" and nothing after that. Every failure this app has actually had
 * happens *later*: a stream that decodes for a minute and then stops, a camera whose capture
 * rate decays to zero while the encoder keeps sending the same frame, a leg the far end never
 * put video on. In all three the tile holds the last frame it got — a still picture that
 * looks like a working call — or black, which looks like a crash.
 *
 * Measured on 2026-10-05: the M23's `capture` fell 15 → 8 → 0 fps mid-call while `encode`
 * stayed at 15, so both peers rendered one stale frame for the rest of the call with nothing
 * on screen to say so. The tiles were not black and not empty; they were *wrong*, which is
 * worse, because nobody thinks to re-dial a call that looks fine.
 *
 * So the tile is told what it is, and says so.
 */
enum class VideoTileState {
    /** Frames are arriving and being decoded. Show the picture. */
    LIVE,

    /**
     * A video stream exists and no picture has come out of it yet.
     *
     * The honest state for the first second or two of every call, and the one the grid
     * already inferred from an unknown frame size.
     */
    CONNECTING,

    /**
     * Decoding, but well under the rate this leg was configured for.
     *
     * Distinguished from [UNAVAILABLE] because the picture *is* updating and the user can
     * still read a room from it; telling them it is unavailable would be a lie they can see
     * through, which costs the rest of the status line its credibility.
     */
    LOW_QUALITY,

    /**
     * No video is reaching this tile: the far end never offered one, it has stopped
     * decoding, or the picture has frozen.
     *
     * Frozen is folded in here rather than given a state of its own because the three are
     * one thing to the person looking at the screen — there is no live picture — and a
     * status line is not the place to explain which of three mechanisms produced it. The
     * media trace is.
     */
    UNAVAILABLE,

    ;

    /** Whether the decoded picture should be drawn, rather than a placeholder. */
    val showsPicture: Boolean get() = this == LIVE || this == LOW_QUALITY
}

/**
 * Each established call's [VideoTileState], by call id.
 *
 * Shaped like [VideoSizes] and published on the same cadence for the same reason: the grid
 * reads both per tile, and two flows that disagreed for a frame would show a placeholder
 * over a live picture.
 */
data class VideoHealth(
    val byCall: Map<String, VideoTileState> = emptyMap(),
) {
    /**
     * [callId]'s state, defaulting to [VideoTileState.CONNECTING].
     *
     * Connecting rather than unavailable, because the default is what a tile shows in the
     * gap between its call being established and the first telemetry tick. "Connecting…"
     * describes that gap exactly; "Video unavailable" would flash a failure on every call
     * that is about to work perfectly.
     */
    fun stateFor(callId: String): VideoTileState = byCall[callId] ?: VideoTileState.CONNECTING

    companion object {
        /** No call has been measured yet. */
        val UNKNOWN: VideoHealth = VideoHealth()
    }
}
