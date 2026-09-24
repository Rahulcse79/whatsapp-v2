package com.whatsappv2.domain.engine

/**
 * A video frame's dimensions in pixels. Zero in either axis means "not known yet".
 *
 * Its own type rather than a `Pair<Int, Int>`, because the whole class of bug this exists
 * to prevent is width and height in the wrong order.
 *
 * It lives in `:domain` rather than in the call screen because two modules need the same
 * number and neither may import the other: `:data:sip` learns the remote frame's shape
 * from the SIP stack, and `:feature:calls` is where the surface gets sized by it. See
 * [VideoSurfaceController.remoteVideoSize].
 */
data class VideoSize(val width: Int, val height: Int) {

    /** True when both axes are positive, so an aspect ratio can be taken. */
    val isKnown: Boolean get() = width > 0 && height > 0

    /** Width over height, or 0f when [isKnown] is false. */
    val aspectRatio: Float get() = if (isKnown) width.toFloat() / height.toFloat() else 0f

    /** The same frame turned a quarter turn. */
    fun transposed(): VideoSize = VideoSize(height, width)

    companion object {
        /** Nothing has been decoded yet. */
        val UNKNOWN: VideoSize = VideoSize(0, 0)
    }
}

/**
 * The two pictures on a video call screen, as the decoder and the camera actually shaped
 * them.
 *
 * One value rather than two flows because they are read together and change together: a
 * camera switch or a re-INVITE moves both, and a screen that received them separately
 * would lay out once with the new remote size against the old local one.
 */
data class VideoSizes(
    /**
     * What the far end is sending, after decoding.
     *
     * The most recent call to publish a frame shape, which is the whole answer on a
     * one-to-one call and merely the newest of several in a conference. Use [remoteFor]
     * whenever a specific call is meant.
     */
    val remote: VideoSize = VideoSize.UNKNOWN,

    /** What this device's camera is producing, after any capture rotation. */
    val local: VideoSize = VideoSize.UNKNOWN,

    /**
     * Each established call's decoded frame shape, by call id.
     *
     * ## Why one size was not enough
     *
     * [remote] is a single value, and every call publishing into it means the last one to
     * decode a frame wins. On a one-to-one call that is correct and this map is a
     * formality. In a conference it laid **every** tile out with one participant's aspect
     * ratio: on a four-party call (2026-09-24) one tile overflowed its cell and across its
     * neighbour, another was a narrow strip in a field of black, and neither shape had
     * anything to do with the stream inside it.
     *
     * The handsets do not agree on a shape and never will — a TC15's front camera, an
     * M14's, and whatever a peer re-negotiates mid-call are three different rectangles —
     * so the grid has to ask per tile.
     */
    val remotes: Map<String, VideoSize> = emptyMap(),
) {
    /**
     * [callId]'s decoded frame shape, falling back to [remote].
     *
     * The fallback matters at the start of a call: the map has no entry until that call
     * has decoded something, and sizing its view to nothing produces a visible flash.
     * [remote] is the best guess available then — some peer's real shape rather than a
     * made-up one.
     */
    fun remoteFor(callId: String): VideoSize = remotes[callId]?.takeIf { it.isKnown } ?: remote

    companion object {
        /** Nothing has been decoded and no camera has opened. */
        val UNKNOWN: VideoSizes = VideoSizes()
    }
}
