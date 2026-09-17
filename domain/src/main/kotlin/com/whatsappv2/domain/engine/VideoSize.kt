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
    /** What the far end is sending, after decoding. */
    val remote: VideoSize = VideoSize.UNKNOWN,

    /** What this device's camera is producing, after any capture rotation. */
    val local: VideoSize = VideoSize.UNKNOWN,
) {
    companion object {
        /** Nothing has been decoded and no camera has opened. */
        val UNKNOWN: VideoSizes = VideoSizes()
    }
}
