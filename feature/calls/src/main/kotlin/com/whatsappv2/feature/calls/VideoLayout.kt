package com.whatsappv2.feature.calls

/**
 * A video frame's dimensions in pixels. Zero in either axis means "not known yet".
 *
 * Its own type rather than a `Pair<Int, Int>`, because the whole class of bug this file
 * exists to prevent is width and height in the wrong order.
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
 * Fitting a video stream into the space on screen (Task 52, DoD 9).
 *
 * ## The two bugs this prevents
 *
 * **Stretching.** A remote stream is whatever the far end sends, and it changes mid-call —
 * a peer that rotates, or that drops resolution when its network degrades, sends a
 * differently-shaped frame with no warning. A view sized to the *first* frame and left
 * there shows every subsequent one stretched. Task 52's third done-when is precisely this,
 * and the fix is that the size is recomputed from the current frame rather than stored.
 *
 * **Rotation.** Device rotation and stream rotation are different things and both happen.
 * The stream carries its own orientation; the phone has another. [fit] takes the
 * already-oriented frame so the two are combined in one place — [orient] — rather than at
 * each call site, which is where they get combined twice or not at all.
 *
 * Pure integer arithmetic, so both are asserted in a JVM test. An instrumented test can
 * tell you a view was laid out; it cannot easily tell you the picture inside it was the
 * wrong shape.
 */
object VideoLayout {

    /**
     * The largest box with [frame]'s aspect ratio that fits inside [available].
     *
     * Letterboxing, never cropping. A cropped video call cuts off the edges of what
     * somebody is showing you, and on a call that is a document or a whiteboard as often
     * as it is a face.
     *
     * Returns [available] unchanged when the frame size is not known yet: the view is
     * about to be filled by the first decoded frame, and shrinking it to nothing in the
     * meantime is a visible flicker at the start of every call.
     */
    fun fit(frame: VideoSize, available: VideoSize): VideoSize {
        if (!frame.isKnown || !available.isKnown) return available

        // Integer arithmetic, cross-multiplied rather than divided: the float version of
        // this rounds two ways and produces a one-pixel bar on one edge at some sizes.
        val fitToWidth = frame.height.toLong() * available.width <= frame.width.toLong() * available.height

        return if (fitToWidth) {
            // The frame is relatively wider, so width is the binding constraint.
            VideoSize(available.width, (frame.height.toLong() * available.width / frame.width).toInt())
        } else {
            VideoSize((frame.width.toLong() * available.height / frame.height).toInt(), available.height)
        }
    }

    /**
     * [frame] as it should appear after [rotationDegrees] of display rotation.
     *
     * A quarter or three-quarter turn swaps the axes; a half turn does not. Anything that
     * is not a multiple of 90 is not something a display rotation produces, and is
     * returned unchanged rather than guessed at.
     */
    fun orient(frame: VideoSize, rotationDegrees: Int): VideoSize {
        val normalised = ((rotationDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN
        return if (normalised == QUARTER_TURN || normalised == THREE_QUARTER_TURN) {
            frame.transposed()
        } else {
            frame
        }
    }

    /**
     * The local preview's size: a fixed fraction of the shorter edge, in the frame's shape.
     *
     * A fraction rather than a dp constant so the preview is the same relative size on a
     * phone and a tablet, and taken from the **shorter** edge so it does not become a
     * stripe down a landscape screen.
     */
    fun previewSize(frame: VideoSize, available: VideoSize): VideoSize {
        if (!available.isKnown) return VideoSize.UNKNOWN
        val shortEdge = minOf(available.width, available.height)
        val box = VideoSize(shortEdge / PREVIEW_DIVISOR, shortEdge / PREVIEW_DIVISOR)
        return fit(frame, box)
    }

    private const val FULL_TURN = 360
    private const val QUARTER_TURN = 90
    private const val THREE_QUARTER_TURN = 270

    /** A quarter of the short edge: big enough to frame yourself, small enough to ignore. */
    private const val PREVIEW_DIVISOR = 4
}
